package com.howtodo.update

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 下载通道的候选顺序决定「用哪个源下载」：primary（查询成功的通道）必须排第一，
 * 下载专用镜像殿后。顺序错了在国内网络下会白白先撞坏源。
 */
class UpdateCheckerTest {

    @Test
    fun `查询成功通道排第一且候选去重`() {
        val got = UpdateDownloader.candidates("https://gh-proxy.com/")
        assertEquals(
            listOf(
                "https://gh-proxy.com/",
                "",
                "https://gh-proxy.org/",
                "https://ghfast.top/",
                "https://ghproxy.net/"
            ),
            got
        )
    }

    @Test
    fun `primary 为直连时直连仍排第一`() {
        val got = UpdateDownloader.candidates("")
        assertEquals("", got.first())
        // 直连 + 查询镜像 gh-proxy.com + 三个下载专用镜像
        assertEquals(5, got.size)
    }

    @Test
    fun `候选不含重复项`() {
        val got = UpdateDownloader.candidates("https://ghfast.top/")
        assertEquals(got.size, got.distinct().size)
    }
}
