package com.mvxxcb.reader.security

import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.common.time.SystemTimeProvider
import com.mvxxcb.reader.common.time.TimeProvider
import com.mvxxcb.reader.domain.error.NetworkFailure
import com.mvxxcb.reader.domain.error.ReaderError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * SecureHttpClientImpl 单测（04 §5 DoD2/3）。
 *
 * 全部经 MockWebServer 真实执行；validator/DNS 以 allowListForTest 放行回环地址
 * （仅测试注入，产品装配不传——见 UrlSecurityValidatorImpl KDoc）。
 * 限额断言按主 Agent 指令用 FetchRequest.maxBytes 传小值达成。
 */
class SecureHttpClientImplTest {

    private lateinit var server: MockWebServer

    private val allowList = setOf("localhost", "127.0.0.1")

    private fun setUpServer(): MockWebServer = MockWebServer().also {
        it.start()
        server = it
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) {
            // 取消场景会留下已中断的连接/延时任务，MockWebServer.shutdown 可能报
            // "Gave up waiting for queue to shut down"——测试基础设施噪音，不作为失败
            try {
                server.shutdown()
            } catch (_: IOException) {
            }
        }
    }

    private fun newClient(
        limits: NetworkLimits = NetworkLimits(minIntervalPerHostMs = 0),
        timeProvider: TimeProvider = SystemTimeProvider(),
        allowList: Set<String> = this.allowList,
        dns: Dns = DnsPinningResolver(allowListForTest = allowList),
    ): SecureHttpClientImpl = SecureHttpClientImpl(
        validator = UrlSecurityValidatorImpl(allowListForTest = allowList),
        limits = limits,
        timeProvider = timeProvider,
        logger = null,
        dns = dns,
    )

    private fun base(path: String = "/"): String = "http://localhost:${server.port}$path"

    private fun assertFailure(
        result: AppResult<FetchResult>,
        check: (ReaderError) -> Unit,
    ) {
        val error = result.errorOrNull()
        assertNotNull("期望 Failure，实际 $result", error)
        check(error!!)
    }

    private fun netFailureOf(result: AppResult<FetchResult>): ReaderError.Network {
        val error = result.errorOrNull()
        assertTrue("期望 ReaderError.Network，实际 $error", error is ReaderError.Network)
        return error as ReaderError.Network
    }

    private fun secFailureOf(result: AppResult<FetchResult>): ReaderError.Security {
        val error = result.errorOrNull()
        assertTrue("期望 ReaderError.Security，实际 $error", error is ReaderError.Security)
        return error as ReaderError.Security
    }

    // ———————————————————— 成功路径 ————————————————————

    @Test(timeout = 30_000L)
    fun `成功响应返回完整 FetchResult`() = runBlocking {
        setUpServer()
        server.enqueue(
            MockResponse()
                .setBody(okio.Buffer().write("<html>正文</html>".toByteArray(charset("GBK"))))
                .addHeader("Content-Type", "text/html; charset=GBK")
                .addHeader("X-Custom-Header", "abc"),
        )
        val client = newClient()
        val result = client.fetch(FetchRequest(url = base("/book/1?from=shelf"), headers = mapOf("User-Agent" to "test-ua")))
        val ok = result.getOrNull()
        assertNotNull("期望成功，实际 ${result.errorOrNull()}", ok)
        ok!!
        assertEquals(200, ok.statusCode)
        assertEquals(base("/book/1?from=shelf"), ok.finalUrl)
        assertEquals(listOf(base("/book/1?from=shelf")), ok.redirectChain)
        assertEquals("text/html", ok.contentType)
        assertEquals("GBK", ok.declaredCharset)
        assertEquals("abc", ok.headers["x-custom-header"]?.single())
        assertNull(ok.headers["X-Custom-Header"])   // key 必须全小写
        assertTrue(ok.headers.keys.all { it == it.lowercase() })
        assertEquals("<html>正文</html>", String(ok.bodyBytes, charset("GBK")))
        assertEquals(false, ok.fromCache)
        assertTrue(ok.elapsedMs >= 0)
    }

    // ———————————————————— 重定向 ————————————————————

    @Test(timeout = 30_000L)
    fun `重定向链逐跳复查后到达终点`() = runBlocking {
        setUpServer()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/r2"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/r3"))
        server.enqueue(MockResponse().setBody("final-body"))
        val client = newClient()
        val result = client.fetch(FetchRequest(url = base("/r1")))
        val ok = result.getOrNull()
        assertNotNull("期望成功，实际 ${result.errorOrNull()}", ok)
        ok!!
        assertEquals(base("/r3"), ok.finalUrl)
        assertEquals(listOf(base("/r1"), base("/r2"), base("/r3")), ok.redirectChain)
        assertEquals("final-body", String(ok.bodyBytes))
        assertEquals(3, server.requestCount)
        assertEquals("/r1", server.takeRequest().path)
        assertEquals("/r2", server.takeRequest().path)
        assertEquals("/r3", server.takeRequest().path)
    }

    @Test(timeout = 30_000L)
    fun `重定向到私网地址被拦截`() = runBlocking {
        setUpServer()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://10.0.0.5/x"))
        val client = newClient()
        val result = client.fetch(FetchRequest(url = base("/redir")))
        val sec = secFailureOf(result)
        assertEquals(BlockedReason.PRIVATE_ADDRESS, sec.reason)
        assertEquals(1, server.requestCount)   // 第二跳未发出
    }

    @Test(timeout = 30_000L)
    fun `重定向死循环报 REDIRECT_LOOP`() = runBlocking {
        setUpServer()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/loop"))
        val client = newClient()
        val result = client.fetch(FetchRequest(url = base("/loop")))
        val net = netFailureOf(result)
        assertEquals(NetworkFailure.REDIRECT_LOOP, net.failure)
        assertEquals(1, server.requestCount)
    }

    @Test(timeout = 30_000L)
    fun `重定向超上限报 TOO_MANY_REDIRECTS`() = runBlocking {
        setUpServer()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/r2"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/r3"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/r4"))
        val client = newClient(limits = NetworkLimits(minIntervalPerHostMs = 0, maxRedirects = 2))
        val result = client.fetch(FetchRequest(url = base("/r1")))
        assertEquals(NetworkFailure.TOO_MANY_REDIRECTS, netFailureOf(result).failure)
        assertEquals(3, server.requestCount)
    }

    @Test(timeout = 30_000L)
    fun `跨主机重定向丢弃 Cookie 头`() = runBlocking {
        setUpServer()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:${server.port}/b"))
        server.enqueue(MockResponse().setBody("ok"))
        val client = newClient()
        val result = client.fetch(
            FetchRequest(url = base("/a"), headers = mapOf("Cookie" to "sid=secret", "User-Agent" to "ua")),
        )
        assertNotNull("期望成功，实际 ${result.errorOrNull()}", result.getOrNull())
        assertEquals(2, server.requestCount)
        server.takeRequest()   // 第一跳带 Cookie
        val second = server.takeRequest()
        assertNull("跨主机重定向不得携带 Cookie", second.getHeader("Cookie"))
        assertEquals("ua", second.getHeader("User-Agent"))
    }

    // ———————————————————— 限额 ————————————————————

    @Test(timeout = 30_000L)
    fun `响应体超 maxBytes 报 RESPONSE_TOO_LARGE（Content-Length 预检）`() = runBlocking {
        setUpServer()
        server.enqueue(MockResponse().setBody("x".repeat(2000)))
        val client = newClient()
        val result = client.fetch(FetchRequest(url = base("/big"), maxBytes = 100))
        assertEquals(NetworkFailure.RESPONSE_TOO_LARGE, netFailureOf(result).failure)
    }

    @Test(timeout = 30_000L)
    fun `流式读取超 maxBytes 中断报 RESPONSE_TOO_LARGE（chunked 无 Content-Length）`() = runBlocking {
        setUpServer()
        val bodyBuffer = okio.Buffer().writeUtf8("y".repeat(5000))
        server.enqueue(MockResponse().setChunkedBody(bodyBuffer, 512))
        val client = newClient()
        val result = client.fetch(FetchRequest(url = base("/chunked"), maxBytes = 1000))
        assertEquals(NetworkFailure.RESPONSE_TOO_LARGE, netFailureOf(result).failure)
    }

    @Test(timeout = 30_000L)
    fun `maxBytes 非正时回退到 limits 默认上限`() = runBlocking {
        setUpServer()
        server.enqueue(MockResponse().setBody("small"))
        val client = newClient()
        val result = client.fetch(FetchRequest(url = base("/"), maxBytes = 0))
        assertNotNull("期望成功，实际 ${result.errorOrNull()}", result.getOrNull())
    }

    // ———————————————————— 限流（假 TimeProvider） ————————————————————

    private class FakeTime(var now: Long = 1_000_000L) : TimeProvider {
        override fun nowMillis(): Long = now
    }

    @Test(timeout = 30_000L)
    fun `同主机最小请求间隔生效（间隔不足挂起等待）`() = runBlocking {
        setUpServer()
        server.enqueue(MockResponse().setBody("one"))
        server.enqueue(MockResponse().setBody("two"))
        // 冻结假时钟：间隔计算恒不足 → fetch 内部 delay(interval) 真实等待
        val client = newClient(
            limits = NetworkLimits(minIntervalPerHostMs = 400, connectTimeoutMs = 2000, readTimeoutMs = 2000),
            timeProvider = FakeTime(),
        )
        val r1 = client.fetch(FetchRequest(url = base("/one")))
        assertNotNull(r1.getOrNull())
        val t0 = System.nanoTime()
        val r2 = client.fetch(FetchRequest(url = base("/two")))
        val wallMs = (System.nanoTime() - t0) / 1_000_000
        assertNotNull("期望第二次请求成功，实际 ${r2.errorOrNull()}", r2.getOrNull())
        assertTrue("第二次请求应至少等待间隔 400ms（实际 ${wallMs}ms）", wallMs >= 350)
        assertEquals(2, server.requestCount)
    }

    @Test(timeout = 30_000L)
    fun `同主机间隔已过则不等待`() = runBlocking {
        setUpServer()
        server.enqueue(MockResponse().setBody("one"))
        server.enqueue(MockResponse().setBody("two"))
        val fake = FakeTime()
        val client = newClient(limits = NetworkLimits(minIntervalPerHostMs = 500), timeProvider = fake)
        assertNotNull(client.fetch(FetchRequest(url = base("/one"))).getOrNull())
        fake.now += 1_000   // 假时钟推进超过间隔 → 不等待
        val t0 = System.nanoTime()
        assertNotNull(client.fetch(FetchRequest(url = base("/two"))).getOrNull())
        val wallMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("间隔已过不应真实等待（实际 ${wallMs}ms）", wallMs < 300)
    }

    @Test(timeout = 30_000L)
    fun `HTTP 429 按 Retry-After 冷却后续请求等待指定时长`() = runBlocking {
        setUpServer()
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
        server.enqueue(MockResponse().setBody("ok"))
        // 冻结假时钟 + 小起始间隔：等待量确定化——第一次 429 → Retry-After=1s 冷却，
        // 第二次请求应真实挂起 ~1000ms（若冷却未生效则仅 ~100ms 基线间隔）
        val client = newClient(
            limits = NetworkLimits(minIntervalPerHostMs = 100, connectTimeoutMs = 2000, readTimeoutMs = 2000),
            timeProvider = FakeTime(),
        )
        val first = client.fetch(FetchRequest(url = base("/rate")))
        val firstError = netFailureOf(first)
        assertEquals(NetworkFailure.HTTP_ERROR_STATUS, firstError.failure)
        assertEquals(429, firstError.statusCode)
        val t0 = System.nanoTime()
        val second = client.fetch(FetchRequest(url = base("/rate")))
        val wallMs = (System.nanoTime() - t0) / 1_000_000
        assertNotNull("期望冷却后重发成功，实际 ${second.errorOrNull()}", second.getOrNull())
        assertTrue("Retry-After=1s 后才应重发第二次请求（实际 ${wallMs}ms）", wallMs >= 900)
        assertEquals(2, server.requestCount)
    }

    @Test(timeout = 30_000L)
    fun `HTTP 错误态触发失败退避（间隔翻倍）`() = runBlocking {
        setUpServer()
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        server.enqueue(MockResponse().setBody("ok"))
        // 冻结假时钟 + 起始间隔 200：404 → onFailure 翻倍至 400，第二次请求真实挂起 400ms
        // （若退避未生效则仅 200ms 基线间隔，无法达到 350ms 断言线）
        val client = newClient(
            limits = NetworkLimits(minIntervalPerHostMs = 200, connectTimeoutMs = 2000, readTimeoutMs = 2000),
            timeProvider = FakeTime(),
        )
        assertEquals(NetworkFailure.HTTP_ERROR_STATUS, netFailureOf(client.fetch(FetchRequest(url = base("/404")))).failure)
        val t0 = System.nanoTime()
        val second = client.fetch(FetchRequest(url = base("/ok")))
        val wallMs = (System.nanoTime() - t0) / 1_000_000
        assertNotNull("期望退避后重发成功，实际 ${second.errorOrNull()}", second.getOrNull())
        assertTrue("失败后应按翻倍间隔退避 ≥350ms（实际 ${wallMs}ms）", wallMs >= 350)
        assertEquals(2, server.requestCount)
    }

    // ———————————————————— 错误路径映射（DoD3：一律 Failure，不抛异常） ————————————————————

    @Test(timeout = 30_000L)
    fun `HTTP 错误状态码返回 HTTP_ERROR_STATUS 且带 statusCode`() = runBlocking {
        setUpServer()
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        val client = newClient()
        val net = netFailureOf(client.fetch(FetchRequest(url = base("/missing"))))
        assertEquals(NetworkFailure.HTTP_ERROR_STATUS, net.failure)
        assertEquals(404, net.statusCode)
    }

    @Test(timeout = 30_000L)
    fun `空响应体返回 EMPTY_RESPONSE`() = runBlocking {
        setUpServer()
        server.enqueue(MockResponse().setBody(""))
        val client = newClient()
        assertEquals(NetworkFailure.EMPTY_RESPONSE, netFailureOf(client.fetch(FetchRequest(url = base("/")))).failure)
    }

    @Test(timeout = 30_000L)
    fun `读超时返回 READ_TIMEOUT`() = runBlocking {
        setUpServer()
        server.enqueue(
            MockResponse().setBody("slow").setBodyDelay(3, TimeUnit.SECONDS),
        )
        val client = newClient(limits = NetworkLimits(minIntervalPerHostMs = 0, connectTimeoutMs = 2000, readTimeoutMs = 300))
        val net = netFailureOf(client.fetch(FetchRequest(url = base("/slow"))))
        assertEquals(NetworkFailure.READ_TIMEOUT, net.failure)
    }

    @Test(timeout = 30_000L)
    fun `DNS 解析失败返回 DNS_FAILURE（OkHttp 建连阶段）`() = runBlocking {
        // 主机名加入 allowList 使 validator 跳过解析（模拟"校验时公网"），
        // 由 OkHttp 建连阶段的 DnsPinningResolver 触发真实 DNS 失败
        setUpServer()
        val client = newClient(allowList = setOf("nx.invalid"))
        val net = netFailureOf(client.fetch(FetchRequest(url = "http://nx.invalid/x")))
        assertEquals(NetworkFailure.DNS_FAILURE, net.failure)
    }

    @Test(timeout = 30_000L)
    fun `连接被拒绝返回 CONNECTION_REFUSED`() = runBlocking {
        val socket = ServerSocket(0)
        val deadPort = socket.localPort
        socket.close()
        val client = newClient()
        val net = netFailureOf(client.fetch(FetchRequest(url = "http://127.0.0.1:$deadPort/")))
        assertEquals(NetworkFailure.CONNECTION_REFUSED, net.failure)
    }

    @Test(timeout = 30_000L)
    fun `非 http scheme 在校验层拦截且不发请求`() = runBlocking {
        setUpServer()
        val client = newClient()
        val sec = secFailureOf(client.fetch(FetchRequest(url = "ftp://localhost/x")))
        assertEquals(BlockedReason.NON_HTTP_SCHEME, sec.reason)
        assertEquals(0, server.requestCount)
    }

    @Test(timeout = 30_000L)
    fun `私网地址在校验层拦截且不发请求`() = runBlocking {
        setUpServer()
        val client = newClient()
        val sec = secFailureOf(client.fetch(FetchRequest(url = "http://192.168.1.100/x")))
        assertEquals(BlockedReason.PRIVATE_ADDRESS, sec.reason)
        assertEquals(0, server.requestCount)
    }

    @Test(timeout = 30_000L)
    fun `畸形 URL 返回 Failure 不抛异常`() = runBlocking {
        setUpServer()
        val client = newClient()
        val error = client.fetch(FetchRequest(url = "::::")).errorOrNull()
        assertNotNull(error)
        assertEquals(0, server.requestCount)
    }

    // ———————————————————— 协程取消桥接 ————————————————————

    @Test(timeout = 30_000L)
    fun `协程取消即时中断底层请求`() = runBlocking {
        setUpServer()
        server.enqueue(MockResponse().setBody("very slow body").setBodyDelay(10, TimeUnit.SECONDS))
        val client = newClient(limits = NetworkLimits(minIntervalPerHostMs = 0, connectTimeoutMs = 2000, readTimeoutMs = 5000))
        val requestStarted = CompletableDeferred<Unit>()
        val job = launch {
            requestStarted.complete(Unit)
            client.fetch(FetchRequest(url = base("/slow")))   // 结果随取消丢弃
        }
        requestStarted.await()
        delay(300)   // 让请求进入正文读取阶段
        val t0 = System.nanoTime()
        job.cancelAndJoin()
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("取消应在 3 秒内完成（实际 ${elapsedMs}ms）", elapsedMs < 3000)
        assertEquals(1, server.requestCount)
    }

    // ———————————————————— 隐私最小化 ————————————————————

    @Test(timeout = 30_000L)
    fun `错误对象中的 URL 不含查询串（日志脱敏）`() = runBlocking {
        setUpServer()
        server.enqueue(MockResponse().setResponseCode(404))
        val client = newClient()
        val net = netFailureOf(client.fetch(FetchRequest(url = base("/p?token=supersecret&uid=42"))))
        assertTrue(
            "错误 url 不应包含查询串，实际 ${net.url}",
            net.url?.contains("supersecret") != true,
        )
    }
}
