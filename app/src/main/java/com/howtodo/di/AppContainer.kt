package com.howtodo.di

import android.content.Context
import com.howtodo.ai.AiRouter
import com.howtodo.ai.ExtractionService
import com.howtodo.ai.LlmClient
import com.howtodo.data.db.AppDatabase
import com.howtodo.data.repo.ChatRepo
import com.howtodo.data.repo.KnowledgeRepo
import com.howtodo.data.repo.ProjectRepo
import com.howtodo.data.repo.ProposalRepo
import com.howtodo.data.repo.ProviderRepo
import com.howtodo.data.repo.ReminderRepo
import com.howtodo.data.repo.SourceRepo
import com.howtodo.data.repo.TaskRepo
import com.howtodo.ingest.ImportManager
import com.howtodo.ingest.IngestPipeline
import com.howtodo.ingest.VisionOcr
import com.howtodo.notify.AlarmScheduler
import com.howtodo.rag.Answerer
import com.howtodo.rag.Retriever
import com.howtodo.util.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 手写依赖容器。
 *
 * 不用 Hilt：本项目依赖很少，手写更透明，也避免注解处理器拖慢本就吃紧的构建内存。
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext
    val db: AppDatabase = AppDatabase.build(appContext)
    val storage = Storage(appContext)

    val projectRepo = ProjectRepo(db)
    val sourceRepo = SourceRepo(db, storage)
    val taskRepo = TaskRepo(db)
    val proposalRepo = ProposalRepo(db)
    val providerRepo = ProviderRepo(db)
    val knowledgeRepo = KnowledgeRepo(db)
    val chatRepo = ChatRepo(db)
    val reminderRepo = ReminderRepo(db)

    val llmClient = LlmClient()
    val router = AiRouter(providerRepo, llmClient)
    val vision = VisionOcr(router)
    val ingest = IngestPipeline(db, storage, vision)
    val importManager = ImportManager(appContext, db, storage, sourceRepo, ingest)
    val extraction = ExtractionService(
        db, sourceRepo, projectRepo, taskRepo, proposalRepo, knowledgeRepo,
        router, storage, ingest
    )
    val retriever = Retriever(db, router)
    val answerer = Answerer(db, retriever, router, knowledgeRepo)
    val alarmScheduler = AlarmScheduler(appContext, reminderRepo)

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
