package com.mvxxcb.reader.parser.pipeline

import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.domain.model.ChapterRef
import com.mvxxcb.reader.domain.model.Confidence
import com.mvxxcb.reader.domain.model.ParsedCatalog
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
 * 目录解析步骤（01 §6 目录分页策略）：
 *  - 章节提取：人工/站点规则（catalogSelector + chapterLinkSelector）→ 通用智能发现；
 *  - 分页：沿 catalogNextPageSelector / 智能发现下一页合并（上限 maxCatalogPages）；
 *  - 防护：已见 URL 集合（Loop → PAGINATION_LOOP_DETECTED）、跨域误链、新页重复率 > 60% 停止、
 *    页间编号倒退 → isLikelyReversed 线索（倒序目录是合法站点形态，标记不中断）；
 *  - 上限触发 → truncatedByLimit=true（如实告知，不静默截断）。
 * 目录与正文严格区分：本步骤只认章节列表特征，不提取正文段落。
 */
internal class CatalogParseStep(private val ctx: ParseContext) {

    suspend fun parse(request: ParseRequest): ParseOutcome<ParsedCatalog> {
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
        val ruleLayer = when {
            rule == null -> null
            rule.isManual -> ParseLayer.MANUAL
            else -> ParseLayer.SITE_RULE
        }
        val strategy = ctx.pagination.CatalogStrategy()

        var pendingNext: String? = sourceUrl
        var lastPageUrl = sourceUrl
        var pagesFetched = 0
        var truncatedByLimit = false
        var ruleDriven = false
        var genericConfidence = Confidence.LOW
        var stopReason: String? = null

        while (pendingNext != null) {
            currentCoroutineContext().ensureActive()

            if (pagesFetched >= options.maxCatalogPages) {
                truncatedByLimit = true // 还有下一页但达到上限（不静默截断）
                warnings += "已达目录分页上限（${options.maxCatalogPages} 页），目录被截断"
                break
            }
            if (!budget.canSpend()) {
                truncatedByLimit = true
                warnings += "请求预算耗尽（maxTotalRequests=${options.maxTotalRequests}），目录被截断"
                break
            }
            if (!options.allowPaginationMerge && pagesFetched >= 1) {
                stopReason = "分页合并已禁用（allowPaginationMerge=false）"
                break
            }

            val candidate = pendingNext
            // 防死循环 / 跨域误链（抓取前守卫）
            if (pagesFetched > 0) {
                when (val verdict = strategy.precheckNext(candidate)) {
                    PaginationMerger.Verdict.Loop -> return ParseOutcome.Failure(
                        ReaderError.Parse(
                            ParseFailure.PAGINATION_LOOP_DETECTED,
                            debugDetail = "目录分页回到已访问页: ${ctx.sanitizeUrl(candidate)}",
                        ),
                        traces.toList(),
                    )
                    is PaginationMerger.Verdict.Stop -> { stopReason = verdict.reason; break }
                    is PaginationMerger.Verdict.StopAsNextChapter -> { stopReason = verdict.reason; break }
                    PaginationMerger.Verdict.Proceed ->
                        when (val hostVerdict = strategy.hostGuard(candidate, lastPageUrl)) {
                            is PaginationMerger.Verdict.Stop -> { stopReason = hostVerdict.reason; break }
                            else -> Unit
                        }
                }
            }

            val page = when (val fetched = ctx.fetchPage(candidate, rule?.encoding, budget)) {
                is AppResult.Success -> fetched.value
                is AppResult.Failure ->
                    return ParseOutcome.Failure(
                        if (pagesFetched == 0) fetched.error
                        else ReaderError.Parse(
                            ParseFailure.CATALOG_PAGINATION_FAILED,
                            debugDetail = "第 ${pagesFetched + 1} 页抓取失败: ${fetched.error.debugDetail ?: fetched.error.javaClass.simpleName}",
                        ),
                        traces.toList(),
                    )
            }
            lastPageUrl = page.url
            strategy.markVisited(page.url)
            pagesFetched++

            // 本页章节提取：人工规则 → 站点规则 → 通用
            var pageChapters: List<ChapterRef> = emptyList()
            var usedRuleLayer: ParseLayer? = null
            if (rule != null) {
                val fromRule = if (rule.isManual) ctx.manualApplier.applyCatalog(rule, page.decoded)
                else ctx.manualApplier.siteRuleCatalog(rule, page.decoded)
                if (fromRule != null && fromRule.chapters.isNotEmpty()) {
                    pageChapters = fromRule.chapters
                    usedRuleLayer = ruleLayer
                    ruleDriven = true
                }
            }
            if (pageChapters.isEmpty()) {
                val genericCatalog = runCatching { ctx.generic.extractChapters(page.document, page.url) }.getOrNull()
                if (genericCatalog != null) {
                    pageChapters = genericCatalog.chapters
                    if (genericConfidence < genericCatalog.confidence) genericConfidence = genericCatalog.confidence
                }
            }
            traces += LayerTrace(
                usedRuleLayer ?: ParseLayer.GENERIC,
                if (pageChapters.isEmpty()) LayerOutcome.PARTIAL else LayerOutcome.APPLIED,
                fieldsFilled = if (pageChapters.isEmpty()) emptyList() else listOf("chapters(${pageChapters.size})"),
                detail = "第 $pagesFetched 页提取 ${pageChapters.size} 章（规则层=${usedRuleLayer ?: "无"}）",
            )

            // 首页零章节 → CATALOG_EMPTY / 动态壳
            if (pageChapters.isEmpty() && pagesFetched == 1) {
                val error = if (ctx.pageLooksDynamic(page)) {
                    ReaderError.Parse(ParseFailure.DYNAMIC_PAGE_REQUIRED, debugDetail = "页面疑似动态渲染，未发现目录结构")
                } else {
                    ReaderError.Parse(ParseFailure.CATALOG_EMPTY, debugDetail = "四层均未发现章节列表")
                }
                return ParseOutcome.Failure(error, traces.toList())
            }

            val dupRate = strategy.mergePage(pageChapters)
            if (dupRate > PaginationMerger.DUPLICATE_STOP_RATE && pagesFetched > 1) {
                stopReason = "新页重复率 ${(dupRate * 100).toInt()}% 超限（>60%），停止翻页"
                break
            }

            // 下一页候选：规则 selector → 通用发现
            pendingNext = nextPageFromRule(rule, page)
                ?: runCatching { ctx.generic.detectNextPageLink(page.document, page.url) }.getOrNull()
        }

        val chapters = strategy.finish()
        if (chapters.isEmpty()) {
            return ParseOutcome.Failure(
                ReaderError.Parse(ParseFailure.CATALOG_EMPTY, debugDetail = stopReason ?: "未提取到任何章节"),
                traces.toList(),
            )
        }
        if (strategy.duplicateSkipped > 0) {
            warnings += "跳过重复章节链接 ${strategy.duplicateSkipped} 个"
        }

        // 倒序检测：页内相邻编号递减占比 或 跨页编号倒退（防倒序 = 检测标记）
        val isLikelyReversed = strategy.pageOrderBacktracked ||
            runCatching { strategy.descendingRatio(chapters.map { it.title }) }.getOrDefault(0.0) >= 0.6

        // 整体置信度（关键字段 = 目录章节列表）
        var overall = when {
            ruleDriven -> Confidence.HIGH
            chapters.size >= 5 -> if (genericConfidence.rank() < Confidence.MEDIUM.rank()) Confidence.MEDIUM else genericConfidence
            else -> genericConfidence
        }
        if (overall.rank() < options.confidenceThreshold.rank()) {
            overall = Confidence.LOW
            warnings += "整体置信度低于阈值（目录来自通用启发式且章节数少），结果不得当可靠数据"
        }

        return ParseOutcome.Success(
            ParsedCatalog(
                sourceUrl = sourceUrl,
                chapters = chapters,
                isLikelyReversed = isLikelyReversed,
                paginationTraversed = pagesFetched,
                truncatedByLimit = truncatedByLimit,
            ),
            overall,
            traces.toList(),
            warnings,
        )
    }

    /** 规则 CATALOG_NEXT_PAGE 选择器取下一页（abs + 归一化）；无规则/无命中返回 null */
    private fun nextPageFromRule(rule: com.mvxxcb.reader.parser.rule.ParsedSiteRule?, page: PageData): String? {
        val spec = rule?.selectors?.get(RuleSelectorKey.CATALOG_NEXT_PAGE) ?: return null
        if (spec !is SelectorSpec.Css) return null // 一期下一页仅支持 css（xpath 未启用）
        val r = SelectorExecutor.executeFirst(spec, page.document)
        val v = (r as? SelectorExecutor.Result.Values)?.values?.firstOrNull() ?: return null
        return UrlNormalizer.resolveAbsolute(page.url, v)
    }
}
