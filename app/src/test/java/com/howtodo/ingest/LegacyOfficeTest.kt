package com.howtodo.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 旧版 Office 解析的回归测试。
 *
 * 前半部分是纯逻辑（不依赖真实文件）；最后一个是「有样本就跑」的冒烟测试：
 * 设置环境变量 HOWTODO_SAMPLE_DIR 指向含 .doc/.xls 的目录即可，
 * 让真机之外也能用真实公文验证一遍，而不是只靠构造出来的字节。
 */
class LegacyOfficeTest {

    @Test
    fun `Word 正文清洗：段落、表格、域代码`() {
        val raw = "标题\r正文\u0007单元格\u0007\r" +
            "开始\u0013 PAGE \\* MERGEFORMAT \u0014第 1 页\u0015结束"
        val text = WordText.clean(raw)
        assertTrue("段落标记应转换行", text.contains("标题\n正文"))
        assertTrue("单元格标记应转制表符", text.contains("单元格\t"))
        assertTrue("域代码指令应丢弃、结果显示", text.contains("开始第 1 页结束"))
        assertTrue("不应残留域分隔符", !text.contains('\u0013') && !text.contains('\u0014'))
    }

    @Test
    fun `ANSI 解码：按代码页与启发式`() {
        val gbk = byteArrayOf(0xD6.toByte(), 0xD0.toByte(), 0xCE.toByte(), 0xC4.toByte())
        assertEquals("中文", AnsiText.decode(gbk, 936))
        // 代码页缺失（1200）时应能从字节猜出 GBK
        assertEquals("中文", AnsiText.decode(gbk, 1200))
        // UTF-8 字节也应正确识别
        assertEquals("中文", AnsiText.decode("中文".toByteArray(), 1200))
        assertEquals("abc", AnsiText.decode("abc".toByteArray(), 1252))
    }

    @Test
    fun `RTF 解析：字库跳过、十六进制中文、Unicode 转义`() {
        val rtf = "{\\rtf1\\ansi\\ansicpg936{\\fonttbl{\\f0\\fnil\\fcharset134 宋体;}}" +
            "\\f0 \\'d6\\'d0\\'ce\\'c4\\par 第二段\\par}"
        val text = RtfReader.parse(rtf)
        assertTrue("字体表不应进入正文", !text.contains("宋体"))
        assertTrue("GBK 双字节应还原成汉字", text.contains("中文"))
        assertTrue("段落应换行", text.contains("中文\n第二段"))

        val uni = RtfReader.parse("{\\rtf1\\ansi \\u20013?\\u25991?ok}")
        assertEquals("中文ok", uni.trim())
    }

    @Test
    fun `内容嗅探：纯文本与 RTF`() {
        val txt = File.createTempFile("howtodo", ".bin")
        val rtf = File.createTempFile("howtodo", ".bin")
        try {
            txt.writeText("这是一段纯文本材料\n第二行", Charsets.UTF_8)
            assertEquals("纯文本", DocumentExtract.extract(txt).format)

            rtf.writeText(
                "{\\rtf1\\ansi\\ansicpg936 \\'d6\\'d0\\'ce\\'c4\\par}",
                Charsets.ISO_8859_1
            )
            val r = DocumentExtract.extract(rtf)
            assertEquals("RTF 文档", r.format)
            assertTrue(r.text.contains("中文"))
        } finally {
            txt.delete()
            rtf.delete()
        }
    }

    @Test
    fun `PPT 记录树按页提取文本`() {
        fun rec(ver: Int, type: Int, body: ByteArray): ByteArray {
            val out = ByteArray(8 + body.size)
            out[0] = (ver and 0xFF).toByte()
            out[1] = ((ver shr 8) and 0xFF).toByte()
            out[2] = (type and 0xFF).toByte()
            out[3] = ((type shr 8) and 0xFF).toByte()
            var l = body.size
            for (k in 0 until 4) {
                out[4 + k] = (l and 0xFF).toByte()
                l = l shr 8
            }
            body.copyInto(out, 8)
            return out
        }

        val slide1 = rec(
            0x0F, 0x03EE,
            rec(0, 0x0FA0, "第一页标题".toByteArray(Charsets.UTF_16LE)) +
                rec(0, 0x0FA8, "abc".toByteArray(Charsets.ISO_8859_1))
        )
        val slide2 = rec(0x0F, 0x03EE, rec(0, 0x0FA0, "第二页".toByteArray(Charsets.UTF_16LE)))
        val slides = LegacyOffice.pptSlides(slide1 + slide2, 936)
        assertEquals(2, slides.size)
        assertTrue(slides[0].contains("第一页标题") && slides[0].contains("abc"))
        assertTrue(slides[1].contains("第二页"))
    }

    @Test
    fun `内容嗅探：HTML 与 UTF-16 文本`() {
        val html = File.createTempFile("howtodo", ".doc")
        val u16 = File.createTempFile("howtodo", ".txt")
        try {
            html.writeText(
                "<!DOCTYPE html><html><body><p>第一段</p><p>第二段</p></body></html>",
                Charsets.UTF_8
            )
            val h = DocumentExtract.extract(html)
            assertEquals("网页文档", h.format)
            assertTrue("标签应被去掉", h.text.contains("第一段") && !h.text.contains("<p>"))

            u16.writeBytes(
                byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "中文文本".toByteArray(Charsets.UTF_16LE)
            )
            val t = DocumentExtract.extract(u16)
            assertEquals("纯文本", t.format)
            assertTrue(t.text.contains("中文文本"))
        } finally {
            html.delete()
            u16.delete()
        }
    }

    @Test
    fun `真实旧版 Office 文件冒烟`() {
        val dir = System.getenv("HOWTODO_SAMPLE_DIR")
        assumeTrue("未设置 HOWTODO_SAMPLE_DIR，跳过真实样本测试", !dir.isNullOrBlank())
        val files = File(dir!!).walkTopDown()
            .filter { it.isFile && it.length() > 2048 }
            .filter { it.extension.lowercase() in setOf("doc", "xls") }
            .filter { !it.name.startsWith("~$") }
            .take(8)
            .toList()
        assumeTrue("样本目录里没有 .doc/.xls", files.isNotEmpty())

        files.forEach { f ->
            val r = DocumentExtract.extract(f)
            println("${f.name}: ${r.format}, ${r.text.length} 字")
            assertTrue("${f.name} 提取内容过短", r.text.length > 200)
            assertTrue("${f.name} 疑似乱码", r.text.count { it.code in 0x4E00..0x9FFF } > 50)
        }
    }
}
