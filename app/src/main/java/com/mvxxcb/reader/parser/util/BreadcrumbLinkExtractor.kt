package com.mvxxcb.reader.parser.util

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 面包屑导航链接提取器（07 契约 v1.1 K8 BookResolver 配套 util；纯函数，Jsoup 不出 parser 包）。
 *
 * 职责：从页面提取面包屑导航中的链接候选（text + 规范化绝对 URL），供 BookResolver
 * 逐个 classify 验证（候选 ≤6 由调用方截断，本提取器只负责"找全、找对、去重"）。
 *
 * 覆盖的常见面包屑形态（通用启发式，非站点硬编码）：
 *  1. 结构化容器：class/id 含 breadcrumb / crumb / 位置(weizhi) / path 的容器内锚点
 *     （含 Bootstrap 风格 ol.breadcrumb>li>a、div.breadcrumb>a、span.crumb 等变体）；
 *  2. 相邻分隔符文本链：无面包屑 class 但锚点与 "> » › / | ::" 等分隔符相邻的短容器
 *     （如 <div>书城 > <a>小说</a> > <a>目录</a></div>，以及章节页 "上一章 | 目录 | 下一章" 导航条）。
 *
 * 约束：
 *  - 相对链接一律经 [UrlNormalizer.resolveAbsolute] 规范化为绝对 URL；无法解析者丢弃；
 *  - javascript:/#/mailto: 及空文本锚点丢弃；同 URL 去重（保留首次出现，即文档序）；
 *  - 结果保持文档出现顺序（结构化容器候选在前，分隔符链候选在后）；
 *  - maxLinks 兜底上限防病态页面；resolver 侧另有 ≤6 候选的业务截断（07 K8）。
 */
object BreadcrumbLinkExtractor {

    /** 面包屑链接候选（resolver 证据链内部模型，不出 parser 包） */
    data class BreadcrumbLink(
        val text: String,
        val url: String, // 已规范化绝对 URL
    )

    /** 结构化面包屑容器选择器（class/id 子串匹配，覆盖中英文常见命名） */
    private val CONTAINER_SELECTORS = listOf(
        "ol[class*=breadcrumb]", "ul[class*=breadcrumb]",
        "div[class*=breadcrumb]", "nav[class*=breadcrumb]", "span[class*=breadcrumb]",
        "[class*=crumb]", "[id*=crumb]",
        "[class*=weizhi]", "[id*=weizhi]",
        "div[class*=position]", "[id*=position]",
        "div[class*=path]", "[id*=path]",
    )

    /** 分隔符文本链判定用分隔符（中英文常见面包屑/路径分隔符号） */
    private val SEPARATORS = listOf(">", "＞", "»", "›", "/", "|", "::")

    private const val DEFAULT_MAX_LINKS = 24

    /** 分隔符链容器约束：锚点过多（导航菜单）或文本过长（正文容器）不算面包屑 */
    private const val SEPARATOR_CHAIN_MAX_ANCHORS = 10
    private const val SEPARATOR_CHAIN_MAX_TEXT = 200

    private val WHITESPACE_RE = Regex("\\s+")

    /**
     * 提取面包屑链接候选。
     * @param document 页面 Document（resolver 侧经注入的 SecureHttpClient 抓取并解析，不出 parser 包）
     * @param pageUrl 当前页 URL（document.baseUri 为空时的相对链接解析基准）
     * @param maxLinks 输出上限（防病态页面；默认 24）
     */
    fun extract(
        document: Document,
        pageUrl: String? = null,
        maxLinks: Int = DEFAULT_MAX_LINKS,
    ): List<BreadcrumbLink> {
        val base = document.baseUri().ifEmpty { pageUrl.orEmpty() }
        val out = LinkedHashMap<String, BreadcrumbLink>()

        fun add(anchor: Element) {
            val href = anchor.attr("href").trim()
            if (href.isEmpty() || href == "#") return
            if (href.startsWith("javascript", true) || href.startsWith("mailto", true)) return
            val text = anchor.text().replace(WHITESPACE_RE, " ").trim()
            if (text.isEmpty()) return
            val abs = if (href.startsWith("http://") || href.startsWith("https://")) {
                UrlNormalizer.normalize(href)
            } else {
                UrlNormalizer.resolveAbsolute(base, href)
            } ?: return
            if (abs !in out) out[abs] = BreadcrumbLink(text, abs)
        }

        // 1) 结构化容器（breadcrumb/crumb/位置/path）；合并为单次 select 以保持文档出现顺序
        val containers = runCatching { document.select(CONTAINER_SELECTORS.joinToString(",")) }.getOrNull().orEmpty()
        for (container in containers) {
            container.select("a[href]").forEach { add(it) }
        }

        // 2) 相邻分隔符文本链（无面包屑 class 的常见形态；锚点按父容器分组判定）
        val anchors = document.select("a[href]")
        val parents = linkedSetOf<Element>()
        for (a in anchors) {
            a.parent()?.let { parents += it }
        }
        for (parent in parents) {
            val anchorsInParent = parent.select("a[href]")
            if (anchorsInParent.isEmpty() || anchorsInParent.size > SEPARATOR_CHAIN_MAX_ANCHORS) continue
            val parentText = parent.text()
            if (parentText.length > SEPARATOR_CHAIN_MAX_TEXT) continue
            if (SEPARATORS.none { parentText.contains(it) }) continue
            anchorsInParent.forEach { add(it) }
        }

        return out.values.take(maxLinks)
    }
}
