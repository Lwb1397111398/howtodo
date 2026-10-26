package com.howtodo.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.howtodo.data.db.AppDatabase
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份与恢复。
 *
 * 备份内容 = 数据库（导出前先做 WAL checkpoint，把 -wal 里的最新写入并入主文件）+ 全部材料原件。
 * 恢复分两段：[stageImport] 先把备份解到暂存目录（此时数据库照常运行，零风险），
 * 校验通过后调用方关掉数据库再调 [commitImport] 换库，最后立即重启进程——
 * 换库与重启之间不留任何可以让 Room 重新打开连接的窗口。
 */
class BackupManager(private val context: Context, private val db: AppDatabase) {

    data class Result(val ok: Boolean, val message: String)

    /** 已解包等待提交的备份 */
    class Staged internal constructor(val dir: File, val materialCount: Int)

    /**
     * 把 WAL 日志落进主库文件。不做这一步，最近未 checkpoint 的写入（可能是当天的
     * 任务/提醒）不会出现在备份里——用户以为备份了，恢复后却丢数据。
     */
    fun checkpoint() {
        runCatching {
            db.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(FULL)")
                .use { it.moveToFirst() }
        }
    }

    fun exportTo(uri: Uri): Result {
        return try {
            val dbFile = context.getDatabasePath("howtodo.db")
            if (!dbFile.exists()) return Result(false, "数据库不存在")

            checkpoint()
            context.contentResolver.openOutputStream(uri)?.use { out ->
                ZipOutputStream(out).use { zip ->
                    // 1) 数据库（已 checkpoint）
                    zip.putNextEntry(ZipEntry("howtodo.db"))
                    dbFile.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()

                    // 2) 材料原件
                    val materials = File(context.filesDir, "materials")
                    if (materials.exists()) {
                        materials.listFiles()?.forEach { f ->
                            if (f.isFile) {
                                zip.putNextEntry(ZipEntry("materials/${f.name}"))
                                f.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    }

                    // 3) 版本标记
                    zip.putNextEntry(ZipEntry("backup.info"))
                    zip.write("howtodo-backup-v1".toByteArray())
                    zip.closeEntry()
                }
            } ?: return Result(false, "无法写入目标文件")

            val size = runCatching { dbFile.length() }.getOrDefault(0L)
            Result(true, "备份完成（数据库 ${size / 1024} KB + 全部材料）")
        } catch (e: Throwable) {
            Result(false, "备份失败：${e.message}")
        }
    }

    /**
     * 第一段：把备份 zip 解到暂存目录，不动现有任何数据。
     * 解包失败或缺数据库时抛错并清理暂存，调用方可以放心把异常展示给用户。
     */
    fun stageImport(uri: Uri): Staged {
        val staging = File(context.filesDir, "restore_staging")
        staging.deleteRecursively()
        staging.mkdirs()
        var restoredDb = false
        var restoredFiles = 0

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        when {
                            name == "howtodo.db" -> {
                                File(staging, "howtodo.db").outputStream().use { zip.copyTo(it) }
                                restoredDb = true
                            }
                            name.startsWith("materials/") && !entry.isDirectory -> {
                                val target = File(staging, name)
                                // 防 zip 路径穿越：解出来的文件必须留在暂存目录内
                                if (target.canonicalPath.startsWith(staging.canonicalPath)) {
                                    target.parentFile?.mkdirs()
                                    target.outputStream().use { zip.copyTo(it) }
                                    restoredFiles++
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: throw IllegalStateException("无法读取备份文件")

            if (!restoredDb) throw IllegalStateException("备份文件里没有数据库")
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }
        return Staged(staging, restoredFiles)
    }

    /**
     * 第二段：真正换库。调用方必须已经 close 数据库，并在成功后立即重启进程。
     * 材料目录整目录替换（备份里没有的旧文件全部清掉，不留孤儿），
     * 缩略图与 LLM 原始报文属于派生缓存，随旧库一起作废。
     */
    fun commitImport(staged: Staged): Result {
        return try {
            val dbFile = context.getDatabasePath("howtodo.db")
            dbFile.parentFile?.mkdirs()
            val newDb = File(staged.dir, "howtodo.db")
            if (!newDb.renameTo(dbFile)) {
                newDb.copyTo(dbFile, overwrite = true)
                newDb.delete()
            }
            // 清理可能残留的 WAL/SHM，否则会与新库不匹配
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()

            val materials = File(context.filesDir, "materials")
            materials.deleteRecursively()
            materials.mkdirs()
            File(staged.dir, "materials").takeIf { it.exists() }?.let { src ->
                src.listFiles()?.forEach { f ->
                    if (f.isFile) f.copyTo(File(materials, f.name), overwrite = true)
                }
            }

            File(context.filesDir, "thumbs").deleteRecursively()
            File(context.filesDir, "raw_llm").deleteRecursively()

            staged.dir.deleteRecursively()
            Result(true, "恢复完成：数据库已替换，材料 ${staged.materialCount} 份，正在重启…")
        } catch (e: Throwable) {
            Result(false, "恢复失败：${e.message}")
        }
    }

    fun materialsSizeKb(): Long {
        val dir = File(context.filesDir, "materials")
        if (!dir.exists()) return 0
        return dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() }?.div(1024) ?: 0
    }

    companion object {
        /**
         * 重启进程：先拉起新任务再杀掉自己。finishAffinity 只关 Activity 不杀进程，
         * 后台协程与 Worker 还可能拿着旧库继续跑；exit 后全新进程重新打开换好的库。
         */
        fun restartApp(context: Context) {
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                context.startActivity(it)
            }
            Runtime.getRuntime().exit(0)
        }
    }
}
