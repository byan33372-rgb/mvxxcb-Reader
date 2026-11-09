package com.mvxxcb.reader.parser.rule

import com.mvxxcb.reader.domain.model.Confidence

/** parser 内部使用的已解析规则（不进 domain） */
data class ParsedSiteRule(
    val ruleId: String,
    val domain: String,
    val isManual: Boolean,              // ruleId 前缀 "manual." 即人工规则
    val encoding: String?,
    val selectors: Map<RuleSelectorKey, SelectorSpec>,
    val cleaningRules: List<CleaningRule>,
    val contentMaxPages: Int?,
)

enum class RuleSelectorKey {
    BOOK_NAME, AUTHOR, COVER, DESCRIPTION,
    CATALOG, CATALOG_NEXT_PAGE, CHAPTER_TITLE, CHAPTER_LINK,
    CONTENT, CONTENT_NEXT_PAGE, NEXT_CHAPTER, PREVIOUS_CHAPTER,
}

sealed interface SelectorSpec {
    data class Css(val expr: String, val attr: String = "text") : SelectorSpec      // attr: text|html|href|src|abs:href…
    data class Xpath(val expr: String) : SelectorSpec
    data class Regex(val expr: String, val group: Int = 1) : SelectorSpec
    data class JsonPath(val expr: String) : SelectorSpec
    // 复杂度上限：xpath 深度≤10、regex 长度≤200、jsonpath 深度≤8（实现校验，超限报 INVALID_ARGUMENT）
}

data class CleaningRule(val type: CleaningType, val pattern: String, val replacement: String = "")
enum class CleaningType { REGEX, SELECTOR, WHITESPACE }

sealed interface RuleExtraction<out T> {
    data class Found<T>(val data: T, val fieldConfidence: Map<String, Confidence>) : RuleExtraction<T>
    data class Miss(val reason: String) : RuleExtraction<Nothing>
}

interface RuleEngine {
    /** 取该域名启用中、版本最高的规则并解析为 ParsedSiteRule；无规则返回 null */
    suspend fun loadRule(domain: String): ParsedSiteRule?
    /** 校验规则 JSON（Schema 见 03 §6）；错误逐字段给出（供规则编辑 UI） */
    fun parseRuleJson(ruleJson: String): com.mvxxcb.reader.common.result.AppResult<ParsedSiteRule>
    /** 按规则抽取三类目标（内部已做清洗；选择器执行失败返回 Miss 原因） */
    fun extractDetail(rule: ParsedSiteRule, page: DecodedPage): RuleExtraction<com.mvxxcb.reader.domain.model.ParsedBookDetail>
    fun extractCatalog(rule: ParsedSiteRule, page: DecodedPage): RuleExtraction<com.mvxxcb.reader.domain.model.ParsedCatalog>
    fun extractContent(rule: ParsedSiteRule, page: DecodedPage): RuleExtraction<com.mvxxcb.reader.domain.model.ParsedChapterContent>
}

/** parser 内部页面对象（Jsoup Document 的封装，绝不出 parser 包） */
data class DecodedPage(
    val url: String,
    val html: String,
    val declaredCharset: String?,
    val detectedCharset: String?,
)
