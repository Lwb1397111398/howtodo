package com.howtodo.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * 私有存储布局。
 *
 * 所有材料原件一律以 id 命名（materials/<id>.bin），中文原名只进数据库。
 * 原因：Windows/Android 上带中文与空格的文件名容易在各种工具链里出问题，
 * 而且用户可能传入同名文件；用 id 命名天然去重且不会冲突。
 */
class Storage(private val context: Context) {

    companion object {
        /** 单文件导入上限：超过会拖垮视觉解析与内存 */
        const val MAX_IMPORT_BYTES = 80L * 1024 * 1024
    }

    /** 文件超过导入上限。调用方应向用户明示原因，不要当作普通读取失败静默吞掉 */
    class FileTooLargeException : Exception("文件超过 ${MAX_IMPORT_BYTES / (1024 * 1024)}MB 导入上限")

    private val root: File get() = context.filesDir

    val materialsDir: File
        get() = File(root, "materials").apply { if (!exists()) mkdirs() }

    val rawDir: File
        get() = File(root, "raw_llm").apply { if (!exists()) mkdirs() }

    val thumbsDir: File
        get() = File(root, "thumbs").apply { if (!exists()) mkdirs() }

    fun materialFile(id: String): File = File(materialsDir, "$id.bin")

    fun newId(): String = UUID.randomUUID().toString().replace("-", "")

    data class Imported(
        val file: File,
        val sha256: String,
        val size: Long,
        val displayName: String,
        val mime: String?
    )

    /**
     * 把外部 Uri 的内容复制到私有目录。
     *
     * 必须在拿到 Uri 后**立即**执行（分享入口尤其如此）：微信给出的临时读权限
     * 在 Activity 结束后可能失效，任何延迟到后台的处理都会失败。
     */
    fun copyFromUri(uri: Uri, id: String): Imported? {
        val resolver = context.contentResolver
        val name = queryDisplayName(uri) ?: "未命名_${id.take(6)}"
        val mime = try {
            resolver.getType(uri)
        } catch (_: Throwable) {
            null
        }

        val tmp = File(materialsDir, "$id.tmp")
        val target = materialFile(id)
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        var tooLarge = false

        try {
            val input: InputStream = resolver.openInputStream(uri) ?: return null
            input.use { ins ->
                tmp.outputStream().use { outs ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        digest.update(buf, 0, n)
                        outs.write(buf, 0, n)
                        size += n
                        // 单文件上限，防止异常大文件拖垮解析
                        if (size > MAX_IMPORT_BYTES) {
                            tooLarge = true
                            break
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            tmp.delete()
            return null
        }
        if (tooLarge) {
            // 拒绝导入残缺文件：明确抛错让调用方提示，而不是把半截文件当成功
            tmp.delete()
            throw FileTooLargeException()
        }

        if (!tmp.renameTo(target)) {
            tmp.delete()
            return null
        }
        return Imported(
            file = target,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            size = size,
            displayName = name,
            mime = mime
        )
    }

    /** 纯文本类材料（粘贴的文本）直接落盘 */
    fun writeText(id: String, text: String, name: String): Imported {
        val target = materialFile(id)
        target.writeText(text, Charsets.UTF_8)
        val sha = sha256Of(target)
        return Imported(target, sha, target.length(), name, "text/plain")
    }

    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun readBytes(file: File): ByteArray? = try {
        if (file.exists()) file.readBytes() else null
    } catch (_: Throwable) {
        null
    }

    fun readText(file: File): String? = try {
        if (file.exists()) file.readText(Charsets.UTF_8) else null
    } catch (_: Throwable) {
        null
    }

    fun deleteMaterial(id: String) {
        materialFile(id).delete()
    }

    fun writeRaw(name: String, content: String): String {
        val f = File(rawDir, name)
        try {
            f.writeText(content, Charsets.UTF_8)
        } catch (_: Throwable) {
        }
        return f.absolutePath
    }

    /** 供 FileProvider 分享/查看原件使用 */
    fun fileForUri(id: String): File = materialFile(id)

    private fun queryDisplayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (_: Throwable) {
        null
    }
}
