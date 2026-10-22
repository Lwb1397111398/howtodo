package com.howtodo.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 任务 + 所属工作标题，用于「今日」聚合视图 */
data class TaskWithProject(
    @Embedded val task: TaskEntity,
    val projectTitle: String,
    val projectColor: Int
)

/** 提案 + 所属工作标题 */
data class ProposalWithProject(
    @Embedded val proposal: ProposalEntity,
    val projectTitle: String
)

/** 材料 + 所属工作标题 */
data class SourceWithProject(
    @Embedded val source: SourceEntity,
    val projectTitle: String?
)

/** 每个工作的进度统计 */
data class ProjectCount(
    val projectId: String,
    val done: Int,
    val total: Int,
    val overdue: Int
)

@Dao
interface TaskViewDao {

    @Query(
        """
        SELECT projectId,
               SUM(CASE WHEN status = 'DONE' THEN 1 ELSE 0 END) AS done,
               COUNT(*) AS total,
               0 AS overdue
        FROM task
        WHERE status != 'CANCELLED'
        GROUP BY projectId
        """
    )
    fun observeProjectCounts(): Flow<List<ProjectCount>>

    @Query(
        """
        SELECT t.*, p.title AS projectTitle, p.color AS projectColor
        FROM task t JOIN project p ON p.id = t.projectId
        WHERE t.status IN ('TODO','NEEDS_CONFIRM')
        ORDER BY (t.dueAt IS NULL), t.dueAt, t.priority
        """
    )
    fun observeOpenWithProject(): Flow<List<TaskWithProject>>

    @Query(
        """
        SELECT t.*, p.title AS projectTitle, p.color AS projectColor
        FROM task t JOIN project p ON p.id = t.projectId
        WHERE t.status = 'TODO' AND t.dueAt IS NOT NULL
        ORDER BY t.dueAt
        """
    )
    suspend fun openDated(): List<TaskWithProject>

    @Query(
        """
        SELECT t.*, p.title AS projectTitle, p.color AS projectColor
        FROM task t JOIN project p ON p.id = t.projectId
        WHERE t.status = 'DONE' AND t.doneAt >= :since
        ORDER BY t.doneAt DESC
        """
    )
    fun observeDoneSince(since: Long): Flow<List<TaskWithProject>>

    @Query(
        """
        SELECT t.*, p.title AS projectTitle, p.color AS projectColor
        FROM task t JOIN project p ON p.id = t.projectId
        WHERE t.projectId = :projectId
        ORDER BY (t.dueAt IS NULL), t.dueAt, t.ord
        """
    )
    fun observeByProject(projectId: String): Flow<List<TaskWithProject>>
}

@Dao
interface ProposalViewDao {
    @Query(
        """
        SELECT pr.*, p.title AS projectTitle
        FROM proposal pr JOIN project p ON p.id = pr.projectId
        WHERE pr.state = 'PENDING'
        ORDER BY pr.createdAt DESC
        """
    )
    fun observePendingWithProject(): Flow<List<ProposalWithProject>>
}

@Dao
interface SourceViewDao {
    @Query(
        """
        SELECT s.*, p.title AS projectTitle
        FROM source s LEFT JOIN project p ON p.id = s.projectId
        WHERE s.projectId = :projectId
        ORDER BY s.importedAt DESC
        """
    )
    fun observeForProject(projectId: String): Flow<List<SourceWithProject>>

    @Query(
        """
        SELECT s.*, p.title AS projectTitle
        FROM source s LEFT JOIN project p ON p.id = s.projectId
        ORDER BY s.importedAt DESC
        """
    )
    fun observeAllWithProject(): Flow<List<SourceWithProject>>
}
