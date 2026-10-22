package com.howtodo.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 防幻觉的第一层：日期必须在原文里找得到依据。
 * 这些用例对应的都是模型真实会犯的错——把「下周五」猜成一个具体日期。
 */
class SchemaParserTest {

    private val due = LocalDate.of(2024, 3, 15)

    @Test
    fun `原文写明月日则通过`() {
        val (ok, note) = SchemaParser.verifyDate(due, "请于3月15日前提交材料")
        assertTrue(ok)
        assertTrue(note == null)
    }

    @Test
    fun `原文带年份也通过`() {
        assertTrue(SchemaParser.verifyDate(due, "2024年3月15日截止").first)
    }

    @Test
    fun `数字型短横线格式也通过`() {
        assertTrue(SchemaParser.verifyDate(due, "deadline: 2024-03-15").first)
        assertTrue(SchemaParser.verifyDate(due, "截止 03/15").first)
    }

    @Test
    fun `原文完全没有日期则不通过`() {
        val (ok, note) = SchemaParser.verifyDate(due, "请尽快提交")
        assertFalse(ok)
        assertTrue(note!!.contains("没有找到"))
    }

    @Test
    fun `相对时间必须人工确认`() {
        val (ok, note) = SchemaParser.verifyDate(due, "下周五之前交")
        assertFalse(ok)
        assertTrue(note!!.contains("相对时间"))
    }

    @Test
    fun `日期对不上时不通过`() {
        val (ok, _) = SchemaParser.verifyDate(due, "请于3月20日前提交")
        assertFalse(ok)
    }

    @Test
    fun `缺少原文依据时不通过`() {
        assertFalse(SchemaParser.verifyDate(due, null).first)
        assertFalse(SchemaParser.verifyDate(due, "  ").first)
    }

    // ---------------------------------------------------------------- 引文回验

    @Test
    fun `逐字命中的引文被保留`() {
        val ev = listOf(SchemaParser.Ev(1L, "请于3月15日前提交材料"))
        val (kept, dropped) = SchemaParser.verifyQuotes(ev) { "通知：请于3月15日前提交材料，逾期不候。" }
        assertEquals(1, kept.size)
        assertEquals(0, dropped)
    }

    @Test
    fun `空白差异不影响命中`() {
        val ev = listOf(SchemaParser.Ev(1L, "请于 3月15日 前提交"))
        val (kept, _) = SchemaParser.verifyQuotes(ev) { "请于3月15日前提交材料" }
        assertEquals(1, kept.size)
    }

    @Test
    fun `模型编造的引文被丢弃`() {
        val ev = listOf(SchemaParser.Ev(1L, "请于3月15日前提交纸质材料到教务处"))
        val (kept, dropped) = SchemaParser.verifyQuotes(ev) { "请于3月15日前提交材料" }
        assertEquals(0, kept.size)
        assertEquals(1, dropped)
    }

    @Test
    fun `找不到对应块时被丢弃`() {
        val ev = listOf(SchemaParser.Ev(99L, "请于3月15日前提交材料"))
        val (kept, dropped) = SchemaParser.verifyQuotes(ev) { null }
        assertEquals(0, kept.size)
        assertEquals(1, dropped)
    }
}
