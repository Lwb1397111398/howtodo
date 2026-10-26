package com.howtodo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(p: ProjectEntity)

    @Update
    suspend fun update(p: ProjectEntity)

    @Query("DELETE FROM project WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM project WHERE id = :id")
    suspend fun byId(id: String): ProjectEntity?

    @Query("SELECT * FROM project ORDER BY pinned DESC, updatedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM project WHERE status = :status ORDER BY pinned DESC, updatedAt DESC")
    suspend fun byStatus(status: String): List<ProjectEntity>

    @Query("SELECT * FROM project WHERE status = 'ACTIVE' ORDER BY pinned DESC, updatedAt DESC")
    suspend fun active(): List<ProjectEntity>

    @Query("SELECT COUNT(*) FROM project WHERE status = 'ACTIVE'")
    fun observeActiveCount(): Flow<Int>

    @Query("UPDATE project SET schemaRev = schemaRev + 1, updatedAt = :now WHERE id = :id")
    suspend fun bumpRev(id: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE project SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE project SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: String, status: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE project SET pinned = :pinned, updatedAt = :now WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE project SET baselineBatchId = :batchId WHERE id = :id")
    suspend fun setBaseline(id: String, batchId: String?)

    @Query("UPDATE project SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long = System.currentTimeMillis())
}

@Dao
interface SourceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(s: SourceEntity)

    @Update
    suspend fun update(s: SourceEntity)

    @Query("SELECT * FROM source WHERE id = :id")
    suspend fun byId(id: String): SourceEntity?

    @Query("SELECT * FROM source WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<SourceEntity>

    @Query("SELECT * FROM source WHERE projectId = :projectId ORDER BY importedAt DESC")
    fun observeByProject(projectId: String): Flow<List<SourceEntity>>

    @Query("SELECT * FROM source WHERE projectId IS NULL ORDER BY importedAt DESC")
    fun observeGlobal(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM source ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM source WHERE sha256 = :sha AND (projectId = :projectId OR projectId IS NULL) LIMIT 1")
    suspend fun findBySha(projectId: String?, sha: String): SourceEntity?

    @Query("SELECT * FROM source WHERE status IN ('NEW','PARSING','NEEDS_VISION') ORDER BY importedAt")
    suspend fun pending(): List<SourceEntity>

    @Query("UPDATE source SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: String, status: String, error: String? = null)

    @Query("UPDATE source SET textChars = :chars, tokensEst = :tokens, pageCount = :pages, ocrUsed = :ocr WHERE id = :id")
    suspend fun setMetrics(id: String, chars: Int, tokens: Int, pages: Int, ocr: Boolean)

    @Query("UPDATE source SET indexedAt = :at WHERE id = :id")
    suspend fun setIndexed(id: String, at: Long = System.currentTimeMillis())

    @Query("UPDATE source SET batchId = :batchId WHERE id = :id")
    suspend fun setBatch(id: String, batchId: String?)

    @Query("UPDATE source SET projectId = :projectId WHERE id = :id")
    suspend fun setProject(id: String, projectId: String?)

    @Query("SELECT * FROM source WHERE projectId = :projectId ORDER BY importedAt DESC")
    suspend fun listByProject(projectId: String): List<SourceEntity>

    @Query("DELETE FROM source WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM source WHERE projectId = :projectId")
    suspend fun countByProject(projectId: String): Int
}

@Dao
interface ChunkDao {
    @Insert
    suspend fun insert(c: ChunkEntity): Long

    @Insert
    suspend fun insertAll(cs: List<ChunkEntity>): List<Long>

    @Query("DELETE FROM chunk WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: String)

    @Query("SELECT * FROM chunk WHERE id = :id")
    suspend fun byId(id: Long): ChunkEntity?

    @Query("SELECT * FROM chunk WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<ChunkEntity>

    @Query("SELECT * FROM chunk WHERE sourceId = :sourceId ORDER BY ord")
    suspend fun bySource(sourceId: String): List<ChunkEntity>

    @Query("SELECT * FROM chunk WHERE sourceId = :sourceId ORDER BY ord LIMIT :limit OFFSET :offset")
    suspend fun page(sourceId: String, limit: Int, offset: Int): List<ChunkEntity>

    @Query("SELECT COUNT(*) FROM chunk WHERE sourceId = :sourceId")
    suspend fun countBySource(sourceId: String): Int

    /** 材料从「材料库」归到某项工作时，已切好的块要跟着换归属，否则工作内检索搜不到 */
    @Query("UPDATE chunk SET projectId = :projectId WHERE sourceId = :sourceId")
    suspend fun setProjectBySource(sourceId: String, projectId: String?)
}

@Dao
interface BatchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(b: BatchEntity)

    @Update
    suspend fun update(b: BatchEntity)

    @Query("SELECT * FROM batch WHERE id = :id")
    suspend fun byId(id: String): BatchEntity?

    @Query("SELECT * FROM batch WHERE projectId = :projectId ORDER BY createdAt DESC")
    fun observeByProject(projectId: String): Flow<List<BatchEntity>>

    @Query("SELECT * FROM batch WHERE projectId = :projectId ORDER BY createdAt DESC LIMIT 1")
    suspend fun latest(projectId: String): BatchEntity?

    /** 上次进程退出时卡在「解析中」的批次，启动时由调用方置 FAILED */
    @Query("SELECT * FROM batch WHERE status = 'EXTRACTING'")
    suspend fun stale(): List<BatchEntity>

    @Query("UPDATE batch SET status = :status, error = :error, finishedAt = :at WHERE id = :id")
    suspend fun setStatus(id: String, status: String, error: String? = null, at: Long? = System.currentTimeMillis())
}

@Dao
interface ProposalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(p: ProposalEntity)

    @Update
    suspend fun update(p: ProposalEntity)

    @Query("SELECT * FROM proposal WHERE id = :id")
    suspend fun byId(id: String): ProposalEntity?

    @Query("SELECT * FROM proposal WHERE projectId = :projectId ORDER BY createdAt DESC")
    fun observeByProject(projectId: String): Flow<List<ProposalEntity>>

    @Query("SELECT * FROM proposal WHERE state = 'PENDING' ORDER BY createdAt DESC")
    fun observePending(): Flow<List<ProposalEntity>>

    @Query("SELECT COUNT(*) FROM proposal WHERE state = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT * FROM proposal WHERE projectId = :projectId AND state = 'PENDING' ORDER BY createdAt DESC LIMIT 1")
    suspend fun pendingFor(projectId: String): ProposalEntity?

    @Query("UPDATE proposal SET state = :state, appliedAt = :at WHERE id = :id")
    suspend fun setState(id: String, state: String, at: Long? = System.currentTimeMillis())
}

@Dao
interface ProposalItemDao {
    @Insert
    suspend fun insertAll(items: List<ProposalItemEntity>): List<Long>

    @Insert
    suspend fun insert(item: ProposalItemEntity): Long

    @Update
    suspend fun update(item: ProposalItemEntity)

    @Query("UPDATE proposal_item SET decision = :decision, decidedAt = :at WHERE id = :id")
    suspend fun setDecision(id: Long, decision: String, at: Long = System.currentTimeMillis())

    @Query("UPDATE proposal_item SET decision = :decision, decidedAt = :at WHERE proposalId = :proposalId")
    suspend fun setAllDecisions(proposalId: String, decision: String, at: Long = System.currentTimeMillis())

    @Query("SELECT * FROM proposal_item WHERE proposalId = :proposalId ORDER BY ord, id")
    fun observeByProposal(proposalId: String): Flow<List<ProposalItemEntity>>

    @Query("SELECT * FROM proposal_item WHERE proposalId = :proposalId ORDER BY ord, id")
    suspend fun byProposal(proposalId: String): List<ProposalItemEntity>

    @Query("SELECT * FROM proposal_item WHERE id = :id")
    suspend fun byId(id: Long): ProposalItemEntity?

    @Query("UPDATE proposal_item SET resultTaskId = :taskId WHERE id = :id")
    suspend fun setResult(id: Long, taskId: String)

    @Query("SELECT COUNT(*) FROM proposal_item WHERE proposalId = :proposalId AND decision = 'ACCEPT'")
    suspend fun acceptedCount(proposalId: String): Int
}

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(t: TaskEntity)

    @Update
    suspend fun update(t: TaskEntity)

    @Query("SELECT * FROM task WHERE id = :id")
    suspend fun byId(id: String): TaskEntity?

    @Query("SELECT * FROM task WHERE projectId = :projectId AND extKey = :extKey")
    suspend fun byExtKey(projectId: String, extKey: String): TaskEntity?

    @Query("SELECT * FROM task WHERE projectId = :projectId ORDER BY (dueAt IS NULL), dueAt, ord")
    fun observeByProject(projectId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM task WHERE projectId = :projectId ORDER BY (dueAt IS NULL), dueAt, ord")
    suspend fun listByProject(projectId: String): List<TaskEntity>

    @Query("SELECT * FROM task WHERE status != 'DONE' AND status != 'CANCELLED' AND dueAt IS NOT NULL ORDER BY dueAt")
    fun observeUpcoming(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM task WHERE status = 'TODO' AND dueAt IS NOT NULL AND dueAt < :end ORDER BY dueAt")
    suspend fun dueBefore(end: Long): List<TaskEntity>

    @Query("SELECT * FROM task WHERE status = 'TODO' AND dueAt >= :start AND dueAt < :end ORDER BY dueAt")
    suspend fun dueBetween(start: Long, end: Long): List<TaskEntity>

    @Query("SELECT * FROM task WHERE status = 'TODO' AND dueAt < :now ORDER BY dueAt")
    suspend fun overdue(now: Long = System.currentTimeMillis()): List<TaskEntity>

    @Query("SELECT * FROM task WHERE status = 'NEEDS_CONFIRM' ORDER BY createdAt DESC")
    fun observeNeedsConfirm(): Flow<List<TaskEntity>>

    @Query("SELECT COUNT(*) FROM task WHERE projectId = :projectId AND status != 'CANCELLED'")
    fun observeTotal(projectId: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM task WHERE projectId = :projectId AND status = 'DONE'")
    fun observeDone(projectId: String): Flow<Int>

    @Query("UPDATE task SET status = :status, doneAt = :doneAt, updatedAt = :now, rev = rev + 1 WHERE id = :id")
    suspend fun setStatus(
        id: String,
        status: String,
        doneAt: Long?,
        now: Long = System.currentTimeMillis()
    )

    @Query("SELECT * FROM task WHERE status != 'CANCELLED' AND status != 'DONE' ORDER BY (dueAt IS NULL), dueAt")
    suspend fun allOpen(): List<TaskEntity>

    @Query("DELETE FROM task WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: String)

    @Query("SELECT MAX(ord) FROM task WHERE projectId = :projectId")
    suspend fun maxOrd(projectId: String): Double?
}

@Dao
interface TaskEvidenceDao {
    @Insert
    suspend fun insertAll(list: List<TaskEvidenceEntity>)

    @Insert
    suspend fun insert(e: TaskEvidenceEntity): Long

    @Query("SELECT * FROM task_evidence WHERE taskId = :taskId")
    fun observeByTask(taskId: String): Flow<List<TaskEvidenceEntity>>

    @Query("SELECT * FROM task_evidence WHERE taskId = :taskId")
    suspend fun byTask(taskId: String): List<TaskEvidenceEntity>

    @Query("DELETE FROM task_evidence WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: String)
}

@Dao
interface ChangeLogDao {
    @Insert
    suspend fun insert(c: ChangeLogEntity): Long

    @Query("SELECT * FROM change_log WHERE projectId = :projectId ORDER BY id DESC LIMIT :limit")
    suspend fun recent(projectId: String, limit: Int = 100): List<ChangeLogEntity>

    @Query("SELECT * FROM change_log WHERE proposalId = :proposalId ORDER BY id DESC")
    suspend fun byProposal(proposalId: String): List<ChangeLogEntity>

    @Query("DELETE FROM change_log WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface KnowledgeDao {
    @Upsert
    suspend fun upsert(e: KnowledgeEntity)

    @Upsert
    suspend fun upsertAll(list: List<KnowledgeEntity>)

    @Query("DELETE FROM knowledge_entity WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM knowledge_entity WHERE id = :id")
    suspend fun byId(id: String): KnowledgeEntity?

    @Query("SELECT * FROM knowledge_entity ORDER BY type, name")
    fun observeAll(): Flow<List<KnowledgeEntity>>

    @Query("SELECT * FROM knowledge_entity WHERE status = 'ACTIVE' ORDER BY type, name")
    suspend fun allActive(): List<KnowledgeEntity>

    @Query("SELECT * FROM knowledge_entity WHERE status = 'ACTIVE' AND (scope = 'GLOBAL' OR projectId = :projectId) ORDER BY type, name")
    suspend fun activeFor(projectId: String?): List<KnowledgeEntity>

    @Query("SELECT * FROM knowledge_entity WHERE status = 'ACTIVE' AND name = :name AND type = :type LIMIT 1")
    suspend fun findByName(name: String, type: String): KnowledgeEntity?

    @Query("SELECT * FROM knowledge_entity WHERE lower(name) LIKE lower(:q) OR aliases LIKE :q OR attributes LIKE :q LIMIT 40")
    suspend fun search(q: String): List<KnowledgeEntity>

    @Query("SELECT COUNT(*) FROM knowledge_entity WHERE status = 'ACTIVE'")
    fun observeCount(): Flow<Int>

    @Query("UPDATE knowledge_entity SET verified = 1, status = 'ACTIVE', updatedAt = :now WHERE id = :id")
    suspend fun verify(id: String, now: Long = System.currentTimeMillis())
}

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(c: ConversationEntity)

    @Query("SELECT * FROM conversation WHERE id = :id")
    suspend fun byId(id: String): ConversationEntity?

    @Query("SELECT * FROM conversation WHERE projectId = :projectId ORDER BY updatedAt DESC")
    fun observeByProject(projectId: String?): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversation ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("UPDATE conversation SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE conversation SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    @Query("DELETE FROM conversation WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(m: MessageEntity)

    @Query("SELECT * FROM message WHERE conversationId = :conversationId ORDER BY createdAt, id")
    fun observeByConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM message WHERE conversationId = :conversationId ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(conversationId: String, limit: Int = 8): List<MessageEntity>

    @Query("DELETE FROM message WHERE conversationId = :conversationId")
    suspend fun deleteByConversation(conversationId: String)
}

@Dao
interface ReminderDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(r: ReminderEntity): Long

    @Query("SELECT * FROM reminder WHERE id = :id")
    suspend fun byId(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminder WHERE state = 'SCHEDULED' ORDER BY triggerAt")
    suspend fun scheduled(): List<ReminderEntity>

    @Query("SELECT * FROM reminder WHERE taskId = :taskId AND state = 'SCHEDULED'")
    suspend fun byTask(taskId: String): List<ReminderEntity>

    @Query("SELECT * FROM reminder WHERE dedupeKey = :key LIMIT 1")
    suspend fun byDedupe(key: String): ReminderEntity?

    @Query("UPDATE reminder SET requestCode = :code WHERE id = :id")
    suspend fun updateRequestCode(id: Long, code: Int)

    @Query("UPDATE reminder SET state = :state, firedAt = :at WHERE id = :id")
    suspend fun setState(id: Long, state: String, at: Long? = System.currentTimeMillis())

    @Query("UPDATE reminder SET state = 'CANCELLED' WHERE taskId = :taskId AND state = 'SCHEDULED'")
    suspend fun cancelByTask(taskId: String)

    @Query("UPDATE reminder SET triggerAt = :at, state = 'SCHEDULED', firedAt = NULL WHERE id = :id")
    suspend fun reschedule(id: Long, at: Long)

    @Query("DELETE FROM reminder WHERE state != 'SCHEDULED' AND triggerAt < :before")
    suspend fun purgeOld(before: Long)
}

@Dao
interface ImportQueueDao {
    @Insert
    suspend fun insert(q: ImportQueueEntity): Long

    @Query("SELECT * FROM import_queue WHERE state = 'PENDING' ORDER BY receivedAt")
    suspend fun pending(): List<ImportQueueEntity>

    @Query("SELECT COUNT(*) FROM import_queue WHERE state = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query("UPDATE import_queue SET state = :state, error = :error WHERE id = :id")
    suspend fun setState(id: Long, state: String, error: String? = null)

    @Query("DELETE FROM import_queue WHERE state = 'COPIED' AND receivedAt < :before")
    suspend fun purgeDone(before: Long)
}

@Dao
interface SettingDao {
    @Upsert
    suspend fun put(s: AppSettingEntity)

    @Query("SELECT value FROM app_setting WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM app_setting WHERE key = :key")
    fun observe(key: String): Flow<String?>

    @Query("DELETE FROM app_setting WHERE key = :key")
    suspend fun remove(key: String)
}

@Dao
interface ProviderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(p: ProviderEntity)

    @Update
    suspend fun update(p: ProviderEntity)

    @Query("DELETE FROM provider WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM provider WHERE id = :id")
    suspend fun byId(id: String): ProviderEntity?

    @Query("SELECT * FROM provider ORDER BY createdAt")
    fun observeAll(): Flow<List<ProviderEntity>>

    @Query("SELECT * FROM provider ORDER BY createdAt")
    suspend fun all(): List<ProviderEntity>
}

@Dao
interface ProviderModelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<ProviderModelEntity>)

    @Query("DELETE FROM provider_model WHERE providerId = :providerId")
    suspend fun clear(providerId: String)

    @Query("SELECT * FROM provider_model WHERE providerId = :providerId ORDER BY modelId")
    suspend fun byProvider(providerId: String): List<ProviderModelEntity>

    @Query("SELECT * FROM provider_model WHERE providerId = :providerId ORDER BY modelId")
    fun observeByProvider(providerId: String): Flow<List<ProviderModelEntity>>

    @Query("SELECT * FROM provider_model WHERE providerId = :providerId AND modelId = :modelId")
    suspend fun byId(providerId: String, modelId: String): ProviderModelEntity?
}

@Dao
interface RoleBindingDao {
    @Upsert
    suspend fun upsert(b: RoleBindingEntity)

    @Query("SELECT * FROM role_binding WHERE role = :role")
    suspend fun byRole(role: String): RoleBindingEntity?

    @Query("SELECT * FROM role_binding")
    fun observeAll(): Flow<List<RoleBindingEntity>>

    @Query("SELECT * FROM role_binding")
    suspend fun all(): List<RoleBindingEntity>
}
