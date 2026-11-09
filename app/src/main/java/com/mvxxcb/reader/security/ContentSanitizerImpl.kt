package com.mvxxcb.reader.security

import com.mvxxcb.reader.domain.model.ContentBlock
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * 内容净化实现（契约 02 §4.3 / 04 §5 DoD4，正文防 XSS）。
 *
 * 策略：不采用"黑名单清洗后仍输出 HTML"的路线，而是**降维为受控块序列**——
 * 输出只有 [ContentBlock.Paragraph]（纯文本）与 [ContentBlock.Image]（URL 受协议白名单
 * 约束）两种形态，阅读器仅受控渲染，结构性消灭 XSS 载体：
 *  - 危险元素整棵剥离：script/style/iframe/frame/frameset/object/embed/applet/
 *    link/meta/base/form 族/template/noscript/svg/math（svg、math 可携带脚本/外部引用）；
 *  - 属性全丢弃：on* 事件、style、href 等一律不进入输出（链接仅保留锚文本）；
 *  - 图片 src 白名单：http/https 或 data:image 前缀（按主 Agent 指令"非图片 data:"禁用）；
 *    javascript: 等危险协议与无法判定协议的 src 直接丢弃该图并计入删除数；
 *  - 相对地址 src 不伪造绝对地址（无 baseUri 入参），保持原样交由 parser 侧绝对化。
 *
 * 段落语义：块级元素（p/div/h1-6/li/tr/td/blockquote…）与 <br>/<hr> 均为段落边界；
 * 行内元素（span/b/i/a…）透明穿透合并文本；空白折叠为单空格，&nbsp; 归一为空格。
 */
class ContentSanitizerImpl : ContentSanitizer {

    override fun sanitizeContentFragment(html: String): SanitizedFragment {
        val doc = Jsoup.parseBodyFragment(html)

        // 1) 危险元素整树剥离（选择在移除前完成，嵌套元素各自计入删除数）
        val dangerous = doc.select(DANGEROUS_SELECTOR)
        var removed = dangerous.size
        dangerous.remove()

        // 2) 顺序遍历产出块序列
        val blocks = ArrayList<ContentBlock>()
        val paragraph = StringBuilder()
        val walker = Walker(blocks, paragraph)

        for (child in doc.body().childNodes()) {
            walker.walk(child)
        }
        walker.flush()

        return SanitizedFragment(blocks = blocks, removedElementCount = removed + walker.droppedImages)
    }

    private class Walker(
        val blocks: ArrayList<ContentBlock>,
        val paragraph: StringBuilder,
    ) {
        var droppedImages = 0

        fun flush() {
            if (paragraph.isBlank()) {
                paragraph.setLength(0)
                return
            }
            blocks.add(ContentBlock.Paragraph(paragraph.toString().trim()))
            paragraph.setLength(0)
        }

        fun walk(node: Node) {
            when (node) {
                is TextNode -> paragraph.append(collapseWhitespace(node.wholeText))
                is Element -> {
                    val tag = node.tagName().lowercase()
                    when {
                        tag == "img" -> handleImage(node)
                        tag == "br" || tag == "hr" -> flush()
                        tag in BLOCK_TAGS -> {
                            flush()
                            for (child in node.childNodes()) walk(child)
                            flush()
                        }
                        else -> {
                            // 行内元素与未知元素：透明穿透（属性不进入输出）
                            for (child in node.childNodes()) walk(child)
                        }
                    }
                }
                else -> Unit   // 注释/DOCTYPE/DataNode：跳过
            }
        }

        private fun handleImage(img: Element) {
            flush()   // 图片总是自成一个块
            val rawSrc = img.attr("src").trim()
            val src = rawSrc.lowercase()
            val isAllowed = src.startsWith("http://") ||
                src.startsWith("https://") ||
                src.startsWith("data:image/") ||
                !SCHEME_RE.containsMatchIn(src)   // 无 scheme 的相对地址：原样保留，不伪造绝对地址
            if (rawSrc.isEmpty() || !isAllowed) {
                droppedImages++
                return
            }
            val alt = img.attr("alt").trim().takeIf { it.isNotEmpty() }
            blocks.add(ContentBlock.Image(url = rawSrc, alt = alt))
        }
    }

    companion object {
        private val DANGEROUS_SELECTOR =
            "script,style,iframe,frame,frameset,object,embed,applet,link,meta,base," +
                "form,input,button,select,textarea,template,noscript,video,audio,svg,math"

        private val BLOCK_TAGS = setOf(
            "p", "div", "section", "article", "aside", "header", "footer", "main", "nav",
            "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "center",
            "ul", "ol", "li", "dl", "dt", "dd",
            "table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption",
            "figure", "figcaption", "details", "summary",
        )

        private val WHITESPACE_RE = Regex("[\\s\\u00A0\\u3000\\u200B\\uFEFF]+")

        private val SCHEME_RE = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

        private fun collapseWhitespace(text: String): String =
            WHITESPACE_RE.replace(text, " ")
    }
}
