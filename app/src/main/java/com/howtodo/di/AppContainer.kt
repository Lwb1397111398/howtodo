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
import com.howtodo.update.UpdateManager
import com.howtodo.util.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 后台分析的可观察状态。
 * null = 空闲；proposalId / error 任一非 null 表示已结束（成功/失败）。
 * 分析跑在 appScope 上，用户离开页面不会中断，界面只负责订阅展示。
 */
data class AnalysisState(
    val projectId: String,
    val message: String,
    val proposalId: String? = null,
    val error: String? = null
)

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
    val providerRepo = ProviderRepo(db)
    val knowledgeRepo = KnowledgeRepo(db)
    val chatRepo = ChatRepo(db)
    val reminderRepo = ReminderRepo(db)
    val alarmScheduler = AlarmScheduler(appContext, reminderRepo)
    // TaskRepo 依赖 reminderRepo/alarmScheduler：取消任务撤提醒、重开任务补排提醒
    val taskRepo = TaskRepo(db, reminderRepo, alarmScheduler)
    val proposalRepo = ProposalRepo(db)
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
    val updateManager = UpdateManager(appContext)

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---------------- 后台分析（App 级作用域，离开页面不中断）----------------

    private val _analysisState = MutableStateFlow<AnalysisState?>(null)
    val analysisState: StateFlow<AnalysisState?> = _analysisState.asStateFlow()

    /** 在应用级作用域启动分析；已有分析在跑时忽略本次请求 */
    fun startAnalysis(projectId: String, sourceIds: List<String>) {
        _analysisState.value?.let { cur ->
            if (cur.proposalId == null && cur.error == null) return
        }
        appScope.launch {
            _analysisState.value = AnalysisState(projectId, "准备分析…")
            val r = extraction.analyze(projectId, sourceIds) { msg ->
                _analysisState.update { it?.copy(message = msg) }
            }
            val cur = _analysisState.value ?: return@launch
            _analysisState.value = r.fold(
                onSuccess = { cur.copy(proposalId = it.proposalId) },
                onFailure = { e -> cur.copy(error = e.message ?: "分析失败") }
            )
        }
    }

    /** 界面消费完结果或用户选「后台运行」后调用 */
    fun dismissAnalysis() {
        _analysisState.value = null
    }
}
