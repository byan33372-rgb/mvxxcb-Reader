package com.mvxxcb.reader.parser

import com.mvxxcb.reader.domain.model.ContentBlock
import com.mvxxcb.reader.parser.rule.CleaningRule
import com.mvxxcb.reader.parser.rule.CleaningType
import com.mvxxcb.reader.parser.util.PaginationMerger
import com.mvxxcb.reader.parser.util.TextCleaner
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TextCleaner 与 PaginationMerger（两套分页策略守卫） */
class TextCleanerAndPaginationTest {

    // —————————————————— TextCleaner ——————————————————

    private val cleaner = TextCleaner()

    @Test
    fun `清理广告与登录提示并保留段落顺序`() {
        val html = """
            <div id="content">
              <div class="ads"><p>广告一</p><p>广告二</p></div>
              <p class="login-tip">请登录后阅读全文</p>
              <p>第一段</p>
              <p>第二段</p>
              <script>alert('x')</script>
            </div>
        """.trimIndent()
        val container = Jsoup.parse(html, "https://a.test/").selectFirst("#content")!!
        val cleaned = cleaner.cleanContentElement(container, emptyList())
        assertFalse(cleaned.contains("广告"))
        assertFalse(cleaned.contains("登录"))
        assertFalse(cleaned.contains("script"))
        val blocks = cleaner.buildBlocksFromHtml(cleaned)
        assertEquals(listOf("第一段", "第二段"), blocks.map { (it as ContentBlock.Paragraph).text })
    }

    @Test
    fun `站点规则清洗_regex与selector与whitespace`() {
        val rules = listOf(
            CleaningRule(CleaningType.REGEX, "【.*?】", ""),
            CleaningRule(CleaningType.SELECTOR, ".promo"),
            CleaningRule(CleaningType.WHITESPACE, "collapse"),
        )
        val container = Jsoup.parse(
            "<div id='c'><p>【置顶】正文开头。</p><p class='promo'>推广文本</p><p>正文  中间   有多余  空白。</p></div>",
            "https://a.test/",
        ).selectFirst("#c")!!
        val cleaned = cleaner.cleanContentElement(container, rules)
        assertFalse(cleaned.contains("推广"))
        val text = cleaner.applyTextRules(cleaned, rules)
        assertFalse(text.contains("【置顶】"))
        assertTrue(text.contains("正文开头。"))
        assertFalse(text.contains("  "))
        assertTrue(text.contains("正文 中间 有多余 空白。"))
    }

    @Test
    fun `图片转绝对地址且保序`() {
        val html = """
            <div id="c">
              <p>段落一</p>
              <img src="/img/a.jpg" alt="图A">
              <p>段落二</p>
            </div>
        """.trimIndent()
        val container = Jsoup.parse(html, "https://imgsite.test/book/1/").selectFirst("#c")!!
        val cleaned = cleaner.cleanContentElement(container, emptyList())
        assertTrue(cleaned.contains("https://imgsite.test/img/a.jpg"))
        val blocks = cleaner.buildBlocksFromHtml(cleaned)
        assertTrue(blocks[0] is ContentBlock.Paragraph)
        val img = blocks[1] as ContentBlock.Image
        assertEquals("https://imgsite.test/img/a.jpg", img.url)
        assertEquals("图A", img.alt)
        assertTrue(blocks[2] is ContentBlock.Paragraph)
    }

    // —————————————————— PaginationMerger ——————————————————

    @Test
    fun `页标题归一剥离页码装饰`() {
        assertEquals("第2章 起点", PaginationMerger.normalizePageTitle("第2章 起点（2/3）"))
        assertEquals("第2章 起点", PaginationMerger.normalizePageTitle("第2章 起点_2"))
        assertEquals("第2章 起点", PaginationMerger.normalizePageTitle("第2章 起点 (第2页)"))
        assertEquals("第2章 起点", PaginationMerger.normalizePageTitle("第2章 起点"))
    }

    @Test
    fun `章节编号提取含中文数字`() {
        assertEquals(12, PaginationMerger.extractChapterNumber("第12章 风起"))
        assertEquals(3, PaginationMerger.extractChapterNumber("第 3 节 雨停"))
        assertEquals(12, PaginationMerger.extractChapterNumber("第十二章"))
        assertEquals(205, PaginationMerger.extractChapterNumber("第二百零五章"))
        assertEquals(null, PaginationMerger.extractChapterNumber("番外：雪夜"))
    }

    @Test
    fun `目录前缀与跨域判定`() {
        assertTrue(
            PaginationMerger.sameDirectoryPrefix(
                "https://a.test/read/300/300_1.html",
                "https://a.test/read/300/300_2.html",
            ),
        )
        assertFalse(
            PaginationMerger.sameDirectoryPrefix(
                "https://a.test/read/300/300_1.html",
                "https://a.test/read/301/301_1.html",
            ),
        )
        assertFalse(PaginationMerger.sameHost("https://a.test/x", "https://b.test/x"))
        assertTrue(PaginationMerger.isPageNavText("下一页"))
        assertTrue(PaginationMerger.isPageNavText("上一章"))
        assertFalse(PaginationMerger.isPageNavText("第3章 死循环页"))
    }

    @Test
    fun `正文策略_已见URL死循环保护`() {
        val s = PaginationMerger().ContentStrategy("https://a.test/r/1.html", "第1章", maxPages = 10, maxChars = 999_999)
        s.mergePage("https://a.test/r/1.html", listOf(ContentBlock.Paragraph("文本")))
        assertEquals(PaginationMerger.Verdict.Loop, s.precheckNext("https://a.test/r/1.html"))
    }

    @Test
    fun `正文策略_跨域与目录前缀守卫`() {
        val s = PaginationMerger().ContentStrategy("https://a.test/r/1.html", "第1章", maxPages = 10, maxChars = 999_999)
        assertTrue(s.precheckNext("https://b.test/r/2.html") is PaginationMerger.Verdict.Stop)
        // 不同目录前缀 → 疑似下一章
        assertTrue(s.precheckNext("https://a.test/next/2.html") is PaginationMerger.Verdict.StopAsNextChapter)
    }

    @Test
    fun `正文策略_标题不一致判误入下一章`() {
        val s = PaginationMerger().ContentStrategy("https://a.test/r/1.html", "第1章 起点", maxPages = 10, maxChars = 999_999)
        assertTrue(s.titleGuard("第1章 起点") is PaginationMerger.Verdict.Proceed)
        assertTrue(s.titleGuard("第2章 新篇") is PaginationMerger.Verdict.StopAsNextChapter)
        // 带页码装饰仍一致
        assertTrue(s.titleGuard("第1章 起点（2/2）") is PaginationMerger.Verdict.Proceed)
    }

    @Test
    fun `正文策略_内容重复率`() {
        val s = PaginationMerger().ContentStrategy("https://a.test/r/1.html", "t", maxPages = 10, maxChars = 999_999)
        s.mergePage("https://a.test/r/1.html", listOf(ContentBlock.Paragraph("甲"), ContentBlock.Paragraph("乙")))
        val newBlocks = listOf(ContentBlock.Paragraph("甲"), ContentBlock.Paragraph("丙"))
        val rate = s.evaluateDuplicateRate(newBlocks)
        assertEquals(0.5, rate, 0.001)
        s.mergePage("https://a.test/r/2.html", newBlocks)
        // 块按页序追加（≤60% 重复率时整页并入，防重由重复率终止 + 清洗负责）
        assertEquals(4, s.blocks.size)
        assertTrue(s.blocks.any { it is ContentBlock.Paragraph && it.text == "丙" })
    }

    @Test
    fun `正文策略_字符预算`() {
        val s = PaginationMerger().ContentStrategy("https://a.test/r/1.html", "t", maxPages = 10, maxChars = 10)
        s.mergePage("https://a.test/r/1.html", listOf(ContentBlock.Paragraph("这段文字超过十个字符用于触发预算")))
        assertTrue(s.charBudgetExceeded())
    }

    @Test
    fun `目录策略_URL去重与重复率与跨页倒退`() {
        val s = PaginationMerger().CatalogStrategy()
        s.markVisited("https://a.test/c/1.html")
        assertEquals(PaginationMerger.Verdict.Loop, s.precheckNext("https://a.test/c/1.html"))
        assertEquals(PaginationMerger.Verdict.Proceed, s.precheckNext("https://a.test/c/2.html"))
        assertTrue(s.hostGuard("https://b.test/c/3.html", "https://a.test/c/2.html") is PaginationMerger.Verdict.Stop)

        val page1 = listOf(
            com.mvxxcb.reader.domain.model.ChapterRef(0, "第1章", "https://a.test/b/1.html"),
            com.mvxxcb.reader.domain.model.ChapterRef(1, "第2章", "https://a.test/b/2.html"),
        )
        assertEquals(0.0, s.mergePage(page1), 0.001)
        // 第二页含 1 个重复 + 2 个新章
        val page2 = listOf(
            com.mvxxcb.reader.domain.model.ChapterRef(0, "第2章", "https://a.test/b/2.html"),
            com.mvxxcb.reader.domain.model.ChapterRef(1, "第3章", "https://a.test/b/3.html"),
            com.mvxxcb.reader.domain.model.ChapterRef(2, "第4章", "https://a.test/b/4.html"),
        )
        val rate = s.mergePage(page2)
        assertEquals(1.0 / 3.0, rate, 0.001)
        assertEquals(1, s.duplicateSkipped)
        assertEquals(4, s.finish().size)
        assertEquals(listOf(0, 1, 2, 3), s.finish().map { it.order })
        assertFalse(s.pageOrderBacktracked)
    }

    @Test
    fun `目录策略_跨页编号倒退标记`() {
        val s = PaginationMerger().CatalogStrategy()
        s.mergePage(listOf(com.mvxxcb.reader.domain.model.ChapterRef(0, "第10章", "u1")))
        s.mergePage(listOf(com.mvxxcb.reader.domain.model.ChapterRef(0, "第3章", "u2")))
        assertTrue(s.pageOrderBacktracked)
    }
}
