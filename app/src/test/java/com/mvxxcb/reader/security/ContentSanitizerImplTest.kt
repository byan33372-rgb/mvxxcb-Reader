package com.mvxxcb.reader.security

import com.mvxxcb.reader.domain.model.ContentBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ContentSanitizerImpl 单测（04 §5 DoD4：净化输出不含 script/style/iframe/on* 属性/javascript: 协议）。
 */
class ContentSanitizerImplTest {

    private val sanitizer = ContentSanitizerImpl()

    private fun texts(fragment: SanitizedFragment): List<String> =
        fragment.blocks.filterIsInstance<ContentBlock.Paragraph>().map { it.text }

    private fun images(fragment: SanitizedFragment): List<ContentBlock.Image> =
        fragment.blocks.filterIsInstance<ContentBlock.Image>()

    private fun allText(fragment: SanitizedFragment): String =
        fragment.blocks.joinToString("\n") {
            when (it) {
                is ContentBlock.Paragraph -> it.text
                is ContentBlock.Image -> "${it.url} ${it.alt ?: ""}"
            }
        }

    @Test
    fun `script-style-iframe 整元素剥离并计数`() {
        val f = sanitizer.sanitizeContentFragment(
            "<p>正文A</p><script>alert(1)</script><style>.x{color:red}</style>" +
                "<iframe src=\"http://evil.test\"></iframe><p>正文B</p>",
        )
        assertEquals(listOf("正文A", "正文B"), texts(f))
        assertTrue("应删除 ≥3 个元素，实际 ${f.removedElementCount}", f.removedElementCount >= 3)
        assertTrue(!allText(f).contains("alert") && !allText(f).contains("color:red"))
    }

    @Test
    fun `on 前缀事件属性全部剥离`() {
        val f = sanitizer.sanitizeContentFragment(
            "<p onclick=\"evil()\" onmouseover=\"steal()\" onerror=\"x()\">正文文字</p>",
        )
        assertEquals(listOf("正文文字"), texts(f))
        assertTrue(!allText(f).contains("evil()") && !allText(f).contains("steal()"))
        assertTrue(!allText(f).contains("on"))
    }

    @Test
    fun `javascript 协议链接剥离后保留锚文本`() {
        val f = sanitizer.sanitizeContentFragment("<a href=\"javascript:alert(document.cookie)\">点我</a>")
        assertEquals(listOf("点我"), texts(f))
        assertTrue(!allText(f).contains("javascript:"))
    }

    @Test
    fun `非图片 data 协议链接剥离`() {
        val f = sanitizer.sanitizeContentFragment("<a href=\"data:text/html;base64,PHNjcmlwdD4=\">链接文字</a>")
        assertEquals(listOf("链接文字"), texts(f))
        assertTrue(!allText(f).contains("data:text/html"))
    }

    @Test
    fun `data 图片与 http 图片保留`() {
        val f = sanitizer.sanitizeContentFragment(
            "<img src=\"data:image/png;base64,iVBORw0KGgo=\" alt=\"内嵌图\">" +
                "<img src=\"http://img.test/cover.jpg\" alt=\"封面\">",
        )
        val imgs = images(f)
        assertEquals(2, imgs.size)
        assertEquals("data:image/png;base64,iVBORw0KGgo=", imgs[0].url)
        assertEquals("内嵌图", imgs[0].alt)
        assertEquals("http://img.test/cover.jpg", imgs[1].url)
        assertEquals("封面", imgs[1].alt)
        assertEquals(0, f.removedElementCount)
    }

    @Test
    fun `危险 src 的图片丢弃并计入删除数`() {
        val f = sanitizer.sanitizeContentFragment(
            "<img src=\"javascript:x()\"><img src=\"data:text/html,<b>x</b>\"><p>文字</p>",
        )
        assertEquals(0, images(f).size)
        assertEquals(listOf("文字"), texts(f))
        assertTrue("应计入 ≥2 个删除，实际 ${f.removedElementCount}", f.removedElementCount >= 2)
    }

    @Test
    fun `svg-noscript-form 等可携带脚本的元素剥离`() {
        val f = sanitizer.sanitizeContentFragment(
            "<svg><script>alert(1)</script></svg><noscript><img src=x onerror=y></noscript>" +
                "<form action=\"http://evil.test\"><input name=a></form><p>正文</p>",
        )
        assertEquals(listOf("正文"), texts(f))
        assertTrue(f.removedElementCount >= 3)
        assertTrue(!allText(f).contains("onerror"))
    }

    @Test
    fun `块级元素与 br 均为段落边界`() {
        val f = sanitizer.sanitizeContentFragment("<div><p>第一段</p><p>第二段</p></div>")
        assertEquals(listOf("第一段", "第二段"), texts(f))

        val f2 = sanitizer.sanitizeContentFragment("<p>第一行<br>第二行</p>")
        assertEquals(listOf("第一行", "第二行"), texts(f2))
    }

    @Test
    fun `行内标签文本合并 保留段落顺序`() {
        val f = sanitizer.sanitizeContentFragment("<p>这是<b>加粗</b>的<span style=\"color:red\">文字</span>段落</p>")
        assertEquals(listOf("这是加粗的文字段落"), texts(f))
    }

    @Test
    fun `图片不打断前后文段落顺序`() {
        val f = sanitizer.sanitizeContentFragment(
            "<p>前文</p><img src=\"http://img.test/x.jpg\" alt=\"配图\"><p>后文</p>",
        )
        assertEquals(3, f.blocks.size)
        assertTrue(f.blocks[0] is ContentBlock.Paragraph && (f.blocks[0] as ContentBlock.Paragraph).text == "前文")
        assertTrue(f.blocks[1] is ContentBlock.Image)
        assertTrue(f.blocks[2] is ContentBlock.Paragraph && (f.blocks[2] as ContentBlock.Paragraph).text == "后文")
    }

    @Test
    fun `空白折叠与 nbsp 归一`() {
        val f = sanitizer.sanitizeContentFragment("<p>词&nbsp;&nbsp;间　距\n  换行</p>")
        assertEquals(listOf("词 间 距 换行"), texts(f))
    }

    @Test
    fun `纯危险内容输出为空块`() {
        val f = sanitizer.sanitizeContentFragment("<script>var a=1;</script><style>body{}</style>")
        assertEquals(0, f.blocks.size)
        assertEquals(2, f.removedElementCount)
    }

    @Test
    fun `相对路径图片 src 原样保留不伪造绝对地址`() {
        val f = sanitizer.sanitizeContentFragment("<img src=\"/img/cover.jpg\" alt=\"相对图\">")
        assertEquals(1, images(f).size)
        assertEquals("/img/cover.jpg", images(f)[0].url)
    }

    @Test
    fun `空输入输出空片段`() {
        val f = sanitizer.sanitizeContentFragment("")
        assertEquals(0, f.blocks.size)
        assertEquals(0, f.removedElementCount)
    }
}
