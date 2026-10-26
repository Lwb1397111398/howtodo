package com.howtodo

import android.app.Application
import com.howtodo.data.db.FtsStore
import com.howtodo.di.AppContainer
import com.howtodo.notify.DailyDigestWorker
import com.howtodo.notify.Notifications
import kotlinx.coroutines.launch

class App : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.ensureChannels(this)
        DailyDigestWorker.ensureScheduled(this)

        container.appScope.launch {
            runCatching { FtsStore.ensure(container.db.openHelper.writableDatabase) }
            runCatching { container.reminderRepo.purgeOld() }
            // 导入队列里已处理完的记录 7 天后清掉，表只进不出会一直涨
            runCatching {
                container.db.importQueueDao().purgeDone(System.currentTimeMillis() - 7L * 24 * 3600_000)
            }
            // 上次分析没跑完（进程被杀）的批次置失败，让用户能重新分析
            runCatching { container.extraction.failStaleBatches() }
            // 开机/冷启动后重建闹钟：Android 重启会清掉所有 AlarmManager 闹钟
            runCatching { container.alarmScheduler.rescheduleAll() }
            // 上次没解析完的材料续上
            runCatching { container.importManager.parsePending() }
            runCatching { container.updateManager.autoCheck() }
        }
    }
}
