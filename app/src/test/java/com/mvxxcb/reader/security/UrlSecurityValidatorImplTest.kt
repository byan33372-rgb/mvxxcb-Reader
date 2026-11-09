package com.mvxxcb.reader.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * UrlSecurityValidatorImpl 单测（04 §5 DoD1：SSRF 全矩阵逐条 Blocked + 合法 URL Allowed）。
 *
 * 全部用假 DNS（[HostResolver] 注入），无真实网络依赖；
 * 不传 allowListForTest（本文件不豁免任何地址，产品路径即默认构造）。
 */
class UrlSecurityValidatorImplTest {

    /** 假解析器：仅映射表内主机可解析，其余一律 UnknownHostException。 */
    private fun fakeResolver(vararg mappings: Pair<String, List<String>>): HostResolver = HostResolver { host ->
        mappings.firstOrNull { it.first == host }?.second
            ?.map { InetAddress.getByName(it) }
            ?.toTypedArray()
            ?: throw UnknownHostException("fake DNS: no mapping for $host")
    }

    private val validator = UrlSecurityValidatorImpl(
        hostResolver = fakeResolver(
            "localhost" to listOf("127.0.0.1"),
            "example.com" to listOf("93.184.216.34"),
            "www.example.com" to listOf("93.184.216.34"),
            "xn--r8jz45g.com" to listOf("93.184.216.34"),   // 例え.com 的 punycode 形态
            "pub.test" to listOf("8.8.8.8"),
            "mixed.test" to listOf("8.8.8.8", "10.0.0.1"),   // 多 A 记录：一公一私（rebinding 形态）
            "nxd.test" to emptyList<String>(),               // 解析为空
        ),
    )

    private fun blockedReason(url: String): BlockedReason {
        val r = validator.validate(url)
        assertTrue("期望 $url 被拦截，实际 $r", r is UrlCheckResult.Blocked)
        return (r as UrlCheckResult.Blocked).reason
    }

    private fun assertAllowed(url: String, normalized: String? = null) {
        val r = validator.validate(url)
        assertTrue("期望 $url 放行，实际 $r", r is UrlCheckResult.Allowed)
        if (normalized != null) assertEquals(normalized, (r as UrlCheckResult.Allowed).normalizedUrl)
    }

    // ———————————————————— DoD1：SSRF 全矩阵 ————————————————————

    @Test
    fun `回环与 localhost 全部 Blocked`() {
        assertEquals(BlockedReason.LOOPBACK, blockedReason("http://localhost/"))
        assertEquals(BlockedReason.LOOPBACK, blockedReason("http://127.0.0.1/"))
        assertEquals(BlockedReason.LOOPBACK, blockedReason("http://127.8.8.8:8080/x"))
    }

    @Test
    fun `私网段 10-172-16-192-168 全部 Blocked`() {
        assertEquals(BlockedReason.PRIVATE_ADDRESS, blockedReason("http://10.1.2.3/"))
        assertEquals(BlockedReason.PRIVATE_ADDRESS, blockedReason("http://172.16.0.1/"))
        assertEquals(BlockedReason.PRIVATE_ADDRESS, blockedReason("http://172.31.255.255/"))
        assertEquals(BlockedReason.PRIVATE_ADDRESS, blockedReason("http://192.168.1.1/"))
    }

    @Test
    fun `链路本地与云元数据 Blocked`() {
        assertEquals(BlockedReason.LINK_LOCAL, blockedReason("http://169.254.1.1/"))
        assertEquals(BlockedReason.METADATA_ENDPOINT, blockedReason("http://169.254.169.254/latest/meta-data/"))
    }

    @Test
    fun `保留段全家族 Blocked`() {
        assertEquals(BlockedReason.RESERVED_ADDRESS, blockedReason("http://0.0.0.0/"))
        assertEquals(BlockedReason.RESERVED_ADDRESS, blockedReason("http://0.1.2.3/"))
        assertEquals(BlockedReason.RESERVED_ADDRESS, blockedReason("http://100.64.0.1/"))       // CGNAT 起点
        assertEquals(BlockedReason.RESERVED_ADDRESS, blockedReason("http://100.127.255.255/"))  // CGNAT 终点
        assertEquals(BlockedReason.RESERVED_ADDRESS, blockedReason("http://192.0.2.1/"))        // TEST-NET-1
        assertEquals(BlockedReason.RESERVED_ADDRESS, blockedReason("http://198.51.100.7/"))     // TEST-NET-2
        assertEquals(BlockedReason.RESERVED_ADDRESS, blockedReason("http://203.0.113.9/"))      // TEST-NET-3
        assertEquals(BlockedReason.RESERVED_ADDRESS, blockedReason("http://198.18.0.1/"))       // 基准测试段
        assertEquals(BlockedReason.RESERVED_ADDRESS, blockedReason("http://224.0.0.1/"))        // 组播
        assertEquals(BlockedReason.RESERVED_ADDRESS, blockedReason("http://240.0.0.1/"))        // 240/4
        assertEquals(BlockedReason.RESERVED_ADDRESS, blockedReason("http://255.255.255.255/"))  // 广播
    }

    @Test
    fun `IPv6 回环-ULA-链路本地-组播 Blocked`() {
        assertEquals(BlockedReason.LOOPBACK, blockedReason("http://[::1]/"))
        assertEquals(BlockedReason.LOOPBACK, blockedReason("http://[::1]:8080/x"))
        assertEquals(BlockedReason.PRIVATE_ADDRESS, blockedReason("http://[fc00::1]/"))
        assertEquals(BlockedReason.PRIVATE_ADDRESS, blockedReason("http://[fd12:3456::1]/"))
        assertEquals(BlockedReason.LINK_LOCAL, blockedReason("http://[fe80::1]/"))
    }

    @Test
    fun `IPv6 内嵌 IPv4 变体全部还原判定 Blocked`() {
        assertEquals(BlockedReason.LOOPBACK, blockedReason("http://[::ffff:127.0.0.1]/"))
        assertEquals(BlockedReason.PRIVATE_ADDRESS, blockedReason("http://[::ffff:192.168.0.1]/"))
        assertEquals(BlockedReason.METADATA_ENDPOINT, blockedReason("http://[::ffff:169.254.169.254]/"))
        assertEquals(BlockedReason.LOOPBACK, blockedReason("http://[::ffff:7f00:1]/"))     // 映射形十六进制写法
        assertEquals(BlockedReason.LOOPBACK, blockedReason("http://[64:ff9b::127.0.0.1]/")) // NAT64 well-known 前缀
        assertEquals(BlockedReason.LOOPBACK, blockedReason("http://[2002:7f00:1::]/"))      // 6to4 内嵌 127.0.0.1
    }

    @Test
    fun `十进制-十六进制-八进制-缩写 IP 字面量变体全部 Blocked`() {
        assertEquals(BlockedReason.LOOPBACK, blockedReason("http://2130706433/"))    // 32 位十进制整数
        assertEquals(BlockedReason.LOOPBACK, blockedReason("http://0x7f000001/"))    // 整段十六进制
        assertEquals(BlockedReason.LOOPBACK, blockedReason("http://0x7f.0.0.1/"))    // 混合十六进制
        assertEquals(BlockedReason.LOOPBACK, blockedReason("http://0177.0.0.1/"))    // 八进制（JDK 会误判为 177）
        assertEquals(BlockedReason.LOOPBACK, blockedReason("http://127.1/"))         // 缩写
        assertEquals(BlockedReason.PRIVATE_ADDRESS, blockedReason("http://0xA.0.0.1/")) // 10.0.0.1
    }

    @Test
    fun `非 http-https scheme 全部 Blocked`() {
        assertEquals(BlockedReason.NON_HTTP_SCHEME, blockedReason("ftp://www.example.com/"))
        assertEquals(BlockedReason.NON_HTTP_SCHEME, blockedReason("file:///etc/passwd"))
        assertEquals(BlockedReason.NON_HTTP_SCHEME, blockedReason("javascript:alert(1)"))
        assertEquals(BlockedReason.NON_HTTP_SCHEME, blockedReason("data:text/html;base64,PGI-"))
        assertEquals(BlockedReason.NON_HTTP_SCHEME, blockedReason("ws://www.example.com/"))
    }

    @Test
    fun `畸形 URL 与缺 scheme Blocked`() {
        assertEquals(BlockedReason.MALFORMED_URL, blockedReason(""))
        assertEquals(BlockedReason.MALFORMED_URL, blockedReason("::::"))
        assertEquals(BlockedReason.MALFORMED_URL, blockedReason("//www.example.com/"))
        assertEquals(BlockedReason.MALFORMED_URL, blockedReason("www.example.com/x"))
        // 空格经编码修复后成为 registry 型 authority → 主机无法解析，报 INVALID_HOST（同样拦截）
        assertEquals(BlockedReason.INVALID_HOST, blockedReason("http://exa mple.com/"))
        // 空主机：URI 行为下为 INVALID_HOST 或 MALFORMED_URL，均视为拦截
        val emptyHostReason = blockedReason("http://")
        assertTrue("空主机应拦截（实际 $emptyHostReason）", emptyHostReason == BlockedReason.INVALID_HOST || emptyHostReason == BlockedReason.MALFORMED_URL)
    }

    @Test
    fun `userinfo 混淆型 URL Blocked`() {
        assertEquals(BlockedReason.INVALID_HOST, blockedReason("http://user:pass@www.example.com/"))
        assertEquals(BlockedReason.INVALID_HOST, blockedReason("http://example.com@127.0.0.1/"))
    }

    @Test
    fun `DNS 解析失败与多 A 记录一私一公 Blocked`() {
        assertEquals(BlockedReason.INVALID_HOST, blockedReason("http://no-such-host.invalid/"))
        assertEquals(BlockedReason.INVALID_HOST, blockedReason("http://nxd.test/"))
        assertEquals(BlockedReason.PRIVATE_ADDRESS, blockedReason("http://mixed.test/"))
    }

    // ———————————————————— 合法公网 URL Allowed ————————————————————

    @Test
    fun `合法公网 URL Allowed 且规范化`() {
        assertAllowed("https://www.example.com/book/123?id=1")
        assertAllowed("http://pub.test/")

        // 大写主机 + 默认端口 → 小写、去默认端口
        assertAllowed("http://EXAMPLE.com:80/x", "http://example.com/x")
        assertAllowed("https://WWW.EXAMPLE.COM", "https://www.example.com/")

        // 非默认端口保留
        assertAllowed("https://www.example.com:8443/a", "https://www.example.com:8443/a")

        // fragment 丢弃
        assertAllowed("https://www.example.com/p#top", "https://www.example.com/p")

        // 尾点主机归一
        assertAllowed("http://pub.test./x", "http://pub.test/x")
    }

    @Test
    fun `IDN 主机转 punycode 后 Allowed`() {
        val r = validator.validate("http://例え.com/章")
        assertTrue("期望 IDN URL 放行，实际 $r", r is UrlCheckResult.Allowed)
        assertTrue((r as UrlCheckResult.Allowed).normalizedUrl.startsWith("http://xn--"))
    }

    @Test
    fun `带空格路径经百分号编码后 Allowed`() {
        assertAllowed("http://pub.test/a b.html", "http://pub.test/a%20b.html")
    }

    // ———————————————————— validateRedirect 逐跳复查 ————————————————————

    @Test
    fun `重定向到私网被拦截`() {
        val r = validator.validateRedirect("https://www.example.com/a", "http://10.0.0.5/x")
        assertTrue("期望重定向目标被拦截，实际 $r", r is UrlCheckResult.Blocked)
        assertEquals(BlockedReason.PRIVATE_ADDRESS, (r as UrlCheckResult.Blocked).reason)
    }

    @Test
    fun `重定向到回环与元数据端点被拦截`() {
        assertEquals(
            BlockedReason.LOOPBACK,
            (validator.validateRedirect("https://www.example.com/a", "http://localhost/") as UrlCheckResult.Blocked).reason,
        )
        assertEquals(
            BlockedReason.METADATA_ENDPOINT,
            (validator.validateRedirect("https://www.example.com/a", "http://169.254.169.254/") as UrlCheckResult.Blocked).reason,
        )
    }

    @Test
    fun `重定向目标为合法公网时放行`() {
        val r = validator.validateRedirect("https://www.example.com/a", "http://pub.test/b")
        assertTrue("期望重定向目标放行，实际 $r", r is UrlCheckResult.Allowed)
        assertEquals("http://pub.test/b", (r as UrlCheckResult.Allowed).normalizedUrl)
    }
}
