package com.mvxxcb.reader.domain.model

data class ParsedBookDetail(
    val sourceUrl: String,                          // 规范化详情页 URL
    val title: String?,                             // 允许为空，禁止伪造
    val author: String?,
    val coverUrl: String?,
    val description: String?,
    val catalogUrl: String?,                        // 与详情页不同时的目录页
    val fieldSources: Map<DetailField, ParseLayer> = emptyMap(),
    val fieldConfidence: Map<DetailField, Confidence> = emptyMap(),
)
enum class DetailField { TITLE, AUTHOR, COVER, DESCRIPTION, CATALOG_URL }

data class ChapterRef(
    val order: Int,                 // 站点目录中的真实顺序（0-based）
    val title: String,
    val url: String,                // 已规范化绝对 URL
    val volumeTitle: String? = null,    // 分卷标题（无分卷为 null）
)

data class ParsedCatalog(
    val sourceUrl: String,
    val chapters: List<ChapterRef>,     // 已按站点目录真实顺序排列
    val isLikelyReversed: Boolean,      // 检测到倒序目录
    val paginationTraversed: Int,       // 实际抓取的目录页数
    val truncatedByLimit: Boolean,      // 达到目录分页上限
)

sealed interface ContentBlock {
    data class Paragraph(val text: String) : ContentBlock
    data class Image(val url: String, val alt: String?) : ContentBlock
}

data class ParsedChapterContent(
    val sourceUrl: String,              // 规范化章节 URL
    val title: String?,                 // 页面章节标题（可与目录不同，可空）
    val blocks: List<ContentBlock>,     // 段落顺序 = 阅读顺序，禁止乱序
    val nextPageUrl: String?,           // 章节内分页下一页（null = 本章完）
    val nextChapterUrl: String?,
    val previousChapterUrl: String?,
    val isTruncated: Boolean,           // 达分页/大小上限被截断
    val wordCount: Int,                 // 纯文字字符数
)
