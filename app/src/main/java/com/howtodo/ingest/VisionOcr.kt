package com.howtodo.ingest

import com.howtodo.ai.AiRouter
import com.howtodo.ai.LlmClient

/**
 * 视觉模型转写。
 *
 * 对微信通知截图这类材料，直接让视觉模型"逐字转写 + 保留所有日期时间人名地点"
 * 比传统 OCR 更稳（手写、倾斜、聊天气泡、合并转发都能读），也不需要额外依赖。
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
        7. 如果是多张图片，按顺序分别用「=== 第N张 ===」分隔。
        8. 只输出转写结果，不要任何解释。
    """.trimIndent()

    data class Page(val index: Int, val image: ImageIngest.Prepared)

    /**
     * 批量转写。每批最多 3 张，兼顾识别质量与请求大小。
     */
    suspend fun transcribe(
        pages: List<Page>,
        documentName: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<String> {
        if (pages.isEmpty()) return Result.failure(IllegalStateException("没有可识别的图片"))
        val sb = StringBuilder()
        val batchSize = 3
        var done = 0
        pages.chunked(batchSize).forEach { batch ->
            val images = batch.map { LlmClient.Part.Image(it.image.mime, it.image.base64) }
            val userText = buildString {
                append("文档名：").append(documentName).append('\n')
                append("请转写下面 ").append(batch.size).append(" 张图片中的全部文字。")
                if (batch.size > 1) append("按「=== 第N张 ===」分隔。")
            }
            val res = router.vision(systemPrompt, userText, images, maxTokens = 4096)
            when (res) {
                is LlmClient.Result.Ok -> {
                    if (batch.size == 1 && pages.size > 1) {
                        sb.append("【第").append(batch[0].index + 1).append("页】\n")
                    }
                    sb.append(res.value.text.trim()).append("\n\n")
                }
                is LlmClient.Result.Err -> {
                    // 单批失败不整体失败：记下缺失，继续识别其余
                    sb.append("（第").append(batch.first().index + 1).append("部分识别失败：")
                        .append(res.error.message.take(80)).append("）\n\n")
                }
            }
            done += batch.size
            onProgress(done, pages.size)
        }
        val text = sb.toString().trim()
        return if (text.isBlank()) Result.failure(IllegalStateException("视觉模型没有返回内容"))
        else Result.success(text)
    }

    suspend fun transcribeSingle(image: ImageIngest.Prepared, name: String): Result<String> =
        transcribe(listOf(Page(0, image)), name)

    /** 纯图片路径：把多张图片文件一起转写 */
    suspend fun transcribeFiles(
        files: List<java.io.File>,
        documentName: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result<String> {
        val pages = ArrayList<Page>()
        files.forEachIndexed { i, f ->
            ImageIngest.prepare(f)?.let { pages.add(Page(i, it)) }
        }
        if (pages.isEmpty()) return Result.failure(IllegalStateException("图片解码失败"))
        return transcribe(pages, documentName, onProgress)
    }
}
