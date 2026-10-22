package com.howtodo.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * 全部实体。约定：
 *  - 主键统一 TEXT（UUID），便于 LLM 引用与跨表引用
 *  - 时间统一 INTEGER（UTC epoch millis）
 *  - 枚举统一用 String + 常量对象（不引入 TypeConverter，减少运行期类型坑）
 *  - 列表/字典类字段用 JSON 字符串存
 */

// ---------------------------------------------------------------- 供应商与模型

@Entity(tableName = "provider")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** 例如 https://dashscope.aliyuncs.com/compatible-mode/v1 */
    val baseUrl: String,
    /** Android Keystore AES-GCM 加密后的密钥 */
    val apiKeyCipher: ByteArray,
    val apiKeyIv: ByteArray,
    val keyVersion: Int = 1,
    /** 可选自定义请求头，JSON object */
    val extraHeaders: String? = null,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean = other is ProviderEntity && other.id == id
    override fun hashCode(): Int = id.hashCode()
}

@Entity(
    tableName = "provider_model",
    primaryKeys = ["providerId", "modelId"],
    indices = [Index("providerId")]
)
data class ProviderModelEntity(
    val providerId: String,
    val modelId: String,
    val displayName: String? = null,
    val contextWindow: Int? = null,
    val maxOutput: Int? = null,
    val supportsVision: Boolean = false,
    val rawJson: String? = null,
    val fetchedAt: Long = System.currentTimeMillis()
)

/** 角色绑定：vision / reasoning / fast / chat */
@Entity(tableName = "role_binding")
data class RoleBindingEntity(
    @PrimaryKey val role: String,
    val providerId: String? = null,
    val modelId: String? = null,
    val temperature: Double = 0.2,
    val maxOutputTokens: Int = 4096,
    /** 上下文预算，可设到 200000 */
    val contextBudget: Int = 32000,
    val updatedAt: Long = System.currentTimeMillis()
)

object AiRole {
    const val VISION = "vision"       // 读图转写
    const val REASONING = "reasoning" // 基线抽取 / 增量合并
    const val FAST = "fast"           // 关键词改写、轻分类
    const val CHAT = "chat"           // 问答
    val ALL = listOf(VISION, REASONING, FAST, CHAT)
    fun label(role: String): String = when (role) {
        VISION -> "识图（图片/PDF 转文字）"
        REASONING -> "推理（抽取任务与合并变更）"
        FAST -> "快速（关键词改写）"
        CHAT -> "问答"
        else -> role
    }
}

// ---------------------------------------------------------------- 工作与材料

object ProjectStatus {
    const val ACTIVE = "ACTIVE"
    const val ARCHIVED = "ARCHIVED"
}

@Entity(tableName = "project", indices = [Index(value = ["status", "updatedAt"])])
data class ProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String? = null,
    val status: String = ProjectStatus.ACTIVE,
    val color: Int = 0,
    val pinned: Boolean = false,
    val baselineBatchId: String? = null,
    /** 乐观并发基准：每应用一个提案 +1 */
    val schemaRev: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

object SourceKind {
    const val IMAGE = "IMAGE"
    const val PDF = "PDF"
    const val DOCX = "DOCX"
    const val XLSX = "XLSX"
    const val PPTX = "PPTX"
    const val TXT = "TXT"
    const val OTHER = "OTHER"
}

object SourceStatus {
    const val NEW = "NEW"
    const val PARSING = "PARSING"
    const val PARSED = "PARSED"
    const val NEEDS_VISION = "NEEDS_VISION"
    const val FAILED = "FAILED"
}

object SourceOrigin {
    const val SHARE = "SHARE"
    const val PICKER = "PICKER"
    const val CAMERA = "CAMERA"
    const val TEXT = "TEXT"
}

@Entity(
    tableName = "source",
    indices = [
        Index(value = ["projectId", "importedAt"]),
        Index("status"),
        Index(value = ["sha256"], unique = false)
    ]
)
data class SourceEntity(
    @PrimaryKey val id: String,
    /** NULL 表示全局材料（不属于某个具体工作） */
    val projectId: String?,
    val displayName: String,
    val mime: String?,
    val kind: String = SourceKind.OTHER,
    /** 私有目录相对路径 materials/<id>.bin */
    val localPath: String,
    val sizeBytes: Long = 0,
    val sha256: String,
    val origUri: String? = null,
    val origin: String = SourceOrigin.PICKER,
    val pageCount: Int = 0,
    val textChars: Int = 0,
    val tokensEst: Int = 0,
    val status: String = SourceStatus.NEW,
    val error: String? = null,
    val batchId: String? = null,
    val ocrUsed: Boolean = false,
    val importedAt: Long = System.currentTimeMillis(),
    val indexedAt: Long? = null
)

@Entity(tableName = "chunk", indices = [Index(value = ["sourceId", "ord"]), Index("projectId")])
data class ChunkEntity(
    /** 与 FTS5 虚拟表 rowid 对齐 */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: String,
    val projectId: String?,
    val ord: Int,
    val pageNo: Int? = null,
    val section: String? = null,
    val text: String,
    val charStart: Int = 0,
    val charEnd: Int = 0,
    val kind: String = "PARA",
    val tokensEst: Int = 0
)

// ---------------------------------------------------------------- 批次与提案

object BatchMode {
    const val BASELINE = "BASELINE"
    const val INCREMENT = "INCREMENT"
}

object BatchStatus {
    const val CREATED = "CREATED"
    const val EXTRACTING = "EXTRACTING"
    const val PROPOSED = "PROPOSED"
    const val APPLIED = "APPLIED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
}

@Entity(tableName = "batch", indices = [Index(value = ["projectId", "createdAt"])])
data class BatchEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val mode: String = BatchMode.BASELINE,
    /** JSON 数组，材料 id */
    val sourceIds: String = "[]",
    val status: String = BatchStatus.CREATED,
    val role: String = AiRole.REASONING,
    val providerId: String? = null,
    val modelId: String? = null,
    val promptVersion: String = "baseline/v1",
    val schemaVersion: Int = 1,
    val requestTokens: Int = 0,
    val responseTokens: Int = 0,
    val rawRequestPath: String? = null,
    val rawResponsePath: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null
)

object ProposalKind {
    const val BASELINE = "BASELINE"
    const val PATCH = "PATCH"
}

object ProposalState {
    const val PENDING = "PENDING"
    const val PARTIALLY_APPLIED = "PARTIALLY_APPLIED"
    const val APPLIED = "APPLIED"
    const val REJECTED = "REJECTED"
    const val SUPERSEDED = "SUPERSEDED"
}

@Entity(tableName = "proposal", indices = [Index(value = ["projectId", "createdAt"]), Index("state")])
data class ProposalEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val batchId: String,
    val kind: String = ProposalKind.BASELINE,
    val state: String = ProposalState.PENDING,
    val baseRev: Int = 0,
    val summary: String? = null,
    val conflicts: Int = 0,
    val unknowns: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val appliedAt: Long? = null
)

object TaskOp {
    const val ADD = "ADD"
    const val UPDATE = "UPDATE"
    const val CANCEL = "CANCEL"
    const val NOOP = "NOOP"
}

object ItemDecision {
    const val PENDING = "PENDING"
    const val ACCEPT = "ACCEPT"
    const val REJECT = "REJECT"
}

object ConflictType {
    const val DONE_CONFLICT = "DONE_CONFLICT"
    const val MISSING_TARGET = "MISSING_TARGET"
    const val DUP_SUSPECT = "DUP_SUSPECT"
    const val AMBIGUOUS = "AMBIGUOUS"
    const val NO_EVIDENCE_DATE = "NO_EVIDENCE_DATE"
}

@Entity(tableName = "proposal_item", indices = [Index(value = ["proposalId", "decision"])])
data class ProposalItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val proposalId: String,
    val op: String,
    val targetExtKey: String? = null,
    val targetTaskId: String? = null,
    /** 应用所需数据的 JSON（add/update 的字段；cancel 的原值快照） */
    val payload: String,
    /** 供 UI 展示的"旧值 -> 新值"摘要 */
    val diffText: String? = null,
    val reason: String? = null,
    val evidenceJson: String? = null,
    val conflict: String? = null,
    val conflictNote: String? = null,
    val decision: String = ItemDecision.PENDING,
    val ord: Int = 0,
    val decidedAt: Long? = null,
    val resultTaskId: String? = null
)

@Entity(tableName = "change_log", indices = [Index(value = ["taskId"]), Index(value = ["projectId", "at"])])
data class ChangeLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: String,
    val taskId: String?,
    val proposalId: String?,
    val op: String,
    val field: String? = null,
    val oldValue: String? = null,
    val newValue: String? = null,
    /** 反向操作 JSON，用于撤销 */
    val undoToken: String? = null,
    val at: Long = System.currentTimeMillis()
)

// ---------------------------------------------------------------- 任务

object TaskStatus {
    const val NEEDS_CONFIRM = "NEEDS_CONFIRM"
    const val TODO = "TODO"
    const val DONE = "DONE"
    const val CANCELLED = "CANCELLED"
}

object TaskOrigin {
    const val AI = "AI"
    const val MANUAL = "MANUAL"
}

@Entity(
    tableName = "task",
    indices = [
        Index(value = ["projectId", "extKey"], unique = true),
        Index(value = ["projectId", "status", "dueAt"]),
        Index(value = ["status", "dueAt"])
    ]
)
data class TaskEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    /** LLM 稳定键（t1..tN），幂等核心 */
    val extKey: String,
    val title: String,
    val detail: String? = null,
    /** 原文没有日期时必须为 NULL */
    val dueAt: Long? = null,
    val dueHasTime: Boolean = false,
    /** 原文表述，如"11月5日17:00前" */
    val dueRaw: String? = null,
    val status: String = TaskStatus.TODO,
    val priority: Int = 2,
    val ord: Double = 0.0,
    val origin: String = TaskOrigin.AI,
    val originBatchId: String? = null,
    val lastBatchId: String? = null,
    val confidence: Double = 1.0,
    /** 依据全局知识推出的约束说明，UI 显示为可否决的"假设" */
    val assumption: String? = null,
    val rev: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val doneAt: Long? = null
)

object EvidenceField {
    const val TITLE = "TITLE"
    const val DUE = "DUE"
    const val DETAIL = "DETAIL"
    const val CANCEL = "CANCEL"
    const val ENTITY = "ENTITY"
}

@Entity(tableName = "task_evidence", indices = [Index("taskId"), Index("chunkId")])
data class TaskEvidenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String,
    val chunkId: Long?,
    val sourceId: String?,
    val field: String = EvidenceField.TITLE,
    val quote: String,
    val pageNo: Int? = null,
    val charStart: Int = 0,
    val charEnd: Int = 0,
    val confidence: Double = 1.0,
    val createdAt: Long = System.currentTimeMillis()
)

// ---------------------------------------------------------------- 全局知识

object EntityType {
    const val PERSON = "PERSON"
    const val PLACE = "PLACE"
    const val TIME_RULE = "TIME_RULE"
    const val CONTACT = "CONTACT"
    const val ORG = "ORG"
    const val RULE = "RULE"
    const val OTHER = "OTHER"
    val ALL = listOf(PERSON, PLACE, TIME_RULE, CONTACT, ORG, RULE, OTHER)
    fun label(t: String): String = when (t) {
        PERSON -> "人物"
        PLACE -> "地点"
        TIME_RULE -> "时间规则"
        CONTACT -> "联系方式"
        ORG -> "机构"
        RULE -> "规则"
        else -> "其他"
    }
}

object EntityScope {
    const val GLOBAL = "GLOBAL"
    const val PROJECT = "PROJECT"
}

@Entity(
    tableName = "knowledge_entity",
    indices = [Index("name"), Index(value = ["type", "status"]), Index(value = ["scope", "projectId"])]
)
data class KnowledgeEntity(
    @PrimaryKey val id: String,
    val type: String = EntityType.PERSON,
    val name: String,
    /** JSON 数组，如 ["张主任","张国强"] */
    val aliases: String = "[]",
    /** JSON object，如 {"office":"综合楼302","offDuty":"17:00"} */
    val attributes: String = "{}",
    /** 可直接注入 prompt 的一句话 */
    val summary: String? = null,
    val scope: String = EntityScope.GLOBAL,
    val projectId: String? = null,
    val confidence: Double = 0.8,
    val status: String = "ACTIVE",
    /** 已人工确认过的条目不会被自动抽取覆盖 */
    val verified: Boolean = false,
    val sourceId: String? = null,
    val chunkId: Long? = null,
    val quote: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

// ---------------------------------------------------------------- 问答

@Entity(tableName = "conversation", indices = [Index("projectId")])
data class ConversationEntity(
    @PrimaryKey val id: String,
    /** NULL 表示全局问答 */
    val projectId: String? = null,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "message", indices = [Index(value = ["conversationId", "createdAt"])])
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    /** user / assistant */
    val role: String,
    val content: String,
    /** JSON 数组：引用块 {label, chunkId, sourceId, sourceName, pageNo, quote, charStart, charEnd} */
    val citations: String? = null,
    val model: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val status: String = "OK",
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

// ---------------------------------------------------------------- 提醒

object ReminderKind {
    const val D3 = "D3"
    const val D1 = "D1"
    const val D0 = "D0"
    const val OVERDUE = "OVERDUE"
    const val DIGEST = "DIGEST"
}

object ReminderState {
    const val SCHEDULED = "SCHEDULED"
    const val FIRED = "FIRED"
    const val CANCELLED = "CANCELLED"
    const val MISSED = "MISSED"
}

@Entity(
    tableName = "reminder",
    indices = [Index(value = ["state", "triggerAt"]), Index("taskId")]
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String? = null,
    val projectId: String? = null,
    val kind: String,
    val triggerAt: Long,
    val state: String = ReminderState.SCHEDULED,
    val requestCode: Int,
    /** taskId|kind|yyyy-MM-dd，唯一，防重复 */
    val dedupeKey: String,
    val firedAt: Long? = null,
    val snoozeUntil: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

// ---------------------------------------------------------------- 导入队列与设置

@Entity(tableName = "import_queue", indices = [Index("state")])
data class ImportQueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val mime: String? = null,
    val name: String? = null,
    val projectId: String? = null,
    val state: String = "PENDING",
    val error: String? = null,
    val receivedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "app_setting")
data class AppSettingEntity(
    @PrimaryKey val key: String,
    val value: String
)
