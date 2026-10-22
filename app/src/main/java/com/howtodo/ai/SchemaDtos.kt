package com.howtodo.ai

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * LLM 结构化输出的解析与本地校验。
 *
 * 这一层是"防幻觉"的第一道闸门：模型给什么不重要，重要的是我们**接受**什么。
 * 所有日期都要经过 evidence 校验，所有引文都要逐字回验。
 */
object SchemaParser {

    private val isoCandidates = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        DateTimeFormatter.ofPattern("yyyy.MM.dd"),
        DateTimeFormatter.ofPattern("yyyy年M月d日"),
        DateTimeFormatter.ofPattern("yyyy年M月d号")
    )
    private val monthDayFormats = listOf(
        DateTimeFormatter.ofPattern("M月d日"),
        DateTimeFormatter.ofPattern("M月d号"),
        DateTimeFormatter.ofPattern("M-d"),
        DateTimeFormatter.ofPattern("M/d")
    )

    private val RELATIVE_WORDS = listOf(
        "今天", "明天", "后天", "昨天", "本周", "下周", "上周", "本月", "下月",
        "月底", "月初", "周内", "即日", "当天", "次日", "之前", "以前", "以内",
        "第", "即日起", "另行通知", "待定", "之后"
    )

    data class Due(
        val date: LocalDate?,
        val time: LocalTime?,
        val precision: String,
        val rawText: String?,
        val chunkId: Long?,
        val epochMillis: Long?,
        val hasTime: Boolean,
        val needsConfirm: Boolean,
        val confirmReason: String?
    )

    data class Ev(val chunkId: Long?, val quote: String, val field: String = "TITLE")

    data class TaskDraft(
        val extKey: String,
        val title: String,
        val detail: String?,
        val due: Due,
        val priority: Int,
        val evidence: List<Ev>,
        val confidence: Double,
        val assumption: String?
    )

    data class EntityDraft(
        val type: String,
        val name: String,
        val aliases: List<String>,
        val attributes: Map<String, String>,
        val summary: String?,
        val confidence: Double,
        val chunkId: Long?,
        val quote: String?
    )

    data class Baseline(
        val title: String?,
        val summary: String?,
        val tasks: List<TaskDraft>,
        val entities: List<EntityDraft>,
        val unknowns: List<String>
    )

    data class Op(
        val op: String,
        val targetExtKey: String?,
        val title: String?,
        val detail: String?,
        val due: Due?,
        val priority: Int?,
        val changes: JsonObject?,
        val evidence: List<Ev>,
        val reason: String?,
        val confidence: Double,
        val assumption: String?,
        val extKey: String?
    )

    data class Conflict(val targetExtKey: String?, val type: String, val question: String)

    data class Patch(
        val summary: String?,
        val ops: List<Op>,
        val entities: List<EntityDraft>,
        val conflicts: List<Conflict>,
        val unknowns: List<String>
    )

    // ---------------------------------------------------------------- 解析入口

    fun parseBaseline(obj: JsonObject, today: LocalDate = LocalDate.now()): Baseline {
        val projectObj = obj.getAsJsonObject("project")
        val tasks = ArrayList<TaskDraft>()
        obj.getAsJsonArray("tasks")?.forEach { el ->
            val o = el.asJsonObject
            val title = o.str("title") ?: return@forEach
            val due = parseDue(o.getAsJsonObject("due"), today)
            tasks.add(
                TaskDraft(
                    extKey = o.str("ext_key") ?: "t${tasks.size + 1}",
                    title = title.trim(),
                    detail = o.str("detail"),
                    due = due,
                    priority = o.int("priority") ?: 2,
                    evidence = parseEvidence(o.getAsJsonArray("evidence")),
                    confidence = o.dbl("confidence") ?: 0.8,
                    assumption = o.str("assumption")
                )
            )
        }
        return Baseline(
            title = projectObj?.str("title"),
            summary = projectObj?.str("summary"),
            tasks = tasks.take(12),
            entities = parseEntities(obj.getAsJsonArray("entities"), today),
            unknowns = obj.stringList("unknowns")
        )
    }

    fun parsePatch(obj: JsonObject, today: LocalDate = LocalDate.now()): Patch {
        val ops = ArrayList<Op>()
        obj.getAsJsonArray("ops")?.forEach { el ->
            val o = el.asJsonObject
            val op = (o.str("op") ?: "").lowercase()
            if (op !in setOf("add", "update", "cancel", "noop")) return@forEach
            val changes = o.getAsJsonObject("changes")
            ops.add(
                Op(
                    op = op.uppercase(),
                    targetExtKey = o.str("target_ext_key"),
                    title = o.str("title") ?: changes?.str("title"),
                    detail = o.str("detail") ?: changes?.str("detail"),
                    due = parseDue(o.getAsJsonObject("due") ?: changes?.getAsJsonObject("due"), today),
                    priority = o.int("priority") ?: changes?.int("priority"),
                    changes = changes,
                    evidence = parseEvidence(o.getAsJsonArray("evidence")),
                    reason = o.str("reason"),
                    confidence = o.dbl("confidence") ?: 0.85,
                    assumption = o.str("assumption") ?: changes?.str("assumption"),
                    extKey = o.str("ext_key")
                )
            )
        }
        val conflicts = ArrayList<Conflict>()
        obj.getAsJsonArray("conflicts")?.forEach { el ->
            val o = el.asJsonObject
            conflicts.add(
                Conflict(
                    targetExtKey = o.str("target_ext_key"),
                    type = o.str("type") ?: "AMBIGUOUS",
                    question = o.str("question") ?: "材料中存在不一致，请确认"
                )
            )
        }
        return Patch(
            summary = obj.str("summary"),
            ops = ops.take(20),
            entities = parseEntities(obj.getAsJsonArray("new_entities"), today),
            conflicts = conflicts,
            unknowns = obj.stringList("unknowns")
        )
    }

    // ---------------------------------------------------------------- 日期

    fun parseDue(o: JsonObject?, today: LocalDate): Due {
        if (o == null) {
            return Due(null, null, "none", null, null, null, false, false, null)
        }
        val rawText = o.str("raw_text")
        val chunkId = o.long("chunk_id")
        val precision = o.str("precision") ?: "day"
        val dateStr = o.str("date")
        val timeStr = o.str("time")

        val date = parseDate(dateStr, today)
        val time = parseTime(timeStr)

        if (date == null) {
            return Due(null, null, "none", rawText, chunkId, null, false, false, null)
        }

        val hasTime = time != null
        val local = if (hasTime) date.atTime(time) else date.atTime(LocalTime.of(23, 59))
        val epoch = local.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        // 校验：日期必须在原文里有依据
        val (ok, reason) = verifyDate(date, rawText)
        return Due(
            date = date,
            time = time,
            precision = precision,
            rawText = rawText,
            chunkId = chunkId,
            epochMillis = epoch,
            hasTime = hasTime,
            needsConfirm = !ok,
            confirmReason = reason
        )
    }

    private fun parseDate(s: String?, today: LocalDate): LocalDate? {
        if (s.isNullOrBlank() || s == "null") return null
        val t = s.trim()
        isoCandidates.forEach { f ->
            try {
                return LocalDate.parse(t, f)
            } catch (_: Throwable) {
            }
        }

        // 只给了"11月5日"这种，补年份；若已过去较久则视为明年
        Regex("(\\d{4})?年?(\\d{1,2})月(\\d{1,2})[日号]").find(t)?.let { m ->
            val year = m.groupValues[1].toIntOrNull()
            val mo = m.groupValues[2].toIntOrNull()
            val d = m.groupValues[3].toIntOrNull()
            if (mo != null && d != null && mo in 1..12 && d in 1..31) {
                return safeDate(year ?: today.year, mo, d, today, year == null)
            }
        }

        Regex("^(\\d{1,2})[-/.](\\d{1,2})$").find(t)?.let { m ->
            val mo = m.groupValues[1].toIntOrNull()
            val d = m.groupValues[2].toIntOrNull()
            if (mo != null && d != null && mo in 1..12 && d in 1..31) {
                return safeDate(today.year, mo, d, today, true)
            }
        }
        return null
    }

    /** 无年份时若推算结果已过去较久（>60 天），按明年处理 */
    private fun safeDate(year: Int, month: Int, day: Int, today: LocalDate, rollover: Boolean): LocalDate? {
        val d = try {
            LocalDate.of(year, month, day)
        } catch (_: Throwable) {
            return null
        }
        return if (rollover && d.isBefore(today.minusDays(60)) && d.year == today.year) d.plusYears(1) else d
    }

    private fun parseTime(s: String?): LocalTime? {
        if (s.isNullOrBlank() || s == "null") return null
        val t = s.trim()
        val m = Regex("(\\d{1,2})[:：](\\d{2})").find(t) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 0..23 || min !in 0..59) return null
        return LocalTime.of(h, min)
    }

    /**
     * 判断 raw_text 是否真的支持这个日期。
     * 数字型（11月5日 / 11-05 / 11/5）直接匹配；
     * 相对型（下周三）在没有明确月日时无法可靠换算，要求人工确认。
     */
    fun verifyDate(date: LocalDate, rawText: String?): Pair<Boolean, String?> {
        if (rawText.isNullOrBlank()) return false to "原文未给出该日期的表述"
        val t = rawText.replace(" ", "")
        val m = date.monthValue
        val d = date.dayOfMonth
        val numeric = listOf(
            "${m}月${d}日", "${m}月${d}号",
            "$m-$d", "$m/$d", "$m.$d",
            "%02d-%02d".format(m, d), "%02d/%02d".format(m, d),
            "${date.year}年${m}月${d}日",
            date.toString()
        )
        if (numeric.any { t.contains(it) }) return true to null
        if (t.contains("${m}月") && t.contains("${d}")) return true to null

        val hasRelative = RELATIVE_WORDS.any { t.contains(it) }
        if (hasRelative) {
            return false to "原文表述为「$rawText」，属于相对时间，请确认是否为 $date"
        }
        return false to "原文「$rawText」中没有找到与 $date 对应的日期"
    }

    // ---------------------------------------------------------------- 引文

    fun parseEvidence(arr: JsonArray?): List<Ev> {
        if (arr == null) return emptyList()
        val out = ArrayList<Ev>()
        arr.forEach { el ->
            try {
                val o = el.asJsonObject
                val quote = o.str("quote") ?: return@forEach
                out.add(Ev(o.long("chunk_id"), quote.trim(), o.str("field") ?: "TITLE"))
            } catch (_: Throwable) {
            }
        }
        return out
    }

    /**
     * 逐字回验：quote 必须真实出现在 chunk 文本里。
     * 通不过的证据被丢弃（宁可没有出处，也不要假出处）。
     */
    fun verifyQuotes(evidence: List<Ev>, chunkText: (Long) -> String?): Pair<List<Ev>, Int> {
        var dropped = 0
        val kept = ArrayList<Ev>()
        evidence.forEach { ev ->
            val id = ev.chunkId ?: run { dropped++; return@forEach }
            val text = chunkText(id) ?: run { dropped++; return@forEach }
            val normQuote = ev.quote.replace(Regex("\\s+"), "")
            val normText = text.replace(Regex("\\s+"), "")
            if (normQuote.length >= 4 && normText.contains(normQuote)) {
                kept.add(ev)
            } else {
                // 宽松兜底：允许前后截断（模型有时多带了省略号）
                val trimmed = normQuote.trim('…', '.', '。', '、')
                if (trimmed.length >= 4 && normText.contains(trimmed)) kept.add(ev) else dropped++
            }
        }
        return kept to dropped
    }

    private fun parseEntities(arr: JsonArray?, today: LocalDate): List<EntityDraft> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { el ->
            try {
                val o = el.asJsonObject
                val name = o.str("name")?.trim() ?: return@mapNotNull null
                if (name.isBlank()) return@mapNotNull null
                val attrs = LinkedHashMap<String, String>()
                o.getAsJsonObject("attributes")?.entrySet()?.forEach { (k, v) ->
                    if (!v.isJsonNull) attrs[k] = v.asString
                }
                val ev = o.getAsJsonObject("evidence")
                EntityDraft(
                    type = (o.str("type") ?: "OTHER").uppercase(),
                    name = name,
                    aliases = o.stringList("aliases"),
                    attributes = attrs,
                    summary = o.str("summary"),
                    confidence = o.dbl("confidence") ?: 0.7,
                    chunkId = ev?.long("chunk_id"),
                    quote = ev?.str("quote")
                )
            } catch (_: Throwable) {
                null
            }
        }
    }

    // JSON 取值小工具 -------------------------------------------------

    private fun JsonObject.str(key: String): String? = try {
        val e = get(key)
        if (e == null || e.isJsonNull) null else e.asString.trim().takeIf { it.isNotEmpty() && it != "null" }
    } catch (_: Throwable) {
        null
    }

    private fun JsonObject.int(key: String): Int? = try {
        val e = get(key)
        if (e == null || e.isJsonNull) null else e.asInt
    } catch (_: Throwable) {
        null
    }

    private fun JsonObject.dbl(key: String): Double? = try {
        val e = get(key)
        if (e == null || e.isJsonNull) null else e.asDouble
    } catch (_: Throwable) {
        null
    }

    private fun JsonObject.long(key: String): Long? = try {
        val e = get(key)
        if (e == null || e.isJsonNull) null else e.asLong
    } catch (_: Throwable) {
        null
    }

    private fun JsonObject.stringList(key: String): List<String> = try {
        val arr = getAsJsonArray(key) ?: return emptyList()
        arr.mapNotNull { if (it.isJsonNull) null else it.asString }
    } catch (_: Throwable) {
        emptyList()
    }
}
