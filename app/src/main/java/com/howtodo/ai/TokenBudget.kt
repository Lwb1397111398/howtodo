package com.howtodo.ai

/**
 * 粗略 token 估算与上下文预算控制。
 *
 * 不引入 tiktoken（体积大且对中文分词与各家模型都不准），改用经验系数：
 * CJK 约 0.75 token/字，ASCII 约 0.25 token/字符。误差通过实测 usage 回写校准。
 */
object TokenBudget {

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var ascii = 0
        for (ch in text) {
            val c = ch.code
            // 中文标点（U+3000–303F）与全角符号（U+FF00–FFEF）也按整字算，
            // 公文里标点密集，旧算法把它们当 0.25 token 会明显低估用量
            if (c in 0x3000..0x9fff || c in 0xf900..0xfaff || c in 0xff00..0xffef) cjk++ else ascii++
        }
        return (cjk * 0.75 + ascii * 0.25).toInt() + 1
    }

    /**
     * 计算可用于「装材料」的预算。
     * @param contextWindow 模型上下文上限（用户可设到 200k）
     * @param maxOutput 预留的最大输出
     * @param fixedOverhead 固定开销（系统指令 + 输出格式约束）
     */
    fun usable(
        contextWindow: Int,
        maxOutput: Int,
        fixedOverhead: Int = 1500,
        safety: Double = 0.60
    ): Int {
        val reserve = minOf(maxOutput, 8192) + fixedOverhead
        val raw = contextWindow - reserve
        val usable = (raw * safety).toInt()
        return if (usable < 500) 500 else usable
    }

    /** 按预算截断长文本，保留首尾（中间用省略标记），用于超长材料预压缩前的兜底 */
    fun truncate(text: String, maxTokens: Int): String {
        if (estimate(text) <= maxTokens) return text
        // 按 token 比例估算字符数，保守取 0.8
        val maxChars = (maxTokens / 0.75).toInt()
        if (maxChars <= 64) return text.take(maxChars)
        val head = (maxChars * 0.65).toInt()
        val tail = maxChars - head
        return text.take(head) + "\n\n……（中间省略）……\n\n" + text.takeLast(tail)
    }
}
