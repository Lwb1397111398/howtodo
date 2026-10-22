package com.howtodo.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * LLM 输出的 JSON 容错提取。
 *
 * 现实中即使给了 response_format=json_object，模型也经常：
 *  - 包一层 ```json 围栏
 *  - 前后加一句"好的，以下是分析结果："
 *  - 尾随逗号 / 单引号 / 中文引号
 * 因此所有结构化输出都必须经过这里，而不是直接 Gson.fromJson。
 */
object JsonExtract {

    private val gson = Gson()

    data class Result(val json: JsonObject?, val raw: String, val error: String?)

    fun objectFrom(text: String): Result {
        val cleaned = stripFence(text).trim()
        if (cleaned.isEmpty()) return Result(null, text, "响应为空")

        // 中文引号必须在**第一次**解析前就统一。
        // Gson 的宽松模式会把 “title” 整个当成键名解析成功，
        // 表现为 json 非空但 get("title") 为 null——比解析失败更难发现。
        val base = normalizeQuotes(cleaned)

        attempt(base)?.let { return Result(it, text, null) }

        // 截取第一个平衡的 {...}，跳过字符串里的花括号
        extractBalanced(base, '{', '}')?.let { candidate ->
            attempt(candidate)?.let { return Result(it, text, null) }
        }
        if (base != cleaned) {
            extractBalanced(cleaned, '{', '}')?.let { candidate ->
                attempt(candidate)?.let { return Result(it, text, null) }
            }
        }
        return Result(null, text, "未找到合法 JSON 对象")
    }

    private fun attempt(s: String): JsonObject? {
        parseObject(s)?.let { return it }
        val fixed = stripTrailingCommas(s)
        if (fixed != s) parseObject(fixed)?.let { return it }
        return null
    }

    private fun parseObject(s: String): JsonObject? = try {
        val el = JsonParser.parseString(s)
        if (el.isJsonObject) el.asJsonObject else null
    } catch (_: Throwable) {
        null
    }

    /** 去掉 ```json ... ``` 围栏 */
    fun stripFence(text: String): String {
        val t = text.trim()
        if (!t.startsWith("```")) return t
        val firstNl = t.indexOf('\n')
        if (firstNl < 0) return t.removePrefix("```")
        var body = t.substring(firstNl + 1)
        val close = body.lastIndexOf("```")
        if (close >= 0) body = body.substring(0, close)
        return body.trim()
    }

    /** 从任意文本中截取第一个花括号平衡的子串（跳过字符串内的括号） */
    fun extractBalanced(text: String, open: Char, close: Char): String? {
        val start = text.indexOf(open)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    /** 中文引号统一成 ASCII 引号 */
    private fun normalizeQuotes(s: String): String =
        s.replace('\u201c', '"').replace('\u201d', '"')
            .replace('\u2018', '\'').replace('\u2019', '\'')

    /** 去掉 } 或 ] 前的多余逗号 */
    private fun stripTrailingCommas(s: String): String = Regex(",\\s*([}\\]])").replace(s, "$1")

    fun toJson(el: Any?): String = gson.toJson(el)
}
