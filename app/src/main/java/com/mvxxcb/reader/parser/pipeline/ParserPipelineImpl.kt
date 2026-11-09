package com.mvxxcb.reader.parser.pipeline

import com.mvxxcb.reader.common.coroutine.DispatcherProvider
import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.common.time.TimeProvider
import com.mvxxcb.reader.domain.model.ParsedBookDetail
import com.mvxxcb.reader.domain.model.ParsedCatalog
import com.mvxxcb.reader.domain.model.ParsedChapterContent
import com.mvxxcb.reader.domain.repository.SourceRuleRepository
import com.mvxxcb.reader.parser.core.ParseOutcome
import com.mvxxcb.reader.parser.core.ParseRequest
import com.mvxxcb.reader.parser.rule.RuleEngine
import com.mvxxcb.reader.security.ContentSanitizer
import com.mvxxcb.reader.security.SecureHttpClient

/**
 * 解析管线实现（契约 02 §5.2：四层调度唯一入口；01 §6 调度与合并策略）。
 *
 * 构造注入（主 Agent 裁定，AppContainer 装配）：
 * ```
 * ParserPipelineImpl(secureHttpClient, ruleEngine, sourceRuleRepository,
 *                    contentSanitizer, dispatchers, timeProvider, logger)
 * ```
 * 注：契约 02 §5.2 注释为 6 参形态；主 Agent 裁定追加 timeProvider（超时预算与时间戳注入）。
 *
 * 职责：
 *  - 全部网络请求经注入的 SecureHttpClient（DoD2）；任务内请求计数 ≤ maxTotalRequests；
 *  - 四层调度（人工规则 → 结构化 → 站点规则 → 通用）+ 逐字段合并 + LayerTrace；
 *  - 目录/正文分页遍历（PaginationMerger 两套独立策略），上限执行 ParseOptions
 *    并置 isTruncated/truncatedByLimit；协程取消在每次分页迭代检查（ensureActive）；
 *  - Jsoup Document 绝不出 parser 包，对外只返回契约模型（DecodedPage 亦为 parser 内部类型）。
 */
class ParserPipelineImpl(
    secureHttpClient: SecureHttpClient,
    ruleEngine: RuleEngine,
    sourceRuleRepository: SourceRuleRepository,
    contentSanitizer: ContentSanitizer,
    dispatchers: DispatcherProvider,
    @Suppress("unused") private val timeProvider: TimeProvider, // 预留给单次解析任务 60s 超时预算（01 §5.5）
    logger: AppLogger,
) : ParserPipeline {

    private val ctx = ParseContext(secureHttpClient, ruleEngine, sourceRuleRepository, contentSanitizer, dispatchers, logger)
    private val detailStep = DetailParseStep(ctx)
    private val catalogStep = CatalogParseStep(ctx)
    private val contentStep = ContentParseStep(ctx)

    override suspend fun parseDetail(request: ParseRequest): ParseOutcome<ParsedBookDetail> =
        detailStep.parse(request)

    override suspend fun parseCatalog(request: ParseRequest): ParseOutcome<ParsedCatalog> =
        catalogStep.parse(request)

    override suspend fun parseChapterContent(request: ParseRequest): ParseOutcome<ParsedChapterContent> =
        contentStep.parse(request)
}
