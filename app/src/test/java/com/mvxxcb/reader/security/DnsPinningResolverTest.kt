package com.mvxxcb.reader.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * DnsPinningResolver 单测（DNS rebinding 防护：解析结果过同一私网黑名单）。
 */
class DnsPinningResolverTest {

    private fun fakeResolver(vararg mappings: Pair<String, List<String>>) = HostResolver { host ->
        mappings.firstOrNull { it.first == host }?.second
            ?.map { InetAddress.getByName(it) }
            ?.toTypedArray()
            ?: throw UnknownHostException("fake DNS: no mapping for $host")
    }

    private val resolver = DnsPinningResolver(
        hostResolver = fakeResolver(
            "pub.test" to listOf("8.8.8.8"),
            "rebind.test" to listOf("8.8.8.8", "192.168.0.1"),   // 一公一私
            "allprivate.test" to listOf("192.168.0.1", "10.0.0.2"),
            "loop.test" to listOf("127.0.0.1"),
        ),
    )

    @Test
    fun `公网解析结果放行`() {
        val result = resolver.lookup("pub.test")
        assertEquals(listOf(InetAddress.getByName("8.8.8.8")), result)
    }

    @Test
    fun `多 A 记录中私网地址被剔除`() {
        val result = resolver.lookup("rebind.test")
        assertEquals(listOf(InetAddress.getByName("8.8.8.8")), result)
    }

    @Test
    fun `全部命中私网时抛 UnknownHostException`() {
        try {
            resolver.lookup("allprivate.test")
            throw AssertionError("期望 UnknownHostException")
        } catch (e: UnknownHostException) {
            assertTrue(e.message!!.contains("黑名单"))
        }
        try {
            resolver.lookup("loop.test")
            throw AssertionError("期望 UnknownHostException")
        } catch (e: UnknownHostException) {
            assertTrue(e.message!!.contains("黑名单"))
        }
    }

    @Test
    fun `解析失败向上抛 UnknownHostException`() {
        try {
            resolver.lookup("no-such.invalid")
            throw AssertionError("期望 UnknownHostException")
        } catch (e: UnknownHostException) {
            // 期望路径
        }
    }

    @Test
    fun `allowListForTest 豁免回环（MockWebServer 场景）`() {
        val testResolver = DnsPinningResolver(
            hostResolver = fakeResolver("loop.test" to listOf("127.0.0.1")),
            allowListForTest = setOf("loop.test"),
        )
        assertEquals(listOf(InetAddress.getByName("127.0.0.1")), testResolver.lookup("loop.test"))
    }
}
