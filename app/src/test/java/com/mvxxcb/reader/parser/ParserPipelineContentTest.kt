package com.mvxxcb.reader.parser

import com.mvxxcb.reader.domain.error.ParseFailure
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.model.Confidence
import com.mvxxcb.reader.domain.model.ContentBlock
import com.mvxxcb.reader.parser.core.ParseOptions
import com.mvxxcb.reader.parser.core.ParseOutcome
import com.mvxxcb.reader.parser.core.ParseRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 正文解析：单页/分页合并/截断/广告清理/图片/死循环/误入下一章 */
class ParserPipelineContentTest {

    private lateinit var http: FakeSecureHttpClient
    private lateinit var repo: FakeSourceRuleRepository

    @Before
    fun setUp() {
        http = FakeSecureHttpClient()
        SampleRoutes.installAll(http)
        repo = FakeSourceRuleRepository()
    }

    private fun parse(url: String, options: ParseOptions = ParseOptions.DEFAULT) = runBlocking {
        buildPipeline(http, repo).parseChapterContent(ParseRequest(url, options))
    }

    private fun paragraphs(outcome: ParseOutcome<*>): List<String> =
        ((outcome as ParseOutcome.Success).data as com.mvxxcb.reader.domain.model.ParsedChapterContent)
            .blocks.filterIsInstance<ContentBlock.Paragraph>().map { it.text }

    @Test
    fun `单页正文_段落顺序与上下章`() = runBlocking {
        val outcome = parse(SampleRoutes.CONTENT_SINGLE)
        val s = outcome as ParseOutcome.Success
        val content = s.data
        assertEquals("第1章 山门初开", content.title)
        assertEquals(4, content.blocks.size)
        assertTrue(content.blocks.all { it is ContentBlock.Paragraph })
        assertTrue(paragraphs(outcome)[0].startsWith("晨雾未散"))
        assertNull(content.nextPageUrl)
        assertEquals("$TEST_BASE/book/1/2.html", content.nextChapterUrl)
        assertEquals("$TEST_BASE/book/1/0.html", content.previousChapterUrl)
        assertFalse(content.isTruncated)
        assertEquals(content.wordCount, content.blocks.sumOf { (it as ContentBlock.Paragraph).text.length })
        assertEquals(Confidence.MEDIUM, s.overallConfidence)
    }

    @Test
    fun `分页正文_两页合并且标题一致`() = runBlocking {
        val outcome = parse(SampleRoutes.CONTENT_PAGED_1)
        val s = outcome as ParseOutcome.Success
        val content = s.data
        assertEquals("第2章 试炼之塔", content.title)
        assertEquals(4, content.blocks.size) // 页1 两段（提示行被清理）+ 页2 两段
        val texts = paragraphs(outcome)
        assertTrue(texts.none { it.contains("本章未完") || it.contains("点击下一页") })
        assertTrue(texts[0].startsWith("试炼之塔立于外门深处"))
        assertTrue(texts[3].startsWith("走出塔门时"))
        assertNull(content.nextPageUrl) // 本章完
        assertEquals("$TEST_BASE/read/301/301_1.html", content.nextChapterUrl)
        assertFalse(content.isTruncated)
        assertEquals(2, http.requests.size)
    }

    @Test
    fun `分页上限_截断并保留下一页URL如实告知`() = runBlocking {
        val s = parse(SampleRoutes.CONTENT_PAGED_1, ParseOptions(maxContentPages = 1)) as ParseOutcome.Success
        assertTrue(s.data.isTruncated)
        assertEquals("$TEST_BASE/read/300/300_2.html", s.data.nextPageUrl)
        assertEquals(2, s.data.blocks.size)
        assertTrue(s.warnings.any { it.contains("上限") })
    }

    @Test
    fun `字符上限_截断标记`() = runBlocking {
        val s = parse(SampleRoutes.CONTENT_SINGLE, ParseOptions(maxContentChars = 50)) as ParseOutcome.Success
        assertTrue(s.data.isTruncated)
        assertNull(s.data.nextPageUrl) // 单页无下一页候选
    }

    @Test
    fun `广告密集正文_清理广告登录提示保留正文`() = runBlocking {
        val outcome = parse(SampleRoutes.CONTENT_ADHEAVY)
        val s = outcome as ParseOutcome.Success
        val texts = paragraphs(outcome)
        assertEquals(4, texts.size)
        assertTrue(texts.all { !it.contains("广告") && !it.contains("登录") && !it.contains("充值") })
        assertTrue(texts[0].startsWith("藏经阁分三层"))
    }

    @Test
    fun `正文含图片_图片块绝对地址与alt保序`() = runBlocking {
        val outcome = parse(SampleRoutes.CONTENT_WITH_IMAGES)
        val s = outcome as ParseOutcome.Success
        val blocks = s.data.blocks
        assertEquals(6, blocks.size)
        assertTrue(blocks[0] is ContentBlock.Paragraph)
        val img1 = blocks[1] as ContentBlock.Image
        assertEquals("$TEST_BASE/img/arena.jpg", img1.url)
        assertEquals("演武场全景", img1.alt)
        val img2 = blocks[3] as ContentBlock.Image
        assertEquals("$TEST_BASE/img/clash.jpg", img2.url)
        assertTrue(blocks[2] is ContentBlock.Paragraph)
    }

    @Test
    fun `死循环保护_下一页指向自身触发PAGINATION_LOOP_DETECTED`() = runBlocking {
        val outcome = parse(SampleRoutes.CHAPTER_LINK_LOOP)
        assertTrue(outcome is ParseOutcome.Failure)
        val err = (outcome as ParseOutcome.Failure).error
        assertTrue(err is ReaderError.Parse)
        assertEquals(ParseFailure.PAGINATION_LOOP_DETECTED, (err as ReaderError.Parse).failure)
    }

    @Test
    fun `误入下一章守卫_下一页实为下一章时停止合并并登记下一章`() = runBlocking {
        // 站点把下一章链接标成「下一页」：标题与本章不一致 → 停止合并，不吞掉下一章内容
        http.route(
            "$TEST_BASE/misc/m1.html",
            """
            <html><body>
            <h1>第8章 上山</h1>
            <div id="content"><p>上山的第一段内容，够长的正文文本以满足候选区判定的阈值要求，这里继续补充一些文字确保通过最小文本长度判定，风声掠过林梢，少年踏着碎石一路向上。</p><p>上山第二段内容同样需要计入文本长度，肩上的行囊轻得几乎感觉不到重量，只有腰间的短刀随着步伐轻轻晃动。</p></div>
            <div class="pages"><a href="/misc/next-chapter.html">下一页</a></div>
            </body></html>
            """.trimIndent(),
        )
        http.route(
            "$TEST_BASE/misc/next-chapter.html",
            """
            <html><body>
            <h1>第9章 下山</h1>
            <div id="content"><p>下山的第一段内容，属于下一章，绝不能被合并进上一章的正文块里，这里同样补充足够多的文字。</p></div>
            </body></html>
            """.trimIndent(),
        )
        val s = parse("$TEST_BASE/misc/m1.html") as ParseOutcome.Success
        val texts = paragraphs(s)
        assertEquals(2, texts.size) // 只含第8章两段
        assertTrue(texts.all { it.contains("上山") })
        assertEquals("$TEST_BASE/misc/next-chapter.html", s.data.nextChapterUrl) // 误链被登记为下一章
        assertNull(s.data.nextPageUrl)
        assertFalse(s.data.isTruncated)
        assertEquals(2, http.requests.size)
    }

    @Test
    fun `正文与目录严格区分_单页正文不产出章节列表噪音`() = runBlocking {
        // content-single 的上一章/下一章是导航链接，目录提取（nav 文本过滤）不应把它们当章节
        val catalog = runBlocking { buildPipeline(http, repo).parseCatalog(ParseRequest(SampleRoutes.CONTENT_SINGLE)) }
        // 正文页无 ≥2 个章节特征链接（导航已过滤）→ CATALOG_EMPTY
        assertTrue(catalog is ParseOutcome.Failure)
        assertEquals(ParseFailure.CATALOG_EMPTY, ((catalog as ParseOutcome.Failure).error as ReaderError.Parse).failure)
    }
}
