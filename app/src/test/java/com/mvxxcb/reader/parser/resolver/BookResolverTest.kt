package com.mvxxcb.reader.parser.resolver

import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.domain.model.BookResolution
import com.mvxxcb.reader.domain.model.Confidence
import com.mvxxcb.reader.domain.model.PageKind
import com.mvxxcb.reader.parser.FixedTimeProvider
import com.mvxxcb.reader.parser.FakeSecureHttpClient
import com.mvxxcb.reader.parser.NoOpLogger
import com.mvxxcb.reader.parser.TestDispatcherProvider
import com.mvxxcb.reader.parser.TEST_BASE
import com.mvxxcb.reader.parser.buildClassifier
import com.mvxxcb.reader.parser.buildPipeline
import com.mvxxcb.reader.parser.sampleText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * BookResolver 契约 K8 单测（六输入矩阵 A-F + 回溯边界 + 面包屑截断 + 置信度语义 + 零 URL 猜测 + hint 回路）。
 * 全部经 FakeSecureHttpClient 内存路由，不触网；请求计数断言同时验证"无枚举扫描"红线。
 */
class BookResolverTest {

    private companion object {
        // resolver 专用路由（与 SampleRoutes 的 v1 路由隔离）
        const val R_DETAIL = "$TEST_BASE/resolver/book/2001.html"
        const val R_CATALOG = "$TEST_BASE/resolver/catalog.html"
        const val R_CH1 = "$TEST_BASE/resolver/read/ch1.html"
        const val R_C30 = "$TEST_BASE/resolver/read/c30.html"
        const val R_C29 = "$TEST_BASE/resolver/read/c29.html"
        const val R_C28 = "$TEST_BASE/resolver/read/c28.html"
        const val R_C27 = "$TEST_BASE/resolver/read/c27.html"
        const val R_C26 = "$TEST_BASE/resolver/read/c26.html"
        const val R_ISOLATED = "$TEST_BASE/resolver/read/isolated.html"
        const val R_FINALE = "$TEST_BASE/resolver/read/finale.html"
        const val R_PARAM = "$TEST_BASE/resolver/read/2001_1.html?font=2&from=share&v=9"

        /** R_PARAM 规范化后形态（from=share 为跟踪参数被去除）；resolver 按规范化 URL 抓取 */
        const val R_PARAM_NORMALIZED = "$TEST_BASE/resolver/read/2001_1.html?font=2&v=9"
        const val R_MULTI_CRUMB = "$TEST_BASE/resolver/read/multi.html"
        const val R_UNKNOWN = "$TEST_BASE/resolver/ambiguous?page=1"
        const val R_MISMATCH_HINT = "$TEST_BASE/resolver/mismatch-catalog.html"
        const val R_MATCH_HINT = "$TEST_BASE/resolver/hint/catalog.html"
        const val R_CHAIN_PREFIX = "$TEST_BASE/read/chain/p"
    }

    private lateinit var http: FakeSecureHttpClient

    @Before
    fun setUp() {
        http = FakeSecureHttpClient()
        installResolverRoutes(http)
    }

    private fun resolve(rawUrl: String, catalogHint: String? = null): AppResult<BookResolution> = runBlocking {
        BookResolverImpl(
            secureHttpClient = http,
            pipeline = buildPipeline(http),
            classifier = buildClassifier(http),
            dispatchers = TestDispatcherProvider(),
            timeProvider = FixedTimeProvider(),
            logger = NoOpLogger(),
        ).resolve(rawUrl, catalogHint)
    }

    // —————————————————————————————————— 路由与样本 ——————————————————————————————————

    private fun chainPage(title: String, prevUrl: String?, breadcrumb: Boolean): String {
        val crumb = if (breadcrumb) {
            """<div class="breadcrumb"><a href="/">首页</a> &gt; <a href="/resolver/book/2001.html">山巅之约</a> &gt; <a href="/resolver/catalog.html">目录</a> &gt; $title</div>"""
        } else {
            ""
        }
        val prev = prevUrl?.let { """<a href="$it">上一章</a>""" } ?: ""
        val body = (1..4).joinToString("") { i -> "<p>回溯链路填充段落第${i}段，用于页面文本占位，内容与断言无关但需要一定长度以贴近真实章节页形态。</p>" }
        return """
            <html><head><meta charset="utf-8"><title>$title - 山巅之约 - 范例书城</title></head><body>
            $crumb
            <div class="chapter-header"><h1>$title</h1>$prev</div>
            <div id="content">$body</div>
            </body></html>
        """.trimIndent()
    }

    private fun installResolverRoutes(http: FakeSecureHttpClient) {
        http.route(R_DETAIL, sampleText("resolver-detail-direct.html"))
        http.route(R_CATALOG, sampleText("resolver-catalog-direct.html"))
        http.route(R_CH1, sampleText("resolver-breadcrumb-catalog.html"))
        http.route(R_C30, sampleText("resolver-chapter-chain.html"))
        http.route(R_C29, chainPage("第29章 冰壁", R_C28, breadcrumb = false))
        http.route(R_C28, chainPage("第28章 营地", R_C27, breadcrumb = false))
        http.route(R_C27, chainPage("第27章 风口", R_C26, breadcrumb = false))
        http.route(R_C26, chainPage("第26章 云海", prevUrl = null, breadcrumb = true))
        http.route(R_ISOLATED, sampleText("resolver-no-entry.html"))
        http.route(R_FINALE, chainPage("第40章 峰顶之约", prevUrl = null, breadcrumb = true))
        http.route(R_PARAM_NORMALIZED, sampleText("resolver-breadcrumb-catalog.html"))
        http.route(R_MULTI_CRUMB, multiCrumbPage())
        http.route(R_UNKNOWN, ambiguousPage())
        http.route(R_MISMATCH_HINT, sampleText("resolver-mismatch-hint.html"))
        http.route(
            R_MATCH_HINT,
            """
            <html><head><meta charset="utf-8"><title>无名残卷 最新章节列表 - 范例书城</title></head><body>
            <div id="list"><dl>
              <dd><a href="/resolver/hint/ch/1.html">第1章 残卷</a></dd>
              <dd><a href="/resolver/hint/ch/2.html">第2章 渡口</a></dd>
              <dd><a href="/resolver/hint/ch/3.html">第3章 雨夜</a></dd>
              <dd><a href="/resolver/hint/ch/4.html">第4章 马蹄</a></dd>
              <dd><a href="/resolver/hint/ch/5.html">第5章 灯笼</a></dd>
            </dl></div>
            </body></html>
            """.trimIndent(),
        )
    }

    /** 8 个面包屑候选（全部为无效链接），验证 >6 截断 */
    private fun multiCrumbPage(): String {
        val anchors = (1..8).joinToString(" &gt; ") { i -> """<a href="/resolver/x/a$i.html">站点${'一' + i - 1}</a>""" }
        return """
            <html><head><meta charset="utf-8"><title>第9章 迷路 - 山巅之约 - 范例书城</title></head><body>
            <div class="breadcrumb">$anchors</div>
            <div id="content"><p>面包屑候选截断验证页面，正文占位。</p></div>
            </body></html>
        """.trimIndent()
    }

    /** 短小且无链接的歧义页（analyzePage 无法判定 → classify UNKNOWN） */
    private fun ambiguousPage(): String = """
        <html><head><meta charset="utf-8"><title>范例书城</title></head><body>
        <div>页面内容很短，既没有章节链接也没有书籍结构化数据，分类器无法判定页面类型。</div>
        </body></html>
    """.trimIndent()

    // —————————————————————————————————— 断言工具 ——————————————————————————————————

    private fun AppResult<BookResolution>.success(): BookResolution {
        assertTrue("期望 Success，实际 $this", this is AppResult.Success)
        return (this as AppResult.Success).value
    }

    private fun BookResolution.evidenceOf(type: String): List<String> =
        evidence.filter { it.startsWith("[$type]") }

    // —————————————————————————————————— 六输入矩阵 A-F ——————————————————————————————————

    @Test
    fun `A_详情页直入_结构化直证_HIGH_直取目录`() = runBlocking {
        val r = resolve(R_DETAIL).success()
        assertEquals(PageKind.BOOK_DETAIL, r.kind)
        assertEquals(R_DETAIL, r.detailUrl)
        assertEquals(R_CATALOG, r.catalogUrl)
        assertNotNull(r.detail)
        assertEquals("山巅之约", r.detail!!.title)
        assertNotNull(r.catalog)
        assertEquals(5, r.chapters.size)
        assertEquals("第1章 出发", r.chapters[0].title)
        // 置信度语义：直入 + 规则/结构化直证 → HIGH
        assertEquals(Confidence.HIGH, r.confidence)
        assertTrue(r.evidence.any { it.startsWith("[目录解析]") && it.contains("5 章") })
        assertFalseOf(r)
        assertEquals(2, http.requests.size) // 详情页 + 目录页，无多余请求
    }

    @Test
    fun `B_目录页直入_直接解析_通用层无规则为_MEDIUM`() = runBlocking {
        val r = resolve(R_CATALOG).success()
        assertEquals(PageKind.CATALOG, r.kind)
        assertEquals(R_CATALOG, r.catalogUrl)
        assertNull(r.detail) // 禁止猜测详情页
        assertNull(r.detailUrl)
        assertEquals(5, r.chapters.size)
        // 置信度语义：无规则/结构化直证 → 不得 HIGH；直入成功解析 → MEDIUM
        assertEquals(Confidence.MEDIUM, r.confidence)
        assertFalseOf(r)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun `C_首章输入_面包屑命中详情入口_锁定目录_MEDIUM`() = runBlocking {
        val r = resolve(R_CH1).success()
        assertEquals(PageKind.CHAPTER, r.kind)
        assertEquals(R_CH1, r.normalizedUrl)
        assertEquals(R_DETAIL, r.detailUrl)
        assertEquals(R_CATALOG, r.catalogUrl)
        assertEquals(5, r.chapters.size)
        // 置信度语义：面包屑回溯命中 → MEDIUM 封顶
        assertEquals(Confidence.MEDIUM, r.confidence)
        // 候选逐个 classify：首页（根链接 classify 抓取 404 → UNKNOWN 跳过）→ 山巅之约（BOOK_DETAIL 命中）
        assertEquals(2, r.evidenceOf("面包屑候选").size)
        assertTrue(r.evidenceOf("面包屑候选").any { it.contains("BOOK_DETAIL") })
        assertFalseOf(r)
        assertEquals(4, http.requests.size) // 章节页 + 首页候选 classify 抓取(404) + 详情 + 目录
    }

    @Test
    fun `D_中间章第30章_面包屑无果_上一章回溯至含面包屑页_命中`() = runBlocking {
        val r = resolve(R_C30).success()
        assertEquals(PageKind.CHAPTER, r.kind)
        assertEquals(R_CATALOG, r.catalogUrl)
        assertEquals(5, r.chapters.size)
        assertEquals(Confidence.MEDIUM, r.confidence)
        // 回溯链：c30 → c29 → c28 → c27 → c26（含面包屑）= 5 跳
        assertTrue(r.evidence.any { it.startsWith("[章节页]") && it.contains("第 5/30 跳") })
        assertEquals(5, r.evidenceOf("章节页").size)
        assertTrue(r.evidenceOf("面包屑").any { it.contains("候选 3 个") })
        assertFalseOf(r)
        assertEquals(8, http.requests.size) // 5 跳抓取 + 首页候选 classify 抓取(404) + 详情 + 目录
    }

    @Test
    fun `E_末章输入_面包屑命中_记录无下一章`() = runBlocking {
        val r = resolve(R_FINALE).success()
        assertEquals(PageKind.CHAPTER, r.kind)
        assertEquals(R_CATALOG, r.catalogUrl)
        assertEquals(Confidence.MEDIUM, r.confidence)
        assertTrue(r.evidenceOf("章节页").single().contains("下一章=无"))
        assertFalseOf(r)
        assertEquals(4, http.requests.size) // 章节页 + 首页候选 classify 抓取(404) + 详情 + 目录
    }

    @Test
    fun `F_复杂参数URL_规范化去跟踪参数后按章节回溯`() = runBlocking {
        val r = resolve(R_PARAM).success()
        // 规范化：from=share 为跟踪参数被去除
        assertEquals("$TEST_BASE/resolver/read/2001_1.html?font=2&v=9", r.normalizedUrl)
        assertTrue(r.evidenceOf("规范化").single().contains("font=2&v=9"))
        assertEquals(PageKind.CHAPTER, r.kind)
        assertEquals(R_CATALOG, r.catalogUrl)
        assertEquals(Confidence.MEDIUM, r.confidence)
        assertFalseOf(r)
        assertEquals(4, http.requests.size) // 章节页 + 首页候选 classify 抓取(404) + 详情 + 目录
    }

    // —————————————————————————————————— 回溯边界与候选截断 ——————————————————————————————————

    @Test
    fun `回溯边界_40页长链在预算内截断_requiresCatalogHint且不越界`() = runBlocking {
        // 40 页回溯链（/read 特征 URL，classify 不抓取）；预算 30 单位（输入 classify 1 + 每跳 1）
        for (i in 40 downTo 2) {
            http.route("$R_CHAIN_PREFIX$i.html", chainPage("第${41 - i}章 长链", "$R_CHAIN_PREFIX${i - 1}.html", breadcrumb = false))
        }
        val r = resolve("${R_CHAIN_PREFIX}40.html").success()
        assertTrue(r.requiresCatalogHint)
        assertEquals(Confidence.LOW, r.confidence)
        assertNull(r.catalogUrl)
        assertNull(r.detailUrl)
        // 有界：预算 30 单位 = 输入 classify 1 + 回溯抓取 29（未跑满 40 跳，未发生枚举式扫描）
        assertEquals(29, r.evidenceOf("章节页").size)
        assertTrue(r.evidence.any { it.contains("请求预算耗尽（maxTotalRequests）") })
        assertEquals(29, http.requests.size)
    }

    @Test
    fun `面包屑候选超过6个_截断为6个逐个验证`() = runBlocking {
        val r = resolve(R_MULTI_CRUMB).success()
        assertTrue(r.requiresCatalogHint) // 全部候选无效且无上一章 → 需要提示
        val tried = r.evidenceOf("面包屑候选")
        assertEquals(6, tried.size) // 8 个候选只验证前 6 个
        assertTrue(tried.none { it.contains("a7.html") || it.contains("a8.html") })
        assertTrue(r.evidenceOf("面包屑").any { it.contains("超出 6 截断") })
        assertEquals(7, http.requests.size) // 输入页 1 + 6 个候选 classify 抓取（全部 404 → UNKNOWN）
    }

    // —————————————————————————————————— 零 URL 猜测 ——————————————————————————————————

    @Test
    fun `无入口章节页_零URL猜测_只抓取输入页并转requiresCatalogHint`() = runBlocking {
        val r = resolve(R_ISOLATED).success()
        assertEquals(PageKind.CHAPTER, r.kind)
        assertTrue(r.requiresCatalogHint)
        assertEquals(Confidence.LOW, r.confidence)
        assertNull(r.detailUrl)
        assertNull(r.catalogUrl)
        assertNull(r.detail)
        assertNull(r.catalog)
        assertEquals(0, r.chapters.size)
        // 红线：无面包屑无上一章 → 绝不拼 URL/枚举扫描，全程只抓取了输入页本身
        assertEquals(listOf(R_ISOLATED), http.requests)
        assertTrue(r.evidenceOf("面包屑候选").isEmpty())
        assertTrue(r.evidenceOf("目录解析").isEmpty())
        assertTrue(r.evidence.any { it.contains("无可用上一章链接") })
        assertTrue(r.evidence.any { it.contains("需要用户补充目录页 URL") })
    }

    @Test
    fun `非法URL_返回Failure_INVALID_URL`() {
        val result = resolve("ht!tp://invalid url")
        assertTrue(result is AppResult.Failure)
        val error = (result as AppResult.Failure).error
        assertTrue(error is com.mvxxcb.reader.domain.error.ReaderError.Network)
        assertEquals(
            com.mvxxcb.reader.domain.error.NetworkFailure.INVALID_URL,
            (error as com.mvxxcb.reader.domain.error.ReaderError.Network).failure,
        )
    }

    // —————————————————————————————————— catalogHint 回路 ——————————————————————————————————

    @Test
    fun `hint_书名匹配_命中目录_MEDIUM`() = runBlocking {
        val r = resolve(R_ISOLATED, catalogHint = R_MATCH_HINT).success()
        assertEquals(R_MATCH_HINT, r.catalogUrl)
        assertEquals(5, r.chapters.size)
        // 置信度语义：hint 属用户补充回路（间接证据）+ 书名匹配 → MEDIUM，不得 HIGH
        assertEquals(Confidence.MEDIUM, r.confidence)
        assertTrue(r.evidenceOf("书名校验").single().contains("书名匹配"))
        assertTrue(r.evidenceOf("书名校验").single().contains("无名残卷"))
        assertFalseOf(r)
        assertEquals(3, http.requests.size) // 章节页 + 提示页书名信号 + 目录解析
    }

    @Test
    fun `hint_书名不符_降为LOW并给出warning_不静默接受`() = runBlocking {
        val r = resolve(R_ISOLATED, catalogHint = R_MISMATCH_HINT).success()
        assertEquals(R_MISMATCH_HINT, r.catalogUrl)
        assertEquals(5, r.chapters.size)
        assertEquals(Confidence.LOW, r.confidence)
        assertTrue(r.evidenceOf("书名校验").single().contains("书名不符"))
        assertTrue(r.evidenceOf("书名校验").single().contains("降为 LOW"))
        assertFalseOf(r) // 目录已按用户提示定位（不再要求补充），但置信度如实为 LOW
        assertEquals(3, http.requests.size)
    }

    @Test
    fun `hint_详情入口_书名不符_降LOW并随行detail`() = runBlocking {
        val r = resolve(R_ISOLATED, catalogHint = R_DETAIL).success()
        assertEquals(R_DETAIL, r.detailUrl)
        assertEquals(R_CATALOG, r.catalogUrl)
        assertEquals("山巅之约", r.detail?.title)
        assertEquals(Confidence.LOW, r.confidence)
        assertTrue(r.evidenceOf("书名校验").single().contains("书名不符"))
        assertEquals(3, http.requests.size) // 章节页 + 详情 + 目录
    }

    @Test
    fun `hint_非法URL_忽略并转requiresCatalogHint`() = runBlocking {
        val r = resolve(R_ISOLATED, catalogHint = "not a url").success()
        assertTrue(r.requiresCatalogHint)
        assertTrue(r.evidenceOf("提示验证").single().contains("非法"))
        assertEquals(1, http.requests.size) // 仅输入章节页
    }

    // —————————————————————————————————— UNKNOWN 低置信路径 ——————————————————————————————————

    @Test
    fun `UNKNOWN输入_低置信尝试回溯_失败即requiresCatalogHint`() = runBlocking {
        val r = resolve(R_UNKNOWN).success()
        assertEquals(PageKind.UNKNOWN, r.kind)
        assertTrue(r.requiresCatalogHint)
        assertEquals(Confidence.LOW, r.confidence)
        assertNull(r.catalogUrl)
        // classify 抓取辅助判定 1 次 + 回溯链抓取输入页 1 次，无任何额外枚举
        assertEquals(2, http.requests.size)
    }

    // —————————————————————————————————— 书名信号单元断言 ——————————————————————————————————

    @Test
    fun `书名信号_章节页title分段_剔除章节特征与站点末段`() {
        val resolver = BookResolverImpl(http, buildPipeline(http), buildClassifier(http), TestDispatcherProvider(), FixedTimeProvider(), NoOpLogger())
        assertEquals(
            listOf("星辰变体"),
            resolver.bookNameCandidatesFromTitle("第30章 雪线 - 星辰变体 - 范例书城"),
        )
        assertEquals(listOf("无名残卷"), resolver.bookNameCandidatesFromTitle("第7章 独行_无名残卷_范例书城"))
        assertEquals(emptyList<String>(), resolver.bookNameCandidatesFromTitle(null))
        assertEquals(emptyList<String>(), resolver.bookNameCandidatesFromTitle(""))
    }

    @Test
    fun `书名匹配_相等或包含为MATCH_无信号为UNVERIFIED_其余MISMATCH`() {
        val resolver = BookResolverImpl(http, buildPipeline(http), buildClassifier(http), TestDispatcherProvider(), FixedTimeProvider(), NoOpLogger())
        assertEquals(BookResolverImpl.NameMatch.MATCHED, resolver.matchBookName(listOf("星辰变体"), listOf("星辰变体 目录")))
        assertEquals(BookResolverImpl.NameMatch.MATCHED, resolver.matchBookName(listOf("无名残卷"), listOf("无名残卷 最新章节列表")))
        assertEquals(BookResolverImpl.NameMatch.MISMATCHED, resolver.matchBookName(listOf("无名残卷"), listOf("迷雾之城 全本目录")))
        assertEquals(BookResolverImpl.NameMatch.UNVERIFIED, resolver.matchBookName(emptyList(), listOf("星辰变体")))
        assertEquals(BookResolverImpl.NameMatch.UNVERIFIED, resolver.matchBookName(listOf("星辰变体"), emptyList()))
    }

    private fun assertFalseOf(r: BookResolution) {
        assertTrue("成功定位时 requiresCatalogHint 必须为 false", !r.requiresCatalogHint)
    }
}
