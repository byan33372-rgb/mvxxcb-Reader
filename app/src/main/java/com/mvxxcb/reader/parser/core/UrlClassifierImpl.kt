package com.mvxxcb.reader.parser.core

import com.mvxxcb.reader.common.coroutine.DispatcherProvider
import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.domain.error.NetworkFailure
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.model.Confidence
import com.mvxxcb.reader.domain.model.PageKind
import com.mvxxcb.reader.domain.model.UrlClassification
import com.mvxxcb.reader.parser.pipeline.RequestBudget
import com.mvxxcb.reader.parser.rule.RuleEngine
import com.mvxxcb.reader.parser.util.UrlNormalizer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 网址分类实现（契约 02 §5.1）：
 * 1. URL 形态启发式（无网络）；
 * 2. 站点规则提示（该域有 contentSelector → 章节页 / catalogSelector → 目录页 / bookNameSelector → 详情页）；
 * 3. 置信度 < MEDIUM 时抓取页面辅助判定（最多 1 次请求，抓取失败仅记 notes 降级）；
 * 4. 最终置信度 < MEDIUM → kind = UNKNOWN（契约注释：低于 MEDIUM 时 kind 允许为 UNKNOWN）。
 *
 * classify 是 suspend；抓取经注入的 SecureHttpClient（任务内请求计数=1，远低于 maxTotalRequests）。
 */
class UrlClassifierImpl(
    private val secureHttpClient: com.mvxxcb.reader.security.SecureHttpClient,
    private val ruleEngine: RuleEngine,
    private val dispatchers: DispatcherProvider,
    private val logger: AppLogger,
) : UrlClassifier {

    private companion object {
        const val TAG = "UrlClassifier"

        val CHAPTER_URL_RE = Regex("(?:/read|/chapter|/book/\\d+/\\d+)|\\d+_\\d+\\.x?html|/\\d+\\.x?html\\?", RegexOption.IGNORE_CASE)
        val CATALOG_URL_RE = Regex("(?:catalog|chapterlist|chapters|/list|/all|/mulu|/bookcase)", RegexOption.IGNORE_CASE)
        val DETAIL_URL_RE = Regex("(?:/book/|/novel/|/xiaoshuo/|/info/|/txt/)", RegexOption.IGNORE_CASE)
    }

    override suspend fun classify(rawUrl: String): AppResult<UrlClassification> {
        val normalized = UrlNormalizer.normalize(rawUrl)
            ?: return AppResult.Failure(
                ReaderError.Network(NetworkFailure.INVALID_URL, url = rawUrl, statusCode = null),
            )
        val host = UrlNormalizer.hostOf(normalized)
        val notes = mutableListOf<String>()

        // 1) URL 形态启发式
        var kind: PageKind? = null
        var confidence = Confidence.LOW
        val chapterHit = CHAPTER_URL_RE.containsMatchIn(normalized)
        val catalogHit = CATALOG_URL_RE.containsMatchIn(normalized)
        val detailHit = DETAIL_URL_RE.containsMatchIn(normalized)
        when {
            chapterHit -> { kind = PageKind.CHAPTER; confidence = Confidence.MEDIUM; notes += "URL 含章节页特征（/read、编号分页等）" }
            catalogHit -> { kind = PageKind.CATALOG; confidence = Confidence.MEDIUM; notes += "URL 含目录页特征（catalog/list/mulu 等）" }
            detailHit -> { kind = PageKind.BOOK_DETAIL; confidence = Confidence.MEDIUM; notes += "URL 含详情页特征（book/novel/info 等）" }
            else -> notes += "URL 无明显页面特征"
        }

        // 2) 站点规则提示（规则选择器组合 ↔ 页面类型：content→章节、catalog→目录、bookName→详情）
        val rule = host?.let { runCatching { ruleEngine.loadRule(it) }.getOrNull() }
        if (rule != null) {
            notes += "命中站点规则 ${rule.ruleId}"
            val ruleKind = when {
                rule.selectors.containsKey(com.mvxxcb.reader.parser.rule.RuleSelectorKey.CONTENT) -> PageKind.CHAPTER
                rule.selectors.containsKey(com.mvxxcb.reader.parser.rule.RuleSelectorKey.CATALOG) -> PageKind.CATALOG
                rule.selectors.containsKey(com.mvxxcb.reader.parser.rule.RuleSelectorKey.BOOK_NAME) -> PageKind.BOOK_DETAIL
                else -> null
            }
            when {
                kind == null && ruleKind != null -> {
                    kind = ruleKind
                    confidence = Confidence.MEDIUM
                    notes += "依据站点规则选择器组合判定为 $ruleKind"
                }
                ruleKind != null && ruleKind == kind -> {
                    confidence = Confidence.HIGH
                    notes += "URL 特征与站点规则选择器组合一致（$kind）"
                }
                else -> notes += "站点规则提示为 $ruleKind，与 URL 特征不一致，暂以 URL 特征为准"
            }
        }

        // 3) 置信度不足 → 抓取页面辅助判定（最多 1 次请求；失败降级不阻断）
        if (confidence.rank() < Confidence.MEDIUM.rank() && host != null) {
            currentCoroutineContext().ensureActive()
            val analysis = fetchAndAnalyze(normalized, rule?.encoding)
            if (analysis == null) {
                notes += "页面抓取失败，仅按 URL 特征判定"
            } else {
                val (pageKind, pageNote) = analysis
                notes += pageNote
                if (pageKind != null) {
                    if (kind == null || pageKind == kind) {
                        kind = pageKind
                        confidence = Confidence.HIGH
                    } else {
                        // 页面结构证据与 URL 特征冲突 → 结构证据优先（更可靠）
                        kind = pageKind
                        confidence = Confidence.MEDIUM
                        notes += "页面结构与 URL 特征不一致，以页面结构为准"
                    }
                }
            }
        }

        // 4) 低于 MEDIUM → UNKNOWN
        if (confidence.rank() < Confidence.MEDIUM.rank()) {
            if (kind != null) notes += "置信度不足（$confidence），kind 记为 UNKNOWN"
            kind = PageKind.UNKNOWN
        }

        return AppResult.Success(
            UrlClassification(
                normalizedUrl = normalized,
                kind = kind ?: PageKind.UNKNOWN,
                confidence = confidence,
                matchedRuleId = rule?.ruleId,
                notes = notes,
            ),
        )
    }

    /** 抓取一次页面并做结构分析；任何失败返回 null（分类不因此失败） */
    private suspend fun fetchAndAnalyze(
        url: String,
        ruleEncoding: String?,
    ): Pair<PageKind?, String>? {
        val budget = RequestBudget(1)
        val referer = try {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.host}/"
        } catch (_: Exception) {
            null
        }
        val headers = buildMap {
            put("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            put("Accept-Language", "zh-CN,zh;q=0.9")
            if (referer != null) put("Referer", referer)
        }
        val result = kotlinx.coroutines.withContext(dispatchers.io) {
            secureHttpClient.fetch(com.mvxxcb.reader.security.FetchRequest(url = url, headers = headers))
        }
        val fetched = (result as? AppResult.Success)?.value ?: return null
        if (fetched.bodyBytes.isEmpty()) return null
        val decoded = kotlinx.coroutines.withContext(dispatchers.default) {
            com.mvxxcb.reader.parser.util.EncodingDetector.detectAndDecode(fetched.bodyBytes, fetched.declaredCharset, ruleEncoding)
        } ?: return null
        val document = kotlinx.coroutines.withContext(dispatchers.default) {
            org.jsoup.Jsoup.parse(decoded.html, fetched.finalUrl)
        }
        return analyzePage(document)
    }

    /** 页面结构分析：章节链接数 / JSON-LD Book / 长正文占比（章节链接计数同样排除 /wenku/ 等非章节路径段与分卷锚，阶段 7 验收缺陷修复） */
    internal fun analyzePage(document: org.jsoup.nodes.Document): Pair<PageKind?, String> {
        val chapterLinks = document.select("a[href]").count { a ->
            val t = a.text().trim()
            if (t.isEmpty() || t.length > 60) return@count false
            val href = a.absUrl("href").ifEmpty { a.attr("href") }
            if (href.isEmpty() || href.startsWith("javascript", true) || href == "#") return@count false
            if (com.mvxxcb.reader.parser.util.CatalogAnchorFilter.isNonChapterPath(href)) return@count false
            if (com.mvxxcb.reader.parser.util.CatalogAnchorFilter.isVolumeAnchor(href)) return@count false
            com.mvxxcb.reader.parser.util.CatalogAnchorFilter.matchesChapterPattern(t, href)
        }
        if (chapterLinks >= 5) return PageKind.CATALOG to "页面含 $chapterLinks 个章节特征链接 → 目录页"

        val structuredExtractor = com.mvxxcb.reader.parser.structured.JsonLdExtractor()
        val hasBookStructured = runCatching { structuredExtractor.extract(document).containsKey(com.mvxxcb.reader.domain.model.DetailField.TITLE) }
            .getOrDefault(false)
        val hasOgBook = runCatching {
            document.selectFirst("meta[property=og:title], meta[property=og:novel:book_name]") != null
        }.getOrDefault(false)
        val hasAuthorMeta = runCatching {
            document.selectFirst("meta[name=author]") != null ||
                Regex("作者[：:]\\s*[\\u4e00-\\u9fa5\\w]{1,20}").containsMatchIn(document.body()?.text().orEmpty().take(3000))
        }.getOrDefault(false)
        if (hasBookStructured || (hasOgBook && hasAuthorMeta) || (hasOgBook && hasCatalogLink(document))) {
            return PageKind.BOOK_DETAIL to "页面含 JSON-LD/OG 书籍结构化数据 → 详情页"
        }

        val bodyText = document.body()?.text().orEmpty()
        val linkCount = document.select("a[href]").size
        if (bodyText.length > 400 && chapterLinks < 3 && linkCount < 60) {
            return PageKind.CHAPTER to "页面为长文本正文（${bodyText.length} 字符，链接 $linkCount 个）→ 章节页"
        }
        return null to "页面结构无法判定页面类型（章节链接 $chapterLinks 个）"
    }

    private fun hasCatalogLink(document: org.jsoup.nodes.Document): Boolean =
        document.select("a[href]").any { it.text().trim().contains("目录") || it.text().trim().contains("章节") }
}
