package com.howtodo.data.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProviderEntity::class,
        ProviderModelEntity::class,
        RoleBindingEntity::class,
        ProjectEntity::class,
        SourceEntity::class,
        ChunkEntity::class,
        BatchEntity::class,
        ProposalEntity::class,
        ProposalItemEntity::class,
        ChangeLogEntity::class,
        TaskEntity::class,
        TaskEvidenceEntity::class,
        KnowledgeEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        ReminderEntity::class,
        ImportQueueEntity::class,
        AppSettingEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun sourceDao(): SourceDao
    abstract fun chunkDao(): ChunkDao
    abstract fun batchDao(): BatchDao
    abstract fun proposalDao(): ProposalDao
    abstract fun proposalItemDao(): ProposalItemDao
    abstract fun taskDao(): TaskDao
    abstract fun taskEvidenceDao(): TaskEvidenceDao
    abstract fun changeLogDao(): ChangeLogDao
    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun reminderDao(): ReminderDao
    abstract fun importQueueDao(): ImportQueueDao
    abstract fun settingDao(): SettingDao
    abstract fun providerDao(): ProviderDao
    abstract fun providerModelDao(): ProviderModelDao
    abstract fun roleBindingDao(): RoleBindingDao

    abstract fun taskViewDao(): TaskViewDao
    abstract fun proposalViewDao(): ProposalViewDao
    abstract fun sourceViewDao(): SourceViewDao

    abstract fun ragDao(): com.howtodo.rag.RagDao

    companion object {
        /**
         * v1→v2：reminder.dedupeKey 唯一化。历史数据里可能已有重复 key，必须先清重
         * （同一 key 优先保留未触发的，其次最早的）再建唯一索引，否则迁移直接失败。
         * requestCode 顺手统一改成行 id（旧数据是哈希值，会互相覆盖闹钟）。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "DELETE FROM reminder WHERE id != (" +
                        "SELECT r2.id FROM reminder r2 WHERE r2.dedupeKey = reminder.dedupeKey " +
                        "ORDER BY CASE WHEN r2.state = 'SCHEDULED' THEN 0 ELSE 1 END, r2.id LIMIT 1)"
                )
                db.execSQL("UPDATE reminder SET requestCode = CAST(id AS INTEGER)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_reminder_dedupeKey` ON `reminder` (`dedupeKey`)")
            }
        }

        /**
         * v2→v3：drop 半成品 entity_fts 虚拟表。它是知识检索 FTS5 方案的遗留，
         * 从未写入也从未查询（知识检索一直走 LIKE），留着只会在每次打开数据库时多建一张空表。
         * chunk_fts 保留：问答与提案引用仍靠它做 BM25 检索。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS entity_fts")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "howtodo.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .addCallback(FtsCallback())
                .build()
    }
}
