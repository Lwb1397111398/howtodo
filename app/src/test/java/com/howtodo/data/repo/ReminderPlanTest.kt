package com.howtodo.data.repo

import com.howtodo.data.db.ReminderKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 三档提醒计划（D-3 / D-1 / 当天）是提醒系统的唯一排期来源。
 * dedupeKey 格式错了防重就失效，档位时间算错了提醒就不准，都由测试兜住。
 */
class ReminderPlanTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    /** 2026-11-05 17:00（有具体时刻） */
    private val dueWithTime: Long =
        LocalDateTime.of(2026, 11, 5, 17, 0).atZone(zone).toInstant().toEpochMilli()

    private fun millisOf(date: String, hour: Int, minute: Int): Long {
        val d = LocalDate.of(date.substring(0, 4).toInt(), date.substring(5, 7).toInt(), date.substring(8, 10).toInt())
        return d.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    }

    @Test
    fun `三档全开且都有具体时刻时 D0 提前两小时`() {
        // now 远早于 D3，三档都不过期
        val now = millisOf("2026-10-01", 8, 0)
        val plan = ReminderPlan.planFor("t1", dueWithTime, dueHasTime = true, d3 = true, d1 = true, d0 = true, zone = zone, now = now)

        assertEquals(listOf(ReminderKind.D3, ReminderKind.D1, ReminderKind.D0), plan.map { it.kind })
        assertEquals(millisOf("2026-11-02", 9, 0), plan[0].triggerAt)
        assertEquals(millisOf("2026-11-04", 9, 0), plan[1].triggerAt)
        // 17:00 - 2h
        assertEquals(dueWithTime - 2 * 3_600_000L, plan[2].triggerAt)
    }

    @Test
    fun `无具体时刻时 D0 落在当天早上九点`() {
        val due = millisOf("2026-11-05", 23, 59)
        val now = millisOf("2026-10-01", 8, 0)
        val plan = ReminderPlan.planFor("t1", due, dueHasTime = false, d3 = true, d1 = true, d0 = true, zone = zone, now = now)

        assertEquals(millisOf("2026-11-05", 9, 0), plan[2].triggerAt)
    }

    @Test
    fun `过期档位被跳过`() {
        // now 在 D3 之后、D1 之前：D3 不再补发
        val now = millisOf("2026-11-03", 10, 0)
        val plan = ReminderPlan.planFor("t1", dueWithTime, dueHasTime = true, d3 = true, d1 = true, d0 = true, zone = zone, now = now)

        assertEquals(listOf(ReminderKind.D1, ReminderKind.D0), plan.map { it.kind })
    }

    @Test
    fun `开关关闭的档位不生成`() {
        val now = millisOf("2026-10-01", 8, 0)
        val plan = ReminderPlan.planFor("t1", dueWithTime, dueHasTime = true, d3 = false, d1 = true, d0 = false, zone = zone, now = now)

        assertEquals(listOf(ReminderKind.D1), plan.map { it.kind })
    }

    @Test
    fun `dedupeKey 格式为 任务id_档位_日期`() {
        val now = millisOf("2026-10-01", 8, 0)
        val plan = ReminderPlan.planFor("task42", dueWithTime, dueHasTime = true, d3 = true, d1 = true, d0 = true, zone = zone, now = now)

        assertEquals("task42|D3|2026-11-02", plan[0].dedupeKey)
        assertEquals("task42|D0|2026-11-05", plan[2].dedupeKey)
        // 同任务同档位重复排期必须得到同一个 key（幂等防重的根基）
        val again = ReminderPlan.planFor("task42", dueWithTime, dueHasTime = true, d3 = true, d1 = true, d0 = true, zone = zone, now = now)
        assertTrue(again.map { it.dedupeKey } == plan.map { it.dedupeKey })
    }
}
