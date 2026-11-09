package com.mvxxcb.reader.parser

import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.domain.error.NetworkFailure
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.model.Confidence
import com.mvxxcb.reader.domain.model.PageKind
import com.mvxxcb.reader.domain.model.UrlClassification
import com.mvxxcb.reader.domain.model.SourceRule
import com.mvxxcb.reader.parser.core.ParseOutcome
import com.mvxxcb.reader.parser.core.ParseRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 网址分类：URL 启发式 / 站点规则提示 / 抓取辅助 / 低置信度 UNKNOWN / 非法 URL */
class UrlClassifierImplTest {

    private lateinit var http: FakeSecureHttpClient
    private lateinit var repo: FakeSourceRuleRepository

    @Before
    fun setUp() {
        http = FakeSecureHttpClient()
        SampleRoutes.installAll(http)
        repo = FakeSourceRuleRepository()
    }

    private fun classify(url: String) = runBlocking { buildClassifier(http, repo).classify(url) }

    @Test
    fun `非法URL返回INVALID_URL失败`() = runBlocking {
        val r = classify("not a url")
        assertTrue(r is AppResult.Failure)
        val err = (r as AppResult.Failure).error
        assertTrue(err is ReaderError.Network)
        assertEquals(NetworkFailure.INVALID_URL, (err as ReaderError.Network).failure)
    }

    @Test
    fun `章节URL特征_中置信度不抓取`() = runBlocking {
        val r = classify(SampleRoutes.CONTENT_PAGED_1) as AppResult.Success
        assertEquals(PageKind.CHAPTER, r.value.kind)
        assertEquals(Confidence.MEDIUM, r.value.confidence)
        assertEquals(0, http.requests.size) // 置信度足够 → 不发辅助请求
        assertTrue(r.value.notes.isNotEmpty())
    }

    @Test
    fun `目录URL特征`() = runBlocking {
        val r = classify(SampleRoutes.CATALOG_NORMAL) as AppResult.Success
        assertEquals(PageKind.CATALOG, r.value.kind)
        assertEquals(Confidence.MEDIUM, r.value.confidence)
    }

    @Test
    fun `详情URL特征`() = runBlocking {
        val r = classify(SampleRoutes.DETAIL_JSONLD) as AppResult.Success
        assertEquals(PageKind.BOOK_DETAIL, r.value.kind)
        assertEquals(Confidence.MEDIUM, r.value.confidence)
    }

    @Test
    fun `无特征URL_抓取辅助判定为目录页`() = runBlocking {
        // /p/123 无 URL 特征 → LOW → 抓取（catalog-normal 有 5 个章节链接）→ CATALOG/HIGH
        http.route("$TEST_BASE/p/123", sampleText("catalog-normal.html"))
        val r = classify("$TEST_BASE/p/123") as AppResult.Success
        assertEquals(PageKind.CATALOG, r.value.kind)
        assertEquals(Confidence.HIGH, r.value.confidence)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `无特征URL且页面无结构_降级UNKNOWN`() = runBlocking {
        // dynamic-shell 无章节链接、无结构化数据、正文极短 → 辅助分析无果 → UNKNOWN/LOW
        val r = classify("$TEST_BASE/appshell/other.html") as AppResult.Success
        // 无特征 URL；但 appshell/main 路由在 other.html 无路由 → 抓取 404 失败 → 仅按 URL 判定 → UNKNOWN
        assertEquals(PageKind.UNKNOWN, r.value.kind)
        assertEquals(Confidence.LOW, r.value.confidence)
        assertTrue(r.value.notes.any { it.contains("抓取失败") || it.contains("无") })
    }

    @Test
    fun `低于MEDIUM时kind记UNKNOWN`() = runBlocking {
        // 章节特征缺失 + 抓取失败 → UNKNOWN（契约 02 §5.1 注释）
        val r = classify("$TEST_BASE/zzz/none.html") as AppResult.Success
        assertEquals(PageKind.UNKNOWN, r.value.kind)
        assertEquals(Confidence.LOW, r.value.confidence)
    }

    @Test
    fun `站点规则与URL特征一致升HIGH`() = runBlocking {
        repo.put(
            SourceRule(
                ruleId = "site.v1",
                domain = "www.sample-novel.test",
                version = 1,
                ruleJson = """{"ruleId":"site.v1","domain":"www.sample-novel.test","version":1,"contentSelector":"#content"}""",
                enabled = true,
                createdAt = 0,
                updatedAt = 0,
            ),
        )
        val r = classify(SampleRoutes.CONTENT_PAGED_1) as AppResult.Success
        assertEquals(PageKind.CHAPTER, r.value.kind)
        assertEquals(Confidence.HIGH, r.value.confidence)
        assertEquals("site.v1", r.value.matchedRuleId)
    }

    @Test
    fun `分类结果携带规范化URL`() = runBlocking {
        val r = classify("HTTPS://WWW.SAMPLE-NOVEL.TEST/read/300/300_1.html?utm_source=x") as AppResult.Success
        assertEquals("$TEST_BASE/read/300/300_1.html", r.value.normalizedUrl)
    }

    @Test
    fun `分类的辅助抓取同样经SecureHttpClient并带伪装头`() = runBlocking {
        http.route("$TEST_BASE/p/123", sampleText("catalog-normal.html"))
        classify("$TEST_BASE/p/123")
        assertEquals(1, http.requests.size)
        val headers = http.requestHeaders.first()
        assertTrue(headers.containsKey("User-Agent"))
        assertTrue(!headers.containsKey("Cookie"))
    }
}
