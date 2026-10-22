package com.howtodo.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Token 估算是上下文预算的唯一依据。
 * 估小了会超上下文被接口拒绝，估大了会白白丢掉材料，
 * 所以这些系数改动必须由测试兜住。
 */
class TokenBudgetTest {

    @Test
    fun `空串估算为 0`() {
        assertEquals(0, TokenBudget.estimate(""))
    }

    @Test
    fun `中文按每字 0_75 估算`() {
        // 2 * 0.75 = 1.5 -> 1，再加固定 +1
        assertEquals(2, TokenBudget.estimate("你好"))
    }

    @Test
    fun `英文按每字符 0_25 估算`() {
        // 8 * 0.25 = 2.0 -> 2，再加固定 +1
        assertEquals(3, TokenBudget.estimate("abcdefgh"))
    }

    @Test
    fun `上下文窗口越大可用预算越大`() {
        val small = TokenBudget.usable(8_000, 4_096)
        val huge = TokenBudget.usable(200_000, 8_192)
        assertTrue(huge > small)
        // (200000 - (8192 + 1500)) * 0.6
        assertEquals(114_184, huge)
    }

    @Test
    fun `窗口过小时给下限而不是负数`() {
        assertEquals(500, TokenBudget.usable(2_000, 4_096))
    }

    @Test
    fun `短文本原样返回`() {
        val s = "今天下午三点开会"
        assertEquals(s, TokenBudget.truncate(s, 1_000))
    }

    @Test
    fun `超长文本截断且保留首尾`() {
        val long = "甲".repeat(5_000)
        val cut = TokenBudget.truncate(long, 100)
        assertTrue(cut.length < long.length)
        assertTrue(cut.contains("中间省略"))
        assertTrue(cut.startsWith("甲"))
    }

    @Test
    fun `截断后估算值应落在预算内`() {
        val long = "这是一段很长的通知内容。".repeat(2_000)
        val budget = 500
        val cut = TokenBudget.truncate(long, budget)
        assertNotEquals(long, cut)
        assertTrue(TokenBudget.estimate(cut) <= budget + 20)
    }
}
