package com.howtodo.data.repo

import com.howtodo.data.db.AppDatabase
import com.howtodo.data.db.ReminderEntity
import com.howtodo.data.db.ReminderKind
import com.howtodo.data.db.ReminderState
import com.howtodo.data.db.TaskEntity
import com.howtodo.data.db.TaskStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 提醒计划生成。
 *
 * 每个任务默认三档：D-3（09:00）、D-1（09:00）、当天（有具体时刻则提前 2 小时，否则 09:00）。
 * 用 dedupeKey = taskId|kind|日期 保证幂等：反复重排不会产生重复通知。
 */
class ReminderRepo(private val db: AppDatabase) {

    private val dao get() = db.reminderDao()

    companion object {
        const val KEY_DIGEST_ENABLED = "digest.enabled"
        const val KEY_DIGEST_HOUR = "digest.hour"
        const val KEY_DIGEST_MINUTE = "digest.minute"
        const val KEY_D3_ENABLED = "remind.d3"
        const val KEY_D1_ENABLED = "remind.d1"
        const val KEY_D0_ENABLED = "remind.d0"
        const val DEFAULT_DIGEST_HOUR = 8
        const val DEFAULT_DIGEST_MINUTE = 0
    }

    private val dayFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private fun zone(): ZoneId = ZoneId.systemDefault()

    suspend fun scheduled(): List<ReminderEntity> = dao.scheduled()

    suspend fun byTask(taskId: String) = dao.byTask(taskId)

    suspend fun markFired(id: Long) = dao.setState(id, ReminderState.FIRED)

    suspend fun cancelTask(taskId: String) = dao.cancelByTask(taskId)

    suspend fun purgeOld() {
        dao.purgeOld(System.currentTimeMillis() - 30L * 24 * 3600_000)
    }

    /** 为单个任务重建提醒（先清后建，幂等） */
    suspend fun rescheduleTask(task: TaskEntity): List<ReminderEntity> =
        rebuildReminders(
            task,
            boolSetting(KEY_D3_ENABLED, true),
            boolSetting(KEY_D1_ENABLED, true),
            boolSetting(KEY_D0_ENABLED, true)
        )

    private suspend fun rebuildReminders(
        task: TaskEntity,
        d3: Boolean,
        d1: Boolean,
        d0: Boolean
    ): List<ReminderEntity> {
        dao.cancelByTask(task.id)
        if (task.status != TaskStatus.TODO) return emptyList()
        val due = task.dueAt ?: return emptyList()

        val now = System.currentTimeMillis()
        val dueDate = Instant.ofEpochMilli(due).atZone(zone()).toLocalDate()
        val out = ArrayList<ReminderEntity>()

        suspend fun add(kind: String, triggerAt: Long) {
            if (triggerAt <= now + 60_000) return
            val key = "${task.id}|$kind|${dayFmt.format(Instant.ofEpochMilli(triggerAt).atZone(zone()).toLocalDate())}"
            val entity = ReminderEntity(
                taskId = task.id,
                projectId = task.projectId,
                kind = kind,
                triggerAt = triggerAt,
                requestCode = 0,
                dedupeKey = key
            )
            // dedupeKey 唯一索引 + IGNORE：重复 key 插不进去（返回 -1），天然幂等
            val id = dao.insert(entity)
            if (id > 0) {
                // requestCode 用行 id：稳定唯一；哈希值会在碰撞时互相覆盖闹钟
                dao.updateRequestCode(id, id.toInt())
                out.add(entity.copy(id = id, requestCode = id.toInt()))
            }
        }

        if (d3) add(ReminderKind.D3, at(dueDate.minusDays(3), LocalTime.of(9, 0)))
        if (d1) add(ReminderKind.D1, at(dueDate.minusDays(1), LocalTime.of(9, 0)))
        if (d0) {
            val t = if (task.dueHasTime) due - 2 * 3600_000L else at(dueDate, LocalTime.of(9, 0))
            add(ReminderKind.D0, t)
        }
        return out
    }

    /** 全量重排：设置只读一次，别每个任务查三遍（几十个任务就是上百次查询） */
    suspend fun rescheduleAll(): Int {
        val d3 = boolSetting(KEY_D3_ENABLED, true)
        val d1 = boolSetting(KEY_D1_ENABLED, true)
        val d0 = boolSetting(KEY_D0_ENABLED, true)
        return db.taskDao().allOpen().sumOf { rebuildReminders(it, d3, d1, d0).size }
    }

    suspend fun digestTime(): Pair<Int, Int> {
        val h = db.settingDao().get(KEY_DIGEST_HOUR)?.toIntOrNull() ?: DEFAULT_DIGEST_HOUR
        val m = db.settingDao().get(KEY_DIGEST_MINUTE)?.toIntOrNull() ?: DEFAULT_DIGEST_MINUTE
        return h to m
    }

    suspend fun digestEnabled(): Boolean = boolSetting(KEY_DIGEST_ENABLED, true)

    suspend fun setDigestTime(hour: Int, minute: Int) {
        db.settingDao().put(com.howtodo.data.db.AppSettingEntity(KEY_DIGEST_HOUR, hour.toString()))
        db.settingDao().put(com.howtodo.data.db.AppSettingEntity(KEY_DIGEST_MINUTE, minute.toString()))
    }

    suspend fun setDigestEnabled(enabled: Boolean) {
        db.settingDao().put(com.howtodo.data.db.AppSettingEntity(KEY_DIGEST_ENABLED, enabled.toString()))
    }

    suspend fun setOffsetEnabled(key: String, enabled: Boolean) {
        db.settingDao().put(com.howtodo.data.db.AppSettingEntity(key, enabled.toString()))
    }

    suspend fun isOffsetEnabled(key: String, default: Boolean = true): Boolean = boolSetting(key, default)

    /** 今日概览数据 */
    suspend fun todayBuckets(): Triple<List<TaskEntity>, List<TaskEntity>, List<TaskEntity>> {
        val today = LocalDate.now()
        val start = at(today, LocalTime.MIN)
        val end = at(today, LocalTime.MAX)
        val overdue = db.taskDao().overdue()
        val todayList = db.taskDao().dueBetween(start, end)
        val soon = db.taskDao().dueBetween(end, end + 3L * 24 * 3600_000)
        return Triple(overdue, todayList, soon)
    }

    private suspend fun boolSetting(key: String, default: Boolean): Boolean {
        val v = db.settingDao().get(key) ?: return default
        return v.toBooleanStrictOrNull() ?: default
    }

    private fun at(date: LocalDate, time: LocalTime): Long =
        date.atTime(time).atZone(zone()).toInstant().toEpochMilli()
}
