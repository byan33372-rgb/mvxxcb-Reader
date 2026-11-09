package com.mvxxcb.reader.parser.rule

import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StateFailure
import com.mvxxcb.reader.domain.model.Confidence
import com.mvxxcb.reader.domain.model.DetailField
import com.mvxxcb.reader.domain.model.ParsedBookDetail
import com.mvxxcb.reader.domain.model.ParsedCatalog
import com.mvxxcb.reader.domain.model.ParsedChapterContent
import com.mvxxcb.reader.domain.model.ParseLayer
import com.mvxxcb.reader.domain.repository.SourceRuleRepository
import com.mvxxcb.reader.parser.util.CatalogAnchorFilter
import com.mvxxcb.reader.parser.util.PaginationMerger
import com.mvxxcb.reader.parser.util.TextCleaner
import com.mvxxcb.reader.parser.util.UrlNormalizer
import com.mvxxcb.reader.security.ContentSanitizer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 站点规则引擎实现（契约 02 §5.3）：
 *  - loadRule：经 SourceRuleRepository 取启用规则并解析；无规则/规则损坏返回 null。
 *  - parseRuleJson：校验规则 JSON（03 §5 Schema）并逐字段回显错误（编码进 debugDetail），
 *    超限报 ReaderError.State(INVALID_ARGUMENT)。
 *  - extractDetail/extractCatalog/extractContent：按规则抽取（内部已做清洗；
 *    选择器执行失败返回 Miss 原因）。
 *
 * 构造注入 contentSanitizer（契约外的装配细节，向主 Agent 说明）：
 * 正文块统一经 ContentSanitizer 构建（防 XSS 唯一出口）；Sanitizer 输出为空时
 * 由 TextCleaner 兜底构建。
 */
class RuleEngineImpl(
    private val sourceRuleRepository: SourceRuleRepository,
    private val contentSanitizer: ContentSanitizer,
) : RuleEngine {

    private val cleaner = TextCleaner()
    private val json = Json { ignoreUnknownKeys = false }

    // —————————————————————— 加载 ——————————————————————

    override suspend fun loadRule(domain: String): ParsedSiteRule? {
        val stored = sourceRuleRepository.getEnabledRule(domain) ?: return null
        return when (val parsed = parseRuleJson(stored.ruleJson)) {
            is AppResult.Success -> parsed.value
            is AppResult.Failure -> null // 启用中的规则损坏 → 视为无规则（记为 MISS，不中断解析）
        }
    }

    // —————————————————————— 规则 JSON 校验 ——————————————————————

    @Serializable
    private data class CleaningRuleDto(
        val type: String? = null,
        val pattern: String? = null,
        val replacement: String? = null,
    )

    @Serializable
    private data class RuleJsonDto(
        val ruleId: String? = null,
        val domain: String? = null,
        val version: Int? = null,
        val enabled: Boolean? = null,
        val encoding: String? = null,
        val bookNameSelector: JsonElement? = null,
        val authorSelector: JsonElement? = null,
        val coverSelector: JsonElement? = null,
        val descriptionSelector: JsonElement? = null,
        val catalogSelector: JsonElement? = null,
        val catalogNextPageSelector: JsonElement? = null,
        val chapterTitleSelector: JsonElement? = null,
        val chapterLinkSelector: JsonElement? = null,
        val contentSelector: JsonElement? = null,
        val nextPageSelector: JsonElement? = null,
        val nextChapterSelector: JsonElement? = null,
        val previousChapterSelector: JsonElement? = null,
        val contentMaxPages: Int? = null,
        val contentCleaningRules: List<CleaningRuleDto> = emptyList(),
    )

    override fun parseRuleJson(ruleJson: String): AppResult<ParsedSiteRule> {
        val errors = linkedMapOf<String, String>()
        val dto: RuleJsonDto = try {
            json.decodeFromString(RuleJsonDto.serializer(), ruleJson)
        } catch (e: Exception) {
            return invalidArgument(mapOf("ruleJson" to "JSON 解析失败: ${e.message?.take(120)}"))
        }

        val ruleId = dto.ruleId?.trim().orEmpty()
        if (ruleId.isEmpty()) {
            errors["ruleId"] = "缺少必填字段"
        } else if (!Regex("^[a-z0-9][a-z0-9._-]{1,63}$").matches(ruleId) &&
            !Regex("^manual\\.[a-z0-9.-]{3,80}$").matches(ruleId)
        ) {
            errors["ruleId"] = "不符合命名规范（slug.v1 或 manual.<domain>）"
        }

        val domain = dto.domain?.trim().orEmpty()
        if (domain.isEmpty()) {
            errors["domain"] = "缺少必填字段"
        } else if (!Regex("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$").matches(domain)) {
            errors["domain"] = "域名格式不正确"
        }

        val version = dto.version
        if (version == null) {
            errors["version"] = "缺少必填字段"
        } else if (version !in 1..999999) {
            errors["version"] = "必须在 1..999999"
        }

        dto.encoding?.let { enc ->
            if (enc !in listOf("UTF-8", "GBK", "GB18030", "BIG5")) errors["encoding"] = "仅允许 UTF-8/GBK/GB18030/BIG5"
        }

        val selectors = linkedMapOf<RuleSelectorKey, SelectorSpec>()
        fun readSelector(key: RuleSelectorKey, el: JsonElement?) {
            el ?: return
            val fieldName = selectorFieldName(key)
            interpretSelector(el, errors, fieldName)?.let { spec ->
                SelectorExecutor.validate(spec)?.let { reason ->
                    errors[fieldName] = reason
                } ?: run { selectors[key] = spec }
            }
        }
        readSelector(RuleSelectorKey.BOOK_NAME, dto.bookNameSelector)
        readSelector(RuleSelectorKey.AUTHOR, dto.authorSelector)
        readSelector(RuleSelectorKey.COVER, dto.coverSelector)
        readSelector(RuleSelectorKey.DESCRIPTION, dto.descriptionSelector)
        readSelector(RuleSelectorKey.CATALOG, dto.catalogSelector)
        readSelector(RuleSelectorKey.CATALOG_NEXT_PAGE, dto.catalogNextPageSelector)
        readSelector(RuleSelectorKey.CHAPTER_TITLE, dto.chapterTitleSelector)
        readSelector(RuleSelectorKey.CHAPTER_LINK, dto.chapterLinkSelector)
        readSelector(RuleSelectorKey.CONTENT, dto.contentSelector)
        readSelector(RuleSelectorKey.CONTENT_NEXT_PAGE, dto.nextPageSelector)
        readSelector(RuleSelectorKey.NEXT_CHAPTER, dto.nextChapterSelector)
        readSelector(RuleSelectorKey.PREVIOUS_CHAPTER, dto.previousChapterSelector)

        val cleaningRules = mutableListOf<CleaningRule>()
        if (dto.contentCleaningRules.size > 32) {
            errors["contentCleaningRules"] = "最多 32 条"
        } else {
            dto.contentCleaningRules.forEachIndexed { i, c ->
                val type = c.type?.trim().orEmpty()
                if (type !in listOf("regex", "selector", "whitespace")) {
                    errors["contentCleaningRules[$i].type"] = "仅允许 regex/selector/whitespace"
                    return@forEachIndexed
                }
                val pattern = c.pattern?.trim().orEmpty()
                if (pattern.isEmpty() || pattern.length > 300) {
                    errors["contentCleaningRules[$i].pattern"] = "长度必须为 1..300"
                    return@forEachIndexed
                }
                if (type == "regex") {
                    try {
                        Regex(pattern)
                    } catch (e: Exception) {
                        errors["contentCleaningRules[$i].pattern"] = "正则语法错误: ${e.message?.take(60)}"
                        return@forEachIndexed
                    }
                }
                cleaningRules += CleaningRule(CleaningType.valueOf(type.uppercase()), pattern, c.replacement.orEmpty())
            }
        }

        val contentMaxPages = dto.contentMaxPages
        if (contentMaxPages != null && contentMaxPages !in 1..20) {
            errors["contentMaxPages"] = "必须在 1..20"
        }

        if (errors.isNotEmpty()) return invalidArgument(errors)

        return AppResult.Success(
            ParsedSiteRule(
                ruleId = ruleId,
                domain = domain,
                isManual = ruleId.startsWith("manual."),
                encoding = dto.encoding,
                selectors = selectors,
                cleaningRules = cleaningRules,
                contentMaxPages = contentMaxPages,
            ),
        )
    }

    private fun invalidArgument(errors: Map<String, String>): AppResult.Failure =
        AppResult.Failure(
            ReaderError.State(
                StateFailure.INVALID_ARGUMENT,
                debugDetail = "fieldErrors={" + errors.entries.joinToString("; ") { "${it.key}=${it.value}" } + "}",
            ),
        )

    private fun selectorFieldName(key: RuleSelectorKey): String = when (key) {
        RuleSelectorKey.BOOK_NAME -> "bookNameSelector"
        RuleSelectorKey.AUTHOR -> "authorSelector"
        RuleSelectorKey.COVER -> "coverSelector"
        RuleSelectorKey.DESCRIPTION -> "descriptionSelector"
        RuleSelectorKey.CATALOG -> "catalogSelector"
        RuleSelectorKey.CATALOG_NEXT_PAGE -> "catalogNextPageSelector"
        RuleSelectorKey.CHAPTER_TITLE -> "chapterTitleSelector"
        RuleSelectorKey.CHAPTER_LINK -> "chapterLinkSelector"
        RuleSelectorKey.CONTENT -> "contentSelector"
        RuleSelectorKey.CONTENT_NEXT_PAGE -> "nextPageSelector"
        RuleSelectorKey.NEXT_CHAPTER -> "nextChapterSelector"
        RuleSelectorKey.PREVIOUS_CHAPTER -> "previousChapterSelector"
    }

    /** 规则 JSON 选择器解释：字符串 = CSS 简写（可带 @attr 后缀）；对象 = {type, expr, attr} */
    private fun interpretSelector(el: JsonElement, errors: MutableMap<String, String>, field: String): SelectorSpec? = when (el) {
        is JsonPrimitive -> {
            if (!el.isString) {
                errors[field] = "选择器必须是字符串或对象"
                null
            } else {
                val (expr, attr) = SelectorExecutor.splitCssAttr(el.content)
                SelectorSpec.Css(expr, attr)
            }
        }
        is JsonObject -> {
            val type = (el["type"] as? JsonPrimitive)?.content?.trim()?.lowercase()
            val expr = (el["expr"] as? JsonPrimitive)?.content?.trim().orEmpty()
            if (expr.isEmpty() || expr.length > 300) {
                errors["$field.expr"] = "长度必须为 1..300"
                null
            } else {
                when (type) {
                    "css" -> SelectorSpec.Css(expr, (el["attr"] as? JsonPrimitive)?.content ?: "text")
                    "xpath" -> SelectorSpec.Xpath(expr)
                    "regex" -> SelectorSpec.Regex(expr, (el["group"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1)
                    "jsonpath" -> SelectorSpec.JsonPath(expr)
                    null -> {
                        errors["$field.type"] = "缺少 type"
                        null
                    }
                    else -> {
                        errors["$field.type"] = "未知类型 $type"
                        null
                    }
                }
            }
        }
        else -> {
            errors[field] = "选择器必须是字符串或对象"
            null
        }
    }

    // —————————————————————— 详情抽取 ——————————————————————

    override fun extractDetail(rule: ParsedSiteRule, page: DecodedPage): RuleExtraction<ParsedBookDetail> {
        val doc = Jsoup.parse(page.html, page.url)
        val values = linkedMapOf<DetailField, String>()

        fun take(key: RuleSelectorKey, field: DetailField, absolutize: Boolean = false) {
            val spec = rule.selectors[key] ?: return
            val r = SelectorExecutor.executeFirst(spec, doc)
            if (r is SelectorExecutor.Result.Values) {
                var v = r.values.first()
                if (absolutize) v = UrlNormalizer.resolveAbsolute(page.url, v) ?: v
                values[field] = v
            }
        }
        take(RuleSelectorKey.BOOK_NAME, DetailField.TITLE)
        take(RuleSelectorKey.AUTHOR, DetailField.AUTHOR)
        take(RuleSelectorKey.COVER, DetailField.COVER, absolutize = true)
        take(RuleSelectorKey.DESCRIPTION, DetailField.DESCRIPTION)

        if (values.isEmpty()) return RuleExtraction.Miss("规则选择器无命中")

        val description = values[DetailField.DESCRIPTION]?.let { cleaner.applyTextRules(it, rule.cleaningRules) }
        val layer = if (rule.isManual) ParseLayer.MANUAL else ParseLayer.SITE_RULE
        val detail = ParsedBookDetail(
            sourceUrl = page.url,
            title = values[DetailField.TITLE],
            author = values[DetailField.AUTHOR],
            coverUrl = values[DetailField.COVER],
            description = description,
            catalogUrl = null, // 规则 Schema 无目录 URL 选择器；由结构化/通用层补充
            fieldSources = values.keys.associateWith { layer },
            fieldConfidence = values.keys.associateWith { Confidence.HIGH },
        )
        return RuleExtraction.Found(
            detail,
            fieldConfidence = values.keys.associate { it.name to Confidence.HIGH },
        )
    }

    // —————————————————————— 目录抽取（单页） ——————————————————————

    override fun extractCatalog(rule: ParsedSiteRule, page: DecodedPage): RuleExtraction<ParsedCatalog> {
        val doc = Jsoup.parse(page.html, page.url)
        val container = resolveContainer(doc, rule.selectors[RuleSelectorKey.CATALOG])
            ?: return RuleExtraction.Miss("catalogSelector 无匹配")

        val linkSpec = rule.selectors[RuleSelectorKey.CHAPTER_LINK]
        val anchors: List<Element> = when (linkSpec) {
            is SelectorSpec.Css -> try {
                container.select(linkSpec.expr)
            } catch (_: Exception) {
                emptyList()
            }
            null -> container.select("a[href]")
            else -> return RuleExtraction.Miss("chapterLinkSelector 类型不支持（仅 css）")
        }

        // 统一锚点过滤（阶段 7 验收缺陷修复）：同域 + 非章节路径段硬排除（/wenku/ 筛选栏等）
        // + 书路径段聚类约束 + 分卷锚识别（vol_*.html 记卷名不入章节）+ URL 去重。
        // 站点规则路径 requireChapterPattern=false：用户选择器已表达意图，不做章节文本特征强制。
        val chapters = CatalogAnchorFilter.buildChapters(anchors, page.url, requireChapterPattern = false)
            ?: return RuleExtraction.Miss("规则选择器未命中章节链接（或全部被非章节锚点过滤）")

        val reversed = PaginationMerger().CatalogStrategy().descendingRatio(chapters.map { it.title }) >= 0.6
        val catalog = ParsedCatalog(
            sourceUrl = page.url,
            chapters = chapters,
            isLikelyReversed = reversed,
            paginationTraversed = 1,
            truncatedByLimit = false,
        )
        return RuleExtraction.Found(catalog, fieldConfidence = mapOf("chapters" to Confidence.HIGH))
    }

    private fun resolveContainer(doc: org.jsoup.nodes.Document, spec: SelectorSpec?): Element? = when (spec) {
        is SelectorSpec.Css -> try {
            doc.select(SelectorExecutor.splitCssAttr(spec.expr).first).first()
        } catch (_: Exception) {
            null
        }
        null -> doc.body()
        else -> null
    }

    // —————————————————————— 正文抽取（单页） ——————————————————————

    override fun extractContent(rule: ParsedSiteRule, page: DecodedPage): RuleExtraction<ParsedChapterContent> {
        val doc = Jsoup.parse(page.html, page.url)

        val title = rule.selectors[RuleSelectorKey.CHAPTER_TITLE]?.let {
            (SelectorExecutor.executeFirst(it, doc) as? SelectorExecutor.Result.Values)?.values?.firstOrNull()
        }

        val container = resolveContainer(doc, rule.selectors[RuleSelectorKey.CONTENT])
            ?: return RuleExtraction.Miss("contentSelector 无匹配")

        val cleanedHtml = cleaner.cleanContentElement(container, rule.cleaningRules)
        var blocks = contentSanitizer.sanitizeContentFragment(cleanedHtml).blocks
        if (blocks.isEmpty()) blocks = cleaner.buildBlocksFromHtml(cleanedHtml)
        blocks = blocks
            .map { b ->
                if (b is com.mvxxcb.reader.domain.model.ContentBlock.Paragraph) {
                    b.copy(text = cleaner.collapseWhitespace(cleaner.applyTextRules(b.text, rule.cleaningRules)))
                } else {
                    b
                }
            }
            .filter { it !is com.mvxxcb.reader.domain.model.ContentBlock.Paragraph || it.text.isNotBlank() }

        if (blocks.isEmpty()) return RuleExtraction.Miss("正文清洗后为空")

        fun navUrl(key: RuleSelectorKey): String? {
            val spec = rule.selectors[key] ?: return null
            val v = (SelectorExecutor.executeFirst(spec, doc) as? SelectorExecutor.Result.Values)?.values?.firstOrNull()
                ?: return null
            return UrlNormalizer.resolveAbsolute(page.url, v)
        }
        val nextPage = navUrl(RuleSelectorKey.CONTENT_NEXT_PAGE)?.takeIf { it != page.url }
        val nextChapter = navUrl(RuleSelectorKey.NEXT_CHAPTER)
        val prevChapter = navUrl(RuleSelectorKey.PREVIOUS_CHAPTER)

        val wordCount = blocks.sumOf { (it as? com.mvxxcb.reader.domain.model.ContentBlock.Paragraph)?.text?.length ?: 0 }
        val content = ParsedChapterContent(
            sourceUrl = page.url,
            title = title,
            blocks = blocks,
            nextPageUrl = nextPage,
            nextChapterUrl = nextChapter,
            previousChapterUrl = prevChapter,
            isTruncated = false,
            wordCount = wordCount,
        )
        return RuleExtraction.Found(content, fieldConfidence = mapOf("content" to Confidence.HIGH))
    }
}
