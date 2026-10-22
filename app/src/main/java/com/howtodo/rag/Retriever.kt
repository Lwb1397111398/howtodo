package com.howtodo.rag

import androidx.room.Dao
import androidx.room.Query
import com.howtodo.ai.AiRouter
import com.howtodo.ai.JsonExtract
import com.howtodo.ai.LlmClient
import com.howtodo.ai.Prompts
import com.howtodo.data.db.AiRole
import com.howtodo.data.db.AppDatabase
import com.howtodo.data.db.ChunkEntity
import com.howtodo.data.db.FtsStore

@Dao
interface RagDao {
    @Query(
        """
        SELECT * FROM chunk
        WHERE (:projectId IS NULL OR projectId = :projectId)
          AND text LIKE :pattern
        ORDER BY id DESC LIMIT :limit
        """
    )
    suspend fun likeSearch(projectId: String?, pattern: String, limit: Int): List<ChunkEntity>

    @Query("SELECT * FROM chunk WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<ChunkEntity>
}

/**
 * 检索器：中文 bigram + FTS5 BM25，无命中时退化为 LIKE。
 *
 * 刻意不用向量检索：embedding 需要额外的模型与费用，而单位通知类材料的关键词
 * 重合度很高（"报送""截止""负责人"），bigram + BM25 的实际召回已经够用，
 * 且完全离线、零额外成本。
 */
class Retriever(
    private val db: AppDatabase,
    private val router: AiRouter
) {

    private val ragDao get() = db.ragDao()

    data class Hit(
        val chunk: ChunkEntity,
        val score: Double,
        val sourceName: String,
        val sourceId: String,
        val matchedKeywords: Int
    )

    suspend fun keywordsOf(question: String): List<String> {
        // 先用快模型改写；失败/未配置则本地切词
        val (json, _) = try {
            router.chatJson(
                AiRole.FAST,
                Prompts.KEYWORD_SYSTEM,
                question,
                maxTokens = 256
            )
        } catch (_: Throwable) {
            null to ""
        }
        val fromLlm = json?.getAsJsonArray("keywords")
            ?.mapNotNull { if (it.isJsonNull) null else it.asString.trim() }
            ?.filter { it.length in 2..12 }
            ?.take(6)
            ?.takeIf { it.isNotEmpty() }

        return fromLlm ?: Bigram.localKeywords(question, 6).ifEmpty {
            // 最后兜底：整句直接当关键词
            listOf(Bigram.normalize(question).take(12)).filter { it.length >= 2 }
        }
    }

    suspend fun search(
        question: String,
        projectId: String?,
        crossProject: Boolean = false,
        limit: Int = 12
    ): List<Hit> {
        val keywords = keywordsOf(question)
        if (keywords.isEmpty()) return emptyList()
        val scope = if (crossProject) null else projectId

        // 1) FTS5
        val expr = Bigram.matchExpression(keywords, allRequired = false)
        val rowids = if (expr != null) {
            FtsStore.search(db.openHelper.writableDatabase, expr, limit * 4)
        } else {
            emptyList()
        }

        val byId = if (rowids.isNotEmpty()) {
            ragDao.byIds(rowids).associateBy { it.id }
        } else {
            emptyMap()
        }

        var ordered = rowids.mapNotNull { byId[it] }
            .filter { scope == null || it.projectId == scope }

        var usedFallback = false
        // 2) LIKE 兜底
        if (ordered.isEmpty()) {
            usedFallback = true
            val seen = HashSet<Long>()
            val collected = ArrayList<ChunkEntity>()
            keywords.take(4).forEach { kw ->
                ragDao.likeSearch(scope, "%${kw}%", limit).forEach { c ->
                    if (seen.add(c.id)) collected.add(c)
                }
            }
            ordered = collected
        }

        if (ordered.isEmpty()) return emptyList()

        // 3) 重排：位置分 + 关键词覆盖率 + 新鲜度
        val now = System.currentTimeMillis()
        val names = HashMap<String, String>()
        val hits = ordered.mapIndexed { idx, c ->
            val src = db.sourceDao().byId(c.sourceId)
            val name = names.getOrPut(c.sourceId) { src?.displayName ?: "材料" }
            val coverage = Bigram.coverage(keywords, c.text)
            val rankScore = 1.0 - (idx.toDouble() / (ordered.size + 1))
            val ageDays = ((now - (src?.importedAt ?: now)) / 86_400_000.0).coerceAtLeast(0.0)
            val recency = 1.0 / (1.0 + ageDays / 60.0)
            val score = if (usedFallback) {
                0.75 * coverage + 0.2 * rankScore + 0.05 * recency
            } else {
                0.55 * coverage + 0.30 * rankScore + 0.10 * recency + 0.05
            }
            Hit(
                chunk = c,
                score = score,
                sourceName = name,
                sourceId = c.sourceId,
                matchedKeywords = keywords.count { Bigram.normalize(c.text).contains(Bigram.normalize(it)) }
            )
        }.sortedByDescending { it.score }

        // 单一来源最多 3 块，避免一份长文档淹没其他材料
        val perSource = HashMap<String, Int>()
        return hits.filter { h ->
            val n = perSource.getOrDefault(h.sourceId, 0)
            if (n >= 3) false else {
                perSource[h.sourceId] = n + 1
                true
            }
        }.take(limit)
    }
}
