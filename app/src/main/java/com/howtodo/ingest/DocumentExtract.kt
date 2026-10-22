package com.howtodo.ingest

import java.io.File
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/**
 * 文档正文提取的统一入口：**先看文件内容，再看扩展名**。
 *
 * 材料在私有目录里一律存成 `materials/<id>.bin`，扩展名只留在数据库里，
 * 所以判断格式只能靠魔数（magic）。这也顺带解决了几个现实问题：
 *  - 法院/政府网站下载的「.doc」常常其实是 HTML 或 RTF
 *  - 微信改过后缀名的 docx、xls 依然能正常识别
 *  - 加密文档（EncryptedPackage 流）能给出明确提示，而不是报「格式不支持」
 */
object DocumentExtract {

    data class Extracted(val format: String, val text: String)

    fun extract(file: File): Extracted {
        val head = headBytes(file, 4096)
        if (head.isEmpty()) throw IllegalStateException("文件为空或无法读取")

        if (isZip(head)) return extractZip(file)
        if (CfbReader.isCfb(head)) return extractOle(file)
        if (isRtf(head)) return Extracted("RTF 文档", RtfReader.read(file))
        if (isPdf(head)) throw IllegalStateException("这个文件其实是 PDF，请用 PDF 方式重新导入")
        if (looksLikeHtml(head)) return Extracted("网页文档", htmlToText(readTextSmart(file)))
        if (hasUtf16Bom(head)) return Extracted("纯文本", readTextSmart(file))
        if (looksTextual(head)) return Extracted("纯文本", readTextSmart(file))
        throw IllegalStateException("暂不支持这个格式，可导入 PDF / Word / Excel / PPT / RTF / ODT / TXT 或直接粘贴文字")
    }

    // ------------------------------------------------------------------ 分流

    private fun extractZip(file: File): Extracted {
        val names = try {
            ZipFile(file).use { z -> z.entries().toList().map { it.name } }
        } catch (_: Throwable) {
            throw IllegalStateException("这个压缩格式文件已损坏，无法打开")
        }
        return when {
            names.any { it == "word/document.xml" } -> Extracted("Word 文档", OfficeExtractors.docx(file))
            names.any { it == "xl/workbook.xml" } -> Extracted("Excel 表格", OfficeExtractors.xlsx(file))
            names.any { it == "ppt/presentation.xml" } -> Extracted("PPT", OfficeExtractors.pptx(file))
            names.any { it == "content.xml" } -> Extracted("OpenDocument 文档", OfficeExtractors.odf(file))
            else -> throw IllegalStateException(
                "这是压缩包，不是文档。请先解压，再导入里面的 Word/Excel/PPT"
            )
        }
    }

    private fun extractOle(file: File): Extracted {
        val cfb = CfbReader.open(file)
            ?: throw IllegalStateException("这个 Office 文件已损坏，无法打开")
        cfb.use { c ->
            val names = c.streamNames()
            if ("EncryptedPackage" in names || "EncryptionInfo" in names) {
                throw IllegalStateException("这个文档设了打开密码，请先在电脑上取消密码再导入")
            }
            return when {
                c.hasStream("WordDocument") -> Extracted("Word 文档", LegacyOffice.doc(c))
                c.hasStream("Workbook") || c.hasStream("Book") ->
                    Extracted("Excel 表格", LegacyOffice.xls(c))

                c.hasStream("PowerPoint Document") -> Extracted("PPT", LegacyOffice.ppt(c))
                else -> throw IllegalStateException(
                    "这个文件是老版 Office 容器，但不是 Word/Excel/PPT 正文（可能是模板或绘图文件）"
                )
            }
        }
    }

    // ------------------------------------------------------------------ 嗅探

    private fun headBytes(file: File, n: Int): ByteArray = try {
        file.inputStream().use { ins ->
            val buf = ByteArray(n)
            var read = 0
            while (read < n) {
                val k = ins.read(buf, read, n - read)
                if (k <= 0) break
                read += k
            }
            if (read == n) buf else buf.copyOf(read)
        }
    } catch (_: Throwable) {
        ByteArray(0)
    }

    private fun isZip(h: ByteArray): Boolean =
        h.size >= 4 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte() &&
            (h[2].toInt() and 0xFF) == 0x03 && (h[3].toInt() and 0xFF) == 0x04

    private fun isPdf(h: ByteArray): Boolean =
        h.size >= 4 && h[0] == '%'.code.toByte() && h[1] == 'P'.code.toByte() &&
            h[2] == 'D'.code.toByte() && h[3] == 'F'.code.toByte()

    private fun isRtf(h: ByteArray): Boolean {
        var i = 0
        while (i < h.size && (h[i].toInt() == 0x20 || h[i].toInt() == 0x0A || h[i].toInt() == 0x0D)) i++
        if (i + 5 > h.size) return false
        return h[i] == '{'.code.toByte() && h[i + 1] == '\\'.code.toByte() &&
            h[i + 2] == 'r'.code.toByte() && h[i + 3] == 't'.code.toByte() &&
            h[i + 4] == 'f'.code.toByte()
    }

    private fun looksLikeHtml(h: ByteArray): Boolean {
        // Word 另存为网页时常出 UTF-16 HTML，先按 BOM 解码再比标签
        val raw = if (hasUtf16Bom(h) && h.size > 2) {
            String(h, 2, h.size - 2, if (h[0] == 0xFF.toByte()) Charsets.UTF_16LE else Charsets.UTF_16BE)
        } else {
            String(h, Charsets.ISO_8859_1)
        }
        val txt = raw.trimStart('\uFEFF', ' ', '\n', '\r', '\t').lowercase()
        return txt.startsWith("<!doctype html") || txt.startsWith("<html") ||
            txt.startsWith("<?xml") && txt.contains("<html") ||
            txt.contains("<html") && txt.contains("<body")
    }

    /** 纯文本判定：可打印字节占绝大多数（UTF-16 文本会因 NUL 被判为否，rar 等压缩包同理） */
    private fun looksTextual(h: ByteArray): Boolean {
        if (h.isEmpty()) return false
        var ok = 0
        for (b in h) {
            val v = b.toInt() and 0xFF
            if (v == 0) return false
            if (v == 9 || v == 10 || v == 13 || v in 0x20..0x7E || v >= 0x80) ok++
        }
        return ok >= h.size * 95 / 100
    }

    /** UTF-16 文本满屏 NUL，不能按「可打印比例」判，先看 BOM */
    private fun hasUtf16Bom(h: ByteArray): Boolean =
        h.size >= 2 && ((h[0] == 0xFF.toByte() && h[1] == 0xFE.toByte()) ||
            (h[0] == 0xFE.toByte() && h[1] == 0xFF.toByte()))

    // ------------------------------------------------------------------ 文本

    fun readTextSmart(file: File): String {
        val bytes = file.readBytes()
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val utf8 = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Throwable) {
            null
        }
        if (utf8 != null) return utf8
        return try {
            String(bytes, charset("GBK"))
        } catch (_: Throwable) {
            String(bytes, Charsets.UTF_8)
        }
    }

    /** 网页另存为 .doc 的情况很常见，按标签切段落 */
    fun htmlToText(html: String): String {
        var s = html.replace(
            Regex("(?is)<(script|style|xml)\\b[^>]*>.*?</\\1>"),
            " "
        )
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)</(p|div|tr|li|h[1-6]|table|section|article)>"), "\n")
        s = s.replace(Regex("(?i)</t[dh]>"), "\t")
        s = s.replace(Regex("(?s)<[^>]+>"), "")
        s = unescapeEntities(s)
        return s.replace("\r\n", "\n")
            .replace(Regex("[ \\t\\u3000]{2,}"), "  ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun unescapeEntities(s: String): String {
        var t = s
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
            .replace("&ldquo;", "“")
            .replace("&rdquo;", "”")
            .replace("&amp;", "&")
        t = Regex("&#(\\d{1,7});").replace(t) { m ->
            val code = m.groupValues[1].toIntOrNull() ?: 0
            if (code in 1..0x10FFFF) String(Character.toChars(code)) else ""
        }
        t = Regex("&#[xX]([0-9a-fA-F]{1,6});").replace(t) { m ->
            val code = m.groupValues[1].toIntOrNull(16) ?: 0
            if (code in 1..0x10FFFF) String(Character.toChars(code)) else ""
        }
        return t
    }
}
