package com.howtodo.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateParserTest {

    /** 构造 releases/latest 响应 JSON。body 传入完整 JSON 字符串值（含首尾引号与转义） */
    private fun releaseJson(body: String, assetName: String = "howtodo-v0.1.8.apk"): String =
        "{\n" +
            "  \"tag_name\": \"latest\",\n" +
            "  \"name\": \"HowTodo latest\",\n" +
            "  \"body\": " + body + ",\n" +
            "  \"assets\": [\n" +
            "    {\"name\": \"CHANGELOG.md\", \"browser_download_url\": \"https://example.com/CHANGELOG.md\", \"size\": 100},\n" +
            "    {\"name\": \"" + assetName + "\", \"browser_download_url\": \"https://github.com/Lwb1397111398/howtodo/releases/download/latest/" + assetName + "\", \"size\": 52428800}\n" +
            "  ]\n" +
            "}"

    @Test
    fun parseMetaReadsVersionFromComment() {
        val meta = UpdateParser.parseMeta(
            "<!-- appupdate versionCode=21 versionName=0.1.21 -->\n更新内容：修复若干问题"
        )
        assertEquals(21, meta?.versionCode)
        assertEquals("0.1.21", meta?.versionName)
    }

    @Test
    fun parseMetaAcceptsDottedAndDashedNames() {
        val meta = UpdateParser.parseMeta("<!-- appupdate versionCode=123 versionName=1.2.3-beta.1 -->")
        assertEquals(123, meta?.versionCode)
        assertEquals("1.2.3-beta.1", meta?.versionName)
    }

    @Test
    fun parseMetaReturnsNullWhenMissingOrInvalid() {
        assertNull(UpdateParser.parseMeta("没有元数据的普通说明"))
        assertNull(UpdateParser.parseMeta(null))
        assertNull(UpdateParser.parseMeta(""))
        assertNull(UpdateParser.parseMeta("<!-- appupdate versionCode=0 versionName=0.1.0 -->"))
    }

    @Test
    fun notesOfStripsMetadataLine() {
        val notes = UpdateParser.notesOf(
            "<!-- appupdate versionCode=21 versionName=0.1.21 -->\n\n修复了闹钟问题"
        )
        assertEquals("修复了闹钟问题", notes)
    }

    @Test
    fun notesOfReturnsNullWhenOnlyMetadata() {
        assertNull(UpdateParser.notesOf("<!-- appupdate versionCode=21 versionName=0.1.21 -->"))
        assertNull(UpdateParser.notesOf(""))
        assertNull(UpdateParser.notesOf(null))
    }

    @Test
    fun parseReleaseExtractsInfoAndNotes() {
        val info = UpdateParser.parseRelease(
            releaseJson("\"<!-- appupdate versionCode=8 versionName=0.1.8 -->\\n修复提醒\"")
        )
        assertEquals(8, info?.versionCode)
        assertEquals("0.1.8", info?.versionName)
        assertEquals(52428800L, info?.apkSize)
        assertTrue(info?.apkUrl?.endsWith("howtodo-v0.1.8.apk") == true)
        assertEquals("修复提醒", info?.notes?.trim())
    }

    @Test
    fun parseReleaseReturnsNullWhenMetadataMissing() {
        assertNull(UpdateParser.parseRelease(releaseJson("\"常规版本说明，没有元数据注释\"")))
    }

    @Test
    fun parseReleaseReturnsNullWhenNoApkAsset() {
        val json = "{\n  \"body\": \"<!-- appupdate versionCode=9 versionName=0.1.9 -->\",\n" +
            "  \"assets\": [{\"name\": \"CHANGELOG.md\", \"browser_download_url\": \"https://example.com/CHANGELOG.md\", \"size\": 100}]\n}"
        assertNull(UpdateParser.parseRelease(json))
    }

    @Test
    fun parseReleaseToleratesMalformedJson() {
        assertNull(UpdateParser.parseRelease("{not valid json"))
        assertNull(UpdateParser.parseRelease(""))
        assertNull(UpdateParser.parseRelease(null))
    }
}
