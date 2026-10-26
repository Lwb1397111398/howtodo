package com.howtodo.data.repo

import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.howtodo.data.db.AppDatabase
import com.howtodo.data.db.ChangeLogEntity
import com.howtodo.data.db.EvidenceField
import com.howtodo.data.db.ItemDecision
import com.howtodo.data.db.ProposalEntity
import com.howtodo.data.db.ProposalItemEntity
import com.howtodo.data.db.ProposalState
import com.howtodo.data.db.TaskEntity
import com.howtodo.data.db.TaskEvidenceEntity
import com.howtodo.data.db.TaskOp
import com.howtodo.data.db.TaskStatus
import java.util.UUID

/**
 * 提案的持久化与应用。
 *
 * 核心原则：AI 永不直接改任务，只产出「提案条目」，由用户逐条接受后
 * 才写进 task 表；每次写入都留 change_log 快照，支持整批撤销。
 */
class ProposalRepo(private val db: AppDatabase) {

    private val dao get() = db.proposalDao()
    private val itemDao get() = db.proposalItemDao()
    private val taskDao get() = db.taskDao()
    private val logDao get() = db.changeLogDao()
    private val gson = Gson()

    fun observeByProject(projectId: String) = dao.observeByProject(projectId)

    fun observePending() = db.proposalViewDao().observePendingWithProject()

    fun observePendingCount() = dao.observePendingCount()

    fun observeItems(proposalId: String) = itemDao.observeByProposal(proposalId)

    suspend fun byId(id: String) = dao.byId(id)

    suspend fun items(proposalId: String) = itemDao.byProposal(proposalId)

    suspend fun create(
        projectId: String,
        batchId: String,
        kind: String,
        summary: String?,
        baseRev: Int,
        conflicts: Int,
        unknowns: String?,
        items: List<ProposalItemEntity>
    ): ProposalEntity {
        val now = System.currentTimeMillis()
        val proposal = ProposalEntity(
            id = UUID.randomUUID().toString().replace("-", ""),
            projectId = projectId,
            batchId = batchId,
            kind = kind,
            state = ProposalState.PENDING,
            baseRev = baseRev,
            summary = summary,
            conflicts = conflicts,
            unknowns = unknowns,
            createdAt = now
        )
        dao.insert(proposal)
        val withOrd = items.mapIndexed { i, it -> it.copy(proposalId = proposal.id, ord = i) }
        itemDao.insertAll(withOrd)
        return proposal
    }

    suspend fun decide(itemId: Long, decision: String) = itemDao.setDecision(itemId, decision)

    suspend fun decideAll(proposalId: String, decision: String) = itemDao.setAllDecisions(proposalId, decision)

    suspend fun reject(proposalId: String) {
        itemDao.setAllDecisions(proposalId, ItemDecision.REJECT)
        dao.setState(proposalId, ProposalState.REJECTED)
    }

    data class ApplyResult(
        val created: Int,
        val updated: Int,
        val cancelled: Int,
        val evidenceWarnings: Int
    )

    /**
     * 应用所有 ACCEPT 的条目。
     * 若 proposal.baseRev 与 project.schemaRev 不一致，说明期间已有其他变更，
     * 置 SUPERSEDED 不静默应用，提示用户重新分析。
     *
     * 全程单事务：连写 task/evidence/log/reminder/proposal/batch 六张表，
     * 中途任何一步失败整体回滚，不会留下「任务改了一半、提案还是待处理」的脏状态。
     */
    suspend fun apply(proposalId: String): Result<ApplyResult> = db.withTransaction {
        val proposal = dao.byId(proposalId)
            ?: return@withTransaction Result.failure(IllegalStateException("提案不存在"))
        val project = db.projectDao().byId(proposal.projectId)
            ?: return@withTransaction Result.failure(IllegalStateException("工作不存在"))

        if (proposal.state != ProposalState.PENDING && proposal.state != ProposalState.PARTIALLY_APPLIED) {
            return@withTransaction Result.failure(IllegalStateException("该提案已处理"))
        }
        if (proposal.baseRev != project.schemaRev) {
            dao.setState(proposalId, ProposalState.SUPERSEDED, null)
            return@withTransaction Result.failure(IllegalStateException("材料或清单已变更，该提案已过期，请重新分析"))
        }

        val items = itemDao.byProposal(proposalId).filter { it.decision == ItemDecision.ACCEPT }
        var created = 0
        var updated = 0
        var cancelled = 0
        var warnings = 0
        var ord = taskDao.maxOrd(proposal.projectId) ?: 0.0

        for (item in items) {
            when (item.op) {
                TaskOp.ADD -> {
                    val p = safeObj(item.payload)
                    val extKey = p.get("extKey")?.takeIf { !it.isJsonNull }?.asString
                        ?: ("m" + UUID.randomUUID().toString().take(8))
                    // 幂等：同 extKey 已存在则退化为更新
                    val exist = taskDao.byExtKey(project.id, extKey)
                    if (exist != null) {
                        if (!applyUpdate(exist, p, proposal, item)) warnings++
                        else updated++
                        continue
                    }
                    val dueAt = p.get("dueAt")?.takeIf { !it.isJsonNull }?.asLong
                    val needsConfirm = p.get("needsConfirm")?.takeIf { !it.isJsonNull }?.asBoolean
                        ?: (dueAt == null)
                    val task = TaskEntity(
                        id = UUID.randomUUID().toString().replace("-", ""),
                        projectId = project.id,
                        extKey = extKey,
                        title = p.get("title")?.asString ?: "未命名任务",
                        detail = p.get("detail")?.takeIf { !it.isJsonNull }?.asString,
                        dueAt = dueAt,
                        dueHasTime = p.get("dueHasTime")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                        dueRaw = p.get("dueRaw")?.takeIf { !it.isJsonNull }?.asString,
                        status = if (needsConfirm) TaskStatus.NEEDS_CONFIRM else TaskStatus.TODO,
                        priority = p.get("priority")?.takeIf { !it.isJsonNull }?.asInt ?: 2,
                        ord = ++ord,
                        originBatchId = proposal.batchId,
                        lastBatchId = proposal.batchId,
                        confidence = p.get("confidence")?.takeIf { !it.isJsonNull }?.asDouble ?: 1.0,
                        assumption = p.get("assumption")?.takeIf { !it.isJsonNull }?.asString
                    )
                    taskDao.insert(task)
                    itemDao.setResult(item.id, task.id)
                    writeEvidence(task.id, item, proposal)
                    log(ChangeLogEntity(
                        projectId = project.id,
                        taskId = task.id,
                        proposalId = proposal.id,
                        op = "CREATE",
                        oldValue = null,
                        newValue = gson.toJson(task)
                    ))
                    created++
                }

                TaskOp.UPDATE -> {
                    val target = item.targetTaskId?.let { taskDao.byId(it) }
                        ?: item.targetExtKey?.let { taskDao.byExtKey(project.id, it) }
                    if (target == null) {
                        warnings++
                        continue
                    }
                    // 已完成的任务不允许被 AI 覆盖
                    if (target.status == TaskStatus.DONE) {
                        warnings++
                        continue
                    }
                    val p = safeObj(item.payload)
                    if (applyUpdate(target, p, proposal, item)) updated++ else warnings++
                }

                TaskOp.CANCEL -> {
                    val target = item.targetTaskId?.let { taskDao.byId(it) }
                        ?: item.targetExtKey?.let { taskDao.byExtKey(project.id, it) }
                    if (target == null) {
                        warnings++
                        continue
                    }
                    if (target.status == TaskStatus.DONE || target.status == TaskStatus.CANCELLED) {
                        warnings++
                        continue
                    }
                    val before = gson.toJson(target)
                    taskDao.update(target.copy(status = TaskStatus.CANCELLED, updatedAt = System.currentTimeMillis()))
                    db.reminderDao().cancelByTask(target.id)
                    log(ChangeLogEntity(
                        projectId = project.id,
                        taskId = target.id,
                        proposalId = proposal.id,
                        op = "CANCEL",
                        oldValue = before,
                        newValue = null
                    ))
                    cancelled++
                }
            }
        }

        db.projectDao().bumpRev(project.id)
        dao.setState(proposalId, ProposalState.APPLIED)
        db.batchDao().setStatus(proposal.batchId, com.howtodo.data.db.BatchStatus.APPLIED)
        db.projectDao().touch(project.id)
        Result.success(ApplyResult(created, updated, cancelled, warnings))
    }

    private suspend fun applyUpdate(
        target: TaskEntity,
        payload: JsonObject,
        proposal: ProposalEntity,
        item: ProposalItemEntity
    ): Boolean {
        val changes = payload.getAsJsonObject("changes") ?: payload
        val before = gson.toJson(target)
        var t = target
        var changed = false

        changes.get("title")?.takeIf { !it.isJsonNull }?.asString?.let {
            if (it.isNotBlank() && it != t.title) {
                t = t.copy(title = it); changed = true
            }
        }
        changes.get("detail")?.takeIf { !it.isJsonNull }?.asString?.let {
            if (it != t.detail) {
                t = t.copy(detail = it); changed = true
            }
        }
        if (changes.has("dueAt")) {
            val el = changes.get("dueAt")
            val newDue = if (el.isJsonNull) null else el.asLong
            if (newDue != t.dueAt) {
                t = t.copy(dueAt = newDue); changed = true
            }
        }
        changes.get("dueHasTime")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
            if (it != t.dueHasTime) {
                t = t.copy(dueHasTime = it); changed = true
            }
        }
        changes.get("dueRaw")?.takeIf { !it.isJsonNull }?.asString?.let {
            if (it != t.dueRaw) {
                t = t.copy(dueRaw = it); changed = true
            }
        }
        changes.get("priority")?.takeIf { !it.isJsonNull }?.asInt?.let {
            if (it != t.priority) {
                t = t.copy(priority = it); changed = true
            }
        }
        changes.get("assumption")?.takeIf { !it.isJsonNull }?.asString?.let {
            if (it != t.assumption) {
                t = t.copy(assumption = it); changed = true
            }
        }

        if (!changed) return false

        // 补上日期后，原来"待确认"的任务自动转为待办
        var status = t.status
        if (status == TaskStatus.NEEDS_CONFIRM && t.dueAt != null) status = TaskStatus.TODO

        val after = t.copy(
            status = status,
            lastBatchId = proposal.batchId,
            rev = t.rev + 1,
            updatedAt = System.currentTimeMillis()
        )
        taskDao.update(after)
        itemDao.setResult(item.id, after.id)
        writeEvidence(after.id, item, proposal)
        log(ChangeLogEntity(
            projectId = proposal.projectId,
            taskId = after.id,
            proposalId = proposal.id,
            op = "UPDATE",
            oldValue = before,
            newValue = gson.toJson(after)
        ))
        return true
    }

    private suspend fun writeEvidence(taskId: String, item: ProposalItemEntity, proposal: ProposalEntity) {
        val json = item.evidenceJson ?: return
        val list = ArrayList<TaskEvidenceEntity>()
        try {
            val arr = JsonParser.parseString(json).asJsonArray
            arr.forEach { el ->
                val o = el.asJsonObject
                list.add(
                    TaskEvidenceEntity(
                        taskId = taskId,
                        chunkId = o.get("chunkId")?.takeIf { !it.isJsonNull }?.asLong,
                        sourceId = o.get("sourceId")?.takeIf { !it.isJsonNull }?.asString,
                        field = o.get("field")?.takeIf { !it.isJsonNull }?.asString ?: EvidenceField.TITLE,
                        quote = o.get("quote")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                        pageNo = o.get("pageNo")?.takeIf { !it.isJsonNull }?.asInt,
                        charStart = o.get("charStart")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                        charEnd = o.get("charEnd")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                        confidence = o.get("confidence")?.takeIf { !it.isJsonNull }?.asDouble ?: 1.0
                    )
                )
            }
        } catch (_: Throwable) {
        }
        if (list.isNotEmpty()) db.taskEvidenceDao().insertAll(list)
    }

    private suspend fun log(entry: ChangeLogEntity) {
        logDao.insert(entry)
    }

    /** 撤销整批应用：按日志逆序恢复快照。单事务，撤销到一半失败会整体回滚。 */
    suspend fun undo(proposalId: String): Result<Int> = db.withTransaction {
        val logs = logDao.byProposal(proposalId)
        if (logs.isEmpty()) return@withTransaction Result.failure(IllegalStateException("没有可撤销的变更"))
        var n = 0
        for (entry in logs) {
            when (entry.op) {
                "CREATE" -> {
                    entry.taskId?.let { id ->
                        db.reminderDao().cancelByTask(id)
                        db.taskEvidenceDao().deleteByTask(id)
                        db.taskDao().byId(id)?.let { t ->
                            db.taskDao().setStatus(id, TaskStatus.CANCELLED, null)
                        }
                    }
                    n++
                }
                "UPDATE", "CANCEL" -> {
                    val snapshot = entry.oldValue ?: continue
                    val old = try {
                        gson.fromJson(snapshot, TaskEntity::class.java)
                    } catch (_: Throwable) {
                        null
                    }
                    if (old != null) {
                        db.taskDao().update(old.copy(updatedAt = System.currentTimeMillis()))
                        n++
                    }
                }
            }
            logDao.delete(entry.id)
        }
        // 撤销后状态回到待处理，允许重新确认
        val proposal = dao.byId(proposalId)
        if (proposal != null) {
            dao.setState(proposalId, ProposalState.PENDING, null)
            db.projectDao().bumpRev(proposal.projectId)
        }
        Result.success(n)
    }

    suspend fun recentChanges(projectId: String, limit: Int = 100) = logDao.recent(projectId, limit)

    private fun safeObj(json: String?): JsonObject = try {
        if (json.isNullOrBlank()) JsonObject() else JsonParser.parseString(json).asJsonObject
    } catch (_: Throwable) {
        JsonObject()
    }
}
