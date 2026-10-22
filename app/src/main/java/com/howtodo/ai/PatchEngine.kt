package com.howtodo.ai

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.howtodo.data.db.ConflictType
import com.howtodo.data.db.ItemDecision
import com.howtodo.data.db.ProposalItemEntity
import com.howtodo.data.db.TaskEntity
import com.howtodo.data.db.TaskOp
import com.howtodo.data.db.TaskStatus
import com.howtodo.util.Format

/**
 * 把 LLM 的草稿转换成「可逐条确认的提案条目」，并在本地做全部硬校验。
 *
 * 这里是产品信任的基石：模型给的任何内容，只要通不过校验就不进正式清单。
 */
class PatchEngine {

    private val gson = Gson()

    data class Built(
        val items: List<ProposalItemEntity>,
        val summary: String,
        val conflicts: Int,
        val unknowns: String?
    )

    // ---------------------------------------------------------------- 基线

    fun buildBaseline(
        baseline: SchemaParser.Baseline,
        chunkText: (Long) -> String?
    ): Built {
        val items = ArrayList<ProposalItemEntity>()
        var conflicts = 0
        val seenTitles = HashSet<String>()

        baseline.tasks.forEachIndexed { idx, t ->
            val (evidence, dropped) = SchemaParser.verifyQuotes(t.evidence, chunkText)
            var confidence = t.confidence
            if (dropped > 0) confidence = (confidence - 0.15 * dropped).coerceAtLeast(0.2)

            var conflict: String? = null
            var note: String? = null

            if (t.due.needsConfirm) {
                conflict = ConflictType.NO_EVIDENCE_DATE
                note = t.due.confirmReason
                conflicts++
            }

            val norm = normalizeTitle(t.title)
            if (norm.isNotBlank() && !seenTitles.add(norm)) {
                conflict = conflict ?: ConflictType.DUP_SUSPECT
                note = listOfNotNull(note, "同一批材料里出现了相似任务").joinToString("；")
                if (conflict == ConflictType.DUP_SUSPECT) conflicts++
            }

            val payload = JsonObject().apply {
                addProperty("extKey", t.extKey.ifBlank { "t${idx + 1}" })
                addProperty("title", t.title)
                addProperty("detail", t.detail)
                if (t.due.epochMillis != null) addProperty("dueAt", t.due.epochMillis) else add("dueAt", null)
                addProperty("dueHasTime", t.due.hasTime)
                addProperty("dueRaw", t.due.rawText)
                addProperty("priority", t.priority.coerceIn(1, 3))
                addProperty("confidence", confidence)
                addProperty("needsConfirm", t.due.needsConfirm || t.due.epochMillis == null)
                if (!t.assumption.isNullOrBlank()) addProperty("assumption", t.assumption)
            }

            items.add(
                ProposalItemEntity(
                    proposalId = "",
                    op = TaskOp.ADD,
                    targetExtKey = t.extKey,
                    payload = gson.toJson(payload),
                    diffText = buildAddDiff(t),
                    reason = t.detail,
                    evidenceJson = evidenceJson(evidence),
                    conflict = conflict,
                    conflictNote = note,
                    decision = if (conflict == null) ItemDecision.ACCEPT else ItemDecision.PENDING
                )
            )
        }

        val summary = buildString {
            append("新增 ").append(items.size).append(" 项任务")
            if (conflicts > 0) append("，").append(conflicts).append(" 项需要你确认")
        }
        return Built(
            items = items,
            summary = summary,
            conflicts = conflicts,
            unknowns = gson.toJson(baseline.unknowns)
        )
    }

    // ---------------------------------------------------------------- 增量

    fun buildPatch(
        patch: SchemaParser.Patch,
        existing: List<TaskEntity>,
        chunkText: (Long) -> String?
    ): Built {
        val items = ArrayList<ProposalItemEntity>()
        val byKey = existing.associateBy { it.extKey }
        val byNormTitle = existing.associateBy { normalizeTitle(it.title) }
        var conflicts = patch.conflicts.size

        // 同一 ext_key 多次出现时只取最后一条
        val dedup = LinkedHashMap<String, SchemaParser.Op>()
        patch.ops.forEach { op ->
            val key = (op.targetExtKey ?: op.extKey ?: op.title) ?: return@forEach
            dedup[key] = op
        }

        dedup.values.forEach { op ->
            val (evidence, dropped) = SchemaParser.verifyQuotes(op.evidence, chunkText)
            var confidence = op.confidence
            if (dropped > 0) confidence = (confidence - 0.15 * dropped).coerceAtLeast(0.2)

            when (op.op) {
                TaskOp.ADD -> {
                    val key = op.extKey ?: op.targetExtKey ?: ""
                    val dupTask = byNormTitle[normalizeTitle(op.title.orEmpty())]
                    var conflict: String? = null
                    var note: String? = null
                    if (op.due?.needsConfirm == true) {
                        conflict = ConflictType.NO_EVIDENCE_DATE
                        note = op.due.confirmReason
                    }
                    if (dupTask != null) {
                        conflict = conflict ?: ConflictType.DUP_SUSPECT
                        note = listOfNotNull(note, "与已有任务「${dupTask.title}」相似，可考虑合并").joinToString("；")
                    }
                    val payload = JsonObject().apply {
                        addProperty("extKey", key.ifBlank { "n" + System.currentTimeMillis().toString().takeLast(6) })
                        addProperty("title", op.title ?: "未命名任务")
                        addProperty("detail", op.detail)
                        if (op.due?.epochMillis != null) addProperty("dueAt", op.due.epochMillis) else add("dueAt", null)
                        addProperty("dueHasTime", op.due?.hasTime ?: false)
                        addProperty("dueRaw", op.due?.rawText)
                        addProperty("priority", (op.priority ?: 2).coerceIn(1, 3))
                        addProperty("confidence", confidence)
                        addProperty("needsConfirm", op.due == null || op.due.needsConfirm || op.due.epochMillis == null)
                        if (!op.assumption.isNullOrBlank()) addProperty("assumption", op.assumption)
                    }
                    items.add(
                        ProposalItemEntity(
                            proposalId = "",
                            op = TaskOp.ADD,
                            targetExtKey = key,
                            payload = gson.toJson(payload),
                            diffText = "新增 · " + dueLabel(op.due),
                            reason = op.reason ?: op.detail,
                            evidenceJson = evidenceJson(evidence),
                            conflict = conflict,
                            conflictNote = note,
                            decision = if (conflict == null) ItemDecision.ACCEPT else ItemDecision.PENDING
                        )
                    )
                    if (conflict != null) conflicts++
                }

                TaskOp.UPDATE -> {
                    val target = byKey[op.targetExtKey]
                    if (target == null) {
                        items.add(
                            ProposalItemEntity(
                                proposalId = "",
                                op = TaskOp.UPDATE,
                                targetExtKey = op.targetExtKey,
                                payload = gson.toJson(JsonObject()),
                                diffText = "找不到对应任务「${op.targetExtKey}」",
                                reason = op.reason,
                                conflict = ConflictType.MISSING_TARGET,
                                conflictNote = "模型引用的任务不在当前清单里，可能已删除",
                                decision = ItemDecision.REJECT
                            )
                        )
                        conflicts++
                        return@forEach
                    }

                    if (target.status == TaskStatus.DONE) {
                        items.add(
                            ProposalItemEntity(
                                proposalId = "",
                                op = TaskOp.UPDATE,
                                targetExtKey = target.extKey,
                                targetTaskId = target.id,
                                payload = gson.toJson(JsonObject()),
                                diffText = "已完成任务「${target.title}」有新变化",
                                reason = op.reason,
                                conflict = ConflictType.DONE_CONFLICT,
                                conflictNote = "该任务你已标记完成，AI 不会改动它；如确有变更请手动处理",
                                decision = ItemDecision.REJECT
                            )
                        )
                        conflicts++
                        return@forEach
                    }

                    val changes = JsonObject()
                    val diffs = ArrayList<String>()

                    op.title?.takeIf { it.isNotBlank() && it != target.title }?.let {
                        changes.addProperty("title", it)
                        diffs.add("标题：${target.title} → $it")
                    }
                    op.detail?.takeIf { it.isNotBlank() && it != target.detail }?.let {
                        changes.addProperty("detail", it)
                    }
                    val newDue = op.due?.epochMillis
                    val changesDue = op.due != null &&
                        (op.due.rawText != null || op.due.epochMillis != null) &&
                        newDue != target.dueAt
                    if (changesDue) {
                        if (newDue != null) changes.addProperty("dueAt", newDue) else changes.add("dueAt", null)
                        changes.addProperty("dueHasTime", op.due!!.hasTime)
                        if (!op.due.rawText.isNullOrBlank()) changes.addProperty("dueRaw", op.due.rawText)
                        diffs.add("截止：${Format.date(target.dueAt).ifBlank { "未定" }} → ${dueLabel(op.due)}")
                    }
                    op.priority?.let {
                        if (it != target.priority) {
                            changes.addProperty("priority", it.coerceIn(1, 3))
                            diffs.add("优先级：${target.priority} → $it")
                        }
                    }
                    if (!op.assumption.isNullOrBlank() && op.assumption != target.assumption) {
                        changes.addProperty("assumption", op.assumption)
                    }

                    if (changes.entrySet().isEmpty()) {
                        return@forEach
                    }

                    var conflict: String? = null
                    var note: String? = null
                    if (op.due?.needsConfirm == true) {
                        conflict = ConflictType.NO_EVIDENCE_DATE
                        note = op.due.confirmReason
                        conflicts++
                    }

                    val payload = JsonObject().apply { add("changes", changes) }
                    items.add(
                        ProposalItemEntity(
                            proposalId = "",
                            op = TaskOp.UPDATE,
                            targetExtKey = target.extKey,
                            targetTaskId = target.id,
                            payload = gson.toJson(payload),
                            diffText = diffs.joinToString("；").ifBlank { "更新「${target.title}」" },
                            reason = op.reason,
                            evidenceJson = evidenceJson(evidence),
                            conflict = conflict,
                            conflictNote = note,
                            decision = if (conflict == null) ItemDecision.ACCEPT else ItemDecision.PENDING
                        )
                    )
                }

                TaskOp.CANCEL -> {
                    val target = byKey[op.targetExtKey]
                    if (target == null) {
                        items.add(
                            ProposalItemEntity(
                                proposalId = "",
                                op = TaskOp.CANCEL,
                                targetExtKey = op.targetExtKey,
                                payload = gson.toJson(JsonObject()),
                                diffText = "找不到要取消的任务",
                                reason = op.reason,
                                conflict = ConflictType.MISSING_TARGET,
                                decision = ItemDecision.REJECT
                            )
                        )
                        conflicts++
                        return@forEach
                    }
                    if (target.status == TaskStatus.DONE || target.status == TaskStatus.CANCELLED) {
                        items.add(
                            ProposalItemEntity(
                                proposalId = "",
                                op = TaskOp.CANCEL,
                                targetExtKey = target.extKey,
                                targetTaskId = target.id,
                                payload = gson.toJson(JsonObject()),
                                diffText = "「${target.title}」已经是完成/取消状态",
                                reason = op.reason,
                                conflict = ConflictType.DONE_CONFLICT,
                                decision = ItemDecision.REJECT
                            )
                        )
                        conflicts++
                        return@forEach
                    }
                    items.add(
                        ProposalItemEntity(
                            proposalId = "",
                            op = TaskOp.CANCEL,
                            targetExtKey = target.extKey,
                            targetTaskId = target.id,
                            payload = gson.toJson(JsonObject()),
                            diffText = "取消「${target.title}」",
                            reason = op.reason,
                            evidenceJson = evidenceJson(evidence),
                            decision = ItemDecision.ACCEPT
                        )
                    )
                }
            }
        }

        // 材料内部自相矛盾的，单独作为待确认项展示
        patch.conflicts.forEach { c ->
            items.add(
                ProposalItemEntity(
                    proposalId = "",
                    op = TaskOp.NOOP,
                    targetExtKey = c.targetExtKey,
                    payload = gson.toJson(JsonObject()),
                    diffText = c.question,
                    reason = "材料内部不一致",
                    conflict = ConflictType.AMBIGUOUS,
                    conflictNote = c.question,
                    decision = ItemDecision.PENDING
                )
            )
        }

        val summary = buildString {
            val addN = items.count { it.op == TaskOp.ADD }
            val upN = items.count { it.op == TaskOp.UPDATE }
            val caN = items.count { it.op == TaskOp.CANCEL }
            if (addN + upN + caN == 0) append("这批材料没有带来变化")
            else {
                val parts = ArrayList<String>()
                if (addN > 0) parts.add("新增 $addN 项")
                if (upN > 0) parts.add("修改 $upN 项")
                if (caN > 0) parts.add("取消 $caN 项")
                append(parts.joinToString("，"))
            }
            if (patch.summary != null) append("｜").append(patch.summary)
        }

        return Built(
            items = items,
            summary = summary,
            conflicts = conflicts,
            unknowns = gson.toJson(patch.unknowns)
        )
    }

    // ---------------------------------------------------------------- 工具

    private fun buildAddDiff(t: SchemaParser.TaskDraft): String {
        val d = dueLabel(t.due)
        return if (d.isBlank()) "新增 · 未定时间" else "新增 · $d"
    }

    private fun dueLabel(d: SchemaParser.Due?): String {
        if (d?.epochMillis == null) return "未定"
        val date = Format.date(d.epochMillis)
        return if (d.hasTime) "$date ${Format.time(d.epochMillis)}" else date
    }

    fun normalizeTitle(s: String): String =
        s.replace(Regex("[\\s\\p{Punct}，。、；：！？（）【】《》\"'“”‘’·]"), "").lowercase()

    private fun evidenceJson(evidence: List<SchemaParser.Ev>): String {
        val arr = JsonArray()
        evidence.forEach { ev ->
            val o = JsonObject()
            if (ev.chunkId != null) o.addProperty("chunkId", ev.chunkId)
            o.addProperty("quote", ev.quote)
            o.addProperty("field", ev.field)
            arr.add(o)
        }
        return gson.toJson(arr)
    }
}
