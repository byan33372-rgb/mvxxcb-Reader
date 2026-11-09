package com.mvxxcb.reader.parser

import com.mvxxcb.reader.common.coroutine.DispatcherProvider
import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.common.time.TimeProvider
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.model.SourceRule
import com.mvxxcb.reader.domain.repository.RuleImportMode
import com.mvxxcb.reader.domain.repository.RuleImportReport
import com.mvxxcb.reader.domain.repository.SourceRuleRepository
import com.mvxxcb.reader.parser.rule.RuleEngineImpl
import com.mvxxcb.reader.parser.pipeline.ParserPipelineImpl
import com.mvxxcb.reader.security.ContentSanitizer
import com.mvxxcb.reader.security.FetchRequest
import com.mvxxcb.reader.security.FetchResult
import com.mvxxcb.reader.security.SecureHttpClient
import com.mvxxcb.reader.security.SanitizedFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/** 测试基址（样本内链接均指向该域） */
const val TEST_BASE = "https://www.sample-novel.test"

/** 加载 04 §7 固定样本（app/src/test/resources/html-samples/） */
fun sampleBytes(name: String): ByteArray =
    checkNotNull(FakeSecureHttpClient::class.java.classLoader.getResourceAsStream("html-samples/$name")) {
        "缺少样本文件 html-samples/$name"
    }.readBytes()

fun sampleText(name: String): String = sampleBytes(name).toString(Charsets.UTF_8)

// ———————————————————————— 网络假实现 ————————————————————————

/**
 * 假 SecureHttpClient：URL → 字节路由；不触网。
 * 统计请求数与请求序列（验证 parser 只经 SecureHttpClient 出网、预算约束）。
 */
class FakeSecureHttpClient : SecureHttpClient {
    val routes = mutableMapOf<String, ByteArray>()
    val declaredCharsets = mutableMapOf<String, String?>()
    val failures = mutableMapOf<String, ReaderError>()
    val requests = mutableListOf<String>()
    val requestHeaders = mutableListOf<Map<String, String>>()

    fun route(url: String, html: String, declaredCharset: String? = null) {
        routes[url] = html.toByteArray(Charsets.UTF_8)
        declaredCharsets[url] = declaredCharset
    }

    fun routeBytes(url: String, bytes: ByteArray, declaredCharset: String? = null) {
        routes[url] = bytes
        declaredCharsets[url] = declaredCharset
    }

    override suspend fun fetch(request: FetchRequest): AppResult<FetchResult> {
        requests += request.url
        requestHeaders += request.headers
        failures[request.url]?.let { return AppResult.Failure(it) }
        val bytes = routes[request.url]
            ?: return AppResult.Failure(
                ReaderError.Network(
                    com.mvxxcb.reader.domain.error.NetworkFailure.HTTP_ERROR_STATUS,
                    url = request.url,
                    statusCode = 404,
                    debugDetail = "FakeSecureHttpClient 无路由: ${request.url}",
                ),
            )
        return AppResult.Success(
            FetchResult(
                finalUrl = request.url,
                statusCode = 200,
                headers = emptyMap(),
                bodyBytes = bytes,
                contentType = "text/html",
                declaredCharset = declaredCharsets[request.url],
                elapsedMs = 1,
                redirectChain = listOf(request.url),
            ),
        )
    }
}

// ———————————————————————— 仓储假实现 ————————————————————————

class FakeSourceRuleRepository : SourceRuleRepository {
    private val map = linkedMapOf<String, SourceRule>()

    fun put(rule: SourceRule) {
        map[rule.ruleId] = rule
    }

    override fun observeAll(): Flow<List<SourceRule>> =
        MutableStateFlow(map.values.sortedBy { it.domain })

    override suspend fun getEnabledRule(domain: String): SourceRule? =
        map.values.firstOrNull { it.domain == domain && it.enabled }

    override suspend fun getRuleByDomain(domain: String): SourceRule? =
        map.values.firstOrNull { it.domain == domain }

    override suspend fun upsertRule(rule: SourceRule): AppResult<Unit> {
        map[rule.ruleId] = rule
        return AppResult.Success(Unit)
    }

    override suspend fun setEnabled(ruleId: String, enabled: Boolean): AppResult<Unit> {
        map[ruleId]?.let { map[ruleId] = it.copy(enabled = enabled) }
        return AppResult.Success(Unit)
    }

    override suspend fun deleteRule(ruleId: String): AppResult<Unit> {
        map.remove(ruleId)
        return AppResult.Success(Unit)
    }

    override suspend fun exportAll(): String = "[]"

    override suspend fun importRules(json: String, mode: RuleImportMode): AppResult<RuleImportReport> =
        AppResult.Success(RuleImportReport(0, 0, emptyList()))
}

// ———————————————————————— 净化假实现 ————————————————————————

/**
 * 假 ContentSanitizer：剥离 script/style/iframe 后按文档顺序构建段落/图片块
 * （真实实现由 WP-S 提供；此处仅承担测试替身职责）。
 */
class FakeContentSanitizer : ContentSanitizer {
    var lastRemovedCount = 0
        private set

    override fun sanitizeContentFragment(html: String): SanitizedFragment {
        if (html.isBlank()) return SanitizedFragment(emptyList(), 0)
        val body = Jsoup.parseBodyFragment(html).body()
        val dangerous = body.select("script,style,iframe,noscript")
        val removed = dangerous.size
        dangerous.remove()
        val blocks = mutableListOf<com.mvxxcb.reader.domain.model.ContentBlock>()
        val buf = StringBuilder()

        fun flush() {
            val t = buf.toString().trim()
            if (t.isNotEmpty()) blocks += com.mvxxcb.reader.domain.model.ContentBlock.Paragraph(t)
            buf.setLength(0)
        }

        fun walk(node: org.jsoup.nodes.Node) {
            when (node) {
                is TextNode -> buf.append(node.text())
                is Element -> when (node.tagName().lowercase()) {
                    "br" -> flush()
                    "img" -> {
                        flush()
                        val url = node.absUrl("src").ifEmpty { node.attr("src") }
                        if (url.isNotEmpty()) {
                            blocks += com.mvxxcb.reader.domain.model.ContentBlock.Image(url, node.attr("alt").ifBlank { null })
                        }
                    }
                    "p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote" -> {
                        flush()
                        node.childNodes().forEach { walk(it) }
                        flush()
                    }
                    "script", "style", "iframe", "noscript" -> Unit
                    else -> node.childNodes().forEach { walk(it) }
                }
                else -> Unit
            }
        }
        body.childNodes().forEach { walk(it) }
        flush()
        lastRemovedCount = removed
        return SanitizedFragment(blocks, removed)
    }
}

// ———————————————————————— 基础件假实现 ————————————————————————

class NoOpLogger : AppLogger {
    val warnings = mutableListOf<String>()
    override fun d(tag: String, message: String) = Unit
    override fun i(tag: String, message: String) = Unit
    override fun w(tag: String, message: String, throwable: Throwable?) {
        warnings += message
    }
    override fun e(tag: String, message: String, throwable: Throwable?) = Unit
    override fun logError(error: ReaderError, context: String) = Unit
}

class FixedTimeProvider(private var now: Long = 1_700_000_000_000L) : TimeProvider {
    override fun nowMillis(): Long = now
    fun advance(ms: Long) {
        now += ms
    }
}

/** 测试调度器：全部 Unconfined（抓取为内存假实现，无真实 IO） */
class TestDispatcherProvider : DispatcherProvider {
    override val main: kotlinx.coroutines.CoroutineDispatcher get() = Dispatchers.Unconfined
    override val io: kotlinx.coroutines.CoroutineDispatcher get() = Dispatchers.Unconfined
    override val default: kotlinx.coroutines.CoroutineDispatcher get() = Dispatchers.Unconfined
}

// ———————————————————————— 组装 ————————————————————————

/** 组装被测管线（真实 RuleEngine/TextCleaner/分页器 + 假网络/仓储/净化） */
fun buildPipeline(
    fakeHttp: FakeSecureHttpClient,
    repo: FakeSourceRuleRepository = FakeSourceRuleRepository(),
    sanitizer: ContentSanitizer = FakeContentSanitizer(),
): ParserPipelineImpl =
    ParserPipelineImpl(
        secureHttpClient = fakeHttp,
        ruleEngine = RuleEngineImpl(repo, sanitizer),
        sourceRuleRepository = repo,
        contentSanitizer = sanitizer,
        dispatchers = TestDispatcherProvider(),
        timeProvider = FixedTimeProvider(),
        logger = NoOpLogger(),
    )

/** 组装分类器 */
fun buildClassifier(
    fakeHttp: FakeSecureHttpClient,
    repo: FakeSourceRuleRepository = FakeSourceRuleRepository(),
): com.mvxxcb.reader.parser.core.UrlClassifierImpl =
    com.mvxxcb.reader.parser.core.UrlClassifierImpl(
        secureHttpClient = fakeHttp,
        ruleEngine = RuleEngineImpl(repo, FakeContentSanitizer()),
        dispatchers = TestDispatcherProvider(),
        logger = NoOpLogger(),
    )

/** 常用 URL 映射（04 §7 样本 ↔ 假站路由） */
object SampleRoutes {
    const val DETAIL_JSONLD = "$TEST_BASE/book/1001.html"
    const val DETAIL_OG = "$TEST_BASE/book/1002.html"
    const val DETAIL_META_ONLY = "$TEST_BASE/book/1003.html"
    const val DETAIL_GBK = "$TEST_BASE/book/1004.html"
    const val DETAIL_EMBEDDED_JSON = "$TEST_BASE/book/1005.html"
    const val CATALOG_NORMAL = "$TEST_BASE/catalog/normal.html"
    const val CATALOG_REVERSED = "$TEST_BASE/catalog/reversed.html"
    const val CATALOG_PAGED_1 = "$TEST_BASE/catalog/paged/1.html"
    const val CATALOG_PAGED_2 = "$TEST_BASE/catalog/paged/2.html"
    const val CATALOG_WITH_ADS = "$TEST_BASE/catalog/ads.html"
    const val CATALOG_VOLUME = "$TEST_BASE/catalog/volume.html"
    const val CATALOG_FILTER_SIDEBAR = "$TEST_BASE/novel/5544/catalog"
    const val CONTENT_SINGLE = "$TEST_BASE/book/1/1.html"
    const val CONTENT_PAGED_1 = "$TEST_BASE/read/300/300_1.html"
    const val CONTENT_PAGED_2 = "$TEST_BASE/read/300/300_2.html"
    const val CONTENT_ADHEAVY = "$TEST_BASE/read/400/400.html"
    const val CONTENT_WITH_IMAGES = "$TEST_BASE/read/500/500.html"
    const val DYNAMIC_SHELL = "$TEST_BASE/appshell/main.html"
    const val CHAPTER_LINK_LOOP = "$TEST_BASE/read/600/600.html"

    /** 全量装载（GBK 样本 declaredCharset 置空以走 meta 探测链） */
    fun installAll(http: FakeSecureHttpClient) {
        http.route(DETAIL_JSONLD, sampleText("detail-jsonld.html"))
        http.route(DETAIL_OG, sampleText("detail-og.html"))
        http.route(DETAIL_META_ONLY, sampleText("detail-meta-only.html"))
        http.routeBytes(DETAIL_GBK, sampleBytes("detail-gbk.html"), declaredCharset = null)
        http.route(DETAIL_EMBEDDED_JSON, sampleText("detail-embedded-json.html"))
        http.route(CATALOG_NORMAL, sampleText("catalog-normal.html"))
        http.route(CATALOG_REVERSED, sampleText("catalog-reversed.html"))
        http.route(CATALOG_PAGED_1, sampleText("catalog-paged-1.html"))
        http.route(CATALOG_PAGED_2, sampleText("catalog-paged-2.html"))
        http.route(CATALOG_WITH_ADS, sampleText("catalog-with-ads.html"))
        http.route(CATALOG_VOLUME, sampleText("catalog-volume.html"))
        http.route(CATALOG_FILTER_SIDEBAR, sampleText("catalog-with-filter-sidebar.html"))
        http.route(CONTENT_SINGLE, sampleText("content-single.html"))
        http.route(CONTENT_PAGED_1, sampleText("content-paged-1.html"))
        http.route(CONTENT_PAGED_2, sampleText("content-paged-2.html"))
        http.route(CONTENT_ADHEAVY, sampleText("content-adheavy.html"))
        http.route(CONTENT_WITH_IMAGES, sampleText("content-with-images.html"))
        http.route(DYNAMIC_SHELL, sampleText("dynamic-shell.html"))
        http.route(CHAPTER_LINK_LOOP, sampleText("chapter-link-loop.html"))
    }
}
