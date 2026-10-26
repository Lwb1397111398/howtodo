package com.howtodo.ingest

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切块决定了两件事：检索能不能命中、引用能不能高亮回原文。
 * 偏移量一旦算错，用户点「查看原文」会跳到风马牛不相及的位置。
 */
class ChunkerTest {

    @Test
    fun `空文本不产生块`() {
        assertTrue(Chunker.split("   \n\n  ").isEmpty())
    }

    @Test
    fun `切块偏移能回指原文`() {
        val src = "第一段：本周工作安排。\n第二段：需要提交的材料。\n第三段：截止时间为周五。"
        val pieces = Chunker.split(src)
        assertTrue(pieces.isNotEmpty())
        pieces.forEach { p ->
            assertTrue("charStart 不能为负", p.charStart >= 0)
            assertTrue("charEnd 不能越界", p.charEnd <= src.length)
            assertTrue("区间必须非空", p.charEnd > p.charStart)
            assertTrue("区间应能取到内容", src.substring(p.charStart, p.charEnd).isNotBlank())
        }
        val joined = pieces.joinToString("\n") { it.text }
        assertTrue(joined.contains("第一段"))
        assertTrue(joined.contains("第三段"))
    }

    /**
     * 回归用例：曾经在 flush 之前把 bufEnd 改成本段结尾，
     * 导致块的 charEnd 把还没进块的那一段也算进去，高亮范围偏大。
     */
    @Test
    fun `块的结束偏移不会越过自身内容`() {
        // 首段长度需超过 700 字上限，才能触发「先 flush 再加入下一段」这条路径
        val body = "这是一段通知内容，用于把缓冲区撑到上限。".repeat(36) + "。"
        val src = body + "\n" + "尾巴段落。"
        val pieces = Chunker.split(src)
        assertTrue(pieces.size >= 2)
        pieces.forEach { p ->
            assertTrue(p.charEnd <= src.length)
            val slice = src.substring(p.charStart, p.charEnd)
            assertTrue("块首文字必须落在自己的区间里", slice.contains(p.text.take(6)))
        }
    }

    @Test
    fun `长文本会被切成多块`() {
        val src = ("这是一段比较长的通知内容，".repeat(20) + "。\n").repeat(6)
        val pieces = Chunker.split(src)
        assertTrue(pieces.size > 1)
        pieces.forEach { assertTrue("单块不应过大", it.text.length <= 1_400) }
    }

    @Test
    fun `页标记能映射到页码`() {
        val src = "第一页的内容。\n第二页的内容。"
        // 标记 = (起点偏移 to 页号)；偏移 8 是「第二页」所在行
        val pieces = Chunker.split(src, pageMarkers = listOf(0 to 1, 8 to 2))
        assertTrue(pieces.isNotEmpty())
        assertTrue(pieces.first().pageNo == 1)
    }

    @Test
    fun `超长单段会被硬切`() {
        val pieces = Chunker.split("甲".repeat(2_000))
        assertTrue(pieces.size >= 2)
        pieces.forEach { assertTrue(it.text.length <= 700) }
    }
}
