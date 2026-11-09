package com.mvxxcb.reader.parser.pipeline

import com.mvxxcb.reader.common.coroutine.DispatcherProvider
import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.domain.error.NetworkFailure
import com.mvxxcb.reader.domain.error.ParseFailure
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.repository.SourceRuleRepository
import com.mvxxcb.reader.parser.core.ParseOptions
import com.mvxxcb.reader.parser.generic.GenericSmartExtractor
import com.mvxxcb.reader.parser.manual.ManualRuleApplier
import com.mvxxcb.reader.parser.rule.DecodedPage
import com.mvxxcb.reader.parser.rule.RuleEngine
import com.mvxxcb.reader.parser.util.EncodingDetector
import com.mvxxcb.reader.parser.util.PaginationMerger
import com.mvxxcb.reader.parser.util.TextCleaner
import com.mvxxcb.reader.parser.util.UrlNormalizer
import com.mvxxcb.reader.security.ContentSanitizer
import com.mvxxcb.reader.security.FetchRequest
import com.mvxxcb.reader.security.SecureHttpClient
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** 任务内请求预算：所有网络请求（含分页遍历）计数 ≤ ParseOptions.maxTotalRequests（契约 02 §5.2） */
internal class RequestBudget(private val max: Int) {
    var used = 0
        private set

    /** 预检（分页循环用；实际扣减统一在 fetchPage.spend，避免双计数） */
    fun canSpend(): Boolean = used < max

    fun spend(): Boolean {
        if (used >= max) return false
        used++
        return true
    }
}

/** 单页抓取 + 解码结果（Jsoup Document 封装在 parser 包内，绝不出 parser） */
internal class PageData(
    val requestedUrl: String,
    val finalUrl: String,
    val document: Document,
    val decoded: DecodedPage,
) {
    val url: String get() = finalUrl
    val html: String get() = decoded.html
}

/**
 * 解析上下文：三个 ParseStep 共享的抓取/解码/清洗/分页设施。
 * 构造注入与契约 02 §5.2 注释一致（主 Agent 裁定追加 timeProvider）。
 */
internal class ParseContext(
    val secureHttpClient: SecureHttpClient,
    val ruleEngine: RuleEngine,
    val sourceRuleRepository: SourceRuleRepository,
    val contentSanitizer: ContentSanitizer,
    val dispatchers: DispatcherProvider,
    val logger: AppLogger,
) {
    companion object {
        private const val TAG = "ParserPipeline"

        /** 基础浏览器伪装（主 Agent 裁定 3：允许 UA/Referer；禁止 Cookie 会话/登录态/绕过访问控制） */
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    val structuredSuite = com.mvxxcb.reader.parser.structured.StructuredExtractorSuite()
    val generic = GenericSmartExtractor()
    val manualApplier = ManualRuleApplier(ruleEngine)
    val pagination = PaginationMerger()
    val cleaner = TextCleaner()

    /** ParseOptions 校验（上限全部 ≥1）；失败返回 State(INVALID_ARGUMENT) */
    fun validateOptions(options: ParseOptions): ReaderError? {
        val problems = mutableListOf<String>()
        if (options.maxCatalogPages < 1) problems += "maxCatalogPages 必须 ≥1"
        if (options.maxContentPages < 1) problems += "maxContentPages 必须 ≥1"
        if (options.maxTotalRequests < 1) problems += "maxTotalRequests 必须 ≥1"
        if (options.maxContentChars < 1) problems += "maxContentChars 必须 ≥1"
        if (problems.isEmpty()) return null
        return ReaderError.State(
            com.mvxxcb.reader.domain.error.StateFailure.INVALID_ARGUMENT,
            debugDetail = problems.joinToString("; "),
        )
    }

    /**
     * 统一抓取入口：全部网络请求经注入的 SecureHttpClient（DoD2，无自建 OkHttpClient）。
     * 失败返回原 ReaderError（不抛异常跨层）；成功完成解码并构建 DecodedPage + Jsoup Document。
     */
    suspend fun fetchPage(url: String, ruleEncoding: String?, budget: RequestBudget): AppResult<PageData> {
        if (!budget.spend()) {
            return AppResult.Failure(
                ReaderError.Parse(ParseFailure.TIMEOUT_BUDGET_EXCEEDED, debugDetail = "请求预算耗尽（maxTotalRequests）"),
            )
        }
        // Referer 基础伪装：scheme://host/（禁止 Cookie/登录态，主 Agent 裁定 3）
        val referer = try {
            val uri = java.net.URI(url)
            if (uri.scheme != null && uri.host != null) "${uri.scheme}://${uri.host}/" else null
        } catch (_: Exception) {
            null
        }
        val headers = buildMap {
            put("User-Agent", USER_AGENT)
            put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            put("Accept-Language", "zh-CN,zh;q=0.9")
            if (referer != null) put("Referer", referer)
        }
        logger.d(TAG, "fetch ${sanitizeUrl(url)}")
        val result = withContext(dispatchers.io) {
            secureHttpClient.fetch(FetchRequest(url = url, headers = headers))
        }
        val fetched = when (result) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> return AppResult.Failure(result.error)
        }
        if (fetched.bodyBytes.isEmpty()) {
            return AppResult.Failure(
                ReaderError.Network(NetworkFailure.EMPTY_RESPONSE, url = url, statusCode = fetched.statusCode),
            )
        }
        val decoded = withContext(dispatchers.default) {
            EncodingDetector.detectAndDecode(fetched.bodyBytes, fetched.declaredCharset, ruleEncoding)
        } ?: return AppResult.Failure(
            ReaderError.Parse(ParseFailure.ENCODING_FAILED, debugDetail = "编码探测与解码失败: ${sanitizeUrl(url)}"),
        )
        val document = withContext(dispatchers.default) {
            Jsoup.parse(decoded.html, fetched.finalUrl)
        }
        return AppResult.Success(
            PageData(
                requestedUrl = url,
                finalUrl = fetched.finalUrl,
                document = document,
                decoded = DecodedPage(
                    url = fetched.finalUrl,
                    html = decoded.html,
                    declaredCharset = fetched.declaredCharset,
                    detectedCharset = decoded.charset.name(),
                ),
            ),
        )
    }

    /** 域名（小写 host）；非法 URL 返回 null */
    fun hostOf(url: String): String? = UrlNormalizer.hostOf(url)

    /**
     * 加载站点规则（含人工规则）：loadRule 异常/无规则均返回 null（逐层失败不中断）。
     */
    suspend fun loadRuleSafely(domain: String?): com.mvxxcb.reader.parser.rule.ParsedSiteRule? {
        if (domain.isNullOrEmpty()) return null
        return try {
            ruleEngine.loadRule(domain)
        } catch (e: Exception) {
            logger.w(TAG, "loadRule 失败 domain=$domain", e)
            null
        }
    }

    /**
     * 动态页提示（一期不做 WebView → DYNAMIC_PAGE_REQUIRED）：
     * 全部层失败且页面具备脚本壳特征时使用。
     */
    fun pageLooksDynamic(page: PageData): Boolean = try {
        generic.pageLooksDynamic(page.document)
    } catch (_: Exception) {
        false
    }

    /** 日志脱敏：去掉 query（隐私最小化，01 §8） */
    fun sanitizeUrl(url: String): String = url.substringBefore('?')
}
