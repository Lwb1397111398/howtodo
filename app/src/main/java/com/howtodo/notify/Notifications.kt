package com.howtodo.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.howtodo.MainActivity
import com.howtodo.R
import com.howtodo.data.db.ReminderKind
import com.howtodo.data.db.ReminderState
import com.howtodo.data.db.TaskEntity
import com.howtodo.util.Format

object Notifications {

    const val CHANNEL_DEADLINE = "deadline"
    const val CHANNEL_DIGEST = "digest"
    const val CHANNEL_PARSE = "parse"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DEADLINE, context.getString(R.string.channel_deadline_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_deadline_desc)
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DIGEST, context.getString(R.string.channel_digest_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_digest_desc)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PARSE, context.getString(R.string.channel_parse_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.channel_parse_desc)
            }
        )
    }

    private fun openApp(context: Context, taskId: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (taskId != null) putExtra("taskId", taskId)
        }
        return PendingIntent.getActivity(
            context,
            (taskId?.hashCode() ?: 0) and 0x7FFFFFFF,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun action(context: Context, action: String, taskId: String, reminderId: Long, requestCode: Int): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            this.action = action
            putExtra("taskId", taskId)
            putExtra("reminderId", reminderId)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun showTaskReminder(
        context: Context,
        task: TaskEntity,
        kind: String,
        reminderId: Long
    ) {
        val title = when (kind) {
            ReminderKind.D3 -> context.getString(R.string.notif_reminder_d3, task.title)
            ReminderKind.D1 -> context.getString(R.string.notif_reminder_d1, task.title)
            ReminderKind.D0 ->
                if (isOverdue(task)) context.getString(R.string.notif_reminder_overdue, task.title)
                else context.getString(R.string.notif_reminder_today, task.title)
            else -> task.title
        }
        val body = buildString {
            val due = Format.dateTime(task.dueAt).ifBlank { "未定" }
            append("截止 ").append(due)
            append(" · ").append(Format.relative(task.dueAt, task.dueHasTime))
            if (!task.detail.isNullOrBlank()) append("\n").append(task.detail.take(60))
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_DEADLINE)
            .setSmallIcon(R.drawable.ic_stat_todo)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, task.id))
            .addAction(0, context.getString(R.string.notif_action_done), action(context, ReminderReceiver.ACTION_DONE, task.id, reminderId, actionCode(task.id, 0)))
            .addAction(0, context.getString(R.string.notif_action_snooze), action(context, ReminderReceiver.ACTION_SNOOZE, task.id, reminderId, actionCode(task.id, 1)))

        notify(context, notificationId(task.id, kind), builder.build())
    }

    /**
     * 通知按钮的 PendingIntent 编号：taskId 全宽映射 + 动作序号。
     * 旧实现压进 4096 个桶（and 0x0FFF），任务一多必然互相覆盖、点错任务。
     */
    private fun actionCode(taskId: String, action: Int): Int =
        (taskId.hashCode() * 2 + action) and 0x7FFFFFFF

    fun showDigest(context: Context, overdue: Int, today: Int, soon: Int, topTitles: List<String>) {
        if (overdue == 0 && today == 0 && soon == 0) return
        val title = buildString {
            if (today > 0) append("今天 $today 项")
            if (overdue > 0) {
                if (isNotEmpty()) append(" · ")
                append("逾期 $overdue 项")
            }
            if (isNotEmpty()) append(" ")
            append("要处理")
        }
        val body = buildString {
            append("今天到期 ").append(today).append(" 项，3 天内 ").append(soon).append(" 项")
            if (overdue > 0) append("，已逾期 ").append(overdue).append(" 项")
            if (topTitles.isNotEmpty()) {
                append("\n\n")
                topTitles.take(4).forEach { append("· ").append(it).append('\n') }
            }
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_DIGEST)
            .setSmallIcon(R.drawable.ic_stat_todo)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.trim()))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))

        notify(context, DIGEST_ID, builder.build())
    }

    fun showParseDone(context: Context, sourceName: String, count: Int) {
        val builder = NotificationCompat.Builder(context, CHANNEL_PARSE)
            .setSmallIcon(R.drawable.ic_stat_todo)
            .setContentTitle(context.getString(R.string.notif_parse_done_title))
            .setContentText(context.getString(R.string.notif_parse_done_body, sourceName))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
        notify(context, (sourceName.hashCode() and 0x0FFF) or 0x3000, builder.build())
    }

    private fun notify(context: Context, id: Int, n: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            // 用户未授予通知权限
        } catch (_: Throwable) {
        }
    }

    private fun notificationId(taskId: String, kind: String): Int =
        (taskId.hashCode() * 31 + kind.hashCode()) and 0x7FFFFFFF

    private fun isOverdue(task: TaskEntity): Boolean =
        (task.dueAt ?: Long.MAX_VALUE) < System.currentTimeMillis()

    const val DIGEST_ID = 0x6000

    fun cancel(context: Context, id: Int) {
        try {
            NotificationManagerCompat.from(context).cancel(id)
        } catch (_: Throwable) {
        }
    }

    fun stateLabel(state: String): String = when (state) {
        ReminderState.FIRED -> "已提醒"
        ReminderState.CANCELLED -> "已取消"
        ReminderState.MISSED -> "错过"
        else -> "待提醒"
    }
}
