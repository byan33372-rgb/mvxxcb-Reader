package com.mvxxcb.reader.parser.manual

import com.mvxxcb.reader.domain.model.Confidence
import com.mvxxcb.reader.domain.model.DetailField
import com.mvxxcb.reader.domain.model.ParsedCatalog
import com.mvxxcb.reader.domain.model.ParsedChapterContent
import com.mvxxcb.reader.parser.structured.FieldCandidate
import com.mvxxcb.reader.parser.rule.DecodedPage
import com.mvxxcb.reader.parser.rule.ParsedSiteRule
import com.mvxxcb.reader.parser.rule.RuleEngine

/**
 * 人工规则应用层（01 §6 步骤 0 / 契约 02 §5.3 注释）：
 * manual.* 规则（用户显式配置）优先于一切自动层，字段置信度 HIGH（用户意图最高优先）。
 * 仅填充规则已配置且命中的字段，其余字段继续走自动层。
 */
class ManualRuleApplier(private val ruleEngine: RuleEngine) {

    /** 人工规则命中的详情字段（MANUAL 层，HIGH） */
    fun applyDetailFields(rule: ParsedSiteRule, page: DecodedPage): Map<DetailField, FieldCandidate> {
        if (!rule.isManual) return emptyMap()
        val found = when (val r = ruleEngine.extractDetail(rule, page)) {
            is com.mvxxcb.reader.parser.rule.RuleExtraction.Found -> r.data
            is com.mvxxcb.reader.parser.rule.RuleExtraction.Miss -> return emptyMap()
        }
        val candidates = linkedMapOf<DetailField, FieldCandidate>()
        listOf(
            DetailField.TITLE to found.title,
            DetailField.AUTHOR to found.author,
            DetailField.COVER to found.coverUrl,
            DetailField.DESCRIPTION to found.description,
        ).forEach { (field, value) ->
            if (!value.isNullOrBlank()) {
                candidates[field] = FieldCandidate(field, value, Confidence.HIGH, "人工规则 ${rule.ruleId}")
            }
        }
        return candidates
    }

    /** 人工规则目录抽取（单页）；Miss 返回 null */
    fun applyCatalog(rule: ParsedSiteRule, page: DecodedPage): ParsedCatalog? {
        if (!rule.isManual) return null
        return when (val r = ruleEngine.extractCatalog(rule, page)) {
            is com.mvxxcb.reader.parser.rule.RuleExtraction.Found -> r.data
            is com.mvxxcb.reader.parser.rule.RuleExtraction.Miss -> null
        }
    }

    /** 人工规则正文抽取（单页）；Miss 返回 null */
    fun applyContent(rule: ParsedSiteRule, page: DecodedPage): ParsedChapterContent? {
        if (!rule.isManual) return null
        return when (val r = ruleEngine.extractContent(rule, page)) {
            is com.mvxxcb.reader.parser.rule.RuleExtraction.Found -> r.data
            is com.mvxxcb.reader.parser.rule.RuleExtraction.Miss -> null
        }
    }

    /** 站点规则（非人工）抽取详情字段候选（SITE_RULE 层，HIGH） */
    fun siteRuleDetailFields(rule: ParsedSiteRule, page: DecodedPage): Map<DetailField, FieldCandidate> {
        if (rule.isManual) return emptyMap()
        val found = when (val r = ruleEngine.extractDetail(rule, page)) {
            is com.mvxxcb.reader.parser.rule.RuleExtraction.Found -> r.data
            is com.mvxxcb.reader.parser.rule.RuleExtraction.Miss -> return emptyMap()
        }
        val candidates = linkedMapOf<DetailField, FieldCandidate>()
        listOf(
            DetailField.TITLE to found.title,
            DetailField.AUTHOR to found.author,
            DetailField.COVER to found.coverUrl,
            DetailField.DESCRIPTION to found.description,
            DetailField.CATALOG_URL to found.catalogUrl,
        ).forEach { (field, value) ->
            if (!value.isNullOrBlank()) {
                candidates[field] = FieldCandidate(field, value, Confidence.HIGH, "站点规则 ${rule.ruleId}")
            }
        }
        return candidates
    }

    /** 站点规则（非人工）目录抽取；Miss 返回 null */
    fun siteRuleCatalog(rule: ParsedSiteRule, page: DecodedPage): ParsedCatalog? {
        if (rule.isManual) return null
        return when (val r = ruleEngine.extractCatalog(rule, page)) {
            is com.mvxxcb.reader.parser.rule.RuleExtraction.Found -> r.data
            is com.mvxxcb.reader.parser.rule.RuleExtraction.Miss -> null
        }
    }

    /** 站点规则（非人工）正文抽取；Miss 返回 null */
    fun siteRuleContent(rule: ParsedSiteRule, page: DecodedPage): ParsedChapterContent? {
        if (rule.isManual) return null
        return when (val r = ruleEngine.extractContent(rule, page)) {
            is com.mvxxcb.reader.parser.rule.RuleExtraction.Found -> r.data
            is com.mvxxcb.reader.parser.rule.RuleExtraction.Miss -> null
        }
    }
}
