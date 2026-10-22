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

        val text: String = try {
            when (source.kind) {
                SourceKind.IMAGE -> {
                    onProgress("识别图片…")
                    usedVision = true
                    val prepared = ImageIngest.prepare(file)
                        ?: throw IllegalStateException("图片解码失败")
                    pageCount = 1
                    vision.transcribeSingle(prepared, source.displayName).getOrThrow()
                }

                SourceKind.PDF -> {
                    onProgress("识别 PDF…")
                    usedVision = true
                    val pages = ArrayList<VisionOcr.Page>()
                    PdfIngest.renderPages(file, pdfMaxPages) { idx, prepared ->
                        pages.add(VisionOcr.Page(idx, prepared))
                    }
                    pageCount = pages.size
                    if (pages.isEmpty()) throw IllegalStateException("PDF 无法渲染，可能已加密或损坏")
                    vision.transcribe(pages, source.displayName) { done, total ->
                        onProgress("识别 PDF 第 $done/$total 页…")
                    }.getOrThrow()
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
        val pieces = Chunker.split(cleaned)
        if (pieces.isEmpty()) {
            sourceDao.setStatus(sourceId, SourceStatus.FAILED, "内容过短")
            return Result.failure(IllegalStateException("内容过短"))
        }

        // 重建索引
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
        sourceDao.setStatus(source.id, SourceStatus.PARSED)
        return Result.success(Outcome(cleaned.length, ids.size, usedVision))
    }

    /** 全文（按序拼接），供抽取使用 */
    suspend fun fullText(sourceIds: List<String>): String {
        val sb = StringBuilder()
        sourceIds.forEach { sid ->
            val src = db.sourceDao().byId(sid) ?: return@forEach
            val chunks = db.chunkDao().bySource(sid)
            if (chunks.isEmpty()) return@forEach
            sb.append("\n=== 《").append(src.displayName).append("》 ===\n")
            chunks.forEach { sb.append(it.text).append('\n') }
        }
        return sb.toString().trim()
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
