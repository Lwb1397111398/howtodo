package com.howtodo.ingest

import com.howtodo.data.db.SourceKind

/**
 * 扩展名 / MIME 到「文档族」的映射。
 *
 * 注意：这里的 DOCX/XLSX/PPTX 指的是**族**（Word / Excel / PPT），不限于 OOXML。
 * 老版 .doc/.xls/.ppt、RTF、ODF、WPS 都归到对应的族，真正的解析方式由
 * [DocumentExtract] 按文件内容（魔数）决定——因为很多「.doc」其实是 RTF 或网页。
 */
object FileKinds {

    private val extMap = mapOf(
        "jpg" to SourceKind.IMAGE, "jpeg" to SourceKind.IMAGE, "png" to SourceKind.IMAGE,
        "webp" to SourceKind.IMAGE, "bmp" to SourceKind.IMAGE, "heic" to SourceKind.IMAGE,
        "gif" to SourceKind.IMAGE,
        "pdf" to SourceKind.PDF,

        // Word 族
        "doc" to SourceKind.DOCX, "docx" to SourceKind.DOCX, "docm" to SourceKind.DOCX,
        "dot" to SourceKind.DOCX, "dotx" to SourceKind.DOCX, "rtf" to SourceKind.DOCX,
        "odt" to SourceKind.DOCX, "wps" to SourceKind.DOCX,

        // Excel 族（csv 本身是纯文本，直接按文本读）
        "xls" to SourceKind.XLSX, "xlsx" to SourceKind.XLSX, "xlsm" to SourceKind.XLSX,
        "xlt" to SourceKind.XLSX, "xltx" to SourceKind.XLSX, "ods" to SourceKind.XLSX,
        "et" to SourceKind.XLSX, "csv" to SourceKind.TXT,

        // PPT 族
        "ppt" to SourceKind.PPTX, "pptx" to SourceKind.PPTX, "pptm" to SourceKind.PPTX,
        "pot" to SourceKind.PPTX, "potx" to SourceKind.PPTX, "odp" to SourceKind.PPTX,
        "dps" to SourceKind.PPTX,

        "txt" to SourceKind.TXT, "md" to SourceKind.TXT, "json" to SourceKind.TXT,
        "xml" to SourceKind.TXT, "log" to SourceKind.TXT, "htm" to SourceKind.TXT,
        "html" to SourceKind.TXT
    )

    val imageMimes = setOf(
        "image/jpeg", "image/jpg", "image/png", "image/webp", "image/bmp", "image/heic", "image/heif"
    )

    fun detect(name: String?, mime: String?): String {
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        extMap[ext]?.let { return it }

        val m = mime?.lowercase().orEmpty()
        return when {
            m.startsWith("image/") -> SourceKind.IMAGE
            m == "application/pdf" -> SourceKind.PDF
            m.contains("wordprocessingml") || m == "application/msword" ||
                m.contains("rtf") || m.contains("opendocument.text") -> SourceKind.DOCX
            m.contains("spreadsheetml") || m == "application/vnd.ms-excel" ||
                m.contains("opendocument.spreadsheet") -> SourceKind.XLSX
            m.contains("presentationml") || m == "application/vnd.ms-powerpoint" ||
                m.contains("opendocument.presentation") -> SourceKind.PPTX
            m.startsWith("text/") -> SourceKind.TXT
            else -> SourceKind.OTHER
        }
    }

    fun imageMimeOf(name: String?, mime: String?): String {
        val m = mime?.lowercase().orEmpty()
        if (m in imageMimes) return if (m == "image/jpg") "image/jpeg" else m
        return when (name?.substringAfterLast('.', "")?.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heic"
            else -> "image/jpeg"
        }
    }

    /** 是否必须走视觉模型（图片、PDF 渲染）。文档、表格、纯文本都能直接读文字 */
    fun needsVision(kind: String): Boolean =
        kind == SourceKind.IMAGE || kind == SourceKind.PDF
}
