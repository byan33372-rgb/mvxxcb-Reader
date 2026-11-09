package com.mvxxcb.reader.parser.generic

import com.mvxxcb.reader.domain.model.ChapterRef
import com.mvxxcb.reader.domain.model.Confidence
import com.mvxxcb.reader.domain.model.DetailField
import com.mvxxcb.reader.parser.structured.FieldCandidate
import com.mvxxcb.reader.parser.util.UrlNormalizer
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * 层③ 通用 HTML 智能提取（01 §6）：文本密度 / 段落数 / 内容长度候选区判定。
 * 所有产出置信度 LOW~MEDIUM（启发式），合并时让位于更高置信度层。
 */
class GenericSmartExtractor {

    private val NOISE_CLASS_RE = Regex(
        "(?i)(?:^|[\\s_\\-])(ad|ads|advert|banner|sidebar|footer|nav|navbar|menu|breadcrumb|recommend|login)(?:$|[\\s_\\-])",
    )

    // —————————— 详情字段（层③） ——————————

    fun extractDetailFields(document: Document, pageUrl: String): Map<DetailField, FieldCandidate> {
        val result = linkedMapOf<DetailField, FieldCandidate>()

        // 标题：第一个非空 h1/h2
        val h1 = document.select("h1, h2").firstOrNull { it.text().trim().isNotEmpty() }
        h1?.let {
            result[DetailField.TITLE] = FieldCandidate(DetailField.TITLE, it.text().trim(), Confidence.LOW, "通用层 h1/h2")
        }

        // 作者：正文文本中的「作者：xxx」
        val authorMatch = Regex("作者[：:]\\s*([\\u4e00-\\u9fa5\\w]{1,20})").find(document.body().text())
        authorMatch?.let {
            result[DetailField.AUTHOR] = FieldCandidate(DetailField.AUTHOR, it.groupValues[1], Confidence.LOW, "通用层 作者正则")
        }

        // 封面：id/class 含 cover 的 img
        val coverImg = document.select("img[id*=cover], img[class*=cover]").firstOrNull()
            ?: document.select("div[class*=cover] img, div[id*=cover] img").firstOrNull()
        coverImg?.let { img ->
            val url = img.absUrl("src").ifEmpty { img.attr("src") }
            if (url.isNotBlank()) {
                result[DetailField.COVER] = FieldCandidate(
                    DetailField.COVER,
                    UrlNormalizer.resolveAbsolute(pageUrl, url) ?: url,
                    Confidence.LOW,
                    "通用层 cover img",
                )
            }
        }

        // 简介：id/class 含 intro/description/desc 的元素
        val desc = document.selectFirst("[id*=intro], [class*=intro], [id*=description], [class*=description], [class*=desc]")
        desc?.let {
            val text = it.text().trim()
            if (text.length in 10..3000) {
                result[DetailField.DESCRIPTION] = FieldCandidate(DetailField.DESCRIPTION, text, Confidence.LOW, "通用层 intro")
            }
        }

        // 目录链接：文本含 目录/章节列表 的 a[href]
        val catalogLink = document.select("a[href]").firstOrNull { a ->
            val t = a.text().trim()
            (t.contains("目录") || t.contains("章节列表") || t.contains("全部章节")) &&
                UrlNormalizer.hostOf(a.absUrl("href")) != null
        }
        catalogLink?.let {
            val url = UrlNormalizer.resolveAbsolute(pageUrl, it.absUrl("href")) ?: return@let
            result[DetailField.CATALOG_URL] = FieldCandidate(DetailField.CATALOG_URL, url, Confidence.MEDIUM, "通用层 目录链接")
        }
        return result
    }

    // —————————— 目录候选（层③） ——————————

    data class GenericCatalog(
        val chapters: List<ChapterRef>,
        val confidence: Confidence,
    )

    /**
     * 智能发现章节列表：在候选容器（div/ul/dl/table/tbody/body）中选「章节特征链接最多」者；
     * 容器评分阶段即按 [CatalogAnchorFilter] 硬排除非章节路径段（/wenku/ 筛选栏、/class/ 分类、
     * 面包屑站务链接等，阶段 7 验收缺陷修复），最终锚点集合交由统一过滤器构建
     * （同域 + 非章节路径段硬排除 + 书路径段聚类约束 + 分卷锚识别 + 去重）。
     * 少于 2 个有效章节视为无目录。
     */
    fun extractChapters(document: Document, pageUrl: String): GenericCatalog? {
        val containers = listOf(document.body()) + document.select("div, ul, dl, table, tbody").toList()
        var best: Pair<Element, List<Element>>? = null
        for (container in containers) {
            val links = container.select("a[href]").filter { looksLikeChapterLink(it, pageUrl) }
            val current = best
            if (links.size >= 2 && (current == null || links.size > current.second.size)) {
                best = container to links
            }
            if (best != null && best.second.size >= 30) break // 足够大，提前结束扫描
        }
        val chosen = best ?: return null
        if (chosen.second.size < 2) return null

        val chapters = com.mvxxcb.reader.parser.util.CatalogAnchorFilter
            .buildChapters(chosen.second, pageUrl, requireChapterPattern = true) ?: return null

        val confidence = if (chapters.size >= 5) Confidence.MEDIUM else Confidence.LOW
        return GenericCatalog(chapters, confidence)
    }

    private fun looksLikeChapterLink(a: Element, pageUrl: String): Boolean {
        val text = a.text().trim()
        val href = a.attr("href")
        if (text.isEmpty() || text.length > 60) return false
        if (href.isEmpty() || href.startsWith("javascript", true) || href == "#") return false
        // 页码/章节导航文本（下一页/上一章等）→ 排除
        if (com.mvxxcb.reader.parser.util.PaginationMerger.isPageNavText(text)) return false
        // 元素自身或祖先带广告噪声 class → 排除
        if (NOISE_CLASS_RE.containsMatchIn(a.attr("class") + " " + a.attr("id"))) return false
        val abs = a.absUrl("href").ifEmpty { href }
        // 同域约束 + 非章节路径段（/wenku/ 筛选栏等）→ 不算章节容器得分；
        // 分卷锚保留计分（它是真实章节列表区域的一部分，卷名登记依赖它进入统一过滤器）
        if (!com.mvxxcb.reader.parser.util.PaginationMerger.sameHost(abs, pageUrl)) return false
        if (com.mvxxcb.reader.parser.util.CatalogAnchorFilter.isNonChapterPath(abs)) return false
        return com.mvxxcb.reader.parser.util.CatalogAnchorFilter.matchesChapterPattern(text, href)
    }

    // —————————— 正文容器与导航链接（层③） ——————————

    data class ContentCandidate(
        val container: Element,
        val confidence: Confidence,
    )

    /**
     * 候选区判定：文本密度 / 段落数 / 内容长度。
     * score = 段落数×2 + 文本长度/100 − 链接文本占比×50；排除 body/html 自身。
     */
    fun findContentContainer(document: Document): ContentCandidate? {
        val candidates = document.select("div, article, section, td, dd, main").toList()
        var bestScore = Double.NEGATIVE_INFINITY
        var best: Element? = null
        for (el in candidates) {
            val text = el.text()
            if (text.length < 100) continue
            val paragraphs = el.select("p").size + el.select("br").size.coerceAtMost(20)
            val linkTextLen = el.select("a").sumOf { it.text().length }
            val linkDensity = if (text.isEmpty()) 1.0 else linkTextLen.toDouble() / text.length
            val score = paragraphs * 2.0 + text.length / 100.0 - linkDensity * 50.0
            if (score > bestScore) {
                bestScore = score
                best = el
            }
        }
        val chosen = best ?: return null
        val paragraphs = chosen.select("p").size
        val confidence = if (paragraphs >= 2 || chosen.text().length >= 500) Confidence.MEDIUM else Confidence.LOW
        return ContentCandidate(chosen, confidence)
    }

    fun detectNextPageLink(document: Document, pageUrl: String): String? =
        detectNavLink(document, pageUrl, setOf("下一页", "下页", "下一頁", "next", "›", "»"), exclude = setOf("上一页", "上一頁"))

    fun detectNextChapterLink(document: Document, pageUrl: String): String? =
        detectNavLink(document, pageUrl, setOf("下一章", "下一节", "next chapter"), exclude = setOf("下一页", "下一頁"))

    fun detectPreviousChapterLink(document: Document, pageUrl: String): String? =
        detectNavLink(document, pageUrl, setOf("上一章", "上一节", "previous chapter"), exclude = setOf("上一页", "上一頁"))

    private fun detectNavLink(document: Document, pageUrl: String, includes: Set<String>, exclude: Set<String>): String? {
        for (a in document.select("a[href]")) {
            val text = a.text().trim().lowercase()
            if (text.isEmpty() || text.length > 10) continue
            if (exclude.any { text.contains(it) }) continue
            if (includes.any { text.contains(it) }) {
                val href = a.absUrl("href").ifEmpty { a.attr("href") }
                if (href.isEmpty() || href.startsWith("javascript", true) || href == "#") continue
                return UrlNormalizer.resolveAbsolute(pageUrl, href) ?: continue
            }
        }
        return null
    }

    /** 疑似动态渲染页（一期明确不做 WebView → DYNAMIC_PAGE_REQUIRED） */
    fun pageLooksDynamic(document: Document): Boolean {
        val scriptsLen = document.select("script").sumOf { it.data().length }
        val textLen = document.body()?.text()?.length ?: 0
        if (scriptsLen > 0 && textLen < 200 && scriptsLen > textLen * 2) return true
        val appShell = document.selectFirst("#app, #root, [id*=app], [data-v-app]") ?: return false
        return appShell.text().isBlank() && scriptsLen > 0
    }
}
