package com.mvxxcb.reader.parser

import com.mvxxcb.reader.domain.error.ParseFailure
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.model.Confidence
import com.mvxxcb.reader.domain.model.SourceRule
import com.mvxxcb.reader.parser.core.ParseOptions
import com.mvxxcb.reader.parser.core.ParseOutcome
import com.mvxxcb.reader.parser.core.ParseRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 目录解析：正序/倒序/分页合并/广告过滤/分卷/上限截断/预算截断/死循环保护/目录正文区分 */
class ParserPipelineCatalogTest {

    private lateinit var http: FakeSecureHttpClient
    private lateinit var repo: FakeSourceRuleRepository

    @Before
    fun setUp() {
        http = FakeSecureHttpClient()
        SampleRoutes.installAll(http)
        repo = FakeSourceRuleRepository()
    }

    private fun parse(url: String, options: ParseOptions = ParseOptions.DEFAULT) = runBlocking {
        buildPipeline(http, repo).parseCatalog(ParseRequest(url, options))
    }

    @Test
    fun `正序目录_五章节按站点顺序`() = runBlocking {
        val outcome = parse(SampleRoutes.CATALOG_NORMAL)
        val s = outcome as ParseOutcome.Success
        val catalog = s.data
        assertEquals(5, catalog.chapters.size)
        assertEquals(listOf(0, 1, 2, 3, 4), catalog.chapters.map { it.order })
        assertEquals("第1章 山门初开", catalog.chapters[0].title)
        assertEquals("$TEST_BASE/book/1/1.html", catalog.chapters[0].url)
        assertFalse(catalog.isLikelyReversed)
        assertEquals(1, catalog.paginationTraversed)
        assertFalse(catalog.truncatedByLimit)
        assertEquals(Confidence.MEDIUM, s.overallConfidence)
    }

    @Test
    fun `倒序目录_检测标记不中断`() = runBlocking {
        val s = parse(SampleRoutes.CATALOG_REVERSED) as ParseOutcome.Success
        assertTrue(s.data.isLikelyReversed)
        // 保留站点目录真实顺序（第5章在前），合并交由 UI 的 CatalogSort
        assertEquals("第5章 万里归航", s.data.chapters[0].title)
        assertEquals(5, s.data.chapters.size)
    }

    @Test
    fun `分页目录_两页合并且不回访上一页`() = runBlocking {
        val s = parse(SampleRoutes.CATALOG_PAGED_1) as ParseOutcome.Success
        val catalog = s.data
        assertEquals(6, catalog.chapters.size)
        assertEquals("第1章 琴台夜雨", catalog.chapters[0].title)
        assertEquals("第6章 誓约之终", catalog.chapters[5].title)
        assertEquals(2, catalog.paginationTraversed)
        assertFalse(catalog.truncatedByLimit)
        // page2 的「上一页」未被打包成章节，也没有重复章节
        assertEquals(6, catalog.chapters.map { it.url }.toSet().size)
    }

    @Test
    fun `广告目录_广告与页脚链接被过滤`() = runBlocking {
        val s = parse(SampleRoutes.CATALOG_WITH_ADS) as ParseOutcome.Success
        val chapters = s.data.chapters
        assertEquals(5, chapters.size)
        assertTrue(chapters.all { !it.url.contains("/ad/") && !it.url.contains("/promote/") })
        assertTrue(chapters.all { it.title.startsWith("第") })
    }

    @Test
    fun `分卷目录_卷名进volumeTitle`() = runBlocking {
        val s = parse(SampleRoutes.CATALOG_VOLUME) as ParseOutcome.Success
        val chapters = s.data.chapters
        assertEquals(4, chapters.size)
        assertEquals("第一卷 灵剑山", chapters[0].volumeTitle)
        assertEquals("第一卷 灵剑山", chapters[1].volumeTitle)
        assertEquals("第二卷 下山行", chapters[2].volumeTitle)
        assertEquals("第二卷 下山行", chapters[3].volumeTitle)
    }

    @Test
    fun `目录分页上限_截断并标记truncatedByLimit`() = runBlocking {
        val s = parse(SampleRoutes.CATALOG_PAGED_1, ParseOptions(maxCatalogPages = 1)) as ParseOutcome.Success
        assertTrue(s.data.truncatedByLimit)
        assertEquals(1, s.data.paginationTraversed)
        assertEquals(3, s.data.chapters.size)
        assertTrue(s.warnings.any { it.contains("上限") })
    }

    @Test
    fun `请求预算耗尽_目录截断不失败`() = runBlocking {
        val s = parse(SampleRoutes.CATALOG_PAGED_1, ParseOptions(maxTotalRequests = 1)) as ParseOutcome.Success
        assertTrue(s.data.truncatedByLimit)
        assertEquals(1, s.data.paginationTraversed)
        assertEquals(3, s.data.chapters.size)
        assertTrue(s.warnings.any { it.contains("预算") })
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `分页死循环保护_触发PAGINATION_LOOP_DETECTED`() = runBlocking {
        // chapter-link-loop.html 的「下一页」指向自身
        val outcome = parse(SampleRoutes.CHAPTER_LINK_LOOP)
        assertTrue(outcome is ParseOutcome.Failure)
        val err = (outcome as ParseOutcome.Failure).error
        assertTrue(err is ReaderError.Parse)
        assertEquals(ParseFailure.PAGINATION_LOOP_DETECTED, (err as ReaderError.Parse).failure)
    }

    @Test
    fun `动态壳页解析目录_报DYNAMIC_PAGE_REQUIRED`() = runBlocking {
        val outcome = parse(SampleRoutes.DYNAMIC_SHELL)
        assertTrue(outcome is ParseOutcome.Failure)
        assertEquals(ParseFailure.DYNAMIC_PAGE_REQUIRED, ((outcome as ParseOutcome.Failure).error as ReaderError.Parse).failure)
    }

    @Test
    fun `allowPaginationMerge关闭_仅取首页`() = runBlocking {
        val s = parse(
            SampleRoutes.CATALOG_PAGED_1,
            ParseOptions(allowPaginationMerge = false),
        ) as ParseOutcome.Success
        assertEquals(3, s.data.chapters.size)
        assertEquals(1, s.data.paginationTraversed)
        assertFalse(s.data.truncatedByLimit)
    }

    // ———— 阶段 7 验收缺陷回归：linovelib 型悬浮筛选栏污染 ————

    @Test
    fun `筛选栏污染目录_仅命中真实章节链接且分卷锚不入库`() = runBlocking {
        // catalog-with-filter-sidebar.html：/wenku/ 筛选栏 12 链接 + 面包屑 + 1 分卷锚 + 20 章节
        val s = parse(SampleRoutes.CATALOG_FILTER_SIDEBAR) as ParseOutcome.Success
        val catalog = s.data
        // 21 个真实 /novel/5544/ 链接中：分卷锚 vol_346133.html 不作章节 → 章节恰为 20
        assertEquals(20, catalog.chapters.size)
        // 零 /wenku/ 污染、零分卷锚、全部位于书路径段 /novel/5544/ 下
        assertTrue(catalog.chapters.all { !it.url.contains("/wenku/") })
        assertTrue(catalog.chapters.all { !it.url.contains("vol_") })
        assertTrue(catalog.chapters.all { it.url.startsWith("$TEST_BASE/novel/5544/") })
        // 文档顺序与分卷语义：分卷锚文本成为后续章节 volumeTitle
        assertEquals("插图", catalog.chapters[0].title)
        assertEquals("第一卷 落第骑士的英谭", catalog.chapters[0].volumeTitle)
        assertEquals("第十八话 启程之日", catalog.chapters[19].title)
        assertEquals("第一卷 落第骑士的英谭", catalog.chapters[19].volumeTitle)
        assertEquals(listOf(0, 1, 2, 3, 4), catalog.chapters.take(5).map { it.order })
        assertFalse(catalog.isLikelyReversed)
        assertEquals(1, catalog.paginationTraversed)
        assertFalse(catalog.truncatedByLimit)
        assertEquals(Confidence.MEDIUM, s.overallConfidence)
    }

    @Test
    fun `筛选栏污染目录_站点规则宽选择器同样过滤`() = runBlocking {
        // 层②：用户规则用最宽选择器（catalogSelector=body, chapterLinkSelector=a）
        // ——同域/非章节路径段硬排除/书路径段聚类/分卷锚识别必须同样生效
        repo.put(
            SourceRule(
                ruleId = "sample-broad.v1",
                domain = "www.sample-novel.test",
                version = 1,
                ruleJson = """{"ruleId":"sample-broad.v1","domain":"www.sample-novel.test","version":1,"catalogSelector":"body","chapterLinkSelector":"a"}""",
                enabled = true,
                createdAt = 0,
                updatedAt = 0,
            ),
        )
        val s = parse(SampleRoutes.CATALOG_FILTER_SIDEBAR) as ParseOutcome.Success
        val catalog = s.data
        assertEquals(20, catalog.chapters.size)
        assertTrue(catalog.chapters.all { !it.url.contains("/wenku/") && !it.url.contains("vol_") })
        assertTrue(catalog.chapters.all { it.url.startsWith("$TEST_BASE/novel/5544/") })
        assertEquals("插图", catalog.chapters[0].title)
        assertEquals("第一卷 落第骑士的英谭", catalog.chapters[0].volumeTitle)
    }
}
