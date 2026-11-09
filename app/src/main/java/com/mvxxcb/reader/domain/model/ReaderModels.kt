package com.mvxxcb.reader.domain.model

/** 阅读器消费的最终内容模型 */
data class ReaderContent(
    val chapterId: Long,
    val chapterIndex: Int,
    val bookId: Long,
    val title: String,                  // 兜底用目录标题
    val blocks: List<ContentBlock>,
)

/** 网址分类结果（由 parser 实现，模型放 domain 供 presentation 使用） */
data class UrlClassification(
    val normalizedUrl: String,
    val kind: PageKind,
    val confidence: Confidence,
    val matchedRuleId: String?,         // 命中的站点规则，可空
    val notes: List<String>,            // 判定依据说明（展示给高级用户）
)
enum class PageKind { BOOK_DETAIL, CATALOG, CHAPTER, UNKNOWN }
enum class Confidence { HIGH, MEDIUM, LOW }
enum class ParseLayer { MANUAL, STRUCTURED, SITE_RULE, GENERIC }
