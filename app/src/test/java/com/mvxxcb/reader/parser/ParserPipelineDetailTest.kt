package com.mvxxcb.reader.parser

import com.mvxxcb.reader.domain.error.NetworkFailure
import com.mvxxcb.reader.domain.error.ParseFailure
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.model.Confidence
import com.mvxxcb.reader.domain.model.DetailField
import com.mvxxcb.reader.domain.model.ParseLayer
import com.mvxxcb.reader.domain.model.SourceRule
import com.mvxxcb.reader.parser.core.LayerOutcome
import com.mvxxcb.reader.parser.core.ParseOutcome
import com.mvxxcb.reader.parser.core.ParseRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 详情解析四层调度：JSON-LD / OG / meta / 内嵌JSON / GBK / 低置信度 / 动态页 / 人工规则 */
class ParserPipelineDetailTest {

    private lateinit var http: FakeSecureHttpClient
    private lateinit var repo: FakeSourceRuleRepository

    @Before
    fun setUp() {
        http = FakeSecureHttpClient()
        SampleRoutes.installAll(http)
        repo = FakeSourceRuleRepository()
    }

    private fun parse(url: String) = runBlocking {
        buildPipeline(http, repo).parseDetail(ParseRequest(url))
    }

    @Test
    fun `JSON-LD详情页_结构化层命中并混合通用层目录链接`() = runBlocking {
        val outcome = parse(SampleRoutes.DETAIL_JSONLD)
        assertTrue(outcome is ParseOutcome.Success)
        val s = outcome as ParseOutcome.Success
        val detail = s.data
        assertEquals("星辰变体", detail.title)
        assertEquals("李玄机", detail.author)
        assertEquals("https://www.sample-novel.test/img/cover-1001.jpg", detail.coverUrl)
        assertTrue(detail.description!!.contains("星辰之力"))
        assertEquals(ParseLayer.STRUCTURED, detail.fieldSources[DetailField.TITLE])
        assertEquals(ParseLayer.GENERIC, detail.fieldSources[DetailField.CATALOG_URL])
        assertEquals("https://www.sample-novel.test/book/1001/catalog.html", detail.catalogUrl)
        assertEquals(Confidence.HIGH, s.overallConfidence)
        assertTrue(s.layerTrace.any { it.layer == ParseLayer.STRUCTURED && it.outcome == LayerOutcome.APPLIED })
    }

    @Test
    fun `OG详情页_og与og-novel键命中`() = runBlocking {
        val outcome = parse(SampleRoutes.DETAIL_OG)
        val s = outcome as ParseOutcome.Success
        assertEquals("北冥渔歌", s.data.title)
        assertEquals("沈孤舟", s.data.author)
        assertEquals("https://www.sample-novel.test/img/cover-1002.jpg", s.data.coverUrl)
        assertEquals(Confidence.HIGH, s.overallConfidence)
    }

    @Test
    fun `meta-only详情页_title标签兜底为LOW并触发低置信度警示`() = runBlocking {
        val outcome = parse(SampleRoutes.DETAIL_META_ONLY)
        val s = outcome as ParseOutcome.Success
        assertEquals("无声之誓", s.data.title)
        assertEquals("顾南山", s.data.author)
        assertEquals(Confidence.LOW, s.data.fieldConfidence[DetailField.TITLE])
        // 整体置信度 = 关键字段（书名）min = LOW < 阈值 MEDIUM → 判 LOW + 警示（ParseOutcome 携带，UI 可展示）
        assertEquals(Confidence.LOW, s.overallConfidence)
        assertTrue(s.warnings.any { it.contains("置信度") })
    }

    @Test
    fun `GBK详情页_真实GBK字节经meta探测正确解码`() = runBlocking {
        val outcome = parse(SampleRoutes.DETAIL_GBK)
        val s = outcome as ParseOutcome.Success
        assertEquals("灵剑传说", s.data.title)
        assertEquals("王小明", s.data.author)
        assertEquals(Confidence.LOW, s.overallConfidence) // title 来自 title 标签兜底
    }

    @Test
    fun `内嵌JSON详情页_提取name与author`() = runBlocking {
        val outcome = parse(SampleRoutes.DETAIL_EMBEDDED_JSON)
        val s = outcome as ParseOutcome.Success
        assertEquals("墨色山河", s.data.title)
        assertEquals("陈青临", s.data.author)
        assertEquals("https://www.sample-novel.test/img/cover-1005.jpg", s.data.coverUrl)
        assertEquals(Confidence.MEDIUM, s.overallConfidence)
    }

    @Test
    fun `动态渲染壳页_报DYNAMIC_PAGE_REQUIRED`() = runBlocking {
        val outcome = parse(SampleRoutes.DYNAMIC_SHELL)
        assertTrue(outcome is ParseOutcome.Failure)
        val err = (outcome as ParseOutcome.Failure).error
        assertTrue(err is ReaderError.Parse)
        assertEquals(ParseFailure.DYNAMIC_PAGE_REQUIRED, (err as ReaderError.Parse).failure)
    }

    @Test
    fun `抓取失败透传网络错误`() = runBlocking {
        val outcome = parse("$TEST_BASE/book/missing.html")
        assertTrue(outcome is ParseOutcome.Failure)
        val err = (outcome as ParseOutcome.Failure).error
        assertTrue(err is ReaderError.Network)
        assertEquals(NetworkFailure.HTTP_ERROR_STATUS, (err as ReaderError.Network).failure)
        // 失败前只发起过 1 次请求
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `人工规则层0优先且字段来源为MANUAL`() = runBlocking {
        val manualHtml = """
            <html><body><h1 id="book-title">人工书名</h1><span class="writer">某人</span></body></html>
        """.trimIndent()
        http.route("$TEST_BASE/book/manual.html", manualHtml)
        repo.put(
            SourceRule(
                ruleId = "manual.www.sample-novel.test",
                domain = "www.sample-novel.test",
                version = 1,
                ruleJson = """{"ruleId":"manual.www.sample-novel.test","domain":"www.sample-novel.test","version":1,"bookNameSelector":"#book-title","authorSelector":".writer"}""",
                enabled = true,
                createdAt = 0,
                updatedAt = 0,
            ),
        )
        val outcome = parse("$TEST_BASE/book/manual.html")
        val s = outcome as ParseOutcome.Success
        assertEquals("人工书名", s.data.title)
        assertEquals("某人", s.data.author)
        assertEquals(ParseLayer.MANUAL, s.data.fieldSources[DetailField.TITLE])
        assertEquals(ParseLayer.MANUAL, s.data.fieldSources[DetailField.AUTHOR])
        assertTrue(s.layerTrace.first { it.layer == ParseLayer.MANUAL }.outcome == LayerOutcome.APPLIED)
    }

    @Test
    fun `ParseOptions非法上限报INVALID_ARGUMENT`() = runBlocking {
        val bad = buildPipeline(http, repo).parseDetail(
            ParseRequest(SampleRoutes.DETAIL_JSONLD, options = com.mvxxcb.reader.parser.core.ParseOptions(maxTotalRequests = 0)),
        )
        assertTrue(bad is ParseOutcome.Failure)
        val err = (bad as ParseOutcome.Failure).error
        assertTrue(err is ReaderError.State)
        assertEquals(com.mvxxcb.reader.domain.error.StateFailure.INVALID_ARGUMENT, (err as ReaderError.State).failure)
    }

    @Test
    fun `请求数与请求头_UA与Referer伪装且无Cookie`() = runBlocking {
        parse(SampleRoutes.DETAIL_JSONLD)
        assertEquals(1, http.requests.size)
        val headers = http.requestHeaders.first()
        assertTrue(headers.containsKey("User-Agent"))
        assertEquals("https://www.sample-novel.test/", headers["Referer"])
        assertTrue(!headers.containsKey("Cookie"))
    }
}
