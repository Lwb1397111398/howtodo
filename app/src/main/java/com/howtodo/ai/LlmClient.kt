package com.howtodo.ai

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容的 Chat Completions 客户端。
 *
 * 只依赖 /chat/completions 与 /models 两个端点，因此 DeepSeek、通义千问（兼容模式）、
 * 豆包、智谱、Kimi、SiliconFlow、OpenAI 都能直接用，用户填 baseUrl + key 即可。
 *
 * 设计上刻意做了几家供应商的兼容处理：
 *  - response_format 不被支持 -> 去掉后重试
 *  - max_tokens 不被支持 -> 换 max_completion_tokens 重试
 *  - 返回内容可能为空但 reasoning_content 有值 -> 兜底取 reasoning
 */
class LlmClient(
    private val http: OkHttpClient = defaultClient()
) {

    private val gson = Gson()

    sealed class Part {
        data class Text(val text: String) : Part()
        /** 图片以 base64 data URL 传递，避免依赖图床 */
        data class Image(val mime: String, val base64: String) : Part()
    }

    data class Msg(val role: String, val parts: List<Part>) {
        constructor(role: String, text: String) : this(role, listOf(Part.Text(text)))
    }

    data class Response(
        val text: String,
        val promptTokens: Int,
        val completionTokens: Int,
        val finishReason: String?
    )

    data class Failure(val message: String, val code: Int = -1)

    /** 调用结果 */
    sealed class Result {
        data class Ok(val value: Response) : Result()
        data class Err(val error: Failure) : Result()
    }

    fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<Msg>,
        temperature: Double = 0.2,
        maxTokens: Int = 4096,
        jsonMode: Boolean = false,
        extraHeaders: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 300
    ): Result {
        var useJsonMode = jsonMode
        var useMaxCompletionTokens = false

        repeat(3) { attempt ->
            val body = buildBody(model, messages, temperature, maxTokens, useJsonMode, useMaxCompletionTokens)
            val result = post(baseUrl, apiKey, body, extraHeaders, timeoutSeconds)
            when (result) {
                is Result.Ok -> return result
                is Result.Err -> {
                    val msg = result.error.message
                    // 兼容性重试：仅在同一次调用内、且明确是参数不被支持时降级
                    if (useJsonMode && (msg.contains("response_format", true) || msg.contains("json_object", true))) {
                        useJsonMode = false
                        return@repeat
                    }
                    if (!useMaxCompletionTokens &&
                        (msg.contains("max_completion_tokens", true) || msg.contains("max_tokens", true) &&
                            msg.contains("not supported", true))
                    ) {
                        useMaxCompletionTokens = true
                        return@repeat
                    }
                    if (attempt < 2 && result.error.code in RETRYABLE) {
                        Thread.sleep(800L * (attempt + 1))
                        return@repeat
                    }
                    return result
                }
            }
        }
        return Result.Err(Failure("请求重试后仍失败"))
    }

    private fun buildBody(
        model: String,
        messages: List<Msg>,
        temperature: Double,
        maxTokens: Int,
        jsonMode: Boolean,
        useMaxCompletionTokens: Boolean
    ): String {
        val root = JsonObject()
        root.addProperty("model", model)
        val arr = JsonArray()
        for (m in messages) {
            val mo = JsonObject()
            mo.addProperty("role", m.role)
            if (m.parts.size == 1 && m.parts[0] is Part.Text) {
                mo.addProperty("content", (m.parts[0] as Part.Text).text)
            } else {
                val parts = JsonArray()
                for (p in m.parts) {
                    when (p) {
                        is Part.Text -> {
                            val o = JsonObject()
                            o.addProperty("type", "text")
                            o.addProperty("text", p.text)
                            parts.add(o)
                        }
                        is Part.Image -> {
                            val o = JsonObject()
                            o.addProperty("type", "image_url")
                            val img = JsonObject()
                            img.addProperty("url", "data:${p.mime};base64,${p.base64}")
                            o.add("image_url", img)
                            parts.add(o)
                        }
                    }
                }
                mo.add("content", parts)
            }
            arr.add(mo)
        }
        root.add("messages", arr)
        root.addProperty("temperature", temperature)
        if (useMaxCompletionTokens) {
            root.addProperty("max_completion_tokens", maxTokens)
        } else {
            root.addProperty("max_tokens", maxTokens)
        }
        if (jsonMode) {
            val rf = JsonObject()
            rf.addProperty("type", "json_object")
            root.add("response_format", rf)
        }
        return gson.toJson(root)
    }

    private fun post(
        baseUrl: String,
        apiKey: String,
        body: String,
        extraHeaders: Map<String, String>,
        timeoutSeconds: Long
    ): Result {
        val url = joinUrl(baseUrl, "chat/completions")
        return try {
            val builder = Request.Builder()
                .url(url)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
            if (apiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer $apiKey")
            extraHeaders.forEach { (k, v) -> builder.addHeader(k, v) }
            builder.post(body.toRequestBody(JSON_MEDIA))

            val client = http.newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(timeoutSeconds + 30, TimeUnit.SECONDS)
                .build()

            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return Result.Err(Failure(extractError(text) ?: "HTTP ${resp.code}", resp.code))
                }
                parseResponse(text)
            }
        } catch (e: Throwable) {
            Result.Err(Failure(e.message ?: e.javaClass.simpleName, -2))
        }
    }

    private fun parseResponse(text: String): Result {
        return try {
            val root = JsonParser.parseString(text).asJsonObject
            val choices = root.getAsJsonArray("choices")
            if (choices == null || choices.size() == 0) {
                Result.Err(Failure("响应中没有 choices"))
            } else {
                val choice = choices[0].asJsonObject
                val msg = choice.getAsJsonObject("message")
                var content = msg?.get("content")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
                if (content.isBlank()) {
                    // 部分推理模型把正文放在 reasoning_content / reasoning
                    content = msg?.get("reasoning_content")?.takeIf { !it.isJsonNull }?.asString
                        ?: msg?.get("reasoning")?.takeIf { !it.isJsonNull }?.asString
                        ?: ""
                }
                val usage = root.getAsJsonObject("usage")
                Result.Ok(
                    Response(
                        text = content,
                        promptTokens = usage?.get("prompt_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                        completionTokens = usage?.get("completion_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                        finishReason = choice.get("finish_reason")?.takeIf { !it.isJsonNull }?.asString
                    )
                )
            }
        } catch (e: Throwable) {
            Result.Err(Failure("解析响应失败：${e.message}"))
        }
    }

    private fun extractError(body: String): String? = try {
        val o = JsonParser.parseString(body).asJsonObject
        val err = o.getAsJsonObject("error")
        err?.get("message")?.asString ?: o.get("message")?.asString ?: body.take(300)
    } catch (_: Throwable) {
        body.take(300)
    }

    data class ModelInfo(
        val id: String,
        val ownedBy: String?,
        val raw: String
    )

    /** 拉取模型列表；部分供应商不支持该端点，调用方需处理空列表并允许手填 */
    fun listModels(baseUrl: String, apiKey: String, extraHeaders: Map<String, String> = emptyMap()): List<ModelInfo> {
        val url = joinUrl(baseUrl, "models")
        return try {
            val builder = Request.Builder().url(url).get().addHeader("Accept", "application/json")
            if (apiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer $apiKey")
            extraHeaders.forEach { (k, v) -> builder.addHeader(k, v) }
            val client = http.newBuilder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(40, TimeUnit.SECONDS)
                .build()
            client.newCall(builder.build()).execute().use { resp ->
                val bodyText = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    emptyList()
                } else {
                    val root = JsonParser.parseString(bodyText).asJsonObject
                    val data = root.getAsJsonArray("data")
                    if (data == null) {
                        emptyList()
                    } else {
                        data.mapNotNull { el ->
                            val o = el.asJsonObject
                            val id = o.get("id")?.takeIf { !it.isJsonNull }?.asString
                            if (id == null) null
                            else ModelInfo(id, o.get("owned_by")?.takeIf { !it.isJsonNull }?.asString, gson.toJson(o))
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val RETRYABLE = setOf(408, 429, 500, 502, 503, 504, -2)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        /** 拼接 baseUrl 与路径，兼容用户填「带 /v1」或「不带 /v1」两种情况 */
        fun joinUrl(baseUrl: String, path: String): String {
            var b = baseUrl.trim().trimEnd('/')
            if (b.endsWith("/chat/completions")) b = b.removeSuffix("/chat/completions")
            if (b.endsWith("/models")) b = b.removeSuffix("/models")
            return "$b/$path"
        }
    }
}
