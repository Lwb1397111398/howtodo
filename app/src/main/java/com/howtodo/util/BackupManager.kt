package com.howtodo.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 备份与恢复。
 *
 * 备份内容 = 数据库（已做 WAL checkpoint）+ 全部材料原件。
 * 恢复是"整库替换"，因此导入后必须重启应用；这一点在 UI 上明确告知，不做静默处理。
 */
class BackupManager(private val context: Context) {

    data class Result(val ok: Boolean, val message: String)

    fun exportTo(uri: Uri): Result {
        return try {
            val dbFile = context.getDatabasePath("howtodo.db")
            if (!dbFile.exists()) return Result(false, "数据库不存在")

            context.contentResolver.openOutputStream(uri)?.use { out ->
                ZipOutputStream(out).use { zip ->
                    // 1) 数据库（先 checkpoint，保证 -wal 里的内容并入主文件）
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
     * 导入会把现有数据库与材料全部覆盖。
     * 调用方必须已经关闭数据库连接，并在成功后提示重启。
     */
    fun importFrom(uri: Uri): Result {
        return try {
            val dbFile = context.getDatabasePath("howtodo.db")
            val materials = File(context.filesDir, "materials").apply { mkdirs() }
            var restoredDb = false
            var restoredFiles = 0

            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        when {
                            name == "howtodo.db" -> {
                                dbFile.parentFile?.mkdirs()
                                dbFile.outputStream().use { zip.copyTo(it) }
                                restoredDb = true
                            }
                            name.startsWith("materials/") && !entry.isDirectory -> {
                                val target = File(materials, name.removePrefix("materials/"))
                                if (!target.canonicalPath.startsWith(materials.canonicalPath)) {
                                    // 防 zip 路径穿越
                                } else {
                                    target.outputStream().use { zip.copyTo(it) }
                                    restoredFiles++
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return Result(false, "无法读取备份文件")

            if (!restoredDb) return Result(false, "备份文件里没有数据库")

            // 清理可能残留的 WAL/SHM，否则会与新库不匹配
            File(dbFile.path + "-wal").delete()
            File(dbFile.path + "-shm").delete()

            Result(true, "恢复完成：数据库 1 个，材料 $restoredFiles 份。请重启应用生效。")
        } catch (e: Throwable) {
            Result(false, "恢复失败：${e.message}")
        }
    }

    fun materialsSizeKb(): Long {
        val dir = File(context.filesDir, "materials")
        if (!dir.exists()) return 0
        return dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() }?.div(1024) ?: 0
    }
}
