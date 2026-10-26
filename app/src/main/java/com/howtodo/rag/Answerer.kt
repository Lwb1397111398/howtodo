package com.howtodo.rag

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.howtodo.ai.AiRouter
import com.howtodo.ai.LlmClient
import com.howtodo.ai.Prompts
import com.howtodo.ai.TokenBudget
import com.howtodo.data.db.AiRole
import com.howtodo.data.db.AppDatabase
import com.howtodo.data.db.MessageEntity
import com.howtodo.data.db.TaskStatus
import com.howtodo.data.repo.KnowledgeRepo
import com.howtodo.util.Format

/**
 * 问答：检索 -> 装包 -> 生成 -> 引用校验。
 *
 * 产品上最关键的一条：**没有依据就说没有**。
 * 一个会编造"截止时间"的助手比没有助手更糟，所以这里对引用做了强制校验。
 */
class Answerer(
    private val db: AppDatabase,
    private val retriever: Retriever,
    private val router: AiRouter,
    private val knowledgeRepo: KnowledgeRepo
) {

    private val gson = Gson()

    data class Answer(
        val text: String,
        val citationsJson: String,
        val hitCount: Int,
        val model: String?,
        val promptTokens: Int,
        val completionTokens: Int,
        val grounded: Boolean
    )

    suspend fun answer(
        question: String,
        projectId: String?,
        crossProject: Boolean = false,
        history: List<MessageEntity> = emptyList()
    ): Answer {
        val hits = retriever.search(question, projectId, crossProject, limit = 12)

        if (hits.isEmpty()) {
            return Answer(
                text = "材料里没有找到跟这个问题相关的内容。\n\n" +
                    "可以试试：\n" +
                    "· 换几个关键词再问一次（比如直接说「材料报送」「签到表」）\n" +
                    "· 把相关通知先导入这个工作\n" +
                    "· 如果这条规矩不在材料里，建议直接问发通知的科室或对接人",
                citationsJson = "[]",
                hitCount = 0,
                model = null,
                promptTokens = 0,
                completionTokens = 0,
                grounded = true
            )
        }

        val target = try {
            router.target(AiRole.CHAT)
        } catch (e: Throwable) {
            return Answer(
                text = "还没有配置问答用的模型。请到「设置 → 模型与角色」把「问答」绑到一个模型上。",
                citationsJson = "[]",
                hitCount = hits.size,
                model = null,
                promptTokens = 0,
                completionTokens = 0,
                grounded = true
            )
        }

        val budget = router.contextBudgetOf(target)
        val (context, citations) = buildContext(hits, budget)

        val knowledge = knowledgeRepo.activeFor(projectId)
        val knownFacts = knowledgeRepo.renderFacts(knowledgeRepo.pickRelevant(knowledge, question + context.take(4000), 15))

        val tasks = if (projectId != null) {
            db.taskDao().listByProject(projectId)
                .filter { it.status != TaskStatus.CANCELLED }
                .take(20)
                .map { Triple(it.title, statusLabel(it.status), it.dueAt) }
        } else {
            emptyList()
        }

        val userPrompt = Prompts.chatUser(question, context, knownFacts, Prompts.taskBrief(tasks))

        // 带上最近 4 轮，保证追问连贯
        val messages = ArrayList<LlmClient.Msg>()
        messages.add(LlmClient.Msg("system", Prompts.CHAT_SYSTEM))
        history.takeLast(8).forEach { m ->
            if (m.content.isNotBlank()) {
                messages.add(LlmClient.Msg(if (m.role == "assistant") "assistant" else "user", m.content.take(2000)))
            }
        }
        messages.add(LlmClient.Msg("user", userPrompt))

        val result = routerClientCall(target, messages)
        var text = when (result) {
            is LlmClient.Result.Ok -> result.value.text
            is LlmClient.Result.Err -> return Answer(
                text = "调用模型失败：${result.error.message}",
                citationsJson = "[]",
                hitCount = hits.size,
                model = target.modelId,
                promptTokens = 0,
                completionTokens = 0,
                grounded = false
            )
        }

        var grounded = true
        if (!hasValidCitation(text, citations.size())) {
            // 一次修正重答：把"必须带引用"再强调一遍
            val stricter = messages.dropLast(1) + LlmClient.Msg(
                "user",
                userPrompt + "\n\n注意：上一次回答没有标注来源。这次每个事实性句子后面必须标注 [S1] 这样的编号；如果确实没有依据，请直接说明材料里没有。"
            )
            val retry = routerClientCall(target, stricter)
            if (retry is LlmClient.Result.Ok) {
                val t2 = retry.value.text
                if (hasValidCitation(t2, citations.size())) text = t2
                else {
                    text = t2
                    grounded = false
                }
            } else {
                grounded = false
            }
            if (!grounded) {
                text = text.trim() + "\n\n（提示：以上内容未能在材料中找到明确出处，请谨慎参考，或换一种问法。）"
            }
        }

        val outTokens = TokenBudget.estimate(text)
        return Answer(
            text = text.trim(),
            citationsJson = citations.toString(),
            hitCount = hits.size,
            model = target.modelId,
            promptTokens = TokenBudget.estimate(userPrompt),
            completionTokens = outTokens,
            grounded = grounded
        )
    }

    private suspend fun routerClientCall(
        target: AiRouter.Target,
        messages: List<LlmClient.Msg>
    ): LlmClient.Result {
        // 复用 AiRouter 里的单例 HTTP 客户端；旧写法每次问答 new 一个 OkHttpClient，连接池全浪费
        return router.chatWith(
            target = target,
            messages = messages,
            temperature = 0.3,
            maxTokens = minOf(target.binding.maxOutputTokens, 4096).coerceAtLeast(1024)
        )
    }

    /** 上下文装包：带 [S1] 编号，按预算贪心放入 */
    private fun buildContext(hits: List<Retriever.Hit>, contextBudget: Int): Pair<String, JsonArray> {
        val usable = TokenBudget.usable(contextBudget, 2048, fixedOverhead = 2000)
        val sb = StringBuilder()
        val arr = JsonArray()
        var used = 0
        var idx = 0

        for (h in hits) {
            val cost = h.chunk.tokensEst + 12
            if (used + cost > usable && idx > 0) break
            idx++
            val label = "S$idx"
            val page = h.chunk.pageNo?.let { " P$it" } ?: ""
            sb.append("[$label] 《").append(h.sourceName).append('》').append(page).append('\n')
            // 首块必装且可能远超预算，统一压回预算内，避免 prompt 爆上下文
            sb.append(TokenBudget.truncate(h.chunk.text, usable)).append("\n\n")
            used += cost

            val c = JsonObject()
            c.addProperty("label", label)
            c.addProperty("chunkId", h.chunk.id)
            c.addProperty("sourceId", h.sourceId)
            c.addProperty("sourceName", h.sourceName)
            if (h.chunk.pageNo != null) c.addProperty("pageNo", h.chunk.pageNo)
            c.addProperty("quote", snippet(h.chunk.text))
            c.addProperty("charStart", h.chunk.charStart)
            c.addProperty("charEnd", h.chunk.charEnd)
            arr.add(c)
        }
        return sb.toString().trim() to arr
    }

    private fun snippet(text: String, max: Int = 120): String =
        if (text.length <= max) text else text.take(max) + "…"

    private fun hasValidCitation(text: String, maxIndex: Int): Boolean {
        val found = Regex("\\[S(\\d+)\\]").findAll(text).toList()
        if (found.isEmpty()) return false
        return found.all { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@all false
            n in 1..maxIndex
        }
    }

    private fun statusLabel(s: String): String = when (s) {
        TaskStatus.DONE -> "已完成"
        TaskStatus.NEEDS_CONFIRM -> "待确认"
        TaskStatus.CANCELLED -> "已取消"
        else -> "待办"
    }
}
