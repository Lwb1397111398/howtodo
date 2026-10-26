package com.howtodo.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.howtodo.data.db.AppDatabase
import com.howtodo.data.db.BatchEntity
import com.howtodo.data.db.BatchMode
import com.howtodo.data.db.BatchStatus
import com.howtodo.data.db.ChunkEntity
import com.howtodo.data.db.EntityScope
import com.howtodo.data.db.ProposalKind
import com.howtodo.data.db.TaskEntity
import com.howtodo.data.db.TaskStatus
import com.howtodo.data.repo.KnowledgeRepo
import com.howtodo.data.repo.ProjectRepo
import com.howtodo.data.repo.ProposalRepo
import com.howtodo.data.repo.SourceRepo
import com.howtodo.data.repo.TaskRepo
import com.howtodo.util.Format
import com.howtodo.util.Storage
import java.time.LocalDate
import java.util.UUID

/**
 * 抽取用例编排：材料 -> 上下文 -> LLM -> 本地校验 -> 提案。
 *
 * 基线（第一次分析）与增量（补充材料）走两条不同路径：
 *  - 基线：要求模型给出完整任务清单
 *  - 增量：只要求模型给出**差异**，避免重跑导致已勾选状态、手工修改全部丢失
 */
class ExtractionService(
    private val db: AppDatabase,
    private val sourceRepo: SourceRepo,
    private val projectRepo: ProjectRepo,
    private val taskRepo: TaskRepo,
    private val proposalRepo: ProposalRepo,
    private val knowledgeRepo: KnowledgeRepo,
    private val router: AiRouter,
    private val storage: Storage,
    private val ingest: com.howtodo.ingest.IngestPipeline
) {

    private val gson = Gson()
    private val patchEngine = PatchEngine()

    data class Result(val proposalId: String, val mode: String, val summary: String, val itemCount: Int)

    class ExtractionException(message: String) : IllegalStateException(message)

    /** 启动兜底：分析已改在应用级作用域跑，但进程被杀仍会留下卡在 EXTRACTING 的批次，置 FAILED 让用户能重新分析 */
    suspend fun failStaleBatches() {
        db.batchDao().stale().forEach {
            db.batchDao().setStatus(it.id, BatchStatus.FAILED, "分析被中断（应用退出），请重新勾选材料分析")
        }
    }

    suspend fun analyze(
        projectId: String,
        sourceIds: List<String>,
        onProgress: (String) -> Unit = {}
    ): kotlin.Result<Result> {
        val project = projectRepo.byId(projectId)
            ?: return kotlin.Result.failure(ExtractionException("工作不存在"))
        if (sourceIds.isEmpty()) return kotlin.Result.failure(ExtractionException("没有选择材料"))

        val existingTasks = taskRepo.listByProject(projectId)
        val isBaseline = existingTasks.isEmpty() && project.baselineBatchId == null

        // 先确保材料已解析
        sourceIds.forEach { sid ->
            val src = sourceRepo.byId(sid) ?: return@forEach
            if (src.status != com.howtodo.data.db.SourceStatus.PARSED) {
                onProgress("解析材料《${src.displayName}》…")
                ingest.ingest(sid, onProgress = onProgress)
            }
        }

        val batchId = UUID.randomUUID().toString().replace("-", "")
        val batch = BatchEntity(
            id = batchId,
            projectId = projectId,
            mode = if (isBaseline) BatchMode.BASELINE else BatchMode.INCREMENT,
            sourceIds = gson.toJson(sourceIds),
            status = BatchStatus.EXTRACTING,
            promptVersion = if (isBaseline) Prompts.BASELINE_VERSION else Prompts.PATCH_VERSION
        )
        db.batchDao().insert(batch)
        projectRepo.bumpRev(projectId)

        val target = try {
            router.target(com.howtodo.data.db.AiRole.REASONING)
        } catch (e: Throwable) {
            db.batchDao().setStatus(batchId, BatchStatus.FAILED, e.message)
            return kotlin.Result.failure(ExtractionException(e.message ?: "未配置模型"))
        }

        // 组装上下文
        onProgress(if (isBaseline) "正在读懂材料…" else "正在比对新增材料…")
        val budget = router.contextBudgetOf(target)
        val (context, chunkMap) = renderContext(sourceIds, budget)
        if (context.isBlank()) {
            db.batchDao().setStatus(batchId, BatchStatus.FAILED, "材料中没有可用文字")
            return kotlin.Result.failure(ExtractionException("材料里没有可分析的文字，请检查材料是否清晰"))
        }

        val allKnowledge = knowledgeRepo.activeFor(projectId)
        val relevant = knowledgeRepo.pickRelevant(allKnowledge, context)
        val knownFacts = knowledgeRepo.renderFacts(relevant)
        val today = LocalDate.now().toString()

        val system = if (isBaseline) Prompts.BASELINE_SYSTEM else Prompts.PATCH_SYSTEM
        val user = if (isBaseline) {
            Prompts.baselineUser(context, today, knownFacts)
        } else {
            Prompts.patchUser(renderCurrentTasks(existingTasks), context, today, knownFacts)
        }

        onProgress("正在生成…")
        val raw = router.chat(
            role = com.howtodo.data.db.AiRole.REASONING,
            system = system,
            user = user,
            temperature = 0.1,
            maxTokens = minOf(target.binding.maxOutputTokens, 8192).coerceAtLeast(2048),
            jsonMode = true
        )

        val responseText = when (raw) {
            is LlmClient.Result.Ok -> raw.value.text
            is LlmClient.Result.Err -> {
                db.batchDao().setStatus(batchId, BatchStatus.FAILED, raw.error.message)
                return kotlin.Result.failure(ExtractionException(raw.error.message))
            }
        }

        // 原始请求/响应落盘，便于复盘"为什么它这么理解"
        val rawPath = storage.writeRaw("$batchId.txt", "=== USER ===\n$user\n\n=== RESPONSE ===\n$responseText")

        val parsed = JsonExtract.objectFrom(responseText)
        val obj = parsed.json
        if (obj == null) {
            db.batchDao().setStatus(batchId, BatchStatus.FAILED, parsed.error)
            return kotlin.Result.failure(
                ExtractionException(
                    "模型返回的不是合法 JSON（${parsed.error}）。可换一个更稳的模型，或在设置里降低温度。"
                )
            )
        }

        val chunkText: (Long) -> String? = { id -> chunkMap[id]?.text }
        val built = if (isBaseline) {
            val baseline = SchemaParser.parseBaseline(obj)
            patchEngine.buildBaseline(baseline, chunkText)
        } else {
            val patch = SchemaParser.parsePatch(obj)
            patchEngine.buildPatch(patch, existingTasks, chunkText)
        }

        if (built.items.isEmpty()) {
            db.batchDao().setStatus(batchId, BatchStatus.PROPOSED)
            db.batchDao().update(
                batch.copy(
                    status = BatchStatus.PROPOSED,
                    rawResponsePath = rawPath,
                    finishedAt = System.currentTimeMillis()
                )
            )
            return kotlin.Result.failure(ExtractionException("这次材料没有导致任何变化：${built.summary}"))
        }

        val proposal = proposalRepo.create(
            projectId = projectId,
            batchId = batchId,
            kind = if (isBaseline) ProposalKind.BASELINE else ProposalKind.PATCH,
            summary = built.summary,
            baseRev = db.projectDao().byId(projectId)?.schemaRev ?: 0,
            conflicts = built.conflicts,
            unknowns = built.unknowns,
            items = built.items
        )

        // 全局知识：直接沉淀，但标记为"未确认"，用户可在知识页核对
        val entities = if (isBaseline) {
            SchemaParser.parseBaseline(obj).entities
        } else {
            SchemaParser.parsePatch(obj).entities
        }
        entities.forEach { e ->
            if (e.name.isBlank() || e.confidence < 0.55) return@forEach
            knowledgeRepo.merge(
                knowledgeRepo.newEntity(
                    type = e.type,
                    name = e.name,
                    aliases = e.aliases,
                    attributes = e.attributes,
                    summary = e.summary,
                    scope = EntityScope.GLOBAL,
                    confidence = e.confidence,
                    chunkId = e.chunkId,
                    quote = e.quote
                )
            )
        }

        db.batchDao().setStatus(batchId, BatchStatus.PROPOSED)
        db.batchDao().update(
            batch.copy(
                status = BatchStatus.PROPOSED,
                rawResponsePath = rawPath,
                providerId = target.providerId,
                modelId = target.modelId,
                requestTokens = TokenBudget.estimate(user),
                responseTokens = TokenBudget.estimate(responseText),
                finishedAt = System.currentTimeMillis()
            )
        )
        if (isBaseline) projectRepo.setBaseline(projectId, batchId)
        sourceIds.forEach { sourceRepo.setBatch(it, batchId) }
        projectRepo.touch(projectId)

        return kotlin.Result.success(
            Result(proposal.id, if (isBaseline) "BASELINE" else "INCREMENT", built.summary, built.items.size)
        )
    }

    // ---------------------------------------------------------------- 上下文

    /**
     * 渲染材料上下文。每个片段带 [C{id}] 编号，模型引用它，我们再回查原文。
     * 按 token 预算装包，超长时按源轮流取，避免只有第一份材料进上下文。
     */
    private suspend fun renderContext(
        sourceIds: List<String>,
        contextBudget: Int
    ): Pair<String, Map<Long, ChunkEntity>> {
        val usable = TokenBudget.usable(contextBudget, 3000)
        val bySource = LinkedHashMap<String, MutableList<ChunkEntity>>()
        sourceIds.forEach { sid ->
            val chunks = db.chunkDao().bySource(sid)
            if (chunks.isNotEmpty()) bySource[sid] = chunks.toMutableList()
        }

        val picked = ArrayList<ChunkEntity>()
        val map = HashMap<Long, ChunkEntity>()
        var used = 0
        var round = 0
        var added = true
        while (added && used < usable) {
            added = false
            for ((_, list) in bySource) {
                if (round >= list.size) continue
                val c = list[round]
                val cost = c.tokensEst + 6
                if (used + cost > usable) continue
                picked.add(c)
                map[c.id] = c
                used += cost
                added = true
            }
            round++
        }

        if (picked.isEmpty()) {
            // 兜底：至少塞第一个片段。单块可能远超预算（OCR 长输出等），
            // 渲染时用 truncate 压回预算内，避免 prompt 直接爆掉模型上下文
            bySource.values.firstOrNull()?.firstOrNull()?.let {
                picked.add(it)
                map[it.id] = it
            }
        }

        val names = sourceIds.associateWith { sid ->
            db.sourceDao().byId(sid)?.displayName ?: "材料"
        }

        val sb = StringBuilder()
        picked.sortedWith(compareBy({ sourceIds.indexOf(it.sourceId) }, { it.ord })).forEach { c ->
            val name = names[c.sourceId] ?: "材料"
            sb.append("[C").append(c.id).append("] 《").append(name).append("》")
            if (c.pageNo != null) sb.append(" P").append(c.pageNo)
            sb.append('\n')
            // 单块超预算时截断渲染（map 里仍存原文，证据校验不受影响）
            sb.append(TokenBudget.truncate(c.text, usable)).append("\n\n")
        }
        return sb.toString().trim() to map
    }

    private fun renderCurrentTasks(tasks: List<TaskEntity>): String {
        if (tasks.isEmpty()) return "（当前没有任务）"
        val sb = StringBuilder()
        tasks.forEach { t ->
            sb.append("- ext_key=").append(t.extKey)
            sb.append(" | ").append(t.title)
            sb.append(" | 状态=").append(
                when (t.status) {
                    TaskStatus.DONE -> "已完成（不要修改）"
                    TaskStatus.NEEDS_CONFIRM -> "待确认"
                    TaskStatus.CANCELLED -> "已取消"
                    else -> "待办"
                }
            )
            sb.append(" | 截止=").append(Format.date(t.dueAt).ifBlank { "未定" })
            if (t.dueHasTime) sb.append(' ').append(Format.time(t.dueAt))
            if (!t.detail.isNullOrBlank()) sb.append(" | 说明=").append(t.detail.take(60))
            sb.append('\n')
        }
        return sb.toString().trim()
    }
}
