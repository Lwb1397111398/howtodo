package com.howtodo.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.howtodo.data.db.ReminderEntity
import com.howtodo.data.repo.ReminderRepo

/**
 * 提醒调度。
 *
 * 精度策略：
 *  - 有精确闹钟权限 -> setExactAndAllowWhileIdle（最准）
 *  - 没有 -> setAndAllowWhileIdle（可能延后几分钟）
 *  - 断电/重装/改时间 -> BootReceiver 全量重排
 */
class AlarmScheduler(
    private val context: Context,
    private val reminderRepo: ReminderRepo
) {

    private fun alarmManager(): AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    fun canScheduleExact(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return alarmManager()?.canScheduleExactAlarms() ?: false
    }

    private fun pendingIntent(r: ReminderEntity): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_ALARM
            putExtra("reminderId", r.id)
            putExtra("taskId", r.taskId)
        }
        return PendingIntent.getBroadcast(
            context,
            r.requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(r: ReminderEntity) {
        val am = alarmManager() ?: return
        val pi = pendingIntent(r)
        val trigger = r.triggerAt
        try {
            if (canScheduleExact()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
        } catch (_: SecurityException) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } catch (_: Throwable) {
            }
        } catch (_: Throwable) {
        }
    }

    fun cancel(r: ReminderEntity) {
        try {
            alarmManager()?.cancel(pendingIntent(r))
        } catch (_: Throwable) {
        }
    }

    /**
     * 撤掉某个已不存在于数据库的提醒的闹钟（删除工作/任务时收尾用）。
     * 用 FLAG_NO_CREATE 只在闹钟还挂着时取消；filterEquals 不比对 extras，
     * 所以 intent 只需要 action 与目标一致。
     */
    fun cancelCode(requestCode: Int, reminderId: Long, taskId: String?) {
        try {
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                action = ReminderReceiver.ACTION_ALARM
                putExtra("reminderId", reminderId)
                putExtra("taskId", taskId)
            }
            val pi = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) alarmManager()?.cancel(pi)
        } catch (_: Throwable) {
        }
    }

    /** 按数据库重建所有闹钟 */
    suspend fun rescheduleAll(): Int {
        val list = reminderRepo.scheduled()
        var n = 0
        val now = System.currentTimeMillis()
        list.forEach { r ->
            if (r.triggerAt < now - 60_000) {
                // 错过的一次性提醒直接跳过，避免开机瞬间连环弹窗
                reminderRepo.markFired(r.id)
                return@forEach
            }
            schedule(r)
            n++
        }
        return n
    }
}
