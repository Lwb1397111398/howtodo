package com.howtodo.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.howtodo.App
import com.howtodo.data.db.AppSettingEntity
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * 每日早报。
 *
 * 用 WorkManager 的周期任务：系统重启后依然保留，不需要我们维护闹钟。
 * 重复执行用 app_setting 里的日期标记去重，避免同一天弹两条。
 */
class DailyDigestWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? App ?: return Result.success()
        return try {
            showNow(app)
            Result.success()
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "daily_digest"
        private const val KEY_LAST_DATE = "digest.lastDate"

        fun ensureScheduled(context: Context) {
            val app = context.applicationContext as? App ?: return
            app.container.appScope.launch {
                runCatching { reschedule(context.applicationContext, app) }
            }
        }

        suspend fun reschedule(context: Context, app: App) {
            val (hour, minute) = app.container.reminderRepo.digestTime()
            val enabled = app.container.reminderRepo.digestEnabled()
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val delay = (nextDigestMillis(hour, minute) - System.currentTimeMillis()).coerceAtLeast(60_000)
            val request = PeriodicWorkRequestBuilder<DailyDigestWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        suspend fun showNow(app: App) {
            val container = app.container
            val today = todayKey()
            val last = container.db.settingDao().get(KEY_LAST_DATE)
            if (last == today) return

            val (overdue, todayList, soon) = container.reminderRepo.todayBuckets()
            val titles = (overdue + todayList).sortedBy { it.dueAt ?: Long.MAX_VALUE }
                .map { it.title }
            Notifications.showDigest(
                app, overdue.size, todayList.size, soon.size, titles
            )
            container.db.settingDao().put(AppSettingEntity(KEY_LAST_DATE, today))
        }
    }
}
