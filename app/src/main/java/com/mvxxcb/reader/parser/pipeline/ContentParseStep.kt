package com.mvxxcb.reader.parser.pipeline

import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.domain.model.Confidence
import com.mvxxcb.reader.domain.model.ParsedChapterContent
import com.mvxxcb.reader.domain.model.ParseLayer
import com.mvxxcb.reader.domain.error.NetworkFailure
import com.mvxxcb.reader.domain.error.ParseFailure
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.parser.core.rank
import com.mvxxcb.reader.parser.core.LayerOutcome
import com.mvxxcb.reader.parser.core.LayerTrace
import com.mvxxcb.reader.parser.core.ParseOutcome
import com.mvxxcb.reader.parser.core.ParseRequest
import com.mvxxcb.reader.parser.rule.RuleSelectorKey
import com.mvxxcb.reader.parser.rule.SelectorExecutor
import com.mvxxcb.reader.parser.rule.SelectorSpec
import com.mvxxcb.reader.parser.util.PaginationMerger
import com.mvxxcb.reader.parser.util.UrlNormalizer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 正文解析步骤（01 §6 正文分页策略）：
 *  - 内容提取：人工/站点规则（contentSelector + cleaningRules）→ 通用智能候选区（文本密度/段落数/内容长度）；
 *  - 分页合并：沿 nextPageSelector / 智能发现合并同章多页，防漏页/重复/倒序/误入下一章：
 *      页 URL 前缀一致性（同 host + 同目录前缀）、页标题一致性、已见 URL 集合、新页重复率 > 60% 终止；
 *  - 上限：maxContentPages（含规则 contentMaxPages 取小）、maxContentChars、maxTotalRequests，
 *    任一触发 → isTruncated=true 并保留 nextPageUrl（如实告知，不静默截断）；
 *  - 误入下一章（标题不一致/目录前缀不同）→ 终止合并并把候选登记为下一章链接；
 *  - 已见 URL 命中 → PAGINATION_LOOP_DETECTED。
 * 目录与正文严格区分：本步骤只提取正文容器与章内导航。
 */
internal class ContentParseStep(private val ctx: ParseContext) {

    suspend fun parse(request: ParseRequest): ParseOutcome<ParsedChapterContent> {
        val options = request.options
        ctx.validateOptions(options)?.let { return ParseOutcome.Failure(it, emptyList()) }
        val sourceUrl = UrlNormalizer.normalize(request.url)
            ?: return ParseOutcome.Failure(
                ReaderError.Network(NetworkFailure.INVALID_URL, url = request.url, statusCode = null),
                emptyList(),
            )
        val budget = RequestBudget(options.maxTotalRequests)
        val traces = mutableListOf<LayerTrace>()
        val warnings = mutableListOf<String>()

        val rule = ctx.loadRuleSafely(ctx.hostOf(sourceUrl))
        val maxPages = options.maxContentPages.coerceAtMost(rule?.contentMaxPages ?: options.maxContentPages)

        // —— 首页 ——
        val firstPage = when (val fetched = ctx.fetchPage(sourceUrl, rule?.encoding, budget)) {
            is AppResult.Success -> fetched.value
            is AppResult.Failure -> return ParseOutcome.Failure(fetched.error, traces.toList())
        }

        var usedLayer: ParseLayer? = null
        var firstTitle: String? = null
        var firstBlocks: List<com.mvxxcb.reader.domain.model.ContentBlock> = emptyList()
        var extractedConfidence = Confidence.LOW

        // 层0/层2：人工/站点规则（manual.* 与站点规则都提供 contentSelector；层序不同，字段级合并意义不大，直接取规则内容）
        if (rule != null) {
            val fromRule = if (rule.isManual) ctx.manualApplier.applyContent(rule, firstPage.decoded)
            else ctx.manualApplier.siteRuleContent(rule, firstPage.decoded)
            if (fromRule != null && fromRule.blocks.isNotEmpty()) {
                usedLayer = if (rule.isManual) ParseLayer.MANUAL else ParseLayer.SITE_RULE
                firstTitle = fromRule.title
                firstBlocks = fromRule.blocks
                extractedConfidence = Confidence.HIGH
                traces += LayerTrace(
                    usedLayer,
                    LayerOutcome.APPLIED,
                    listOf("blocks(${firstBlocks.size})", "title"),
                    "规则 ${rule.ruleId} 提取 ${firstBlocks.size} 块",
                )
            } else {
                traces += LayerTrace(
                    if (rule.isManual) ParseLayer.MANUAL else ParseLayer.SITE_RULE,
                    LayerOutcome.PARTIAL,
                    detail = "规则 ${rule.ruleId} 未命中正文",
                )
            }
        } else {
            traces += LayerTrace(ParseLayer.MANUAL, LayerOutcome.SKIPPED_NO_RULE, detail = "无人工规则")
            traces += LayerTrace(ParseLayer.SITE_RULE, LayerOutcome.SKIPPED_NO_RULE, detail = "该域名无站点规则")
        }

        // 层3：通用智能候选区（规则未命中时兜底）
        if (firstBlocks.isEmpty()) {
            val genericContainer = runCatching { ctx.generic.findContentContainer(firstPage.document) }.getOrNull()
            if (genericContainer != null) {
                val cleanedHtml = runCatching {
                    ctx.cleaner.cleanContentElement(genericContainer.container, rule?.cleaningRules ?: emptyList())
                }.getOrNull()
                var blocks = if (cleanedHtml != null) ctx.contentSanitizer.sanitizeContentFragment(cleanedHtml).blocks else emptyList()
                if (blocks.isEmpty() && cleanedHtml != null) blocks = ctx.cleaner.buildBlocksFromHtml(cleanedHtml)
                if (blocks.isNotEmpty()) {
                    usedLayer = ParseLayer.GENERIC
                    firstTitle = firstPage.document.selectFirst("h1, h2")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: firstPage.document.title().trim().takeIf { it.isNotEmpty() }
                    firstBlocks = blocks
                    extractedConfidence = genericContainer.confidence
                    traces += LayerTrace(
                        ParseLayer.GENERIC,
                        LayerOutcome.APPLIED,
                        listOf("blocks(${blocks.size})"),
                        "通用候选区判定命中（段落 ${blocks.size}）",
                    )
                }
            }
        }

        if (firstBlocks.isEmpty()) {
            val error = if (ctx.pageLooksDynamic(firstPage)) {
                ReaderError.Parse(ParseFailure.DYNAMIC_PAGE_REQUIRED, debugDetail = "页面疑似动态渲染，未发现正文结构")
            } else {
                ReaderError.Parse(ParseFailure.CONTENT_EMPTY, debugDetail = "四层均未提取到正文内容")
            }
            return ParseOutcome.Failure(error, traces.toList())
        }

        // —— 分页合并（独立策略，见 PaginationMerger.ContentStrategy） ——
        val strategy = ctx.pagination.ContentStrategy(
            firstPageUrl = firstPage.url,
            firstPageTitle = firstTitle,
            maxPages = maxPages,
            maxChars = options.maxContentChars,
        )
        strategy.mergePage(firstPage.url, firstBlocks)
        var pagesFetched = 1
        var isTruncated = false
        var finalNextPageUrl: String? = null
        var nextChapterUrl: String? = null
        var previousChapterUrl: String? = null
        var iteration = 0

        var currentPage = firstPage
        while (true) {
            currentCoroutineContext().ensureActive()
            iteration++

            // 章内导航（每页都要收集：下一页/下一章/上一章）
            val navNextPage = nextPageFromRule(rule, currentPage)
                ?: runCatching { ctx.generic.detectNextPageLink(currentPage.document, currentPage.url) }.getOrNull()
            val navNextChapter = chapterLinkFromRule(rule, RuleSelectorKey.NEXT_CHAPTER, currentPage)
                ?: runCatching { ctx.generic.detectNextChapterLink(currentPage.document, currentPage.url) }.getOrNull()
            val navPrevChapter = chapterLinkFromRule(rule, RuleSelectorKey.PREVIOUS_CHAPTER, currentPage)
                ?: runCatching { ctx.generic.detectPreviousChapterLink(currentPage.document, currentPage.url) }.getOrNull()
            if (nextChapterUrl == null && navNextChapter != null && navNextChapter != currentPage.url) nextChapterUrl = navNextChapter
            if (previousChapterUrl == null && navPrevChapter != null && navPrevChapter != currentPage.url) previousChapterUrl = navPrevChapter

            val candidateNext = navNextPage

            // 上限守卫（页数 / 字符 / 请求预算）→ isTruncated，保留下一页 URL 如实告知
            if (iteration >= maxPages) {
                isTruncated = true
                finalNextPageUrl = candidateNext
                warnings += "已达正文分页上限（$maxPages 页），本章被截断"
                break
            }
            if (strategy.charBudgetExceeded()) {
                isTruncated = true
                finalNextPageUrl = candidateNext
                warnings += "已达正文字符上限（${options.maxContentChars}），本章被截断"
                break
            }
            if (candidateNext == null) break // 本章完

            // 防死循环 / 跨域 / 目录前缀（抓取前）
            when (val verdict = strategy.precheckNext(candidateNext)) {
                PaginationMerger.Verdict.Loop -> return ParseOutcome.Failure(
                    ReaderError.Parse(
                        ParseFailure.PAGINATION_LOOP_DETECTED,
                        debugDetail = "正文分页回到已访问页: ${ctx.sanitizeUrl(candidateNext)}",
                    ),
                    traces.toList(),
                )
                is PaginationMerger.Verdict.Stop -> break
                is PaginationMerger.Verdict.StopAsNextChapter -> {
                    if (nextChapterUrl == null) nextChapterUrl = candidateNext
                    warnings += "${verdict.reason}，停止合并"
                    break
                }
                PaginationMerger.Verdict.Proceed -> Unit
            }
            if (!budget.canSpend()) {
                isTruncated = true
                finalNextPageUrl = candidateNext
                warnings += "请求预算耗尽（maxTotalRequests=${options.maxTotalRequests}），本章被截断"
                break
            }

            val nextPage = when (val fetched = ctx.fetchPage(candidateNext, rule?.encoding, budget)) {
                is AppResult.Success -> fetched.value
                is AppResult.Failure -> return ParseOutcome.Failure(
                    ReaderError.Parse(
                        ParseFailure.CONTENT_PAGINATION_FAILED,
                        debugDetail = "第 ${pagesFetched + 1} 页抓取失败: ${fetched.error.debugDetail ?: fetched.error.javaClass.simpleName}",
                    ),
                    traces.toList(),
                )
            }
            pagesFetched++

            // 标题一致性守卫（抓取后）：不一致 → 误入下一章
            val nextTitle = nextPage.document.selectFirst("h1, h2")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            when (val titleVerdict = strategy.titleGuard(nextTitle)) {
                is PaginationMerger.Verdict.StopAsNextChapter -> {
                    if (nextChapterUrl == null) nextChapterUrl = candidateNext
                    warnings += "${titleVerdict.reason}，停止合并"
                    break
                }
                else -> Unit
            }

            // 提取下一页正文块（与首页同一提取路径）
            val nextBlocks = extractBlocksForPage(rule, nextPage, usedLayer)
            // 新页重复率 > 60% → 终止（防重复页死循环的变体：URL 新但内容重复）
            val dupRate = strategy.evaluateDuplicateRate(nextBlocks)
            if (dupRate > PaginationMerger.DUPLICATE_STOP_RATE) {
                warnings += "新页内容重复率 ${(dupRate * 100).toInt()}% 超限（>60%），终止合并"
                break
            }
            strategy.mergePage(nextPage.url, nextBlocks)
            currentPage = nextPage
        }

        val blocks = strategy.blocks
        val wordCount = blocks.sumOf {
            (it as? com.mvxxcb.reader.domain.model.ContentBlock.Paragraph)?.text?.length ?: 0
        }

        // 整体置信度（关键字段 = 正文内容）
        var overall = extractedConfidence
        if (overall.rank() < options.confidenceThreshold.rank()) {
            overall = Confidence.LOW
            warnings += "整体置信度低于阈值（正文来自通用启发式提取），结果不得当可靠数据"
        }

        return ParseOutcome.Success(
            ParsedChapterContent(
                sourceUrl = sourceUrl,
                title = firstTitle,
                blocks = blocks,
                nextPageUrl = finalNextPageUrl,
                nextChapterUrl = nextChapterUrl,
                previousChapterUrl = previousChapterUrl,
                isTruncated = isTruncated,
                wordCount = wordCount,
            ),
            overall,
            traces.toList(),
            warnings,
        )
    }

    /** 与首页相同的提取路径取下一页块（保持层一致性） */
    private fun extractBlocksForPage(
        rule: com.mvxxcb.reader.parser.rule.ParsedSiteRule?,
        page: PageData,
        usedLayer: ParseLayer?,
    ): List<com.mvxxcb.reader.domain.model.ContentBlock> {
        if (rule != null) {
            val fromRule = if (rule.isManual) ctx.manualApplier.applyContent(rule, page.decoded)
            else ctx.manualApplier.siteRuleContent(rule, page.decoded)
            if (fromRule != null && fromRule.blocks.isNotEmpty()) return fromRule.blocks
        }
        val container = runCatching { ctx.generic.findContentContainer(page.document) }.getOrNull() ?: return emptyList()
        val cleanedHtml = runCatching {
            ctx.cleaner.cleanContentElement(container.container, rule?.cleaningRules ?: emptyList())
        }.getOrNull() ?: return emptyList()
        var blocks = ctx.contentSanitizer.sanitizeContentFragment(cleanedHtml).blocks
        if (blocks.isEmpty()) blocks = ctx.cleaner.buildBlocksFromHtml(cleanedHtml)
        return blocks
    }

    private fun nextPageFromRule(rule: com.mvxxcb.reader.parser.rule.ParsedSiteRule?, page: PageData): String? {
        val spec = rule?.selectors?.get(RuleSelectorKey.CONTENT_NEXT_PAGE) ?: return null
        if (spec !is SelectorSpec.Css) return null // 一期下一页仅支持 css（xpath 未启用）
        val v = (SelectorExecutor.executeFirst(spec, page.document) as? SelectorExecutor.Result.Values)?.values?.firstOrNull()
            ?: return null
        return UrlNormalizer.resolveAbsolute(page.url, v)
    }

    private fun chapterLinkFromRule(rule: com.mvxxcb.reader.parser.rule.ParsedSiteRule?, key: RuleSelectorKey, page: PageData): String? {
        val spec = rule?.selectors?.get(key) ?: return null
        if (spec !is SelectorSpec.Css) return null
        val v = (SelectorExecutor.executeFirst(spec, page.document) as? SelectorExecutor.Result.Values)?.values?.firstOrNull()
            ?: return null
        return UrlNormalizer.resolveAbsolute(page.url, v)
    }
}
