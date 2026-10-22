package com.howtodo.rag

import java.text.Normalizer

/**
 * 中文 bigram 编码器。
 *
 * SQLite FTS5 内置的 unicode61 分词器对中文几乎不可用（会把整句当成一个 token），
 * 又不想引入第三方分词库或向量库，因此采用「索引侧预先切成二元组」的策略：
 *
 *   提交报名表 -> "提交 交报 报名 名表 提交报名表"
 *
 * 查询侧对关键词做同样的切分，然后用 FTS5 的短语查询（相邻 token）还原出
 * 「子串匹配」的语义。这样既能用上 BM25 打分，又不需要任何中文分词依赖。
 */
object Bigram {

    private val ZERO_WIDTH = Regex("[\\u200b-\\u200f\\u202a-\\u202e\\ufeff]")
    private val CJK = Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff]")

    /** 归一化：NFKC、小写、去零宽控制字符、压缩空白 */
    fun normalize(text: String): String {
        val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return ZERO_WIDTH.replace(nfkc, "")
            .lowercase()
            .replace(Regex("[ \\t\\u3000]+"), " ")
            .trim()
    }

    /**
     * 把一段文本编码成可供 FTS5(unicode61) 索引的 token 串。
     * CJK 连续段 -> 相邻二元组 + 整段原词；ASCII/数字段 -> 整词。
     */
    fun encode(text: String): String = encodeInternal(text, keepWholeRun = true)

    /**
     * 查询侧编码：**不**保留整段原词。
     *
     * 索引侧把「提交报名表」切成 `提交 交报 报名 名表 提交报名表`，
     * 若查询「提交报名」也保留整串 token，得到的短语是
     * `"提交 交报 报名 提交报名"`，FTS5 要求四个 token 相邻出现，
     * 而文档里「报名」后面跟着的是「名表」，永远匹配不上。
     * 因此查询只保留二元组，短语退化为 `"提交 交报 报名"`，正好是子串语义。
     */
    fun encodeQuery(text: String): String = encodeInternal(text, keepWholeRun = false)

    private fun encodeInternal(text: String, keepWholeRun: Boolean): String {
        if (text.isBlank()) return ""
        val normalized = normalize(text)
        val out = StringBuilder(normalized.length * 2)
        val run = StringBuilder()

        fun flushCjk() {
            if (run.isEmpty()) return
            val s = run.toString()
            run.setLength(0)
            if (s.length == 1) {
                out.append(s).append(' ')
                return
            }
            for (i in 0 until s.length - 1) {
                out.append(s, i, i + 2).append(' ')
            }
            // 整段保留一份，便于短语查询精确命中
            if (keepWholeRun && s.length <= 24) out.append(s).append(' ')
        }

        var i = 0
        while (i < normalized.length) {
            val ch = normalized[i]
            when {
                CJK.matches(ch.toString()) -> {
                    run.append(ch)
                    i++
                }
                ch.isLetterOrDigit() -> {
                    // ASCII / 其他字母：整词保留
                    flushCjk()
                    val start = i
                    while (i < normalized.length &&
                        normalized[i].isLetterOrDigit() &&
                        !CJK.matches(normalized[i].toString())
                    ) i++
                    out.append(normalized, start, i).append(' ')
                }
                else -> {
                    flushCjk()
                    i++
                }
            }
        }
        flushCjk()
        return out.toString().trim()
    }

    /** 把单个关键词变成 FTS5 短语表达式（相邻 token），返回 null 表示无法使用 */
    fun phraseFor(keyword: String): String? {
        val tokens = encodeQuery(keyword).split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        // 单个短语查询：FTS5 中 "a b c" 表示 a、b、c 必须相邻出现
        return "\"" + tokens.joinToString(" ") { it.replace("\"", "") } + "\""
    }

    /**
     * 构造 MATCH 表达式。
     * @param keywords 关键词列表
     * @param allRequired true 用 AND，false 用 OR
     */
    fun matchExpression(keywords: List<String>, allRequired: Boolean = false): String? {
        val phrases = keywords.mapNotNull { phraseFor(it) }.distinct()
        if (phrases.isEmpty()) return null
        val op = if (allRequired) " AND " else " OR "
        return phrases.joinToString(op) { "($it)" }
    }

    // 中文停用词与疑问词，仅用于本地兜底切词
    private val STOP = setOf(
        "的", "了", "是", "在", "和", "与", "及", "或", "我", "你", "他", "她", "它",
        "我们", "你们", "他们", "这个", "那个", "这", "那", "什么", "怎么", "怎么样",
        "如何", "为什么", "哪里", "哪个", "多少", "请问", "可以", "需要", "应该",
        "吗", "呢", "吧", "啊", "把", "被", "对", "从", "到", "会", "要", "有", "没有",
        "一下", "一个", "现在", "然后", "所以", "但是", "如果", "因为"
    )

    private val SPLIT = Regex("[\\s,，。、；;：:！!？?（）()\\[\\]【】\"'“”‘’/\\\\|<>~`@#$%^&*+=_-]+")

    /**
     * 本地兜底切词（LLM 改写失败时使用）。
     * 策略：按标点切分 -> 去停用词 -> 保留 2 字以上片段 -> 长片段再按 4 字滑窗补充
     */
    fun localKeywords(text: String, limit: Int = 6): List<String> {
        val norm = normalize(text)
        val raw = SPLIT.split(norm).filter { it.isNotBlank() }
        val cand = LinkedHashSet<String>()
        for (piece in raw) {
            if (piece.length < 2) continue
            if (piece in STOP) continue
            cand.add(piece)
            if (piece.length > 6) {
                // 长句再补充滑窗片段，提高召回
                var i = 0
                while (i + 3 <= piece.length && cand.size < limit * 2) {
                    val sub = piece.substring(i, i + 3)
                    if (sub !in STOP) cand.add(sub)
                    i += 3
                }
            }
        }
        return cand.filter { it !in STOP }.take(limit)
    }

    /** 关键词在原文中的覆盖率（用于重排，防止 bigram 误命中） */
    fun coverage(keywords: List<String>, text: String): Double {
        if (keywords.isEmpty()) return 0.0
        val t = normalize(text)
        val hit = keywords.count { t.contains(normalize(it)) }
        return hit.toDouble() / keywords.size
    }
}
