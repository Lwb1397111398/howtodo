package com.howtodo.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型输出从来不是干净的 JSON。这里是最后一道解析关口：
 * 只要它误判，整次分析就白跑，所以按真实见到的脏输出逐条固化。
 */
class JsonExtractTest {

    @Test
    fun `去掉 markdown 代码围栏`() {
        val r = JsonExtract.objectFrom("```json\n{\"a\":1}\n```")
        assertNotNull(r.json)
        assertEquals(1, r.json!!.get("a").asInt)
    }

    @Test
    fun `前后夹带说明文字也能取出对象`() {
        val r = JsonExtract.objectFrom(
            "好的，以下是分析结果：\n{\"tasks\":[{\"title\":\"交报名表\"}]}\n希望有帮助。"
        )
        assertNotNull(r.json)
        val arr = r.json!!.getAsJsonArray("tasks")
        assertEquals("交报名表", arr[0].asJsonObject.get("title").asString)
    }

    @Test
    fun `字符串内部的花括号不影响截取`() {
        val r = JsonExtract.objectFrom("结果：{\"note\":\"格式 {a:1} 的示例\",\"n\":2}")
        assertNotNull(r.json)
        assertEquals(2, r.json!!.get("n").asInt)
        assertTrue(r.json!!.get("note").asString.contains("{a:1}"))
    }

    @Test
    fun `尾随逗号可修复`() {
        val r = JsonExtract.objectFrom("{\"a\":1,\"b\":[1,2,],}")
        assertNotNull(r.json)
        assertEquals(1, r.json!!.get("a").asInt)
    }

    @Test
    fun `中文引号可修复`() {
        val r = JsonExtract.objectFrom("{“title”:“打印材料”}")
        assertNotNull(r.json)
        assertEquals("打印材料", r.json!!.get("title").asString)
    }

    @Test
    fun `转义引号不会截断对象`() {
        val r = JsonExtract.objectFrom("{\"quote\":\"他说\\\"周三交\\\"\"}")
        assertNotNull(r.json)
        assertTrue(r.json!!.get("quote").asString.contains("周三交"))
    }

    @Test
    fun `完全不是 JSON 时返回错误而不是抛异常`() {
        val r = JsonExtract.objectFrom("抱歉，我无法完成这个任务。")
        assertNull(r.json)
        assertNotNull(r.error)
    }

    @Test
    fun `空响应给出明确错误`() {
        val r = JsonExtract.objectFrom("   ")
        assertNull(r.json)
        assertEquals("响应为空", r.error)
    }
}
