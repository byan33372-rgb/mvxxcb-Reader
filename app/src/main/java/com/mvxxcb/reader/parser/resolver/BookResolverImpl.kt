package com.mvxxcb.reader.parser.resolver

import com.mvxxcb.reader.common.coroutine.DispatcherProvider
import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.common.time.TimeProvider
import com.mvxxcb.reader.domain.error.NetworkFailure
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.model.BookResolution
import com.mvxxcb.reader.domain.model.ChapterRef
import com.mvxxcb.reader.domain.model.Confidence
import com.mvxxcb.reader.domain.model.DetailField
import com.mvxxcb.reader.domain.model.PageKind
import com.mvxxcb.reader.domain.model.ParsedBookDetail
import com.mvxxcb.reader.domain.model.ParsedCatalog
import com.mvxxcb.reader.domain.model.UrlClassification
import com.mvxxcb.reader.parser.core.ParseOptions
import com.mvxxcb.reader.parser.core.ParseOutcome
import com.mvxxcb.reader.parser.core.ParseRequest
import com.mvxxcb.reader.parser.core.UrlClassifier
import com.mvxxcb.reader.parser.core.rank
import com.mvxxcb.reader.parser.generic.GenericSmartExtractor
import com.mvxxcb.reader.parser.pipeline.ParserPipeline
import com.mvxxcb.reader.parser.util.BreadcrumbLinkExtractor
import com.mvxxcb.reader.parser.util.CatalogAnchorFilter
import com.mvxxcb.reader.parser.util.EncodingDetector
import com.mvxxcb.reader.parser.util.UrlNormalizer
import com.mvxxcb.reader.security.FetchRequest
import com.mvxxcb.reader.security.SecureHttpClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * 反向识别器实现（契约 v1.1 K8；设计 07 §K8 静态回溯链，无 WebView）。
 *
 * 构造注入（对齐现有 parser 组件模式，主 Agent 装配；UrlNormalizer / EncodingDetector /
 * CatalogAnchorFilter / GenericSmartExtractor / BreadcrumbLinkExtractor 为 parser 内
 * 无状态 object/纯类，直接复用不注入）：
 * ```
 * BookResolverImpl(secureHttpClient, pipeline, classifier, dispatchers, timeProvider, logger)
 * ```
 *
 * 流程（07 K8 策略 1–5，章节页回溯链逐字对齐）：
 *  1. classify → BOOK_DETAIL：parseDetail→catalogUrl→parseCatalog；CATALOG：直接 parseCatalog；
 *  2. CHAPTER/UNKNOWN：解析当前章节页（书名信号/上下章链接）→ 面包屑候选 ≤6 个逐个 classify
 *     验证（命中 BOOK_DETAIL 或 CATALOG 即锁定）→ 面包屑无果时沿上一章链接有界回溯
 *     （总访问 ≤30 跳，每跳恰 1 次抓取，visited 集合防环，沿途每页重试面包屑，命中即止）；
 *  3. catalogHint 非空时按 K8 第 4 条验证（validate→classify→parse→与章节页书名匹配校验，
 *     不一致给 LOW + warning，不静默接受）；
 *  4. 全部失败 → Success(requiresCatalogHint=true, confidence=LOW, evidence 完整记录尝试链)；
 *     仅 URL 非法返回 Failure(Network.INVALID_URL)。
 *
 * 红线（设计冻结）：
 *  - 禁止 URL 猜测：detailUrl/catalogUrl 只来自用户输入、页面声明链接或 pipeline 解析输出；
 *    无面包屑无上一章时必须 requiresCatalogHint，绝不拼接/枚举 URL；
 *  - 全部页面获取经注入的 [SecureHttpClient]；回溯每跳 1 次抓取 + 面包屑候选 classify
 *    （保守按 ≤1 请求计）+ pipeline 解析按授权额度扣减，总量 ≤ ParseOptions.maxTotalRequests；
 *  - Jsoup Document 不出 parser 包；detail/catalog 契约输出一律经 [ParserPipeline]（解析红线）；
 *    resolver 内 Document 仅用于导航证据采集（面包屑/书名信号/上下章链接），不产出任何 Parsed* 模型。
 *
 * 置信度语义（任务冻结）：HIGH=规则/结构化直证（直入详情且书名来自结构化/规则层）；
 * MEDIUM=回溯/间接命中（面包屑、上一章链、hint 书名匹配，MEDIUM 封顶）；
 * LOW=requiresCatalogHint 或 hint 书名不符/无法校验。
 */
class BookResolverImpl(
    private val secureHttpClient: SecureHttpClient,
    private val pipeline: ParserPipeline,
    private val classifier: UrlClassifier,
    private val dispatchers: DispatcherProvider,
    @Suppress("unused") private val timeProvider: TimeProvider, // 预留：超时预算与时间戳注入（对齐 ParserPipelineImpl 模式）
    private val logger: AppLogger,
) : com.mvxxcb.reader.domain.resolver.BookResolver {

    private companion object {
        const val TAG = "BookResolver"

        /** 07 K8：回溯有界 ≤30 跳（回溯链每跳恰 1 次抓取，与 maxTotalRequests=30 默认预算一致） */
        const val MAX_BACKTRACE_STEPS = 30

        /** 07 K8：面包屑候选 ≤6 个逐个 classify 验证 */
        const val MAX_BREADCRUMB_CANDIDATES = 6

        /** 书名信号最短长度（过短片段不做书名匹配，防误判） */
        const val MIN_NAME_LENGTH = 2

        private val WHITESPACE_RE = Regex("\\s+")
    }

    /** 尝试链步进日志（尝试类型/URL/结果/置信度；渲染为字符串进 BookResolution.evidence） */
    internal data class StepLog(
        val type: String,
        val url: String? = null,
        val result: String,
        val confidence: Confidence? = null,
    ) {
        fun render(): String = buildString {
            append('[').append(type).append(']')
            if (url != null) append(' ').append(url)
            append(" → ").append(result)
            if (confidence != null) append("（置信度 ").append(confidence).append('）')
        }
    }

    /** 书名匹配三态（internal 供单测断言） */
    internal enum class NameMatch { MATCHED, MISMATCHED, UNVERIFIED }

    /** 目录解析输出随行置信度（ParseOutcome.overallConfidence，供 resolution 置信度合成） */
    private data class CatalogParseResult(val catalog: ParsedCatalog, val confidence: Confidence)

    /** 单次 resolve 的可变状态（resolve 串行使用，无跨调用共享可变状态） */
    private class Session {
        val logs = mutableListOf<StepLog>()

        /** 规范化后的输入 URL（BookResolution.normalizedUrl 的唯一来源，禁止误用候选/hint URL） */
        lateinit var inputUrl: String

        /** 全局请求预算：resolver 直抓 1 次/请求；classify 保守计 ≤1 次/调用；pipeline 按授权额度扣减 */
        var remainingRequests: Int = ParseOptions.DEFAULT.maxTotalRequests
    }

    private val generic = GenericSmartExtractor()

    // —————————————————————————————————— 入口 ——————————————————————————————————

    override suspend fun resolve(rawUrl: String, catalogHint: String?): AppResult<BookResolution> {
        val session = Session()
        logger.d(TAG, "resolve start: ${rawUrl.substringBefore('?')}${if (catalogHint != null) " (with hint)" else ""}")
        val normalized = UrlNormalizer.normalize(rawUrl)
        if (normalized == null) {
            session.logs += StepLog("规范化", rawUrl, "URL 非法，无法规范化")
            return AppResult.Failure(
                ReaderError.Network(NetworkFailure.INVALID_URL, url = rawUrl, statusCode = null),
            )
        }
        session.logs += StepLog("规范化", rawUrl, "规范化为 $normalized")
        session.inputUrl = normalized

        val classification: UrlClassification? = when (val result = classifier.classify(rawUrl)) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> {
                session.logs += StepLog(
                    "分类", rawUrl,
                    "classify 失败：${describe(result.error)}",
                    Confidence.LOW,
                )
                null
            }
        }
        if (classification != null) {
            session.logs += StepLog("分类", classification.normalizedUrl, "kind=${classification.kind}", classification.confidence)
            classification.notes.forEach { session.logs += StepLog("分类依据", null, it) }
        }
        // classify 保守计 ≤1 次请求（URL 特征命中时通常 0 次抓取；结果无法区分是否抓取过，一律按上限扣减）
        session.remainingRequests -= 1
        val kind = classification?.kind ?: PageKind.UNKNOWN

        val resolution = when (kind) {
            PageKind.BOOK_DETAIL -> resolveDirectDetail(session, normalized, catalogHint)
            PageKind.CATALOG -> resolveDirectCatalog(session, normalized, catalogHint)
            PageKind.CHAPTER, PageKind.UNKNOWN -> resolveViaChapterChain(session, normalized, kind, catalogHint)
        }
        return AppResult.Success(resolution)
    }

    // —————————————————————————————————— 直入分支 ——————————————————————————————————

    /** BOOK_DETAIL 直入：parseDetail → catalogUrl → parseCatalog；无目录链接不猜测，转 hint/requiresCatalogHint */
    private suspend fun resolveDirectDetail(session: Session, normalized: String, catalogHint: String?): BookResolution {
        val detail = parseDetailOnce(session, normalized)
        if (detail == null) {
            session.logs += StepLog("结论", normalized, "详情解析失败，目录未定位")
            val hinted = hintOrNull(session, catalogHint, emptyList(), PageKind.BOOK_DETAIL)
            return hinted ?: partialRequiresHint(
                session, PageKind.BOOK_DETAIL,
                detailUrl = normalized, detail = null, catalogUrl = null,
            )
        }
        session.logs += StepLog("详情解析", detail.sourceUrl, "title=${detail.title ?: "缺失"}", titleConfidenceOf(detail))

        val catalogUrl = detail.catalogUrl
        if (catalogUrl != null) {
            // 直入路径：置信度锚 = 书名字段置信度（规则/结构化直证 → HIGH；见类 KDoc 置信度语义）
            val resolved = parseFinalCatalog(
                session, catalogUrl, PageKind.BOOK_DETAIL,
                pathCapConf = null, anchorConf = titleConfidenceOf(detail),
                detail = detail, detailUrl = detail.sourceUrl,
            )
            if (resolved != null) return resolved
        } else {
            session.logs += StepLog("详情解析", detail.sourceUrl, "详情页未声明目录链接，禁止猜测合成目录 URL")
        }

        // 目录未定位：先试 hint（书名信号 = 详情 title）
        val hinted = hintOrNull(session, catalogHint, listOfNotNull(detail.title?.takeIf { it.isNotBlank() }), PageKind.BOOK_DETAIL)
        if (hinted != null) return hinted
        return partialRequiresHint(
            session, PageKind.BOOK_DETAIL,
            detailUrl = detail.sourceUrl, detail = detail, catalogUrl = null,
        )
    }

    /** CATALOG 直入：直接 parseCatalog（禁猜测详情页；detail 保持 null） */
    private suspend fun resolveDirectCatalog(session: Session, normalized: String, catalogHint: String?): BookResolution {
        val resolved = parseFinalCatalog(
            session, normalized, PageKind.CATALOG,
            pathCapConf = null, anchorConf = null,
            detail = null, detailUrl = null,
        )
        if (resolved != null) return resolved
        session.logs += StepLog("结论", normalized, "目录解析失败")
        val hinted = hintOrNull(session, catalogHint, emptyList(), PageKind.CATALOG)
        if (hinted != null) return hinted
        return partialRequiresHint(session, PageKind.CATALOG, detailUrl = null, detail = null, catalogUrl = null)
    }

    // —————————————————————————————————— 章节回溯链 ——————————————————————————————————

    /**
     * CHAPTER/UNKNOWN 输入的静态回溯链（07 K8 步骤 2–5）：
     * 当前章节页解析 → 面包屑候选 ≤6 逐个验证 → 上一章有界回溯（≤30 跳、防环、沿途重试面包屑）
     * → catalogHint 验证 → requiresCatalogHint。
     */
    private suspend fun resolveViaChapterChain(
        session: Session,
        normalized: String,
        inputKind: PageKind,
        catalogHint: String?,
    ): BookResolution {
        val visited = linkedSetOf(normalized)
        var current: String? = normalized
        var steps = 0
        var chapterNameCandidates: List<String> = emptyList()

        while (current != null) {
            currentCoroutineContext().ensureActive()
            if (steps >= MAX_BACKTRACE_STEPS) {
                session.logs += StepLog("回溯上限", current, "已达回溯上限 $MAX_BACKTRACE_STEPS 跳，停止回溯")
                break
            }
            if (session.remainingRequests <= 0) {
                session.logs += StepLog("预算", current, "请求预算耗尽（maxTotalRequests），停止回溯")
                break
            }

            val document = fetchDocument(session, current)
            if (document == null) break
            steps++
            val pageTitle = document.title().trim()
            if (chapterNameCandidates.isEmpty()) {
                chapterNameCandidates = bookNameCandidatesFromTitle(pageTitle)
            }
            val prevUrl = generic.detectPreviousChapterLink(document, current)?.takeIf { it != current && it !in visited }
            val nextUrl = generic.detectNextChapterLink(document, current)
            session.logs += StepLog(
                "章节页", current,
                buildString {
                    append("第 $steps/$MAX_BACKTRACE_STEPS 跳 title=").append(pageTitle.ifEmpty { "缺失" })
                    append(" 上一章=").append(prevUrl ?: "无")
                    append(" 下一章=").append(nextUrl ?: "无")
                },
            )

            // 面包屑候选 ≤6 个逐个 classify 验证（命中即锁定返回）
            val candidates = BreadcrumbLinkExtractor.extract(document, current)
            val isTruncated = candidates.size > MAX_BREADCRUMB_CANDIDATES
            val tried = candidates.take(MAX_BREADCRUMB_CANDIDATES)
            session.logs += StepLog(
                "面包屑", current,
                buildString {
                    append("候选 ${tried.size} 个")
                    if (isTruncated) append("（共 ${candidates.size} 个，超出 $MAX_BREADCRUMB_CANDIDATES 截断）")
                },
            )
            var budgetBroke = false
            for (candidate in tried) {
                currentCoroutineContext().ensureActive()
                if (session.remainingRequests <= 0) {
                    session.logs += StepLog("预算", candidate.url, "请求预算耗尽，剩余候选未验证")
                    budgetBroke = true
                    break
                }
                val candidateKind = classifyCandidate(session, candidate.text, candidate.url) ?: continue
                when (candidateKind) {
                    PageKind.CATALOG -> {
                        val resolved = parseFinalCatalog(
                            session, candidate.url, inputKind,
                            pathCapConf = Confidence.MEDIUM, anchorConf = null,
                            detail = null, detailUrl = null,
                        )
                        if (resolved != null) return resolved
                    }
                    PageKind.BOOK_DETAIL -> {
                        val detail = parseDetailOnce(session, candidate.url)
                        if (detail == null) {
                            session.logs += StepLog("面包屑候选", candidate.url, "详情解析失败，继续下一候选")
                            continue
                        }
                        session.logs += StepLog("详情解析", detail.sourceUrl, "面包屑命中详情页 title=${detail.title ?: "缺失"}", titleConfidenceOf(detail))
                        val catalogUrl = detail.catalogUrl
                        if (catalogUrl == null) {
                            session.logs += StepLog("详情解析", detail.sourceUrl, "详情页未声明目录链接，继续下一候选")
                            continue
                        }
                        val resolved = parseFinalCatalog(
                            session, catalogUrl, inputKind,
                            pathCapConf = Confidence.MEDIUM, anchorConf = titleConfidenceOf(detail),
                            detail = detail, detailUrl = detail.sourceUrl,
                        )
                        if (resolved != null) return resolved
                    }
                    else -> Unit // CHAPTER/UNKNOWN 候选非目录/详情入口，跳过
                }
            }
            if (!budgetBroke && tried.isNotEmpty()) {
                session.logs += StepLog("面包屑", current, "本页全部候选未命中目录/详情入口")
            }

            // 面包屑无果 → 沿上一章链接回溯（visited 防环）
            if (prevUrl == null) {
                session.logs += StepLog("上一章回溯", current, "无可用上一章链接，回溯链终止")
                break
            }
            visited += prevUrl
            current = prevUrl
        }

        // catalogHint 验证（K8 第 4 条：validate → classify → parse → 书名匹配校验）
        if (catalogHint != null) {
            val hinted = hintOrNull(session, catalogHint, chapterNameCandidates, inputKind)
            if (hinted != null) return hinted
        }

        session.logs += StepLog("结论", normalized, "面包屑与上一章回溯均未定位目录入口，需要用户补充目录页 URL")
        return BookResolution(
            normalizedUrl = normalized,
            kind = inputKind,
            detailUrl = null,
            catalogUrl = null,
            detail = null,
            catalog = null,
            chapters = emptyList(),
            confidence = Confidence.LOW,
            requiresCatalogHint = true,
            evidence = session.logs.map { it.render() },
        )
    }

    // —————————————————————————————————— hint 验证 ——————————————————————————————————

    /**
     * catalogHint 验证回路；返回 null 表示 hint 不可用（原因已记录），调用方继续失败路径。
     * 书名匹配校验：MATCHED → min(parse, MEDIUM)；MISMATCHED/UNVERIFIED → LOW（不静默接受）。
     */
    private suspend fun hintOrNull(
        session: Session,
        catalogHint: String?,
        knownNameCandidates: List<String>,
        inputKind: PageKind,
    ): BookResolution? {
        if (catalogHint.isNullOrBlank()) return null
        val normalizedHint = UrlNormalizer.normalize(catalogHint)
        if (normalizedHint == null) {
            session.logs += StepLog("提示验证", catalogHint, "目录提示 URL 非法，忽略")
            return null
        }
        if (session.remainingRequests <= 0) {
            session.logs += StepLog("预算", normalizedHint, "请求预算耗尽，目录提示未能验证")
            return null
        }
        session.remainingRequests -= 1 // classify 内部至多 1 次抓取，保守计数
        val hintClassification = when (val result = classifier.classify(catalogHint)) {
            is AppResult.Success -> result.value
            is AppResult.Failure -> {
                session.logs += StepLog("提示验证", normalizedHint, "classify 失败：${describe(result.error)}")
                return null
            }
        }
        session.logs += StepLog("提示验证", normalizedHint, "kind=${hintClassification.kind}", hintClassification.confidence)

        return when (hintClassification.kind) {
            PageKind.CATALOG -> resolveHintCatalog(session, normalizedHint, inputKind, knownNameCandidates)
            PageKind.BOOK_DETAIL -> resolveHintDetail(session, normalizedHint, inputKind, knownNameCandidates)
            else -> {
                session.logs += StepLog("提示验证", normalizedHint, "提示页分类为 ${hintClassification.kind}，无法作为目录/详情入口")
                null
            }
        }
    }

    private suspend fun resolveHintCatalog(
        session: Session,
        normalizedHint: String,
        inputKind: PageKind,
        knownNameCandidates: List<String>,
    ): BookResolution? {
        // 提示目录页书名信号（供与已知页书名匹配校验；1 次抓取计入预算）
        var hintNameCandidates: List<String> = emptyList()
        if (session.remainingRequests > 0) {
            val hintDoc = fetchDocument(session, normalizedHint)
            if (hintDoc != null) hintNameCandidates = bookNameCandidatesFromTitle(hintDoc.title().trim())
        }
        val match = matchBookName(knownNameCandidates, hintNameCandidates)
        session.logs += StepLog("书名校验", normalizedHint, renderNameMatch(match, knownNameCandidates, hintNameCandidates))

        val parsed = parseCatalogWithBudget(session, normalizedHint) ?: return null
        if (parsed.catalog.chapters.isEmpty()) {
            session.logs += StepLog("目录解析", normalizedHint, "提示目录零章节，视为无效")
            return null
        }
        return buildResolution(
            session, inputKind,
            detailUrl = null, detail = null,
            catalogUrl = parsed.catalog.sourceUrl, catalog = parsed,
            confidence = when (match) {
                NameMatch.MATCHED -> minConf(Confidence.MEDIUM, parsed.confidence)
                NameMatch.MISMATCHED, NameMatch.UNVERIFIED -> Confidence.LOW
            },
            requiresCatalogHint = false,
        )
    }

    private suspend fun resolveHintDetail(
        session: Session,
        normalizedHint: String,
        inputKind: PageKind,
        knownNameCandidates: List<String>,
    ): BookResolution? {
        val detail = parseDetailOnce(session, normalizedHint)
        if (detail == null) {
            session.logs += StepLog("提示验证", normalizedHint, "提示详情页解析失败")
            return null
        }
        session.logs += StepLog("详情解析", detail.sourceUrl, "提示命中详情页 title=${detail.title ?: "缺失"}", titleConfidenceOf(detail))
        val hintTitle = detail.title?.takeIf { it.isNotBlank() }
        val match = matchBookName(knownNameCandidates, listOfNotNull(hintTitle))
        session.logs += StepLog("书名校验", normalizedHint, renderNameMatch(match, knownNameCandidates, listOfNotNull(hintTitle)))

        val catalogUrl = detail.catalogUrl
        if (catalogUrl == null) {
            session.logs += StepLog("提示验证", normalizedHint, "提示详情页未声明目录链接，禁止猜测合成")
            return partialRequiresHint(session, inputKind, detailUrl = detail.sourceUrl, detail = detail, catalogUrl = null)
        }
        val parsed = parseCatalogWithBudget(session, catalogUrl)
        if (parsed == null || parsed.catalog.chapters.isEmpty()) {
            session.logs += StepLog("目录解析", catalogUrl, "提示目录解析失败或零章节")
            return partialRequiresHint(session, inputKind, detailUrl = detail.sourceUrl, detail = detail, catalogUrl = null)
        }
        return buildResolution(
            session, inputKind,
            detailUrl = detail.sourceUrl, detail = detail,
            catalogUrl = parsed.catalog.sourceUrl, catalog = parsed,
            confidence = when (match) {
                NameMatch.MATCHED -> minConf(Confidence.MEDIUM, minConf(parsed.confidence, titleConfidenceOf(detail) ?: Confidence.MEDIUM))
                NameMatch.MISMATCHED, NameMatch.UNVERIFIED -> Confidence.LOW
            },
            requiresCatalogHint = false,
        )
    }

    // —————————————————————————————————— pipeline 调用 ——————————————————————————————————

    /** 详情解析：恰授权 1 次请求（详情页无分页）；失败返回 null（已记录日志） */
    private suspend fun parseDetailOnce(session: Session, url: String): ParsedBookDetail? {
        if (session.remainingRequests <= 0) {
            session.logs += StepLog("预算", url, "请求预算耗尽，详情解析未执行")
            return null
        }
        session.remainingRequests -= 1
        val options = ParseOptions(
            maxCatalogPages = 1,
            maxContentPages = 1,
            maxTotalRequests = 1,
            maxContentChars = ParseOptions.DEFAULT.maxContentChars,
            allowPaginationMerge = false,
        )
        return when (val outcome = pipeline.parseDetail(ParseRequest(url, options))) {
            is ParseOutcome.Success -> outcome.data
            is ParseOutcome.Failure -> {
                session.logs += StepLog("详情解析", url, "失败：${describe(outcome.error)}")
                null
            }
        }
    }

    /**
     * 最终目录解析（定位锁定）：授权 min(remaining, 默认目录分页上限) 次请求并全额扣减
     * （授权额度即上限，pipeline 实际用量 ≤ 授权，总量 ≤ maxTotalRequests 红线）。
     *
     * 置信度合成（见类 KDoc 语义）：
     *  - pathCapConf 非空 = 回溯/间接命中：conf = min(MEDIUM, anchor?, parse)（永不 HIGH）；
     *  - pathCapConf 为空 = 直入：conf = anchor（书名结构化/规则直证）?: parse。
     */
    private suspend fun parseFinalCatalog(
        session: Session,
        url: String,
        inputKind: PageKind,
        pathCapConf: Confidence?,
        anchorConf: Confidence?,
        detail: ParsedBookDetail?,
        detailUrl: String?,
    ): BookResolution? {
        val parsed = parseCatalogWithBudget(session, url) ?: return null
        if (parsed.catalog.chapters.isEmpty()) {
            session.logs += StepLog("目录解析", url, "目录零章节，不锁定")
            return null
        }
        val confidence = when {
            pathCapConf != null -> minConf(pathCapConf, minConf(anchorConf ?: Confidence.HIGH, parsed.confidence))
            anchorConf != null -> anchorConf
            else -> parsed.confidence
        }
        session.logs += StepLog("目录锁定", url, "命中目录入口（${parsed.catalog.chapters.size} 章）", confidence)
        return buildResolution(
            session, inputKind,
            detailUrl = detailUrl, detail = detail,
            catalogUrl = parsed.catalog.sourceUrl, catalog = parsed,
            confidence = confidence,
            requiresCatalogHint = false,
        )
    }

    /** 目录解析（预算内授权 + 全额扣减）；失败返回 null */
    private suspend fun parseCatalogWithBudget(session: Session, url: String): CatalogParseResult? {
        if (session.remainingRequests <= 0) {
            session.logs += StepLog("预算", url, "请求预算耗尽，目录解析未执行")
            return null
        }
        val grant = session.remainingRequests.coerceAtMost(ParseOptions.DEFAULT.maxCatalogPages)
        session.remainingRequests -= grant
        val options = ParseOptions(
            maxCatalogPages = grant,
            maxContentPages = 1,
            maxTotalRequests = grant,
            maxContentChars = ParseOptions.DEFAULT.maxContentChars,
            allowPaginationMerge = true,
        )
        return when (val outcome = pipeline.parseCatalog(ParseRequest(url, options))) {
            is ParseOutcome.Success -> {
                session.logs += StepLog(
                    "目录解析", url,
                    "成功（${outcome.data.chapters.size} 章，分页 ${outcome.data.paginationTraversed} 页" +
                        "${if (outcome.data.truncatedByLimit) "，已达上限截断" else ""}）",
                    outcome.overallConfidence,
                )
                outcome.warnings.forEach { session.logs += StepLog("目录警告", url, it) }
                CatalogParseResult(outcome.data, outcome.overallConfidence)
            }
            is ParseOutcome.Failure -> {
                session.logs += StepLog("目录解析", url, "失败：${describe(outcome.error)}")
                null
            }
        }
    }

    /** 面包屑候选 classify 验证（保守计 1 次请求）；失败/UNKNOWN 返回 null */
    private suspend fun classifyCandidate(session: Session, text: String, url: String): PageKind? {
        if (session.remainingRequests <= 0) {
            session.logs += StepLog("预算", url, "请求预算耗尽，候选未验证")
            return null
        }
        session.remainingRequests -= 1
        return when (val result = classifier.classify(url)) {
            is AppResult.Success -> {
                session.logs += StepLog(
                    "面包屑候选", url,
                    "「${text.take(20)}」classify=${result.value.kind}",
                    result.value.confidence,
                )
                result.value.kind
            }
            is AppResult.Failure -> {
                session.logs += StepLog("面包屑候选", url, "classify 失败，跳过")
                null
            }
        }
    }

    // —————————————————————————————————— 页面抓取（导航证据专用） ——————————————————————————————————

    /**
     * 导航证据抓取（面包屑/书名信号/上下章链接；每调用恰 1 次请求并计数）。
     * 仅服务回溯链导航，不产出任何 Parsed* 契约模型（detail/catalog/content 解析一律走 pipeline）。
     */
    private suspend fun fetchDocument(session: Session, url: String): Document? {
        if (session.remainingRequests <= 0) {
            session.logs += StepLog("预算", url, "请求预算耗尽，停止抓取")
            return null
        }
        session.remainingRequests -= 1
        val referer = try {
            val uri = java.net.URI(url)
            if (uri.scheme != null && uri.host != null) "${uri.scheme}://${uri.host}/" else null
        } catch (_: Exception) {
            null
        }
        val headers = buildMap {
            put("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            put("Accept-Language", "zh-CN,zh;q=0.9")
            if (referer != null) put("Referer", referer)
        }
        val fetched = withContext(dispatchers.io) {
            secureHttpClient.fetch(FetchRequest(url = url, headers = headers))
        }
        val result = (fetched as? AppResult.Success)?.value
        if (result == null || result.bodyBytes.isEmpty()) {
            session.logs += StepLog("抓取", url, "失败：${if (fetched is AppResult.Failure) describe(fetched.error) else "空响应"}")
            return null
        }
        val decoded = withContext(dispatchers.default) {
            EncodingDetector.detectAndDecode(result.bodyBytes, result.declaredCharset, null)
        }
        if (decoded == null) {
            session.logs += StepLog("抓取", url, "解码失败")
            return null
        }
        return withContext(dispatchers.default) { Jsoup.parse(decoded.html, result.finalUrl) }
    }

    // —————————————————————————————————— 结果组装 ——————————————————————————————————

    private fun buildResolution(
        session: Session,
        inputKind: PageKind,
        detailUrl: String?,
        detail: ParsedBookDetail?,
        catalogUrl: String?,
        catalog: CatalogParseResult?,
        confidence: Confidence,
        requiresCatalogHint: Boolean,
    ): BookResolution {
        val chapters: List<ChapterRef> = catalog?.catalog?.chapters ?: emptyList()
        session.logs += StepLog(
            "结论", null,
            buildString {
                append(if (requiresCatalogHint) "需要用户补充目录页 URL" else "定位完成")
                append(" detailUrl=").append(detailUrl ?: "无")
                append(" catalogUrl=").append(catalogUrl ?: "无")
                append(" 章节数=").append(chapters.size)
            },
            confidence,
        )
        return BookResolution(
            normalizedUrl = session.inputUrl,
            kind = inputKind,
            detailUrl = detailUrl,
            catalogUrl = catalogUrl,
            detail = detail,
            catalog = catalog?.catalog,
            chapters = chapters,
            confidence = if (requiresCatalogHint) Confidence.LOW else confidence,
            requiresCatalogHint = requiresCatalogHint,
            evidence = session.logs.map { it.render() },
        )
    }

    /** 失败/部分结果：requiresCatalogHint=true（confidence 强制 LOW，不把不确定结果包装为成功） */
    private fun partialRequiresHint(
        session: Session,
        inputKind: PageKind,
        detailUrl: String?,
        detail: ParsedBookDetail?,
        catalogUrl: String?,
    ): BookResolution {
        session.logs += StepLog(
            "结论", session.inputUrl,
            "目录未定位（detailUrl=${detailUrl ?: "无"}，catalogUrl=${catalogUrl ?: "无"}），需要用户补充目录页 URL",
            Confidence.LOW,
        )
        return BookResolution(
            normalizedUrl = session.inputUrl,
            kind = inputKind,
            detailUrl = detailUrl,
            catalogUrl = catalogUrl,
            detail = detail,
            catalog = null,
            chapters = emptyList(),
            confidence = Confidence.LOW,
            requiresCatalogHint = true,
            evidence = session.logs.map { it.render() },
        )
    }

    // —————————————————————————————————— 工具 ——————————————————————————————————

    private fun describe(error: ReaderError): String = error.debugDetail ?: when (error) {
        is ReaderError.Parse -> "Parse.${error.failure.name}"
        is ReaderError.Network -> "Network.${error.failure.name}"
        is ReaderError.State -> "State.${error.failure.name}"
        is ReaderError.Security -> "Security"
        is ReaderError.Storage -> "Storage.${error.failure.name}"
        is ReaderError.Unknown -> "Unknown"
    }

    private fun minConf(a: Confidence, b: Confidence): Confidence = if (a.rank() <= b.rank()) a else b

    private fun titleConfidenceOf(detail: ParsedBookDetail): Confidence? =
        detail.fieldConfidence[DetailField.TITLE]

    /**
     * 章节页书名信号：<title> 按 _ - — – | · ，, 分段；
     * 剔除章节特征段（第N章/节/回，复用 CatalogAnchorFilter 文本特征）与末段（站点名惯用位）；
     * 剩余非空段即书名候选（供 hint 书名匹配校验）。
     */
    internal fun bookNameCandidatesFromTitle(title: String?): List<String> {
        if (title.isNullOrBlank()) return emptyList()
        val segments = title.split('_', '-', '—', '–', '|', '·', '，', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val nonChapter = segments.filterNot { CatalogAnchorFilter.matchesChapterPattern(it, "") }
        return (if (nonChapter.size >= 2) nonChapter.dropLast(1) else nonChapter)
            .filter { it.length >= MIN_NAME_LENGTH }
    }

    /** 书名匹配：任一候选对相等或互相包含即 MATCHED；一侧无信号 UNVERIFIED；否则 MISMATCHED */
    internal fun matchBookName(known: List<String>, other: List<String>): NameMatch {
        if (known.isEmpty() || other.isEmpty()) return NameMatch.UNVERIFIED
        val knownNorm = known.map { it.replace(WHITESPACE_RE, "") }
        val otherNorm = other.map { it.replace(WHITESPACE_RE, "") }
        for (a in knownNorm) {
            for (b in otherNorm) {
                if (a.length < MIN_NAME_LENGTH || b.length < MIN_NAME_LENGTH) continue
                if (a == b || a.contains(b) || b.contains(a)) return NameMatch.MATCHED
            }
        }
        return NameMatch.MISMATCHED
    }

    private fun renderNameMatch(match: NameMatch, known: List<String>, other: List<String>): String = when (match) {
        NameMatch.MATCHED -> "书名匹配（${known.firstOrNull() ?: "?"} ↔ ${other.firstOrNull() ?: "?"}）"
        NameMatch.MISMATCHED -> "书名不符（已知=${known.joinToString("/").ifEmpty { "无信号" }}，提示页=${other.joinToString("/").ifEmpty { "无信号" }}），结果降为 LOW"
        NameMatch.UNVERIFIED -> "无法校验书名（${if (known.isEmpty()) "已知页" else "提示页"}无书名信号），结果降为 LOW"
    }
}
