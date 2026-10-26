package com.howtodo.ingest

import com.howtodo.ai.AiRouter
import com.howtodo.ai.LlmClient

/**
 * 视觉模型转写。
 *
 * 对微信通知截图这类材料，直接让视觉模型"逐字转写 + 保留所有日期时间人名地点"
 * 比传统 OCR 更稳（手写、倾斜、聊天气泡、合并转发都能读），也不需要额外依赖。
 *
 * 页码约定：多页文档的正文里由本类拼上「【第N页】」标记（N = 渲染序号 + 1，即 PDF 真实页号），
 * 上游据此切出带页码的 chunk。识别失败**不再把「（第N部分识别失败）」之类的占位文本
 * 混进正文**——那会被切块、进索引、甚至被当成原文引用；失败明细单独返回，由上游展示。
 */
class VisionOcr(private val router: AiRouter) {

    private val systemPrompt = """
        你是一个严谨的中文文档转写器。你的唯一任务是把图片里的文字**完整、逐字**转写出来。

        规则：
        1. 不要总结、不要改写、不要补充图片里没有的内容。
        2. 时间是关键信息，"11月5日17:00前""下周三""本周内"等必须原样保留。
        3. 人名、部门名、地点、电话号码、金额、数量必须原样保留。
        4. 聊天截图请保留说话人与内容的对应关系，格式为「说话人：内容」。
        5. 表格用 Markdown 表格表示。
        6. 图片里看不清的字用 [?] 代替，不要猜。
        7. 如果是多张图片，按顺序分别用「=== 第N张 ===」分隔（N 按 1、2、3… 编）。
        8. 只输出转写结果，不要任何解释。
    """.trimIndent()

    data class Page(val index: Int, val image: ImageIngest.Prepared)

    /** 单页转写结果：text 为 null 表示该页失败 */
    data class PageTranscript(val index: Int, val text: String?)

    /** 转写总产出：正文（含页码标记）+ 失败明细（空列表 = 全部成功） */
    data class Transcribed(val text: String, val failures: List<String>)

    /**
     * 批量转写。每批最多 3 张，兼顾识别质量与请求大小；
     * 失败的页不进正文，明细进 [Transcribed.failures]。
     */
    suspend fun transcribe(
        pages: List<Page>,
        documentName: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<Transcribed> {
        if (pages.isEmpty()) return Result.failure(IllegalStateException("没有可识别的图片"))
        val texts = HashMap<Int, String?>()
        val failures = ArrayList<String>()
        var done = 0
        pages.chunked(BATCH_SIZE).forEach { batch ->
            val (pts, errs) = transcribeBatch(batch, documentName)
            pts.forEach { texts[it.index] = it.text }
            failures.addAll(errs)
            done += batch.size
            onProgress(done, pages.size)
        }
        val sb = StringBuilder()
        for (p in pages) {
            val t = texts[p.index] ?: continue
            if (pages.size > 1) sb.append("【第").append(p.index + 1).append("页】\n")
            sb.append(t).append("\n\n")
        }
        val text = sb.toString().trim()
        return if (text.isBlank()) {
            Result.failure(IllegalStateException(failures.firstOrNull()?.take(120) ?: "视觉模型没有返回内容"))
        } else {
            Result.success(Transcribed(text, failures))
        }
    }

    /** 单批（≤3 张）转写。返回逐页结果与失败明细。供 IngestPipeline 流式调用 */
    suspend fun transcribeBatch(
        batch: List<Page>,
        documentName: String
    ): Pair<List<PageTranscript>, List<String>> {
        val images = batch.map { LlmClient.Part.Image(it.image.mime, it.image.base64) }
        val userText = buildString {
            append("文档名：").append(documentName).append('\n')
            append("请转写下面 ").append(batch.size).append(" 张图片中的全部文字。")
            if (batch.size > 1) append("按「=== 第N张 ===」分隔，N 按 1~").append(batch.size).append(" 编。")
        }
        val res = router.vision(systemPrompt, userText, images, maxTokens = 4096)
        return when (res) {
            is LlmClient.Result.Ok -> {
                val text = res.value.text.trim()
                if (text.isBlank()) {
                    batch.map { PageTranscript(it.index, null) } to
                        listOf("第${batch.first().index + 1}页起共 ${batch.size} 页没有识别到文字")
                } else if (batch.size == 1) {
                    listOf(PageTranscript(batch[0].index, text)) to emptyList()
                } else {
                    val segments = splitSheets(text)
                    if (segments.size == batch.size) {
                        batch.mapIndexed { i, p ->
                            val seg = segments[i]
                            if (seg.isBlank()) {
                                PageTranscript(p.index, null)
                            } else {
                                PageTranscript(p.index, seg)
                            }
                        } to segments.mapIndexedNotNull { i, seg ->
                            if (seg.isBlank()) "第${batch[i].index + 1}页没有识别到文字" else null
                        }
                    } else {
                        batch.mapIndexed { i, p ->
                            if (i == 0) PageTranscript(p.index, text) else PageTranscript(p.index, null)
                        } to listOf(
                            "第${batch[batch.size - 1].index + 1}页起的识别结果未能按页拆分，只保留了第${batch[0].index + 1}页的部分"
                        )
                    }
                }
            }
            is LlmClient.Result.Err -> batch.map { PageTranscript(it.index, null) } to
                batch.map { "第${it.index + 1}页识别失败：${res.error.message.take(80)}" }
        }
    }

    /** 按「=== ... ===」分隔行拆段；开头空段（第一个分隔符之前的空内容）丢弃 */
    private fun splitSheets(text: String): List<String> {
        val parts = Regex("={2,}[^=\\n]{0,16}={2,}").split(text).map { it.trim() }
        return if (parts.size >= 2 && parts.first().isEmpty()) parts.drop(1) else parts
    }

    suspend fun transcribeSingle(image: ImageIngest.Prepared, name: String): Result<Transcribed> =
        transcribe(listOf(Page(0, image)), name)

    /** 纯图片路径：把多张图片文件一起转写 */
    suspend fun transcribeFiles(
        files: List<java.io.File>,
        documentName: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result<Transcribed> {
        val pages = ArrayList<Page>()
        files.forEachIndexed { i, f ->
            ImageIngest.prepare(f)?.let { pages.add(Page(i, it)) }
        }
        if (pages.isEmpty()) return Result.failure(IllegalStateException("图片解码失败"))
        return transcribe(pages, documentName, onProgress)
    }

    companion object {
        const val BATCH_SIZE = 3
    }
}
