package com.mvxxcb.reader.parser.resolver

import com.mvxxcb.reader.parser.TEST_BASE
import com.mvxxcb.reader.parser.sampleText
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 面包屑链接提取器：结构化容器 / 分隔符文本链 / 规范化 / 去重 / 截断（07 K8 配套 util） */
class BreadcrumbLinkExtractorTest {

    private fun extract(html: String, base: String = "$TEST_BASE/resolver/read/ch1.html") =
        com.mvxxcb.reader.parser.util.BreadcrumbLinkExtractor.extract(Jsoup.parse(html, base), base)

    @Test
    fun `结构化容器_ol_breadcrumb_li_a_提取并跳过当前位锚缺失`() {
        val html = """
            <html><body>
            <ol class="breadcrumb">
              <li><a href="/">首页</a></li>
              <li><a href="/novel/100/">星辰变体</a></li>
              <li class="active">第30章 雪线</li>
            </ol>
            </body></html>
        """.trimIndent()
        val links = extract(html)
        assertEquals(2, links.size)
        assertEquals("$TEST_BASE/", links[0].url)
        assertEquals("首页", links[0].text)
        assertEquals("$TEST_BASE/novel/100", links[1].url) // 规范化：尾 / 去除
        assertEquals("星辰变体", links[1].text)
    }

    @Test
    fun `div_breadcrumb_分隔符相邻文本链_样本页提取`() {
        val doc = Jsoup.parse(sampleText("resolver-breadcrumb-catalog.html"), "$TEST_BASE/resolver/read/ch1.html")
        val links = com.mvxxcb.reader.parser.util.BreadcrumbLinkExtractor.extract(doc)
        assertEquals(listOf("首页", "山巅之约", "目录"), links.map { it.text })
        assertEquals("$TEST_BASE/", links[0].url)
        assertEquals("$TEST_BASE/resolver/book/2001.html", links[1].url)
        assertEquals("$TEST_BASE/resolver/catalog.html", links[2].url)
    }

    @Test
    fun `id_crumb与class_crumb容器命中`() {
        val html = """
            <html><body>
            <div id="crumb"><a href="/a/">A</a> &gt; <a href="/a/b/">B</a> &gt; C</div>
            <span class="crumb"><a href="/d/">D</a></span>
            </body></html>
        """.trimIndent()
        val links = extract(html)
        assertEquals(listOf("A", "B", "D"), links.map { it.text })
    }

    @Test
    fun `无面包屑class的分隔符文本链兜底`() {
        val html = """
            <html><body>
            <div>位置：<a href="/sort/1/">分类</a> » <a href="/book/55/">书目</a> » 第9章</div>
            </body></html>
        """.trimIndent()
        val links = extract(html)
        assertEquals(listOf("分类", "书目"), links.map { it.text })
    }

    @Test
    fun `相对链接按页面基址规范化_javascript与井号锚丢弃_去重保序`() {
        val html = """
            <html><body>
            <div class="breadcrumb">
              <a href="/x/">甲</a> &gt; <a href="javascript:void(0)">坏</a> &gt;
              <a href="#">井</a> &gt; <a href="/x/">甲</a> &gt; <a href="../y/">乙</a>
            </div>
            </body></html>
        """.trimIndent()
        val links = extract(html)
        assertEquals(listOf("甲", "乙"), links.map { it.text })
        assertEquals("$TEST_BASE/x", links[0].url)
        assertEquals("$TEST_BASE/resolver/y", links[1].url) // ../y/ 相对 /resolver/read/ch1.html
    }

    @Test
    fun `maxLinks截断_防病态页面`() {
        val anchors = (1..30).joinToString(" &gt; ") { "<a href=\"/p/$it/\">P$it</a>" }
        val html = "<html><body><div class=\"breadcrumb\">$anchors</div></body></html>"
        val links = extract(html)
        assertEquals(24, links.size) // 默认兜底上限 24
        assertTrue(links.all { it.url.startsWith("$TEST_BASE/p/") })
    }

    @Test
    fun `章节页导航条_上一章_目录_下一章_分隔符链可产出目录候选`() {
        val html = """
            <html><body>
            <div class="chapter-header">
              <a href="/read/29.html">上一章</a> | <a href="/catalog/5.html">目录</a> | <a href="/read/31.html">下一章</a>
            </div>
            </body></html>
        """.trimIndent()
        val links = extract(html)
        // 导航条整体作为分隔符链候选产出（是否为目录入口由 resolver classify 验证）
        assertEquals(listOf("上一章", "目录", "下一章"), links.map { it.text })
        assertEquals("$TEST_BASE/catalog/5.html", links[1].url)
    }
}
