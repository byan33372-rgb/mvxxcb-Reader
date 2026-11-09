package com.mvxxcb.reader.parser.pipeline

import com.mvxxcb.reader.parser.core.ParseOutcome
import com.mvxxcb.reader.parser.core.ParseRequest

// 契约 02 §5.2：四层调度唯一入口（实现 ParserPipelineImpl 由 WP-P 提供）。
// 构造注入（由 AppContainer 装配）：
//   ParserPipelineImpl(secureHttpClient, ruleEngine, sourceRuleRepository,
//                      contentSanitizer, dispatchers, logger)
// pipeline 自行取规则（经 SourceRuleRepository 接口）并按 01 §6 执行四层调度；
// 目录/正文分页遍历中的每次网络请求计数受 ParseOptions.maxTotalRequests 约束，
// 配合 SecureHttpClient 主机限流。

interface ParserPipeline {
    /** 详情解析：JSON-LD → OG/meta → 内嵌 JSON → 站点规则 → 通用提取（合并策略见 01 §6） */
    suspend fun parseDetail(request: ParseRequest): ParseOutcome<com.mvxxcb.reader.domain.model.ParsedBookDetail>

    /** 目录解析：处理正序/倒序/分页/相对链接/重复章节/广告链接/分卷；顺序按目录真实关系 */
    suspend fun parseCatalog(request: ParseRequest): ParseOutcome<com.mvxxcb.reader.domain.model.ParsedCatalog>

    /** 正文解析：标题/段落/图片/分页合并/上下章 URL；正文与目录严格区分 */
    suspend fun parseChapterContent(request: ParseRequest): ParseOutcome<com.mvxxcb.reader.domain.model.ParsedChapterContent>
}
