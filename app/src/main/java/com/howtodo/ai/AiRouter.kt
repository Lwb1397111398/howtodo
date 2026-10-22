package com.howtodo.ai

import com.howtodo.data.db.AiRole
import com.howtodo.data.db.RoleBindingEntity
import com.howtodo.data.repo.ProviderRepo

/**
 * 角色路由：把「识图 / 推理 / 快速 / 问答」映射到用户配置的具体供应商+模型。
 *
 * 用户明确要求不同功能可以用不同 API（便宜的模型跑关键词改写，强模型跑抽取，
 * 视觉模型读截图），所以所有调用都必须经由这里，不允许在业务里写死模型名。
 */
class AiRouter(
    private val providerRepo: ProviderRepo,
    private val client: LlmClient
) {

    data class Target(
        val role: String,
        val providerId: String,
        val providerName: String,
        val baseUrl: String,
        val apiKey: String,
        val modelId: String,
        val binding: RoleBindingEntity,
        val extraHeaders: Map<String, String>
    )

    class NotConfigured(val role: String) :
        IllegalStateException("未配置「${AiRole.label(role)}」使用的模型，请到设置里绑定一个供应商和模型")

    /** 按角色取目标；该角色未绑定时回退到推理/问答角色 */
    suspend fun target(role: String): Target {
        val chain = when (role) {
            AiRole.VISION -> listOf(AiRole.VISION, AiRole.REASONING, AiRole.CHAT)
            AiRole.FAST -> listOf(AiRole.FAST, AiRole.CHAT, AiRole.REASONING)
            AiRole.CHAT -> listOf(AiRole.CHAT, AiRole.REASONING)
            else -> listOf(AiRole.REASONING, AiRole.CHAT)
        }
        for (r in chain) {
            val binding = providerRepo.binding(r) ?: continue
            val pid = binding.providerId ?: continue
            val mid = binding.modelId ?: continue
            val provider = providerRepo.byId(pid) ?: continue
            val key = providerRepo.apiKeyOf(provider) ?: continue
            return Target(
                role = role,
                providerId = pid,
                providerName = provider.name,
                baseUrl = provider.baseUrl,
                apiKey = key,
                modelId = mid,
                binding = binding,
                extraHeaders = providerRepo.extraHeadersOf(provider)
            )
        }
        throw NotConfigured(role)
    }

    suspend fun targetOrNull(role: String): Target? = try {
        target(role)
    } catch (_: Throwable) {
        null
    }

    suspend fun isConfigured(): Boolean = targetOrNull(AiRole.REASONING) != null

    /** 纯文本调用 */
    suspend fun chat(
        role: String,
        system: String?,
        user: String,
        temperature: Double? = null,
        maxTokens: Int? = null,
        jsonMode: Boolean = false
    ): LlmClient.Result {
        val t = target(role)
        val msgs = ArrayList<LlmClient.Msg>()
        if (!system.isNullOrBlank()) msgs.add(LlmClient.Msg("system", system))
        msgs.add(LlmClient.Msg("user", user))
        return client.chat(
            baseUrl = t.baseUrl,
            apiKey = t.apiKey,
            model = t.modelId,
            messages = msgs,
            temperature = temperature ?: t.binding.temperature,
            maxTokens = maxTokens ?: t.binding.maxOutputTokens,
            jsonMode = jsonMode,
            extraHeaders = t.extraHeaders
        )
    }

    /** 多模态调用（图片 + 文本） */
    suspend fun vision(
        system: String?,
        userText: String,
        images: List<LlmClient.Part.Image>,
        maxTokens: Int? = null
    ): LlmClient.Result {
        val t = target(AiRole.VISION)
        val parts = ArrayList<LlmClient.Part>()
        parts.add(LlmClient.Part.Text(userText))
        images.forEach { parts.add(it) }
        val msgs = ArrayList<LlmClient.Msg>()
        if (!system.isNullOrBlank()) msgs.add(LlmClient.Msg("system", system))
        msgs.add(LlmClient.Msg("user", parts))
        return client.chat(
            baseUrl = t.baseUrl,
            apiKey = t.apiKey,
            model = t.modelId,
            messages = msgs,
            temperature = 0.1,
            maxTokens = maxTokens ?: t.binding.maxOutputTokens,
            jsonMode = false,
            extraHeaders = t.extraHeaders
        )
    }

    /** 调用并解析 JSON，失败时带着原始响应返回，便于用户看到问题所在 */
    suspend fun chatJson(
        role: String,
        system: String,
        user: String,
        maxTokens: Int? = null
    ): Pair<com.google.gson.JsonObject?, String> {
        val r = chat(role, system, user, maxTokens = maxTokens, jsonMode = true)
        return when (r) {
            is LlmClient.Result.Ok -> {
                val parsed = JsonExtract.objectFrom(r.value.text)
                parsed.json to r.value.text
            }
            is LlmClient.Result.Err -> null to r.error.message
        }
    }

    fun contextBudgetOf(t: Target): Int =
        if (t.binding.contextBudget > 0) t.binding.contextBudget else 32000
}
