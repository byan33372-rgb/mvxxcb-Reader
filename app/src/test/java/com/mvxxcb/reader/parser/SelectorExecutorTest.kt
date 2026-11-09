package com.mvxxcb.reader.parser

import com.mvxxcb.reader.parser.rule.SelectorExecutor
import com.mvxxcb.reader.parser.rule.SelectorSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 选择器统一执行器：css/regex/jsonpath 支持、XPath 一期 Miss、复杂度上限（主 Agent 裁定 1/2） */
class SelectorExecutorTest {

    private val html = """
        <html><body>
        <h1 id="title">测试书名</h1>
        <div id="content"><p>第一段</p><p>第二段</p></div>
        <a class="next" href="/book/2/2.html">下一页</a>
        <img class="cover" src="/img/c.jpg">
        <script type="application/ld+json">{"@type":"Book","name":"内嵌书名"}</script>
        </body></html>
    """.trimIndent()

    private val doc = Jsoup.parse(html, "https://www.example.com/book/2/1.html")

    @Test
    fun `css选择器取文本`() {
        val r = SelectorExecutor.execute(SelectorSpec.Css("#title"), doc)
        assertTrue(r is SelectorExecutor.Result.Values)
        assertEquals(listOf("测试书名"), (r as SelectorExecutor.Result.Values).values)
    }

    @Test
    fun `css选择器取属性`() {
        val r = SelectorExecutor.execute(SelectorSpec.Css("a.next", attr = "href"), doc)
        assertEquals(listOf("/book/2/2.html"), (r as SelectorExecutor.Result.Values).values)

        // 简写 "@href" 形态
        val r2 = SelectorExecutor.execute(SelectorSpec.Css("a.next@abs:href"), doc)
        assertEquals(listOf("https://www.example.com/book/2/2.html"), (r2 as SelectorExecutor.Result.Values).values)
    }

    @Test
    fun `css多值按文档顺序`() {
        val r = SelectorExecutor.execute(SelectorSpec.Css("#content p"), doc)
        assertEquals(listOf("第一段", "第二段"), (r as SelectorExecutor.Result.Values).values)
    }

    @Test
    fun `css无匹配返回Miss`() {
        val r = SelectorExecutor.execute(SelectorSpec.Css("#not-exists"), doc)
        assertTrue(r is SelectorExecutor.Result.Miss)
    }

    @Test
    fun `regex选择器`() {
        val r = SelectorExecutor.execute(SelectorSpec.Regex("<h1 id=\"title\">(.*?)</h1>"), doc)
        assertEquals(listOf("测试书名"), (r as SelectorExecutor.Result.Values).values)
    }

    @Test
    fun `regex非法表达式返回Miss不抛异常`() {
        val r = SelectorExecutor.execute(SelectorSpec.Regex("第([unclosed"), doc)
        assertTrue(r is SelectorExecutor.Result.Miss)
    }

    @Test
    fun `xpath命中返回一期未启用Miss`() {
        // 主 Agent 裁定 1：SelectorSpec.Xpath 命中 → Miss("XPath 执行器一期未启用，请使用 CSS")
        val r = SelectorExecutor.execute(SelectorSpec.Xpath("//h1/text()"), doc)
        assertTrue(r is SelectorExecutor.Result.Miss)
        assertEquals(SelectorExecutor.XPATH_DISABLED_REASON, (r as SelectorExecutor.Result.Miss).reason)
    }

    @Test
    fun `jsonpath自研点号路径`() {
        val json = Json.parseToJsonElement("""{"a":{"b":[{"n":"one"},{"n":"two"}],"c":"tail"}}""") as JsonObject
        assertEquals("two", SelectorExecutor.resolveJsonPath(json, "a.b[1].n")!!.let { (it as JsonPrimitive).content })
        assertEquals("tail", SelectorExecutor.resolveJsonPath(json, "a.c")!!.let { (it as JsonPrimitive).content })
        assertNull(SelectorExecutor.resolveJsonPath(json, "a.b[5].n"))
        assertNull(SelectorExecutor.resolveJsonPath(json, "x.y.z"))
        // 无 jsonSource 时从页面内嵌 JSON 收集
        val r = SelectorExecutor.execute(SelectorSpec.JsonPath("name"), doc)
        assertEquals(listOf("内嵌书名"), (r as SelectorExecutor.Result.Values).values)
    }

    @Test
    fun `复杂度上限_regex长度200`() {
        val ok = SelectorSpec.Regex("a".repeat(200))
        assertNull(SelectorExecutor.validate(ok))
        val over = SelectorSpec.Regex("a".repeat(201))
        assertTrue(SelectorExecutor.validate(over)!!.contains("regex 长度超过上限"))
    }

    @Test
    fun `复杂度上限_jsonpath深度8`() {
        assertNull(SelectorExecutor.validate(SelectorSpec.JsonPath("a.b.c.d.e.f.g.h")))
        val over = SelectorExecutor.validate(SelectorSpec.JsonPath("a.b.c.d.e.f.g.h.i"))
        assertTrue(over!!.contains("jsonpath 深度超过上限"))
    }

    @Test
    fun `复杂度上限_xpath深度10`() {
        assertNull(SelectorExecutor.validate(SelectorSpec.Xpath("a/b/c/d/e/f/g/h/i/j")))
        val over = SelectorExecutor.validate(SelectorSpec.Xpath("a/b/c/d/e/f/g/h/i/j/k"))
        assertTrue(over!!.contains("xpath 深度超过上限"))
    }

    @Test
    fun `jsonpath深度计算含索引段`() {
        // 深度 = 路径节点数：a / b / [0] / c 共 4 段
        assertEquals(4, SelectorExecutor.jsonPathDepth("a.b[0].c"))
        assertEquals(2, SelectorExecutor.jsonPathDepth("a[1]"))
        assertEquals(8, SelectorExecutor.jsonPathDepth("a.b.c.d.e.f.g.h"))
    }
}
