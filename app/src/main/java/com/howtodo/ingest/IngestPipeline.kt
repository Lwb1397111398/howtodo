package com.howtodo.ingest

import com.howtodo.ai.TokenBudget
import com.howtodo.data.db.AppDatabase
import com.howtodo.data.db.ChunkEntity
import com.howtodo.data.db.FtsStore
import com.howtodo.data.db.SourceKind
import com.howtodo.data.db.SourceStatus
import com.howtodo.rag.Bigram
import com.howtodo.util.Storage

/**
 * 材料入库流水线：原件 -> 文本 -> 分块 -> 索引。
 *
 * 幂等设计：同一份材料重复入库会先清掉旧的 chunk 与倒排索引再重建，
 * 不会产生重复片段污染检索结果。
 */
class IngestPipeline(
    private val db: AppDatabase,
    private val storage: Storage,
    private val vision: VisionOcr
) {

    data class Outcome(val chars: Int, val chunks: Int, val usedVision: Boolean)

    suspend fun ingest(
        sourceId: String,
        pdfMaxPages: Int = 15,
        onProgress: (String) -> Unit = {}
    ): Result<Outcome> {
        val sourceDao = db.sourceDao()
        val source = sourceDao.byId(sourceId)
            ?: return Result.failure(IllegalStateException("材料不存在"))
        val file = storage.materialFile(source.id)
        if (!file.exists() || file.length() == 0L) {
            sourceDao.setStatus(sourceId, SourceStatus.FAILED, "文件不存在或为空")
            return Result.failure(IllegalStateException("文件不存在或为空"))
        }

        sourceDao.setStatus(sourceId, SourceStatus.PARSING)
        var usedVision = false
        var pageCount = 0
        // 视觉识别的部分失败明细（某几页失败/超页截断）；全部失败时直接 FAILED 走异常路径
        val partialNotes = ArrayList<String>()

        val text: String = try {
            when (source.kind) {
                SourceKind.IMAGE -> {
                    onProgress("识别图片…")
                    usedVision = true
                    val prepared = ImageIngest.prepare(file)
                        ?: throw IllegalStateException("图片解码失败")
                    pageCount = 1
                    vision.transcribeSingle(prepared, source.displayName).getOrThrow().text
                }

                SourceKind.PDF -> {
                    onProgress("识别 PDF…")
                    usedVision = true
                    val ocr = transcribePdf(file, source.displayName, pdfMaxPages, onProgress)
                    pageCount = ocr.rendered
                    partialNotes.addAll(ocr.failures)
                    ocr.text
                }

                // 其余一律走「按内容嗅探」：docx/doc/rtf/网页另存的 doc、xls/xlsx、ppt/pptx、odt、txt…
                // 扩展名不可靠（.doc 可能是 RTF 或 HTML，材料本身在私有目录里还存成 .bin）
                else -> {
                    onProgress(progressText(source.displayName))
                    DocumentExtract.extract(file).text
                }
            }
        } catch (e: Throwable) {
            val msg = e.message ?: e.javaClass.simpleName
            sourceDao.setStatus(sourceId, SourceStatus.FAILED, msg.take(200))
            return Result.failure(e)
        }

        val cleaned = cleanup(text)
        if (cleaned.isBlank()) {
            sourceDao.setStatus(sourceId, SourceStatus.FAILED, "没有从材料中读到文字")
            return Result.failure(IllegalStateException("没有从材料中读到文字"))
        }

        onProgress("建立索引…")
        // 「【第N页】」标记是转写器自己拼的：扫描清洗后的全文取真实偏移与页号，
        // 喂给切块器，chunk 的 pageNo 从此有值（引用跳转、提案页都能标页码）
        val pageMarkers = Regex("【第(\\d+)页】").findAll(cleaned)
            .map { it.range.first to it.groupValues[1].toInt() }
            .toList()
            .ifEmpty { null }
        val pieces = Chunker.split(cleaned, pageMarkers)
        if (pieces.isEmpty()) {
            sourceDao.setStatus(sourceId, SourceStatus.FAILED, "内容过短")
            return Result.failure(IllegalStateException("内容过短"))
        }

        // 重建索引。先确认材料还在：OCR 可能跑很久，期间用户可能已经把这份材料删了，
        // 那时再插 chunk 会留下指向空 source 的孤儿片段，污染检索结果。
        if (sourceDao.byId(source.id) == null) {
            return Result.failure(IllegalStateException("材料已被删除"))
        }
        db.chunkDao().deleteBySource(source.id)
        val raw = db.openHelper.writableDatabase
        FtsStore.deleteBySource(raw, source.id)

        val entities = pieces.mapIndexed { i, p ->
            ChunkEntity(
                sourceId = source.id,
                projectId = source.projectId,
                ord = i,
                pageNo = p.pageNo,
                section = p.section,
                text = p.text,
                charStart = p.charStart,
                charEnd = p.charEnd,
                kind = p.kind,
                tokensEst = TokenBudget.estimate(p.text)
            )
        }
        val ids = db.chunkDao().insertAll(entities)
        ids.forEachIndexed { i, id ->
            FtsStore.indexChunk(raw, id, Bigram.encode(entities[i].text))
        }

        sourceDao.setMetrics(
            id = source.id,
            chars = cleaned.length,
            tokens = TokenBudget.estimate(cleaned),
            pages = if (pageCount > 0) pageCount else 1,
            ocr = usedVision
        )
        sourceDao.setIndexed(source.id)
        // PARSED 但带部分失败明细（某几页识别失败/超页截断）：预览页顶部会展示提示，
        // 不影响已识别部分正常检索与引用
        sourceDao.setStatus(
            source.id,
            SourceStatus.PARSED,
            partialNotes.joinToString("；").take(200).ifBlank { null }
        )
        return Result.success(Outcome(cleaned.length, ids.size, usedVision))
    }

    private class PdfOcr(val text: String, val failures: List<String>, val rendered: Int)

    /**
     * 流式转写 PDF：渲染一批（3 页）就交视觉模型转写一批，prepared 用完即弃，
     * 内存峰值只有一批而不是全部页。
     */
    private suspend fun transcribePdf(
        file: java.io.File,
        displayName: String,
        maxPages: Int,
        onProgress: (String) -> Unit
    ): PdfOcr = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val pager = PdfIngest.pager(file)
            ?: throw IllegalStateException("PDF 无法渲染，可能已加密或损坏")
        val realPages = pager.pageCount
        val expected = minOf(realPages, maxPages)
        val texts = HashMap<Int, String?>()
        val failures = ArrayList<String>()
        var rendered = 0
        try {
            var batch = ArrayList<VisionOcr.Page>(VisionOcr.BATCH_SIZE)
            while (rendered < maxPages) {
                val prepared = pager.renderNext() ?: break
                batch.add(VisionOcr.Page(rendered, prepared))
                rendered++
                if (batch.size >= VisionOcr.BATCH_SIZE) {
                    val (pts, errs) = vision.transcribeBatch(batch, displayName)
                    pts.forEach { texts[it.index] = it.text }
                    failures.addAll(errs)
                    batch = ArrayList(VisionOcr.BATCH_SIZE)
                    onProgress("识别 PDF 第 $rendered/$expected 页…")
                }
            }
            if (batch.isNotEmpty()) {
                val (pts, errs) = vision.transcribeBatch(batch, displayName)
                pts.forEach { texts[it.index] = it.text }
                failures.addAll(errs)
                onProgress("识别 PDF 第 $rendered/$expected 页…")
            }
        } finally {
            runCatching { pager.close() }
        }
        if (rendered == 0) throw IllegalStateException("PDF 无法渲染，可能已加密或损坏")

        val sb = StringBuilder()
        for (i in 0 until rendered) {
            val t = texts[i] ?: continue
            sb.append("【第").append(i + 1).append("页】\n").append(t).append("\n\n")
        }
        val text = sb.toString().trim()
        if (text.isBlank()) {
            throw IllegalStateException(failures.firstOrNull()?.take(120) ?: "视觉模型没有返回内容")
        }
        if (realPages > rendered) failures.add("PDF 共 $realPages 页，仅识别前 $rendered 页")
        PdfOcr(text, failures, rendered)
    }

    private fun cleanup(text: String): String {
        var t = text.replace("\r\n", "\n").replace('\u0000', ' ')
        t = t.replace(Regex("[ \\t\\u3000]{2,}"), "  ")
        t = t.replace(Regex("\n{3,}"), "\n\n")
        return t.trim()
    }

    private fun progressText(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        val label = when (ext) {
            "doc", "docx", "docm", "dot", "dotx", "rtf", "odt", "wps" -> "Word 文档"
            "xls", "xlsx", "xlsm", "xlt", "xltx", "ods", "et" -> "Excel 表格"
            "ppt", "pptx", "pptm", "pot", "potx", "odp", "dps" -> "PPT"
            "htm", "html" -> "网页"
            "txt", "md", "csv", "json", "xml", "log" -> "文本"
            else -> "文档"
        }
        return if (label == "文本") "读取文本…" else "解析$label…"
    }
}
