package com.howtodo.data.repo

import com.howtodo.data.db.AppDatabase
import com.howtodo.data.db.TaskEntity
import com.howtodo.data.db.TaskEvidenceEntity
import com.howtodo.data.db.TaskStatus
import com.howtodo.notify.AlarmScheduler
import kotlinx.coroutines.flow.Flow

class TaskRepo(
    private val db: AppDatabase,
    private val reminderRepo: ReminderRepo,
    private val alarmScheduler: AlarmScheduler
) {

    private val dao get() = db.taskDao()
    private val viewDao get() = db.taskViewDao()
    private val evidenceDao get() = db.taskEvidenceDao()

    fun observeByProject(projectId: String) = viewDao.observeByProject(projectId)

    fun observeOpen() = viewDao.observeOpenWithProject()

    fun observeTotals(projectId: String): Flow<Int> = dao.observeTotal(projectId)

    fun observeDone(projectId: String): Flow<Int> = dao.observeDone(projectId)

    suspend fun byId(id: String) = dao.byId(id)

    suspend fun byExtKey(projectId: String, extKey: String) = dao.byExtKey(projectId, extKey)

    suspend fun listByProject(projectId: String) = dao.listByProject(projectId)

    suspend fun insert(t: TaskEntity) = dao.insert(t)

    suspend fun update(t: TaskEntity) = dao.update(t)

    suspend fun openDated() = viewDao.openDated()

    suspend fun dueBetween(start: Long, end: Long) = dao.dueBetween(start, end)

    suspend fun overdue() = dao.overdue()

    suspend fun setStatus(id: String, status: String) {
        val doneAt = if (status == TaskStatus.DONE) System.currentTimeMillis() else null
        dao.setStatus(id, status, doneAt)
        // 已完成/已取消的任务不再提醒（已注册的闹钟到点后由 Receiver 的状态检查兜底跳过）
        if (status == TaskStatus.DONE || status == TaskStatus.CANCELLED) {
            db.reminderDao().cancelByTask(id)
        }
    }

    suspend fun toggleDone(id: String) {
        val t = dao.byId(id) ?: return
        val next = if (t.status == TaskStatus.DONE) TaskStatus.TODO else TaskStatus.DONE
        setStatus(id, next)
        if (next == TaskStatus.TODO) {
            // 误标完成又重新打开的任务把提醒排回去（已过期的档位会被自动跳过）
            reminderRepo.rescheduleTask(t).forEach { alarmScheduler.schedule(it) }
        }
    }

    suspend fun addEvidence(list: List<TaskEvidenceEntity>) {
        if (list.isNotEmpty()) evidenceDao.insertAll(list)
    }

    fun observeEvidence(taskId: String) = evidenceDao.observeByTask(taskId)

    suspend fun evidenceOf(taskId: String) = evidenceDao.byTask(taskId)

    suspend fun maxOrd(projectId: String) = dao.maxOrd(projectId) ?: 0.0
}
