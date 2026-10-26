package com.howtodo.data.repo

import com.howtodo.ai.LlmClient
import com.howtodo.data.db.AppDatabase
import com.howtodo.data.db.ProviderEntity
import com.howtodo.data.db.ProviderModelEntity
import com.howtodo.data.db.RoleBindingEntity
import com.howtodo.util.Crypto
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ProviderRepo(private val db: AppDatabase) {

    private val dao get() = db.providerDao()
    private val modelDao get() = db.providerModelDao()
    private val roleDao get() = db.roleBindingDao()

    fun observeProviders(): Flow<List<ProviderEntity>> = dao.observeAll()

    suspend fun all(): List<ProviderEntity> = dao.all()

    suspend fun byId(id: String): ProviderEntity? = dao.byId(id)

    /** apiKey 传 null 表示不修改已存的 Key */
    suspend fun save(
        id: String?,
        name: String,
        baseUrl: String,
        apiKey: String?,
        extraHeaders: String? = null
    ): ProviderEntity {
        val now = System.currentTimeMillis()
        val existing = id?.let { dao.byId(it) }
        val keyPair = if (!apiKey.isNullOrBlank()) Crypto.encrypt(apiKey) else null
        val entity = ProviderEntity(
            id = existing?.id ?: UUID.randomUUID().toString().replace("-", ""),
            name = name.trim().ifBlank { "未命名供应商" },
            baseUrl = baseUrl.trim().trimEnd('/'),
            apiKeyCipher = keyPair?.first ?: existing?.apiKeyCipher ?: ByteArray(0),
            apiKeyIv = keyPair?.second ?: existing?.apiKeyIv ?: ByteArray(0),
            extraHeaders = extraHeaders ?: existing?.extraHeaders,
            enabled = true,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        dao.insert(entity)
        return entity
    }

    /** 解密失败返回 null（Keystore 密钥被清除），UI 提示重新填写 */
    fun apiKeyOf(p: ProviderEntity): String? {
        if (p.apiKeyCipher.isEmpty()) return null
        return Crypto.decrypt(p.apiKeyCipher, p.apiKeyIv)
    }

    /** true = 存过 Key 但现在解不开（换机迁移/系统密钥库被清除），需要用户重新填写 */
    fun apiKeyBroken(p: ProviderEntity): Boolean =
        p.apiKeyCipher.isNotEmpty() && Crypto.decrypt(p.apiKeyCipher, p.apiKeyIv) == null

    fun extraHeadersOf(p: ProviderEntity): Map<String, String> {
        val raw = p.extraHeaders ?: return emptyMap()
        return try {
            val o = com.google.gson.JsonParser.parseString(raw).asJsonObject
            o.entrySet().associate { it.key to it.value.asString }
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    suspend fun delete(id: String) {
        modelDao.clear(id)
        dao.delete(id)
        // 清除引用该供应商的角色绑定
        roleDao.all().filter { it.providerId == id }.forEach {
            roleDao.upsert(it.copy(providerId = null, modelId = null))
        }
    }

    suspend fun saveModels(providerId: String, models: List<LlmClient.ModelInfo>) {
        modelDao.clear(providerId)
        if (models.isEmpty()) return
        val now = System.currentTimeMillis()
        modelDao.insertAll(
            models.map { m ->
                ProviderModelEntity(
                    providerId = providerId,
                    modelId = m.id,
                    displayName = m.id,
                    supportsVision = guessVision(m.id),
                    rawJson = m.raw,
                    fetchedAt = now
                )
            }
        )
    }

    suspend fun models(providerId: String): List<ProviderModelEntity> = modelDao.byProvider(providerId)

    fun observeModels(providerId: String) = modelDao.observeByProvider(providerId)

    suspend fun model(providerId: String, modelId: String) = modelDao.byId(providerId, modelId)

    suspend fun addManualModel(providerId: String, modelId: String, contextWindow: Int?, maxOutput: Int?) {
        modelDao.insertAll(
            listOf(
                ProviderModelEntity(
                    providerId = providerId,
                    modelId = modelId,
                    displayName = modelId,
                    contextWindow = contextWindow,
                    maxOutput = maxOutput,
                    supportsVision = guessVision(modelId)
                )
            )
        )
    }

    fun observeBindings(): Flow<List<RoleBindingEntity>> = roleDao.observeAll()

    suspend fun bindings(): List<RoleBindingEntity> = roleDao.all()

    suspend fun binding(role: String): RoleBindingEntity? = roleDao.byRole(role)

    suspend fun setBinding(
        role: String,
        providerId: String?,
        modelId: String?,
        temperature: Double = 0.2,
        maxOutputTokens: Int = 4096,
        contextBudget: Int = 32000
    ) {
        roleDao.upsert(
            RoleBindingEntity(
                role = role,
                providerId = providerId,
                modelId = modelId,
                temperature = temperature,
                maxOutputTokens = maxOutputTokens,
                contextBudget = contextBudget
            )
        )
    }

    private fun guessVision(modelId: String): Boolean {
        val id = modelId.lowercase()
        return listOf(
            "vl", "vision", "4v", "4.1v", "omni", "gpt-4o", "gpt-4.1", "gemini",
            "qwen-vl", "qwen2-vl", "qwen2.5-vl", "doubao-vision", "glm-4v", "internvl", "llava"
        ).any { id.contains(it) }
    }
}
