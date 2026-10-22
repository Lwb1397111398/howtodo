package com.howtodo.data.repo

import com.howtodo.data.db.AppDatabase
import com.howtodo.data.db.ProjectEntity
import com.howtodo.data.db.ProjectStatus
import com.howtodo.data.db.ProposalViewDao
import com.howtodo.data.db.TaskViewDao
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ProjectRepo(private val db: AppDatabase) {

    private val dao get() = db.projectDao()

    fun observeAll(): Flow<List<ProjectEntity>> = dao.observeAll()

    fun observeActiveCount(): Flow<Int> = dao.observeActiveCount()

    fun observeOpenTasks() = db.taskViewDao().observeOpenWithProject()

    fun observePendingProposals() = db.proposalViewDao().observePendingWithProject()

    suspend fun byId(id: String): ProjectEntity? = dao.byId(id)

    suspend fun active(): List<ProjectEntity> = dao.active()

    suspend fun create(title: String, description: String? = null): ProjectEntity {
        // 同名工作去重（避免 AI 从材料里推断出的标题重复建）
        val exists = dao.active().firstOrNull { it.title.trim() == title.trim() }
        if (exists != null) return exists
        val now = System.currentTimeMillis()
        val p = ProjectEntity(
            id = UUID.randomUUID().toString().replace("-", ""),
            title = title.trim().ifBlank { "未命名工作" },
            description = description,
            createdAt = now,
            updatedAt = now
        )
        dao.insert(p)
        return p
    }

    suspend fun rename(id: String, title: String) = dao.rename(id, title)

    suspend fun archive(id: String) = dao.setStatus(id, ProjectStatus.ARCHIVED)

    suspend fun unarchive(id: String) = dao.setStatus(id, ProjectStatus.ACTIVE)

    suspend fun pin(id: String, pinned: Boolean) = dao.setPinned(id, pinned)

    suspend fun touch(id: String) = dao.touch(id)

    suspend fun bumpRev(id: String) = dao.bumpRev(id)

    suspend fun setBaseline(id: String, batchId: String?) = dao.setBaseline(id, batchId)

    suspend fun delete(id: String) {
        db.taskDao().deleteByProject(id)
        dao.delete(id)
    }
}

class SourceRepo(
    private val db: AppDatabase,
    private val storage: com.howtodo.util.Storage
) {
    private val dao get() = db.sourceDao()

    fun observeByProject(projectId: String) = db.sourceViewDao().observeForProject(projectId)

    fun observeAll() = db.sourceViewDao().observeAllWithProject()

    suspend fun byId(id: String) = dao.byId(id)

    suspend fun byIds(ids: List<String>) = dao.byIds(ids)

    suspend fun pending() = dao.pending()

    /** 已存在同内容材料时直接返回旧记录（幂等） */
    suspend fun findByHash(projectId: String?, sha: String) = dao.findBySha(projectId, sha)

    suspend fun insert(s: com.howtodo.data.db.SourceEntity) = dao.insert(s)

    suspend fun setStatus(id: String, status: String, error: String? = null) = dao.setStatus(id, status, error)

    suspend fun setMetrics(id: String, chars: Int, tokens: Int, pages: Int, ocr: Boolean) =
        dao.setMetrics(id, chars, tokens, pages, ocr)

    suspend fun markIndexed(id: String) = dao.setIndexed(id)

    suspend fun setBatch(id: String, batchId: String?) = dao.setBatch(id, batchId)

    suspend fun setProject(id: String, projectId: String?) {
        dao.setProject(id, projectId)
        db.chunkDao().setProjectBySource(id, projectId)
    }

    suspend fun delete(id: String) {
        db.chunkDao().deleteBySource(id)
        storage.deleteMaterial(id)
        dao.delete(id)
    }

    suspend fun fileOf(id: String) = storage.materialFile(id)
}
