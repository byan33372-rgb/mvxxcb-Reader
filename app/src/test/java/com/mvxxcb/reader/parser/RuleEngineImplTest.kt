package com.mvxxcb.reader.parser

import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StateFailure
import com.mvxxcb.reader.domain.model.Confidence
import com.mvxxcb.reader.domain.model.DetailField
import com.mvxxcb.reader.domain.model.ParseLayer
import com.mvxxcb.reader.domain.model.SourceRule
import com.mvxxcb.reader.parser.rule.DecodedPage
import com.mvxxcb.reader.parser.rule.ParsedSiteRule
import com.mvxxcb.reader.parser.rule.RuleEngineImpl
import com.mvxxcb.reader.parser.rule.RuleExtraction
import com.mvxxcb.reader.parser.rule.RuleSelectorKey
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 站点规则引擎：规则 JSON 校验、三类抽取、XPath Miss、清洗规则 */
class RuleEngineImplTest {

    private val repo = FakeSourceRuleRepository()
    private val sanitizer = FakeContentSanitizer()
    private val engine = RuleEngineImpl(repo, sanitizer)

    private val pageHtml = """
        <html><head><meta name="author" content="规则作者"></head><body>
        <h1 class="bt">规则书名</h1>
        <div class="cover"><img src="/img/rule.jpg"></div>
        <div class="desc">这是简介文本。本章未完，点击下一页继续阅读（提示文本）</div>
        <div id="list">
          <dl>
            <dt>第一卷 试炼</dt>
            <dd><a href="/book/7/1.html">第1章 启程</a></dd>
            <dd><a href="/book/7/2.html">第2章 遇敌</a></dd>
            <dt>第二卷 风暴</dt>
            <dd><a href="/book/7/3.html">第3章 登船</a></dd>
          </dl>
        </div>
        <div id="chapter">
          <h2 class="ct">第4章 风暴之眼</h2>
          <div id="content">
            <div class="ads">广告段落请忽略。</div>
            <p>第一段正文内容，讲述主角登船之后的见闻与冲突。</p>
            <p>第二段正文内容，风暴逼近甲板倾斜，众人抢救货物。（本章未完，点击下一页继续阅读）</p>
            <p>第三段正文内容，最终船长下令转向，避开了漩涡中心。</p>
          </div>
          <a id="prev" href="/book/7/3.html">上一章</a>
          <a id="np" href="/book/7/5.html">下一页</a>
          <a id="nc" href="/book/7/6.html">下一章</a>
        </div>
        </body></html>
    """.trimIndent()

    private fun page(url: String = "https://www.rule-site.test/book/7/4.html", html: String = pageHtml) =
        DecodedPage(url, html, declaredCharset = null, detectedCharset = "UTF-8")

    private val ruleJson = """
        {
          "ruleId": "rule-site.v1",
          "domain": "www.rule-site.test",
          "version": 1,
          "enabled": true,
          "bookNameSelector": "h1.bt",
          "authorSelector": "meta[name=author]@content",
          "coverSelector": "div.cover img@src",
          "descriptionSelector": "div.desc",
          "catalogSelector": "#list",
          "chapterLinkSelector": "#list dd a",
          "chapterTitleSelector": "h2.ct",
          "contentSelector": "#content",
          "nextPageSelector": "a#np@href",
          "nextChapterSelector": "a#nc@href",
          "previousChapterSelector": "a#prev@href",
          "contentCleaningRules": [
            {"type": "regex", "pattern": "（?本章未完.*?点击下一页继续阅读）?", "replacement": ""},
            {"type": "selector", "pattern": "div.ads"},
            {"type": "whitespace", "pattern": "collapse"}
          ]
        }
    """.trimIndent()

    @Test
    fun `parseRuleJson 合法规则`() {
        val r = engine.parseRuleJson(ruleJson)
        assertTrue(r is AppResult.Success)
        val rule = (r as AppResult.Success).value
        assertEquals("rule-site.v1", rule.ruleId)
        assertEquals("www.rule-site.test", rule.domain)
        assertEquals(false, rule.isManual)
        assertEquals(RuleSelectorKey.BOOK_NAME, rule.selectors.keys.first())
        assertEquals(11, rule.selectors.size)
        assertEquals(3, rule.cleaningRules.size)
    }

    @Test
    fun `parseRuleJson manual前缀识别为人工规则`() {
        val r = engine.parseRuleJson(
            """{"ruleId":"manual.www.rule-site.test","domain":"www.rule-site.test","version":1,"bookNameSelector":"h1"}""",
        )
        assertTrue((r as AppResult.Success).value.isManual)
    }

    @Test
    fun `parseRuleJson 缺必填字段逐字段报错`() {
        val r = engine.parseRuleJson("""{"domain":"bad domain without tld","version":0}""")
        assertTrue(r is AppResult.Failure)
        val err = (r as AppResult.Failure).error
        assertTrue(err is ReaderError.State)
        assertEquals(StateFailure.INVALID_ARGUMENT, (err as ReaderError.State).failure)
        val detail = err.debugDetail.orEmpty()
        assertTrue(detail.contains("ruleId"))
        assertTrue(detail.contains("domain"))
        assertTrue(detail.contains("version"))
    }

    @Test
    fun `parseRuleJson JSON语法错误`() {
        val r = engine.parseRuleJson("{not json")
        assertTrue(r is AppResult.Failure)
        assertTrue((r as AppResult.Failure).error is ReaderError.State)
    }

    @Test
    fun `parseRuleJson 复杂度超限报INVALID_ARGUMENT`() {
        // regex 长度 > 200
        val longRegex = "a".repeat(201)
        val r1 = engine.parseRuleJson(
            """{"ruleId":"x.v1","domain":"www.rule-site.test","version":1,"authorSelector":{"type":"regex","expr":"$longRegex"}}""",
        )
        assertTrue((r1 as AppResult.Failure).error.debugDetail.orEmpty().contains("authorSelector"))
        // jsonpath 深度 > 8
        val deepPath = (1..9).joinToString(".") { "k$it" }
        val r2 = engine.parseRuleJson(
            """{"ruleId":"x.v1","domain":"www.rule-site.test","version":1,"authorSelector":{"type":"jsonpath","expr":"$deepPath"}}""",
        )
        assertTrue((r2 as AppResult.Failure).error.debugDetail.orEmpty().contains("jsonpath 深度超过上限"))
        // xpath 深度 > 10
        val deepXpath = (1..11).joinToString("/") { "s$it" }
        val r3 = engine.parseRuleJson(
            """{"ruleId":"x.v1","domain":"www.rule-site.test","version":1,"authorSelector":{"type":"xpath","expr":"$deepXpath"}}""",
        )
        assertTrue((r3 as AppResult.Failure).error.debugDetail.orEmpty().contains("xpath 深度超过上限"))
    }

    @Test
    fun `extractDetail 按规则抽取并规范化封面URL`() {
        val rule = (engine.parseRuleJson(ruleJson) as AppResult.Success).value
        val r = engine.extractDetail(rule, page())
        assertTrue(r is RuleExtraction.Found)
        val detail = (r as RuleExtraction.Found).data
        assertEquals("规则书名", detail.title)
        assertEquals("规则作者", detail.author)
        assertEquals("https://www.rule-site.test/img/rule.jpg", detail.coverUrl)
        assertTrue(detail.description!!.startsWith("这是简介文本。"))
        assertTrue(!detail.description!!.contains("本章未完"))
        assertEquals(ParseLayer.SITE_RULE, detail.fieldSources[DetailField.TITLE])
        assertEquals(Confidence.HIGH, detail.fieldConfidence[DetailField.TITLE])
    }

    @Test
    fun `extractDetail xpath选择器命中一期Miss`() {
        // 主 Agent 裁定 1：xpath 选择器 → Miss("XPath 执行器一期未启用，请使用 CSS")
        val rule = ParsedSiteRule(
            ruleId = "manual.www.rule-site.test",
            domain = "www.rule-site.test",
            isManual = true,
            encoding = null,
            selectors = mapOf(RuleSelectorKey.BOOK_NAME to com.mvxxcb.reader.parser.rule.SelectorSpec.Xpath("//h1")),
            cleaningRules = emptyList(),
            contentMaxPages = null,
        )
        val r = engine.extractDetail(rule, page())
        assertTrue(r is RuleExtraction.Miss)
    }

    @Test
    fun `extractCatalog 章节顺序与分卷与导航过滤`() {
        val rule = (engine.parseRuleJson(ruleJson) as AppResult.Success).value
        val r = engine.extractCatalog(rule, page())
        assertTrue(r is RuleExtraction.Found)
        val catalog = (r as RuleExtraction.Found).data
        assertEquals(3, catalog.chapters.size)
        assertEquals(0, catalog.chapters[0].order)
        assertEquals("第1章 启程", catalog.chapters[0].title)
        assertEquals("https://www.rule-site.test/book/7/1.html", catalog.chapters[0].url)
        assertEquals("第一卷 试炼", catalog.chapters[0].volumeTitle)
        assertEquals("第二卷 风暴", catalog.chapters[2].volumeTitle)
        // 站点为正序
        assertEquals(false, catalog.isLikelyReversed)
    }

    @Test
    fun `extractContent 清洗规则与导航URL`() {
        val rule = (engine.parseRuleJson(ruleJson) as AppResult.Success).value
        val r = engine.extractContent(rule, page())
        assertTrue(r is RuleExtraction.Found)
        val content = (r as RuleExtraction.Found).data
        assertEquals("第4章 风暴之眼", content.title)
        assertEquals(3, content.blocks.size) // 广告 div 被 selector 规则移除
        val allText = content.blocks.joinToString("\n") { it.toString() }
        assertTrue(!allText.contains("广告"))
        assertTrue(!allText.contains("本章未完"))
        assertTrue(content.blocks[0].toString().contains("第一段正文内容"))
        assertEquals("https://www.rule-site.test/book/7/5.html", content.nextPageUrl)
        assertEquals("https://www.rule-site.test/book/7/6.html", content.nextChapterUrl)
        assertEquals("https://www.rule-site.test/book/7/3.html", content.previousChapterUrl)
        assertEquals(content.wordCount, content.blocks.sumOf { b -> (b as? com.mvxxcb.reader.domain.model.ContentBlock.Paragraph)?.text?.length ?: 0 })
    }

    @Test
    fun `extractCatalog 过滤非章节路径段与分卷锚（linovelib 型筛选栏污染）`() {
        // 阶段 7 验收缺陷回归：宽选择器（body + a）下面包屑/筛选栏/分卷锚必须被过滤
        val rule = ParsedSiteRule(
            ruleId = "broad.v1",
            domain = "www.linovelib.test",
            isManual = false,
            encoding = null,
            selectors = mapOf(
                RuleSelectorKey.CATALOG to com.mvxxcb.reader.parser.rule.SelectorSpec.Css("body"),
                RuleSelectorKey.CHAPTER_LINK to com.mvxxcb.reader.parser.rule.SelectorSpec.Css("a"),
            ),
            cleaningRules = emptyList(),
            contentMaxPages = null,
        )
        val page = DecodedPage(
            url = "$TEST_BASE/novel/5544/catalog",
            html = sampleText("catalog-with-filter-sidebar.html"),
            declaredCharset = null,
            detectedCharset = "UTF-8",
        )
        val r = engine.extractCatalog(rule, page)
        assertTrue(r is RuleExtraction.Found)
        val catalog = (r as RuleExtraction.Found).data
        assertEquals(20, catalog.chapters.size)
        assertTrue(catalog.chapters.all { !it.url.contains("/wenku/") && !it.url.contains("vol_") })
        assertTrue(catalog.chapters.all { it.url.startsWith("$TEST_BASE/novel/5544/") })
        assertEquals("插图", catalog.chapters[0].title)
        assertEquals("第一卷 落第骑士的英谭", catalog.chapters[0].volumeTitle)
        assertEquals("第十八话 启程之日", catalog.chapters[19].title)
    }

    @Test
    fun `loadRule 无规则与损坏规则返回null`() = kotlinx.coroutines.runBlocking {
        assertNull(engine.loadRule("www.rule-site.test"))
        repo.put(
            SourceRule(
                ruleId = "broken.v1",
                domain = "www.rule-site.test",
                version = 1,
                ruleJson = "{invalid",
                enabled = true,
                createdAt = 0,
                updatedAt = 0,
            ),
        )
        assertNull(engine.loadRule("www.rule-site.test"))
        repo.deleteRule("broken.v1") // 同域唯一启用规则：移除损坏规则后加载正常规则
        repo.put(
            SourceRule(
                ruleId = "ok.v1",
                domain = "www.rule-site.test",
                version = 1,
                ruleJson = ruleJson,
                enabled = true,
                createdAt = 0,
                updatedAt = 0,
            ),
        )
        val loaded = engine.loadRule("www.rule-site.test")
        assertTrue(loaded is ParsedSiteRule)
    }
}
