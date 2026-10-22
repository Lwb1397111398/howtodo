package com.howtodo.update

import com.google.gson.JsonParser

/**
 * 纯 JVM 的 Release 解析逻辑（不依赖 Android SDK，可单测）。
 *
 * 版本元数据放在 Release 说明第一行的 HTML 注释里（GitHub 网页渲染不可见）：
 * <!-- appupdate versionCode=21 versionName=0.1.21 -->
 */
object UpdateParser {

    private val META = Regex(
        "<!--\\s*appupdate\\s+versionCode=(\\d+)\\s+versionName=([A-Za-z0-9._\\-]+)\\s*-->"
    )

    data class Meta(val versionCode: Int, val versionName: String)

    fun parseMeta(body: String?): Meta? {
        if (body == null) return null
        val m = META.find(body) ?: return null
        val code = m.groupValues[1].toIntOrNull() ?: return null
        if (code <= 0) return null
        return Meta(code, m.groupValues[2])
    }

    /** 去掉元数据注释后的正文，用于展示更新内容 */
    fun notesOf(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return body.replace(META, "").trim().ifEmpty { null }
    }

    /**
     * 解析 GitHub releases/latest API 返回的 JSON。
     * 返回 null 表示结构不符合（缺元数据或没有 .apk 资产）。
     */
    fun parseRelease(json: String?): UpdateInfo? {
        if (json.isNullOrBlank()) return null
        return try {
            val obj = JsonParser.parseString(json).asJsonObject
            val body = obj.get("body")?.takeIf { !it.isJsonNull }?.asString
            val meta = parseMeta(body) ?: return null
            val assets = obj.getAsJsonArray("assets") ?: return null
            var url: String? = null
            var size = 0L
            for (a in assets) {
                val o = a.asJsonObject
                val name = o.get("name")?.takeIf { !it.isJsonNull }?.asString ?: continue
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                url = o.get("browser_download_url")?.takeIf { !it.isJsonNull }?.asString
                size = o.get("size")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                break
            }
            if (url.isNullOrBlank()) return null
            UpdateInfo(meta.versionCode, meta.versionName, url!!, size, notesOf(body), "")
        } catch (_: Throwable) {
            null
        }
    }
}
