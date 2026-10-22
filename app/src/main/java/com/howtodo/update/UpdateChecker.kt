package com.howtodo.update

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 检查更新：直连 → 透传镜像，逐条尝试。
 *
 * 国内运营商对 api.github.com 的 DNS 污染是域名维度的：查询走通的通道，
 * 下载大概率也走得通，所以查询成功时把通道前缀记进 UpdateInfo.sourcePrefix，
 * 下载候选把该前缀排在第一位复用。
 */
class UpdateChecker(private val repoOwner: String, private val repoName: String) {

    companion object {
        /** 能透传 GitHub API 的镜像（2026-10-22 实测：gh-proxy.org 已不再透传 API，降级为下载候选；ghfast.top / ghproxy.net 本就只透传下载） */
        val QUERY_PREFIXES = listOf("", "https://gh-proxy.com/")
        /** 仅透传下载的镜像，只进下载候选，不用于查询 */
        val DOWNLOAD_ONLY_PREFIXES = listOf("https://gh-proxy.org/", "https://ghfast.top/", "https://ghproxy.net/")
        private const val UA = "HowTodo-Updater"
    }

    sealed class Result {
        data class Available(val info: UpdateInfo) : Result()
        /** 云端存在发布但版本不比本机新 */
        data object NoUpdate : Result()
        /** 所有通道都查不到（404）：私有仓库需配只读令牌，或云端尚无发布 */
        data object Unpublished : Result()
        /** 有源连不上/被拒（非 404）：网络问题，别误导用户等 CI */
        data class NetworkError(val message: String) : Result()
    }

    fun check(localVersionCode: Int, token: String?): Result {
        var saw404 = false
        var lastError = ""
        for (prefix in QUERY_PREFIXES) {
            val url = prefix + "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = true
                    // GitHub API 强制要求 User-Agent，缺了直接 403
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept", "application/vnd.github+json")
                    if (!token.isNullOrBlank()) {
                        // 私有仓库：匿名请求一律 404，需带 Contents:Read 的 Fine-grained 令牌
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                }
                when (conn.responseCode) {
                    200 -> {
                        val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                        val info = UpdateParser.parseRelease(body)
                            ?: return Result.NetworkError("云端发布的内容不完整，稍后再试")
                        return if (info.versionCode > localVersionCode) {
                            // 记住查询成功的通道，下载优先复用它
                            Result.Available(info.copy(sourcePrefix = prefix))
                        } else {
                            Result.NoUpdate
                        }
                    }
                    401 -> return Result.NetworkError("更新令牌无效，请在设置里重新填写")
                    403 -> { lastError = "访问被限流（HTTP 403）"; continue }
                    404 -> { saw404 = true; continue }
                    else -> { lastError = "HTTP " + conn.responseCode; continue }
                }
            } catch (e: IOException) {
                lastError = "直连与镜像都失败（" + (e.message ?: "网络异常") + "）"
            } catch (e: Throwable) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        return if (saw404 && lastError.isBlank()) Result.Unpublished
        else if (saw404 && lastError.contains("HTTP")) Result.Unpublished
        else Result.NetworkError(lastError)
    }
}

/** 下载器：多候选通道轮换、流式写 .part、长度校验后重命名、同版本完整包复用 */
object UpdateDownloader {

    sealed class Result {
        data class Ok(val file: File) : Result()
        data class Error(val message: String) : Result()
    }

    fun candidates(primary: String): List<String> =
        (listOf(primary) + UpdateChecker.QUERY_PREFIXES + UpdateChecker.DOWNLOAD_ONLY_PREFIXES)
            .distinct()

    fun download(
        info: UpdateInfo,
        destDir: File,
        onProgress: (Int) -> Unit
    ): Result {
        destDir.mkdirs()
        val target = File(destDir, "update-${info.versionCode}.apk")
        if (target.exists() && info.apkSize > 0 && target.length() == info.apkSize) {
            // 同版本完整包已存在：直接复用，不联网重下
            onProgress(100)
            return Result.Ok(target)
        }
        val part = File(destDir, target.name + ".part")
        var lastError = ""
        for (prefix in candidates(info.sourcePrefix)) {
            val url = prefix + info.apkUrl
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "HowTodo-Updater")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    lastError = "HTTP " + code
                    continue
                }
                val expected = conn.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
                conn.inputStream.use { ins ->
                    java.io.FileOutputStream(part).use { outs ->
                        val buf = ByteArray(64 * 1024)
                        var received = 0L
                        while (true) {
                            val n = ins.read(buf)
                            if (n <= 0) break
                            outs.write(buf, 0, n)
                            received += n
                            if (expected > 0) {
                                onProgress(((received * 100) / expected).toInt().coerceIn(0, 100))
                            }
                        }
                    }
                }
                if (expected > 0 && part.length() != expected) {
                    // 校验字节数与 Content-Length 一致，不留半截包
                    lastError = "下载不完整（" + part.length() + "/" + expected + " 字节）"
                    continue
                }
                if (!part.renameTo(target)) {
                    lastError = "写入缓存失败"
                    continue
                }
                onProgress(100)
                return Result.Ok(target)
            } catch (e: Throwable) {
                lastError = e.message ?: e.javaClass.simpleName
                continue
            }
        }
        part.delete()
        return Result.Error(lastError.ifBlank { "直连与镜像都下载失败" })
    }
}
