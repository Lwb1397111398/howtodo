package com.howtodo.data.repo

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.howtodo.data.db.AppDatabase
import com.howtodo.data.db.EntityScope
import com.howtodo.data.db.KnowledgeEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * 全局知识（人物/地点/时间规则…）。
 *
 * 这一层不只是"存起来"，更重要的用途是**反向注入**到材料解析：
 * 知道"张老师 17:00 下班"，LLM 才能把"把材料交给张老师"推断成"需 17:00 前送达"。
 */
class KnowledgeRepo(private val db: AppDatabase) {

    private val dao get() = db.knowledgeDao()
    private val gson = Gson()

    fun observeAll(): Flow<List<KnowledgeEntity>> = dao.observeAll()

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun allActive(): List<KnowledgeEntity> = dao.allActive()

    suspend fun activeFor(projectId: String?): List<KnowledgeEntity> = dao.activeFor(projectId)

    suspend fun byId(id: String) = dao.byId(id)

    suspend fun search(q: String) = dao.search(q)

    suspend fun save(e: KnowledgeEntity) = dao.upsert(e)

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun verify(id: String) = dao.verify(id)

    fun newEntity(
        type: String,
        name: String,
        aliases: List<String> = emptyList(),
        attributes: Map<String, String> = emptyMap(),
        summary: String? = null,
        scope: String = EntityScope.GLOBAL,
        projectId: String? = null,
        confidence: Double = 0.8,
        sourceId: String? = null,
        chunkId: Long? = null,
        quote: String? = null
    ): KnowledgeEntity = KnowledgeEntity(
        id = UUID.randomUUID().toString().replace("-", ""),
        type = type,
        name = name.trim(),
        aliases = gson.toJson(aliases),
        attributes = gson.toJson(attributes),
        summary = summary,
        scope = scope,
        projectId = projectId,
        confidence = confidence,
        sourceId = sourceId,
        chunkId = chunkId,
        quote = quote
    )

    /**
     * 合并写入：同名同类型视为同一实体。
     * 规则：已人工确认(verified)的字段不被覆盖；新值优先于旧的低置信值。
     */
    suspend fun merge(candidate: KnowledgeEntity): KnowledgeEntity {
        val existing = dao.findByName(candidate.name, candidate.type)
            ?: candidate.also { dao.upsert(it) }

        if (existing.id == candidate.id || existing.verified) {
            // 已确认的条目只补空字段，不覆盖
            val mergedAttrs = JsonObject()
            parseObj(existing.attributes).entrySet().forEach { mergedAttrs.add(it.key, it.value) }
            parseObj(candidate.attributes).entrySet().forEach { (k, v) ->
                if (!mergedAttrs.has(k) || mergedAttrs.get(k).asString.isBlank()) mergedAttrs.add(k, v)
            }
            val mergedAliases = (parseArr(existing.aliases) + parseArr(candidate.aliases)).distinct()
            val updated = existing.copy(
                attributes = gson.toJson(mergedAttrs),
                aliases = gson.toJson(mergedAliases),
                summary = existing.summary ?: candidate.summary,
                updatedAt = System.currentTimeMillis()
            )
            dao.upsert(updated)
            return updated
        }

        val mergedAttrs = JsonObject()
        parseObj(existing.attributes).entrySet().forEach { mergedAttrs.add(it.key, it.value) }
        parseObj(candidate.attributes).entrySet().forEach { mergedAttrs.add(it.key, it.value) }
        val mergedAliases = (parseArr(existing.aliases) + parseArr(candidate.aliases)).distinct()
        val updated = existing.copy(
            attributes = gson.toJson(mergedAttrs),
            aliases = gson.toJson(mergedAliases),
            summary = candidate.summary ?: existing.summary,
            confidence = maxOf(existing.confidence, candidate.confidence),
            sourceId = candidate.sourceId ?: existing.sourceId,
            chunkId = candidate.chunkId ?: existing.chunkId,
            quote = candidate.quote ?: existing.quote,
            updatedAt = System.currentTimeMillis()
        )
        dao.upsert(updated)
        return updated
    }

    /**
     * 从材料全文里挑出相关的已知事实，用于注入解析 prompt。
     * 匹配 name 与 aliases 的子串命中；上限 30 条 / 约 3000 字。
     */
    fun pickRelevant(entities: List<KnowledgeEntity>, haystack: String, limit: Int = 30): List<KnowledgeEntity> {
        if (entities.isEmpty()) return emptyList()
        val text = haystack.lowercase()
        val scored = entities.map { e ->
            val names = (listOf(e.name) + parseArr(e.aliases)).filter { it.isNotBlank() }
            var hit = 0
            names.forEach { n -> if (n.length >= 2 && text.contains(n.lowercase())) hit++ }
            e to hit
        }.filter { it.second > 0 }

        val picked = scored.sortedByDescending { it.second }.map { it.first }.toMutableList()
        // 时间规则/地点通常对"能不能在某个时间点完成"影响最大，补充几条高置信的
        entities.filter { it.type == "TIME_RULE" || it.type == "PLACE" }
            .filter { it.verified || it.confidence >= 0.8 }
            .forEach { if (picked.size < limit && picked.none { p -> p.id == it.id }) picked.add(it) }

        return picked.take(limit)
    }

    /** 渲染为 prompt 中的只读事实块 */
    fun renderFacts(entities: List<KnowledgeEntity>): String {
        if (entities.isEmpty()) return ""
        val sb = StringBuilder()
        var budget = 3000
        entities.forEachIndexed { i, e ->
            val line = buildString {
                append("[E${i + 1}] ")
                append(e.name)
                if (e.type.isNotBlank()) append("（${typeCn(e.type)}）")
                parseObj(e.attributes).entrySet().forEach { (k, v) ->
                    append("；$k=").append(v.asString)
                }
                e.summary?.takeIf { it.isNotBlank() }?.let { append("；").append(it) }
            }
            if (budget - line.length < 0) return@forEachIndexed
            budget -= line.length
            sb.append(line).append('\n')
        }
        return sb.toString().trim()
    }

    fun attributesOf(e: KnowledgeEntity): Map<String, String> =
        parseObj(e.attributes).entrySet().associate { it.key to it.value.asString }

    fun aliasesOf(e: KnowledgeEntity): List<String> = parseArr(e.aliases)

    private fun typeCn(t: String): String = com.howtodo.data.db.EntityType.label(t)

    private fun parseObj(json: String?): JsonObject = try {
        if (json.isNullOrBlank()) JsonObject() else JsonParser.parseString(json).asJsonObject
    } catch (_: Throwable) {
        JsonObject()
    }

    private fun parseArr(json: String?): List<String> = try {
        if (json.isNullOrBlank()) emptyList()
        else {
            val a: JsonArray = JsonParser.parseString(json).asJsonArray
            a.mapNotNull { if (it.isJsonNull) null else it.asString }
        }
    } catch (_: Throwable) {
        emptyList()
    }
}
