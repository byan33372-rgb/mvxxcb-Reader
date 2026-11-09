package com.mvxxcb.reader.parser.util

import com.mvxxcb.reader.domain.model.ChapterRef
import org.jsoup.nodes.Element

/**
 * 目录候选锚点过滤器（阶段 7 模拟器验收缺陷修复，generic 层③与 site-rule 层②共用）。
 *
 * 缺陷背景（linovelib.com 实测）：悬浮筛选栏（/wenku/ 下文库筛选 36 链接）与面包屑导航
 * 污染目录提取——旧逻辑仅靠"第N章文本 OR 数字 .html 链接"特征判定，/wenku/dengekibunko/1.html
 * 命中数字链接特征且候选数多于真实章节，导致筛选栏容器得分反超章节容器。
 *
 * 修复规则（契约 02 §5.2 目录解析语义内的通用启发式，非站点硬编码）：
 *  1. 硬排除：锚点解析后必须与当前页同域；路径**首段**属于非章节路径段清单
 *     （/wenku/ /class/ /huihu/ /huiku/ /fenlei/ /rank/ 等——文库筛选/分类/排行/站务页）的直接排除；
 *     页码/章节导航文本（下一页/上一章…）排除。
 *  2. 书路径段约束（聚类实现）：通过章节特征筛选后的候选锚点，若存在 ≥2 个成员且占比 ≥60%
 *     的同一目录前缀簇，则只保留该簇——即"锚点路径前缀包含书的路径段"（如 /novel/5544/）。
 *     书路径段从候选簇反推而非要求详情 URL（parseCatalog 只持有目录页 URL）；
 *     对目录前缀各异的多目录站点（簇不占优）自动退回全量特征候选，不误杀。
 *  3. 分卷锚识别：href 末段为 vol_123.html / volume-12.html 等形态的锚点是**分卷标记**
 *     而非章节（ChapterRef.url 语义 = 章节正文页 URL，分卷页无正文）——不作为章节入库，
 *     其文本按文档顺序作为后续章节的 volumeTitle（与 dl/dt 结构性分卷互为补充，
 *     dt/h2 结构优先，分卷锚文本兜底）。
 *  4. URL 去重：同 URL 保留首次出现（站点目录常有重复挂载）。
 */
object CatalogAnchorFilter {

    /**
     * 非章节路径**首段**清单（通用启发式）。
     * 只检查 URL 路径第一段，避免误杀深路径章节（如 /novel/5544/346134.html 首段是 novel）。
     */
    private val NON_CHAPTER_FIRST_SEGMENTS = setOf(
        // 文库/书库筛选与分类
        "wenku", "huihu", "huiku", "shuku", "class", "classify", "category", "categ",
        "fenlei", "sort", "tags", "tag", "label",
        // 排行/榜单/更新列表
        "rank", "ranking", "paihang", "top", "toplist", "lastupdate", "zuixin", "tops",
        // 站务/用户/推广
        "bookcase", "bookshelf", "review", "pinglun", "author", "zuozhe", "search",
        "user", "member", "login", "register", "app", "download", "help", "about",
        "contact", "links", "friendlink", "news", "mobile", "api", "vote", "tuijian",
        "recommend",
        // 广告与推广位
        "ad", "ads", "advert", "advertisement", "banner", "promote", "promotion",
    )

    /** 分卷锚 href 末段形态：vol_346133.html / volume-12.html / juan3.html … */
    private val VOLUME_ANCHOR_RE = Regex("^(?:vol|volume|juan|fenjuan)[_\\-]?\\d+\\.x?html?$")

    /** 章节文本特征：第N章/节/回（支持中文数字） */
    private val TEXT_CHAPTER_RE = Regex("第\\s*[0-9零〇一二两三四五六七八九十百千]+\\s*[章节回]")

    /** 章节链接特征：数字形 .html/.htm/.xhtml 链接 */
    private val URL_CHAPTER_RE = Regex("\\d+[/_\\-]?\\d*\\.x?html?\\b", RegexOption.IGNORE_CASE)

    fun isNonChapterPath(url: String): Boolean {
        val path = rawPathOf(url) ?: return false
        val first = path.trimStart('/').substringBefore('/').lowercase()
        if (first.isEmpty()) return false
        return first in NON_CHAPTER_FIRST_SEGMENTS
    }

    fun isVolumeAnchor(url: String): Boolean {
        val path = rawPathOf(url) ?: return false
        val last = path.substringAfterLast('/').lowercase()
        return VOLUME_ANCHOR_RE.matches(last)
    }

    /** 章节特征判定（第N章文本 OR 数字 .html 链接 + 短文本） */
    fun matchesChapterPattern(text: String, href: String): Boolean =
        TEXT_CHAPTER_RE.containsMatchIn(text) || (URL_CHAPTER_RE.containsMatchIn(href) && text.length <= 50)

    private fun rawPathOf(url: String): String? = try {
        java.net.URI(url.trim()).rawPath
    } catch (_: Exception) {
        null
    }

    /**
     * 统一锚点过滤与章节构建（generic 与 site-rule 两路唯一实现）。
     *
     * @param anchors          候选锚点（文档顺序）
     * @param pageUrl          当前目录页 URL（规范化；同域约束与相对链接解析基准）
     * @param requireChapterPattern true=层③无规则约束，锚点必须命中章节特征；
     *                              false=层②站点规则（用户选择器已表达意图），仅硬排除+聚类兜底
     * @return 章节引用列表（order 重排 0..n-1）；有效章节 <2 返回 null
     */
    fun buildChapters(
        anchors: List<Element>,
        pageUrl: String,
        requireChapterPattern: Boolean,
    ): List<ChapterRef>? {
        val baseHost = UrlNormalizer.hostOf(pageUrl) ?: return null

        class Stage1(val title: String, val url: String, val el: Element)

        val survivors = mutableListOf<Stage1>()
        for (a in anchors) {
            val text = a.text().trim()
            val href = a.attr("href")
            if (text.isEmpty() || text.length > 80) continue
            if (href.isEmpty() || href.startsWith("javascript", true) || href == "#") continue
            if (PaginationMerger.isPageNavText(text)) continue                     // 规则 1：导航文本
            val abs = UrlNormalizer.resolveAbsolute(pageUrl, href) ?: continue
            if (UrlNormalizer.hostOf(abs) != baseHost) continue                    // 规则 1：同域
            if (abs == pageUrl) continue                                           // 自链（目录页自身）
            if (isNonChapterPath(abs)) continue                                    // 规则 1：非章节路径段硬排除
            // 分卷锚保留在候选流中（聚类计数与卷名登记需要），最终循环跳过入库（规则 3）
            if (requireChapterPattern && !matchesChapterPattern(text, href)) continue
            survivors += Stage1(text.take(120), abs, a)
        }
        if (survivors.size < 2) return null

        // 规则 2：书路径段聚类约束（≥2 成员且 ≥60% 占比的目录前缀簇）
        val kept: List<Stage1> = run {
            val clusters = survivors.groupBy { PaginationMerger.directoryPrefix(it.url) }
            val dominant = clusters.entries
                .filter { it.key != null }
                .maxByOrNull { it.value.size }
            if (dominant != null && dominant.value.size >= 2 &&
                dominant.value.size * 10 >= survivors.size * 6
            ) {
                dominant.value
            } else {
                survivors
            }
        }

        // 规则 3/4：文档序遍历——分卷锚登记卷名（结构性 dt/h2 优先），章节去重入库
        val seen = mutableSetOf<String>()
        var currentVolume: String? = null
        val chapters = mutableListOf<ChapterRef>()
        for (c in kept) {
            if (isVolumeAnchor(c.url)) {
                if (c.title.isNotBlank()) currentVolume = c.title
                continue
            }
            if (!seen.add(c.url)) continue
            val volume = structuralVolumeTitle(c.el) ?: currentVolume
            chapters += ChapterRef(order = chapters.size, title = c.title, url = c.url, volumeTitle = volume)
        }
        return chapters.ifEmpty { null }
    }

    private val STRUCTURAL_VOLUME_TAGS = setOf("dt", "h2", "h3", "h4")

    /**
     * 结构性分卷标题：链接前方最近的 dt/h2/h3/h4 兄弟节点文本
     * （dl/dt/dd 与 ul+h2 两种目录布局；沿父链向上最多 4 层）。
     */
    fun structuralVolumeTitle(link: Element): String? {
        var node: Element? = link
        var depth = 0
        while (node != null && depth < 4) {
            var prev = node.previousElementSibling()
            while (prev != null) {
                if (prev.tagName().lowercase() in STRUCTURAL_VOLUME_TAGS) {
                    val t = prev.text().trim()
                    if (t.isNotEmpty() && t.length <= 60 && t != link.text().trim()) return t
                }
                prev = prev.previousElementSibling()
            }
            node = node.parent()?.takeIf { it.tagName().lowercase() in setOf("dd", "li", "div", "ul", "dl") }
            depth++
        }
        return null
    }
}
