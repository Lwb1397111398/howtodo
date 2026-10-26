package com.howtodo.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.howtodo.App
import com.howtodo.data.db.TaskStatus
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ALARM = "com.howtodo.ACTION_ALARM"
        const val ACTION_DONE = "com.howtodo.ACTION_DONE"
        const val ACTION_SNOOZE = "com.howtodo.ACTION_SNOOZE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? App ?: return
        val container = app.container
        val pending = goAsync()

        when (intent.action) {
            ACTION_DONE -> {
                val taskId = intent.getStringExtra("taskId") ?: run { pending.finish(); return }
                container.appScope.launch {
                    try {
                        container.taskRepo.setStatus(taskId, TaskStatus.DONE)
                        container.reminderRepo.cancelTask(taskId)
                    } finally {
                        pending.finish()
                    }
                }
            }

            ACTION_SNOOZE -> {
                val taskId = intent.getStringExtra("taskId")
                val reminderId = intent.getLongExtra("reminderId", -1L)
                container.appScope.launch {
                    try {
                        if (reminderId > 0) {
                            val at = System.currentTimeMillis() + 3600_000L
                            container.db.reminderDao().reschedule(reminderId, at)
                            container.db.reminderDao().byId(reminderId)?.let {
                                container.alarmScheduler.schedule(it)
                            }
                        } else if (taskId != null) {
                            // 没有原提醒行时补建一条
                            val task = container.taskRepo.byId(taskId)
                            if (task != null) {
                                container.reminderRepo.rescheduleTask(task)
                                container.alarmScheduler.rescheduleAll()
                            }
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }

            else -> {
                val reminderId = intent.getLongExtra("reminderId", -1L)
                container.appScope.launch {
                    try {
                        val reminder = container.db.reminderDao().byId(reminderId)
                        val taskId = reminder?.taskId ?: intent.getStringExtra("taskId")
                        if (taskId != null) {
                            val task = container.taskRepo.byId(taskId)
                            // 已完成/已取消的任务到点不再提醒
                            if (task != null && task.status != TaskStatus.DONE && task.status != TaskStatus.CANCELLED) {
                                if (androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                                    Notifications.showTaskReminder(
                                        context,
                                        task,
                                        reminder?.kind ?: "D0",
                                        reminderId
                                    )
                                    reminder?.let { container.reminderRepo.markFired(it.id) }
                                } else {
                                    // 无权限发不出去：记「错过」而不是「已提醒」，别让数据库假装送达
                                    reminder?.let {
                                        container.db.reminderDao().setState(it.id, com.howtodo.data.db.ReminderState.MISSED)
                                    }
                                }
                            } else {
                                reminder?.let { container.reminderRepo.markFired(it.id) }
                            }
                        } else {
                            reminder?.let { container.reminderRepo.markFired(it.id) }
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_DATE_CHANGED
            )
        ) return

        val app = context.applicationContext as? App ?: return
        val pending = goAsync()
        app.container.appScope.launch {
            try {
                app.container.alarmScheduler.rescheduleAll()
            } finally {
                pending.finish()
            }
        }
    }
}

/** 每日早报：用 WorkManager 保证跨重启仍生效 */
internal fun nextDigestMillis(hour: Int, minute: Int): Long {
    val zone = ZoneId.systemDefault()
    val now = java.time.LocalDateTime.now(zone)
    var target = now.toLocalDate().atTime(LocalTime.of(hour, minute))
    if (!target.isAfter(now)) target = target.plusDays(1)
    return target.atZone(zone).toInstant().toEpochMilli()
}

internal fun todayKey(): String = LocalDate.now().toString()
