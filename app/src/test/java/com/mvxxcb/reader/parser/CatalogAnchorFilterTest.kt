package com.mvxxcb.reader.parser

import com.mvxxcb.reader.parser.util.CatalogAnchorFilter
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CatalogAnchorFilter 单元语义（阶段 7 验收缺陷修复：目录锚点过滤） */
class CatalogAnchorFilterTest {

    @Test
    fun `非章节路径段硬排除清单`() {
        // 真实站点污染源形态
        assertTrue(CatalogAnchorFilter.isNonChapterPath("$TEST_BASE/wenku/dengekibunko/1.html"))
        assertTrue(CatalogAnchorFilter.isNonChapterPath("$TEST_BASE/wenku/all/1.html"))
        assertTrue(CatalogAnchorFilter.isNonChapterPath("$TEST_BASE/class/1.html"))
        assertTrue(CatalogAnchorFilter.isNonChapterPath("$TEST_BASE/huihu/1.html"))
        assertTrue(CatalogAnchorFilter.isNonChapterPath("$TEST_BASE/huiku/1.html"))
        assertTrue(CatalogAnchorFilter.isNonChapterPath("$TEST_BASE/fenlei/xuanhuan/1.html"))
        assertTrue(CatalogAnchorFilter.isNonChapterPath("$TEST_BASE/rank/week/1.html"))
        assertTrue(CatalogAnchorFilter.isNonChapterPath("$TEST_BASE/ad/click?from=banner"))
        assertTrue(CatalogAnchorFilter.isNonChapterPath("$TEST_BASE/about"))
        // 真实章节路径不受误伤（首段检查不深入）
        assertFalse(CatalogAnchorFilter.isNonChapterPath("$TEST_BASE/novel/5544/346134.html"))
        assertFalse(CatalogAnchorFilter.isNonChapterPath("$TEST_BASE/book/1/1.html"))
        assertFalse(CatalogAnchorFilter.isNonChapterPath("$TEST_BASE/read/300/300_1.html"))
    }

    @Test
    fun `分卷锚识别`() {
        assertTrue(CatalogAnchorFilter.isVolumeAnchor("$TEST_BASE/novel/5544/vol_346133.html"))
        assertTrue(CatalogAnchorFilter.isVolumeAnchor("$TEST_BASE/novel/5544/volume-12.html"))
        assertTrue(CatalogAnchorFilter.isVolumeAnchor("$TEST_BASE/novel/5544/juan3.html"))
        assertFalse(CatalogAnchorFilter.isVolumeAnchor("$TEST_BASE/novel/5544/346134.html"))
        assertFalse(CatalogAnchorFilter.isVolumeAnchor("$TEST_BASE/novel/5544.html"))
    }

    @Test
    fun `章节特征判定`() {
        assertTrue(CatalogAnchorFilter.matchesChapterPattern("第1章 山门初开", "/book/1/1.html"))
        assertTrue(CatalogAnchorFilter.matchesChapterPattern("插图", "/novel/5544/346134.html"))
        // 特征判定本身不区分路径段（/wenku/ 由 isNonChapterPath 硬排除，两层职责分离）
        assertTrue(CatalogAnchorFilter.matchesChapterPattern("电击文库", "/wenku/dengekibunko/1.html"))
        // 超长链接文本（>50 字符）即使命中数字链接特征也不算章节（导航/推荐位防护）
        assertFalse(
            CatalogAnchorFilter.matchesChapterPattern(
                "这是一个特别特别长的导航标题文本，已经远远超过五十个字符的长度上限，因此即使它指向一个数字编号的链接地址，也不应当被判定为章节特征链接。",
                "/novel/5544/346134.html",
            ),
        )
    }

    @Test
    fun `统一构建_筛选栏污染与分卷锚与聚类约束`() {
        val html = """
            <html><body>
            <div class="breadcrumb">
              <a href="/">首页</a> &gt; <a href="/wenku/all/1.html">全部文库</a> &gt; <a href="/novel/5544.html">书名</a>
            </div>
            <div class="tabA-float cate-cell"><ul>
              <li><a href="/wenku/dengekibunko/1.html">电击文库</a></li>
              <li><a href="/wenku/kadokawa/1.html">角川文库</a></li>
              <li><a href="/class/1.html">分类</a></li>
            </ul></div>
            <div id="list">
              <a href="/novel/5544/vol_100.html">第一卷 上</a>
              <a href="/novel/5544/101.html">第1章 开端</a>
              <a href="/novel/5544/102.html">第2章 相遇</a>
              <a href="/novel/5544/103.html">第3章 风波</a>
              <a href="/novel/5544/101.html">第1章 开端（重复挂载）</a>
            </div>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "$TEST_BASE/novel/5544/catalog")
        val chapters = CatalogAnchorFilter.buildChapters(
            doc.select("a[href]").toList(),
            pageUrl = "$TEST_BASE/novel/5544/catalog",
            requireChapterPattern = true,
        )!!
        // 分卷锚 → 卷名；重复 URL 保留首次；面包屑详情链接与 /wenku/、/class/ 全部排除
        assertEquals(3, chapters.size)
        assertEquals(listOf("第1章 开端", "第2章 相遇", "第3章 风波"), chapters.map { it.title })
        assertEquals(listOf(0, 1, 2), chapters.map { it.order })
        assertTrue(chapters.all { it.url.startsWith("$TEST_BASE/novel/5544/") && !it.url.contains("vol_") })
        assertEquals("第一卷 上", chapters[0].volumeTitle)
    }

    @Test
    fun `统一构建_多目录站点簇不占优时退回全量候选`() {
        val html = """
            <html><body><div id="list">
              <a href="/read/1/1_1.html">第1章 甲</a>
              <a href="/read/2/2_1.html">第2章 乙</a>
              <a href="/read/3/3_1.html">第3章 丙</a>
            </div></body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "$TEST_BASE/read/all.html")
        val chapters = CatalogAnchorFilter.buildChapters(
            doc.select("a[href]").toList(),
            pageUrl = "$TEST_BASE/read/all.html",
            requireChapterPattern = true,
        )!!
        // 每章独立目录（簇各 1 个 <2）→ 不启用聚类约束，避免误杀
        assertEquals(3, chapters.size)
    }

    @Test
    fun `统一构建_无效锚点集合返回null`() {
        val doc = Jsoup.parse("<a href='/x'>单章</a>", "$TEST_BASE/novel/1/catalog")
        assertNull(
            CatalogAnchorFilter.buildChapters(
                doc.select("a[href]").toList(),
                "$TEST_BASE/novel/1/catalog",
                requireChapterPattern = true,
            ),
        )
    }
}
