package com.howtodo.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中文检索的地基。这里错一点，用户搜「报名表」就找不到东西，
 * 而且不会有任何报错——属于最难被发现的那类故障。
 */
class BigramTest {

    @Test
    fun `索引侧切成二元组并保留整段原词`() {
        val tokens = Bigram.encode("提交报名表").split(" ").filter { it.isNotBlank() }
        assertEquals(listOf("提交", "交报", "报名", "名表", "提交报名表"), tokens)
    }

    @Test
    fun `查询侧只保留二元组`() {
        val tokens = Bigram.encodeQuery("提交报名").split(" ").filter { it.isNotBlank() }
        assertEquals(listOf("提交", "交报", "报名"), tokens)
    }

    /**
     * 回归用例：查询若保留整段 token，短语会变成
     * `"提交 交报 报名 提交报名"`，而文档里「报名」之后是「名表」，
     * FTS5 的相邻性要求导致永远匹配不上。多字关键词必须能搜到。
     */
    @Test
    fun `多字关键词的短语不会因整段 token 失配`() {
        assertEquals("\"提交 交报 报名\"", Bigram.phraseFor("提交报名"))
    }

    @Test
    fun `中英混排分别按词与二元组处理`() {
        val tokens = Bigram.encodeQuery("交Word表").split(" ").filter { it.isNotBlank() }
        assertEquals(listOf("交", "word", "表"), tokens)
    }

    @Test
    fun `全角字母数字会被归一化`() {
        assertEquals("abc123", Bigram.normalize("ＡＢＣ１２３"))
    }

    @Test
    fun `多个关键词默认 OR 拼接`() {
        assertEquals("(\"报名\") OR (\"截止\")", Bigram.matchExpression(listOf("报名", "截止")))
    }

    @Test
    fun `要求全部命中时用 AND`() {
        assertEquals(
            "(\"报名\") AND (\"截止\")",
            Bigram.matchExpression(listOf("报名", "截止"), allRequired = true)
        )
    }

    @Test
    fun `覆盖率反映关键词命中比例`() {
        val keywords = listOf("报名", "截止", "不存在")
        val text = "请于本周五前完成报名，截止时间另行通知"
        assertEquals(2.0 / 3.0, Bigram.coverage(keywords, text), 1e-9)
    }

    @Test
    fun `纯符号与纯空白不产生查询短语`() {
        assertNull(Bigram.phraseFor("！！！"))
        assertEquals("", Bigram.encode("   \n  "))
    }

    @Test
    fun `本地兜底切词会去掉疑问词`() {
        val kws = Bigram.localKeywords("请问张老师的办公室应该怎么走")
        assertTrue(kws.isNotEmpty())
        assertTrue(kws.any { it.contains("张老师") || it.contains("办公室") })
        assertTrue(kws.none { it == "怎么" })
    }
}
