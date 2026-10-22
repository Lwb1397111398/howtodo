package com.howtodo.ingest

import java.io.File
import java.util.zip.ZipFile

/**
 * Office Open XML 解析。
 *
 * 不用 Apache POI（体积 10MB+ 且会拖慢构建），而是直接用 ZipFile 读 XML。
 * docx/xlsx/pptx 本质就是 zip + xml，机器生成的 XML 结构规整，正则提取文本足够可靠。
 */
object OoxmlReader {

    fun entries(file: File): List<String> = try {
        ZipFile(file).use { z -> z.entries().toList().map { it.name } }
    } catch (_: Throwable) {
        emptyList()
    }

    fun read(file: File, entry: String): String? = try {
        ZipFile(file).use { z ->
            val e = z.getEntry(entry) ?: return null
            z.getInputStream(e).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    } catch (_: Throwable) {
        null
    }

    fun unescape(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    fun stripTags(xml: String): String = unescape(xml.replace(Regex("<[^>]+>"), ""))

    /** 提取所有 <tag ...>text</tag> 的文本 */
    fun texts(xml: String, tag: String): List<String> {
        val re = Regex("<$tag(?:\\s[^>]*)?>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
        return re.findAll(xml).map { unescape(it.groupValues[1]) }.toList()
    }

    /** 自然排序：sheet2 在 sheet10 之前 */
    fun naturalSort(names: List<String>): List<String> = names.sortedWith { a, b ->
        val ra = Regex("(\\d+)").findAll(a).map { it.value.toIntOrNull() ?: 0 }.toList()
        val rb = Regex("(\\d+)").findAll(b).map { it.value.toIntOrNull() ?: 0 }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            if (ra[i] != rb[i]) return@sortedWith ra[i].compareTo(rb[i])
        }
        if (ra.size != rb.size) ra.size.compareTo(rb.size) else a.compareTo(b)
    }
}

object OfficeExtractors {

    /** docx：段落文本，保留段落换行；表格单元格文本也会被 <w:t> 覆盖 */
    fun docx(file: File): String {
        val xml = OoxmlReader.read(file, "word/document.xml") ?: return ""
        // 段落结束标记当换行，制表符当空格
        val withBreaks = xml
            .replace("</w:p>", "\n")
            .replace("<w:br/>", "\n")
            .replace("<w:tab/>", "\t")
        val sb = StringBuilder()
        val re = Regex("<w:t(?:\\s[^>]*)?>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
        // 按段落切分后再抽文本，避免把整段拼成一行
        withBreaks.split('\n').forEach { para ->
            val line = re.findAll(para).joinToString("") { OoxmlReader.unescape(it.groupValues[1]) }
            if (line.isNotBlank()) sb.append(line.trim()).append('\n')
        }
        return sb.toString()
    }

    /** xlsx：按工作表输出「工作表名 + 行文本」，共享字符串会正确还原 */
    fun xlsx(file: File): String {
        val shared = ArrayList<String>()
        OoxmlReader.read(file, "xl/sharedStrings.xml")?.let { xml ->
            Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { m ->
                val texts = Regex("<t(?:\\s[^>]*)?>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(m.groupValues[1])
                    .joinToString("") { OoxmlReader.unescape(it.groupValues[1]) }
                shared.add(texts)
            }
        }

        val sheets = OoxmlReader.entries(file)
            .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
        val sb = StringBuilder()
        OoxmlReader.naturalSort(sheets).forEachIndexed { sheetIdx, entry ->
            val xml = OoxmlReader.read(file, entry) ?: return@forEachIndexed
            sb.append("【工作表").append(sheetIdx + 1).append("】\n")
            Regex("<row(?:\\s[^>]*)?>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { row ->
                val cells = ArrayList<String>()
                Regex("<c(\\s[^>]*)?>(.*?)</c>", RegexOption.DOT_MATCHES_ALL).findAll(row.groupValues[2]).forEach { cm ->
                    val attrs = cm.groupValues[1]
                    val inner = cm.groupValues[2]
                    val isShared = attrs.contains("t=\"s\"")
                    val v = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(inner)?.groupValues?.get(1)
                    val inline = Regex("<is>.*?<t(?:\\s[^>]*)?>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                        .find(inner)?.groupValues?.get(1)
                    val value = when {
                        inline != null -> OoxmlReader.unescape(inline)
                        v == null -> ""
                        isShared -> shared.getOrNull(v.toIntOrNull() ?: -1).orEmpty()
                        else -> OoxmlReader.unescape(v)
                    }
                    if (value.isNotBlank()) cells.add(value.trim())
                }
                if (cells.isNotEmpty()) sb.append(cells.joinToString(" | ")).append('\n')
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /** pptx：每页幻灯片文本，带页码标记 */
    fun pptx(file: File): String {
        val slides = OoxmlReader.entries(file)
            .filter { it.startsWith("ppt/slides/slide") && it.endsWith(".xml") }
        val sb = StringBuilder()
        OoxmlReader.naturalSort(slides).forEachIndexed { i, entry ->
            val xml = OoxmlReader.read(file, entry) ?: return@forEachIndexed
            val lines = Regex("<a:t(?:\\s[^>]*)?>(.*?)</a:t>", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml)
                .map { OoxmlReader.unescape(it.groupValues[1]).trim() }
                .filter { it.isNotEmpty() }
                .toList()
            sb.append("【第").append(i + 1).append("页】\n")
            lines.forEach { sb.append(it).append('\n') }
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * ODF（.odt/.ods/.odp）：zip + content.xml，和 OOXML 同源思路。
     * 段落/标题当换行，表格单元格当制表符，其他标签直接丢掉。
     */
    fun odf(file: File): String {
        val xml = try {
            java.util.zip.ZipFile(file).use { z ->
                val e = z.getEntry("content.xml") ?: return ""
                z.getInputStream(e).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        } catch (_: Throwable) {
            return ""
        }
        var s = xml
            .replace(Regex("(?s)<text:tracked-changes.*?</text:tracked-changes>"), " ")
            .replace(Regex("(?s)<text:note\\b.*?</text:note>"), " ")
            .replace("<text:line-break/>", "\n")
            .replace("<text:tab/>", "\t")
            .replace("<text:s/>", " ")
            .replace("</text:p>", "\n")
            .replace("</text:h>", "\n")
            .replace("</table:table-row>", "\n")
            .replace("</table:table-cell>", "\t")
        s = OoxmlReader.stripTags(s)
        s = Regex("&#(\\d{1,7});").replace(s) { m ->
            val code = m.groupValues[1].toIntOrNull() ?: 0
            if (code in 1..0x10FFFF) String(Character.toChars(code)) else ""
        }
        return s
    }
}
