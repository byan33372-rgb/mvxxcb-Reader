package com.mvxxcb.reader.parser.util

import com.mvxxcb.reader.domain.model.ContentBlock
import com.mvxxcb.reader.parser.rule.CleaningRule
import com.mvxxcb.reader.parser.rule.CleaningType
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * 正文清洗（04 §2 / 01 §6）：清理广告/导航/页脚/登录提示，保留段落顺序与合法图片。
 *
 * 职责边界：
 *  - [cleanContentElement]：DOM 级清理（站点规则 SELECTOR 移除 + 启发式广告/登录清理），
 *    返回清理后的内部 HTML（交 ContentSanitizer 二次净化并构建内容块）。
 *  - [applyTextRules]：文本级 REGEX / WHITESPACE 清洗规则。
 *  - [buildBlocksFromHtml]：兜底块构建（ContentSanitizer 输出为空时使用，保持段落顺序）。
 *
 * 隐私与安全：图片 URL 一律先转绝对地址；不保留任何事件属性（块模型只含文本与 URL）。
 */
class TextCleaner {

    /** 启发式清理命中的 id/class 关键词（词边界匹配，避免误杀含 ad 的正常词） */
    private val NOISE_CLASS_RE = Regex(
        "(?i)(?:^|[\\s_\\-])(ad|ads|advert|advertisement|banner|promote|promotion|" +
            "sidebar|footer|nav|navbar|menu|breadcrumb|login|signin|recommend|recomm|tip|tips|notice-bar)(?:$|[\\s_\\-])",
    )

    private val NOISE_TAGS = setOf("script", "style", "iframe", "noscript", "form", "button", "input", "select", "aside", "footer", "nav")

    private val LOGIN_PROMPT_RE = Regex(
        "(?i)(请登录|登录后|立即登录|点击登录|注册会员|立即注册|开通.?vip|订阅本章|订阅全书|" +
            "下载.{0,6}(app|客户端)|app.{0,4}(阅读|内查看)|扫码关注)",
    )

    /**
     * DOM 级清理：规则 SELECTOR 移除 + 启发式噪声清理。
     * 就地清理调用方传入的容器（容器来自本次抓取新建的 Document，无共享状态），
     * 以保证 `absUrl` 能基于文档 baseUri 解析出绝对图片地址。
     * 返回清理后的 innerHtml（调用方再交给 ContentSanitizer 净化并构建块）。
     */
    fun cleanContentElement(container: Element, cleaningRules: List<CleaningRule>): String {
        val work = container

        // 1. 危险/噪声标签
        work.select(NOISE_TAGS.joinToString(",") { it }).remove()

        // 2. 站点规则：SELECTOR 移除（先于启发式，尊重站点特异性）
        cleaningRules.filter { it.type == CleaningType.SELECTOR }.forEach { rule ->
            try {
                work.select(rule.pattern).remove()
            } catch (_: Exception) {
                // 无效选择器忽略（不中断清洗）
            }
        }

        // 3. 启发式：id/class 噪声元素
        work.select("[id],[class]").forEach { el ->
            val marker = (el.attr("id") + " " + el.attr("class")).trim()
            if (marker.isNotEmpty() && NOISE_CLASS_RE.containsMatchIn(marker)) {
                el.remove()
            }
        }

        // 4. 启发式：登录/订阅提示（整元素删除；「本章未完」类页码标记是正文内嵌文本，
        //    不能整段删除——由站点规则的 REGEX 清洗在文本级处理）
        work.select("div,p,span,section,td,li").forEach { el ->
            val text = el.text()
            if (text.length <= 80 && LOGIN_PROMPT_RE.containsMatchIn(text)) {
                el.remove()
            }
        }

        // 5. 清空残留空节点
        work.select("p,div,span").forEach { el -> if (el.text().isBlank() && el.select("img").isEmpty()) el.remove() }

        // 6. 图片链接转绝对地址（在序列化前完成，Sanitizer 的 Image.url 即为绝对地址）
        work.select("img").forEach { img ->
            val abs = img.absUrl("src").ifEmpty { img.attr("src") }
            if (abs.isNotBlank()) img.attr("src", abs)
        }

        return work.html()
    }

    /** 文本级清洗规则（REGEX / WHITESPACE）；SELECTOR 规则已在 DOM 阶段处理 */
    fun applyTextRules(text: String, rules: List<CleaningRule>): String {
        var result = text
        for (rule in rules) {
            when (rule.type) {
                CleaningType.REGEX -> try {
                    result = Regex(rule.pattern).replace(result, rule.replacement)
                } catch (_: Exception) {
                    // 无效正则忽略
                }
                CleaningType.WHITESPACE -> result = collapseWhitespace(result)
                CleaningType.SELECTOR -> Unit // 已在 DOM 阶段处理
            }
        }
        return result
    }

    /** 连续空白折叠为单个空格、去首尾（WHITESPACE 规则与通用兜底） */
    fun collapseWhitespace(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    /**
     * 兜底块构建：从清理后的 HTML 依文档顺序构建段落/图片块（段落顺序 = 阅读顺序）。
     * 仅当注入的 ContentSanitizer 输出为空但 DOM 有内容时使用。
     */
    fun buildBlocksFromHtml(html: String): List<ContentBlock> {
        if (html.isBlank()) return emptyList()
        val body = Jsoup.parseBodyFragment(html).body()
        val blocks = mutableListOf<ContentBlock>()
        val textBuf = StringBuilder()

        fun flushText() {
            val t = textBuf.toString().trim()
            if (t.isNotEmpty()) blocks += ContentBlock.Paragraph(t)
            textBuf.setLength(0)
        }

        fun walk(node: Node) {
            when (node) {
                is TextNode -> textBuf.append(node.text())
                is Element -> when (node.tagName().lowercase()) {
                    "br" -> flushText()
                    "img" -> {
                        flushText()
                        val url = node.absUrl("src").ifEmpty { node.attr("src") }
                        if (url.isNotBlank() && !url.startsWith("javascript:", ignoreCase = true)) {
                            blocks += ContentBlock.Image(url, node.attr("alt").ifBlank { null })
                        }
                    }
                    "script", "style", "iframe", "noscript" -> Unit
                    "p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote" -> {
                        // 块级元素：整段输出（内部 br 拆行）
                        flushText()
                        node.childNodes().forEach { walk(it) }
                        flushText()
                    }
                    else -> node.childNodes().forEach { walk(it) }
                }
                else -> Unit
            }
        }

        body.childNodes().forEach { walk(it) }
        flushText()
        return blocks
    }
}
