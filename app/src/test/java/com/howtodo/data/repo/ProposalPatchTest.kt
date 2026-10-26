package com.howtodo.data.repo

import com.google.gson.JsonParser
import com.howtodo.data.db.TaskEntity
import com.howtodo.data.db.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 提案补丁的纯应用逻辑：AI 给的 changes 套到任务上的规则。
 * 规则错了会覆盖用户数据或留下脏状态，由测试兜住。
 */
class ProposalPatchTest {

    private fun task(
        title: String = "原任务",
        detail: String? = null,
        dueAt: Long? = null,
        dueHasTime: Boolean = false,
        status: String = TaskStatus.TODO,
        priority: Int = 2,
        rev: Int = 1
    ) = TaskEntity(
        id = "task1",
        projectId = "p1",
        extKey = "t1",
        title = title,
        detail = detail,
        dueAt = dueAt,
        dueHasTime = dueHasTime,
        status = status,
        priority = priority,
        rev = rev
    )

    private fun patch(json: String) = JsonParser.parseString(json).asJsonObject

    @Test
    fun `无实际变化时返回 null 不写库`() {
        val t = task(title = "交报名表", dueAt = 1_700_000_000_000)
        val out = patchTask(
            t,
            patch("""{"changes": {"title": "交报名表", "dueAt": 1700000000000}}"""),
            batchId = "b1",
            now = 1L
        )
        assertNull(out)
    }

    @Test
    fun `标题修改生效并递增 rev`() {
        val t = task(title = "交报名表")
        val out = patchTask(
            t,
            patch("""{"changes": {"title": "交报名表给张老师"}}"""),
            batchId = "b1",
            now = 123L
        )
        assertNotNull(out)
        assertEquals("交报名表给张老师", out!!.title)
        assertEquals(2, out.rev)
        assertEquals("b1", out.lastBatchId)
        assertEquals(123L, out.updatedAt)
    }

    @Test
    fun `空标题不会被当成修改`() {
        val t = task(title = "交报名表")
        val out = patchTask(
            t,
            patch("""{"changes": {"title": "  "}}"""),
            batchId = "b1",
            now = 1L
        )
        assertNull(out)
    }

    @Test
    fun `日期置空允许且补上日期后待确认任务自动转待办`() {
        // 原：待确认（无日期）→ 补上日期 → 自动 TODO
        val t = task(status = TaskStatus.NEEDS_CONFIRM)
        val out = patchTask(
            t,
            patch("""{"changes": {"dueAt": 1700000000000}}"""),
            batchId = "b1",
            now = 1L
        )
        assertNotNull(out)
        assertEquals(1_700_000_000_000L, out!!.dueAt)
        assertEquals(TaskStatus.TODO, out.status)
    }

    @Test
    fun `已完成的任务状态不被补丁改动`() {
        val t = task(title = "交报名表", status = TaskStatus.DONE, rev = 3)
        val out = patchTask(
            t,
            patch("""{"changes": {"title": "新标题"}}"""),
            batchId = "b1",
            now = 1L
        )
        // 状态保护在 apply() 调用方，这里只验证 patch 本身不动 status
        assertNotNull(out)
        assertEquals(TaskStatus.DONE, out!!.status)
    }

    @Test
    fun `payload 无 changes 包裹时按自身字段处理`() {
        val t = task(title = "交报名表", priority = 2)
        val out = patchTask(
            t,
            patch("""{"title": "交报名表（改）", "priority": 1}"""),
            batchId = "b1",
            now = 1L
        )
        assertNotNull(out)
        assertEquals("交报名表（改）", out!!.title)
        assertEquals(1, out.priority)
    }
}
