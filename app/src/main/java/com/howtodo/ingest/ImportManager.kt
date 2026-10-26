package com.howtodo.ingest

import android.content.Context
import android.net.Uri
import com.howtodo.data.db.AppDatabase
import com.howtodo.data.db.ImportQueueEntity
import com.howtodo.data.db.SourceEntity
import com.howtodo.data.db.SourceKind
import com.howtodo.data.db.SourceOrigin
import com.howtodo.data.db.SourceStatus
import com.howtodo.data.repo.SourceRepo
import com.howtodo.util.Storage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * 材料导入。
 *
 * 两段式：**先复制再入库**。
 * 分享进来的 Uri 读权限可能是临时的，必须第一时间把字节拷进私有目录，
 * 否则用户切个应用权限就没了，表现为"导入失败但不知道原因"。
 */
class ImportManager(
    private val context: Context,
    private val db: AppDatabase,
    private val storage: Storage,
    private val sourceRepo: SourceRepo,
    private val ingest: IngestPipeline
) {

    private val parseMutex = Mutex()

    data class Outcome(
        val imported: Int,
        val duplicated: Int,
        val failed: Int,
        val sourceIds: List<String>,
        val errors: List<String>
    )

    /**
     * 立即复制所有 Uri 到私有目录，然后写库。
     * @param autoParse 是否立刻在后台解析（图片/PDF 会调用视觉模型）
     */
    suspend fun importUris(
        uris: List<Uri>,
        projectId: String?,
        origin: String = SourceOrigin.SHARE,
        autoParse: Boolean = true
    ): Outcome {
        var imported = 0
        var duplicated = 0
        var failed = 0
        val ids = ArrayList<String>()
        val errors = ArrayList<String>()

        uris.forEach { uri ->
            val id = storage.newId()
            val copied = try {
                storage.copyFromUri(uri, id)
            } catch (_: Storage.FileTooLargeException) {
                failed++
                db.importQueueDao().insert(
                    ImportQueueEntity(
                        uri = uri.toString(),
                        state = "FAILED",
                        error = "文件超过 ${Storage.MAX_IMPORT_BYTES / (1024 * 1024)}MB 导入上限"
                    )
                )
                errors.add("有文件超过 ${Storage.MAX_IMPORT_BYTES / (1024 * 1024)}MB 导入上限，未导入")
                return@forEach
            }
            if (copied == null) {
                failed++
                db.importQueueDao().insert(
                    ImportQueueEntity(
                        uri = uri.toString(),
                        state = "FAILED",
                        error = "无法读取文件流"
                    )
                )
                errors.add("有一个文件读取失败，可改用相册或文件管理器再试")
                return@forEach
            }

            val exist = sourceRepo.findByHash(projectId, copied.sha256)
            if (exist != null) {
                // 同内容已存在：删掉刚拷的副本，复用旧记录
                storage.deleteMaterial(id)
                duplicated++
                ids.add(exist.id)
                if (projectId != null && exist.projectId == null) {
                    sourceRepo.setProject(exist.id, projectId)
                }
                return@forEach
            }

            val kind = FileKinds.detect(copied.displayName, copied.mime)
            val entity = SourceEntity(
                id = id,
                projectId = projectId,
                displayName = copied.displayName,
                mime = copied.mime,
                kind = kind,
                localPath = "materials/$id.bin",
                sizeBytes = copied.size,
                sha256 = copied.sha256,
                origUri = uri.toString(),
                origin = origin,
                status = SourceStatus.NEW
            )
            sourceRepo.insert(entity)
            imported++
            ids.add(id)
        }

        if (autoParse && ids.isNotEmpty()) {
            parseOne(ids, onProgress = {})
        }
        return Outcome(imported, duplicated, failed, ids, errors)
    }

    suspend fun importText(text: String, name: String, projectId: String?): String {
        val id = storage.newId()
        val written = storage.writeText(id, text, name)
        val entity = SourceEntity(
            id = id,
            projectId = projectId,
            displayName = name.ifBlank { "粘贴的文字" },
            mime = "text/plain",
            kind = SourceKind.TXT,
            localPath = "materials/$id.bin",
            sizeBytes = written.size,
            sha256 = written.sha256,
            origin = SourceOrigin.TEXT,
            status = SourceStatus.NEW
        )
        sourceRepo.insert(entity)
        return id
    }

    /** 串行解析，避免同时发起多路视觉模型请求把内存/额度打爆 */
    suspend fun parseOne(sourceIds: List<String>, onProgress: (String) -> Unit) {
        parseMutex.withLock {
            sourceIds.forEach { id ->
                val src = db.sourceDao().byId(id) ?: return@forEach
                if (src.status == SourceStatus.PARSED) return@forEach
                ingest.ingest(id, onProgress = onProgress)
            }
        }
    }

    /**
     * 重新解析。与 [parseOne] 的区别是**不跳过已解析材料**：先把状态打回 NEW、
     * 清掉上次的失败原因，再走一遍 [IngestPipeline.ingest]。
     * 旧 chunk 与倒排索引由 ingest 内部先删后建，不会留下重复片段。
     */
    suspend fun reparse(sourceId: String, onProgress: (String) -> Unit = {}) {
        parseMutex.withLock {
            val src = db.sourceDao().byId(sourceId) ?: return
            if (src.status == SourceStatus.PARSING) return
            db.sourceDao().setStatus(sourceId, SourceStatus.NEW, null)
            ingest.ingest(sourceId, onProgress = onProgress)
        }
    }

    suspend fun parsePending(onProgress: (String) -> Unit = {}) {
        val pending = db.sourceDao().pending().filter { it.status == SourceStatus.NEW }
        if (pending.isEmpty()) return
        parseMutex.withLock {
            pending.forEach { src ->
                ingest.ingest(src.id, onProgress = onProgress)
            }
        }
    }

    fun newSourceId(): String = UUID.randomUUID().toString().replace("-", "")
}
