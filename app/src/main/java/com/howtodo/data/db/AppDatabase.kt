package com.howtodo.data.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import android.content.Context

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
    version = 1,
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
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "howtodo.db")
                .addCallback(FtsCallback())
                .build()
    }
}
