package com.mvxxcb.reader.parser.pipeline

import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.domain.model.Confidence
import com.mvxxcb.reader.domain.model.DetailField
import com.mvxxcb.reader.domain.model.ParsedBookDetail
import com.mvxxcb.reader.domain.model.ParseLayer
import com.mvxxcb.reader.domain.error.ParseFailure
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.parser.core.rank
import com.mvxxcb.reader.parser.core.LayerOutcome
import com.mvxxcb.reader.parser.core.LayerTrace
import com.mvxxcb.reader.parser.core.ParseOutcome
import com.mvxxcb.reader.parser.core.ParseRequest
import com.mvxxcb.reader.parser.structured.FieldCandidate

/**
 * 详情解析步骤（01 §6 四层调度 + 逐字段合并 + LayerTrace）：
 *
 *   层0 人工规则（manual.*，用户意图最高优先）
 *   层1 结构化（JSON-LD → OG → meta → 内嵌 JSON）
 *   层2 站点规则（仅填充未填或 LOW 的字段；冲突取置信度高者，同级先到）
 *   层3 通用智能提取（填充剩余）
 *
 * 合并锁定：字段一旦被 HIGH 填充，后续层不得覆盖（01 §6 锁定规则）。
 * 整体置信度 = 关键字段（书名）置信度取 min；低于 confidenceThreshold 判 LOW 并入 warnings。
 * 字段允许为空禁止伪造；书名缺失 → DETAIL_INCOMPLETE；四层全空且动态壳 → DYNAMIC_PAGE_REQUIRED。
 */
internal class DetailParseStep(private val ctx: ParseContext) {

    /** 逐字段合并器：先到 + 高置信度覆盖 + HIGH 锁定（禁止 ordinal 比较，见 rank() 注释） */
    internal class FieldMerger {
        data class Winner(val candidate: FieldCandidate, val layer: ParseLayer)

        private val winners = linkedMapOf<DetailField, Winner>()

        fun offer(candidates: Map<DetailField, FieldCandidate>, layer: ParseLayer) {
            candidates.forEach { (field, candidate) -> offer(field, candidate, layer) }
        }

        fun offer(field: DetailField, candidate: FieldCandidate, layer: ParseLayer) {
            val existing = winners[field] ?: run {
                winners[field] = Winner(candidate, layer)
                return
            }
            // HIGH 锁定 + 高置信度才覆盖（同置信度先到先得）
            val existingRank = existing.candidate.confidence.rank()
            if (existingRank >= Confidence.HIGH.rank()) return
            if (candidate.confidence.rank() > existingRank) {
                winners[field] = Winner(candidate, layer)
            }
        }

        val size: Int get() = winners.size
        val winnersMap: Map<DetailField, Winner> get() = winners
    }

    suspend fun parse(request: ParseRequest): ParseOutcome<ParsedBookDetail> {
        val options = request.options
        ctx.validateOptions(options)?.let {
            return ParseOutcome.Failure(it, emptyList())
        }
        val budget = RequestBudget(options.maxTotalRequests)
        val traces = mutableListOf<LayerTrace>()
        val warnings = mutableListOf<String>()

        val rule = ctx.loadRuleSafely(ctx.hostOf(request.url))
        val page = when (val fetched = ctx.fetchPage(request.url, rule?.encoding, budget)) {
            is AppResult.Success -> fetched.value
            is AppResult.Failure -> return ParseOutcome.Failure(fetched.error, traces.toList())
        }

        val merger = FieldMerger()

        // 层0：人工规则（manual.*）
        if (rule != null && rule.isManual) {
            val manualFields = runCatching { ctx.manualApplier.applyDetailFields(rule, page.decoded) }.getOrElse { emptyMap() }
            merger.offer(manualFields, ParseLayer.MANUAL)
            traces += LayerTrace(
                ParseLayer.MANUAL,
                if (manualFields.isEmpty()) LayerOutcome.PARTIAL else LayerOutcome.APPLIED,
                manualFields.keys.map { it.name },
                "人工规则 ${rule.ruleId} 命中 ${manualFields.size} 字段",
            )
        } else {
            traces += LayerTrace(ParseLayer.MANUAL, LayerOutcome.SKIPPED_NO_RULE, detail = "无人工规则")
        }

        // 层1：结构化
        runCatching { ctx.structuredSuite.extractDetailFields(page.document, page.url) }
            .onSuccess { structured ->
                merger.offer(structured.fields, ParseLayer.STRUCTURED)
                traces += LayerTrace(
                    ParseLayer.STRUCTURED,
                    if (structured.fields.isEmpty()) LayerOutcome.PARTIAL else LayerOutcome.APPLIED,
                    structured.fields.keys.map { it.name },
                    structured.summary,
                )
            }
            .onFailure { e ->
                traces += LayerTrace(ParseLayer.STRUCTURED, LayerOutcome.FAILED, detail = "结构化提取异常: ${e.message?.take(80)}")
            }

        // 层2：站点规则（manual 已在层0；此处仅站点规则）
        if (rule != null && !rule.isManual) {
            val siteFields = runCatching { ctx.manualApplier.siteRuleDetailFields(rule, page.decoded) }.getOrElse { emptyMap() }
            merger.offer(siteFields, ParseLayer.SITE_RULE)
            traces += LayerTrace(
                ParseLayer.SITE_RULE,
                if (siteFields.isEmpty()) LayerOutcome.PARTIAL else LayerOutcome.APPLIED,
                siteFields.keys.map { it.name },
                "站点规则 ${rule.ruleId} 命中 ${siteFields.size} 字段",
            )
        } else {
            traces += LayerTrace(ParseLayer.SITE_RULE, LayerOutcome.SKIPPED_NO_RULE, detail = "该域名无站点规则")
        }

        // 层3：通用智能提取
        runCatching { ctx.generic.extractDetailFields(page.document, page.url) }
            .onSuccess { genericFields ->
                merger.offer(genericFields, ParseLayer.GENERIC)
                traces += LayerTrace(
                    ParseLayer.GENERIC,
                    if (genericFields.isEmpty()) LayerOutcome.PARTIAL else LayerOutcome.APPLIED,
                    genericFields.keys.map { it.name },
                    "通用层命中 ${genericFields.size} 字段",
                )
            }
            .onFailure { e ->
                traces += LayerTrace(ParseLayer.GENERIC, LayerOutcome.FAILED, detail = "通用提取异常: ${e.message?.take(80)}")
            }

        // 汇总（字段允许为空，禁止伪造）
        val titleWinner = merger.winnersMap[DetailField.TITLE]
        val detail = ParsedBookDetail(
            sourceUrl = page.url,
            title = titleWinner?.candidate?.value,
            author = merger.winnersMap[DetailField.AUTHOR]?.candidate?.value,
            coverUrl = merger.winnersMap[DetailField.COVER]?.candidate?.value,
            description = merger.winnersMap[DetailField.DESCRIPTION]?.candidate?.value,
            catalogUrl = merger.winnersMap[DetailField.CATALOG_URL]?.candidate?.value,
            fieldSources = merger.winnersMap.mapValues { it.value.layer },
            fieldConfidence = merger.winnersMap.mapValues { it.value.candidate.confidence },
        )

        if (detail.title.isNullOrBlank()) {
            // 全部层未识别出书名：动态壳 → DYNAMIC_PAGE_REQUIRED；否则 DETAIL_INCOMPLETE
            val error = if (merger.size == 0 && ctx.pageLooksDynamic(page)) {
                ReaderError.Parse(ParseFailure.DYNAMIC_PAGE_REQUIRED, debugDetail = "页面疑似动态渲染壳，一期不做 WebView 渲染")
            } else {
                ReaderError.Parse(
                    ParseFailure.DETAIL_INCOMPLETE,
                    fieldErrors = mapOf("title" to "四层均未提取到书名"),
                    debugDetail = if (merger.size == 0) "四层均未识别出任何字段" else "关键字段（书名）缺失",
                )
            }
            return ParseOutcome.Failure(error, traces.toList())
        }

        // 整体置信度 = 关键字段（书名）min；低于阈值判 LOW 并入 warnings（rank 显式映射，禁 ordinal）
        var overall = titleWinner!!.candidate.confidence
        if (overall.rank() < options.confidenceThreshold.rank()) {
            overall = Confidence.LOW
            warnings += "整体置信度低于阈值（书名置信度 ${titleWinner.candidate.confidence} < ${options.confidenceThreshold}），结果不得当可靠数据"
        }
        return ParseOutcome.Success(detail, overall, traces.toList(), warnings)
    }
}
