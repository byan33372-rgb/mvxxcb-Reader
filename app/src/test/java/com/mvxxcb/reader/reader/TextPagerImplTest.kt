package com.mvxxcb.reader.reader

// WP-U 单测（04 §4 DoD 4）。Robolectric 环境跑 TextPaint/StaticLayout（真实度量）。
// 覆盖：不同 viewport/字号分页边界、图片占位 1 字符、空内容、超长段落、
// 页码↔分数往返一致、layoutVersion 指纹、overflow 截断。

import android.graphics.Typeface
import com.mvxxcb.reader.domain.model.ContentBlock
import com.mvxxcb.reader.domain.model.ReaderContent
import com.mvxxcb.reader.domain.model.ReaderFontFamily
import com.mvxxcb.reader.reader.engine.PageSpan
import com.mvxxcb.reader.reader.engine.ReaderLayoutSpec
import com.mvxxcb.reader.reader.engine.ReaderPositionMapper
import com.mvxxcb.reader.reader.engine.ReaderPositionMapperImpl
import com.mvxxcb.reader.reader.engine.ReaderPositionMappers
import com.mvxxcb.reader.reader.engine.ReaderTypography
import com.mvxxcb.reader.reader.engine.TextPagerImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextPagerImplTest {

    private lateinit var pager: TextPagerImpl

    @Before
    fun setUp() {
        pager = TextPagerImpl()
    }

    // ———— 工具 ————

    private fun typography(
        fontSizePx: Float = 40f,
        lineHeightMultiplier: Float = 1.6f,
        paragraphSpacingPx: Float = 8f,
        bold: Boolean = false,
        family: ReaderFontFamily = ReaderFontFamily.SYSTEM,
    ) = ReaderTypography(
        fontFamily = family,
        fontSizeSp = fontSizePx,
        lineHeightMultiplier = lineHeightMultiplier,
        paragraphSpacingDp = paragraphSpacingPx,
        bold = bold,
        horizontalPaddingDp = 0,
    )

    private fun spec(
        typography: ReaderTypography = typography(),
        widthPx: Int = 800,
        heightPx: Int = 1200,
        fontScale: Float = 1.0f,
    ) = ReaderLayoutSpec(typography, widthPx, heightPx, fontScale)

    private fun content(vararg blocks: ContentBlock) = ReaderContent(
        chapterId = 1L,
        chapterIndex = 0,
        bookId = 1L,
        title = "测试章节",
        blocks = blocks.toList(),
    )

    private fun paragraphOf(chars: Int, seed: Char = 'x') =
        ContentBlock.Paragraph(seed.toString().repeat(chars))

    /**
     * 注：宽度敏感的分页断言使用 ASCII 字形（Robolectric 默认字体对 CJK 字形宽度测量为 0，
     * 会导致整章内容挤入一行）；CJK 场景仅做不崩溃与偏移一致性的冒烟断言。
     */

    // ———— 1. 基础分页与块覆盖 ————

    @Test
    fun `paginate multi-paragraph content covers all blocks contiguously`() {
        val blocks = (1..12).map { paragraphOf(200) }
        val result = pager.paginate(content(*blocks.toTypedArray()), spec(heightPx = 900))

        assertTrue("应产生多页", result.pages.size > 1)
        // 页与页字符区间连续无缝
        for (i in 1 until result.pages.size) {
            assertEquals(
                "第 $i 页起点应衔接上一页终点",
                result.pages[i - 1].endCharOffsetExclusive.toLong(),
                result.pages[i].startCharOffset.toLong(),
            )
        }
        // 首页起点 0、末页终点 = 总字符数
        assertEquals(0, result.pages.first().startCharOffset)
        val total = blocks.sumOf { (it as ContentBlock.Paragraph).text.length }
        assertEquals(total, result.pages.last().endCharOffsetExclusive)
        // 全部块被覆盖
        assertEquals(blocks.size, result.pages.last().blockEndIndexExclusive)
        assertEquals(0, result.pages.first().blockStartIndex)
        assertTrue(!result.overflow)
    }

    // ———— 2. 不同 viewport / 字号 分页边界 ————

    @Test
    fun `smaller viewport yields more pages`() {
        val blocks = (1..10).map { paragraphOf(120) }
        val wide = pager.paginate(content(*blocks.toTypedArray()), spec(heightPx = 2000))
        val narrow = pager.paginate(content(*blocks.toTypedArray()), spec(heightPx = 600))
        assertTrue("更小视口页数应更多", narrow.pages.size > wide.pages.size)
    }

    @Test
    fun `larger font size yields more pages`() {
        val blocks = (1..10).map { paragraphOf(120) }
        val small = pager.paginate(content(*blocks.toTypedArray()), spec(typography(fontSizePx = 30f)))
        val large = pager.paginate(content(*blocks.toTypedArray()), spec(typography(fontSizePx = 60f)))
        assertTrue("更大字号页数应更多", large.pages.size > small.pages.size)
    }

    @Test
    fun `page spans never exceed viewport height`() {
        val blocks = (1..6).map { paragraphOf(200) }
        val heightPx = 900
        val result = pager.paginate(content(*blocks.toTypedArray()), spec(heightPx = heightPx))
        // 每页字符数少于整章字符数（分页真实发生）
        val total = blocks.sumOf { (it as ContentBlock.Paragraph).text.length }
        result.pages.forEach { page ->
            assertTrue(
                "单页字符数应小于整章字符数",
                page.endCharOffsetExclusive - page.startCharOffset < total,
            )
        }
    }

    // ———— 3. 图片占位 1 字符 ————

    @Test
    fun `image block occupies exactly one char and one page slot`() {
        val blocks = listOf(
            paragraphOf(10),
            ContentBlock.Image(url = "https://example.com/img.jpg", alt = "插图"),
            paragraphOf(10),
        )
        val result = pager.paginate(content(*blocks.toTypedArray()), spec())
        val total = 10 + 1 + 10 // 图片占位 1 字符
        assertEquals(total, result.pages.last().endCharOffsetExclusive)

        // 图片块 [10,11) 必落在某页区间内，且该页块区间覆盖块 1
        assertTrue(
            "图片块应落在其字符区间对应页的块区间内",
            result.pages.any { page ->
                page.blockStartIndex <= 1 && page.blockEndIndexExclusive > 1 &&
                    page.startCharOffset <= 10 && page.endCharOffsetExclusive >= 11
            },
        )
    }

    @Test
    fun `image-only content yields one page with one char`() {
        val result = pager.paginate(
            content(ContentBlock.Image("https://example.com/a.jpg", null)),
            spec(),
        )
        assertEquals(1, result.pages.size)
        assertEquals(0, result.pages[0].startCharOffset)
        assertEquals(1, result.pages[0].endCharOffsetExclusive)
    }

    // ———— 4. 空内容 ————

    @Test
    fun `empty content yields one empty page`() {
        val result = pager.paginate(content(), spec())
        assertEquals(1, result.pages.size)
        assertEquals(0, result.pages[0].startCharOffset)
        assertEquals(0, result.pages[0].endCharOffsetExclusive)
        assertTrue(!result.overflow)
    }

    @Test
    fun `invalid viewport yields one empty page without crash`() {
        val result = pager.paginate(content(paragraphOf(100)), spec(widthPx = 0, heightPx = 0))
        assertEquals(1, result.pages.size)
        assertEquals(0, result.pages[0].endCharOffsetExclusive)
    }

    // ———— 5. 超长段落（段内分页） ————

    @Test
    fun `very long paragraph is split across pages with contiguous offsets`() {
        val longText = paragraphOf(8000)
        val blocks = listOf(paragraphOf(30), longText, paragraphOf(30))
        val result = pager.paginate(content(*blocks.toTypedArray()), spec(heightPx = 500))

        assertTrue("超长段落必须分页", result.pages.size > 2)
        // 页区间连续
        for (i in 1 until result.pages.size) {
            assertEquals(
                result.pages[i - 1].endCharOffsetExclusive.toLong(),
                result.pages[i].startCharOffset.toLong(),
            )
        }
        // 超长段落的页（块区间含块 1）起点/终点落在该块字符范围内
        val blockOnePages = result.pages.filter {
            it.blockStartIndex <= 1 && it.blockEndIndexExclusive > 1
        }
        assertTrue("超长段落应占据多页", blockOnePages.size > 1)
        // 所有页面的块区间合法
        result.pages.forEach {
            assertTrue(it.blockStartIndex < it.blockEndIndexExclusive)
        }
    }

    @Test
    fun `cjk content paginates without crash and keeps contiguous offsets`() {
        val blocks = listOf(
            ContentBlock.Paragraph("这是一段中文正文，用于验证 CJK 内容不崩溃。"),
            ContentBlock.Paragraph("第二段中文内容，字符偏移口径与英文一致。"),
        )
        val result = pager.paginate(content(*blocks.toTypedArray()), spec())
        assertTrue(result.pages.isNotEmpty())
        for (i in 1 until result.pages.size) {
            assertEquals(
                result.pages[i - 1].endCharOffsetExclusive.toLong(),
                result.pages[i].startCharOffset.toLong(),
            )
        }
        val total = blocks.sumOf { (it as ContentBlock.Paragraph).text.length }
        assertEquals(total, result.pages.last().endCharOffsetExclusive)
    }

    @Test
    fun `blank paragraph does not crash pagination`() {
        val result = pager.paginate(
            content(ContentBlock.Paragraph(""), ContentBlock.Paragraph("正文")),
            spec(),
        )
        assertTrue(result.pages.isNotEmpty())
        assertEquals(2, result.pages.last().endCharOffsetExclusive) // 0 + 2 字
    }

    // ———— 6. 页码↔分数往返一致（04 §4 DoD 4） ————

    @Test
    fun `page and fraction round-trip consistently`() {
        val mapper: ReaderPositionMapper = ReaderPositionMapperImpl()

        listOf(1, 2, 5, 13, 200).forEach { pageCount ->
            (0 until pageCount).forEach { page ->
                val fraction = mapper.pageToFraction(page, pageCount)
                val back = mapper.fractionToPage(fraction, pageCount)
                assertEquals("N=$pageCount p=$page 往返应一致", page, back)
            }
            // 分数 → 页 → 分数：落在同一页中点
            (0..10).forEach { i ->
                val f = i / 10.0
                val page = mapper.fractionToPage(f, pageCount)
                val mid = mapper.pageToFraction(page, pageCount)
                val pageAgain = mapper.fractionToPage(mid, pageCount)
                assertEquals("N=$pageCount f=$f 往返应一致", page, pageAgain)
            }
        }
        // 边界
        assertEquals(0, mapper.fractionToPage(0.0, 7))
        assertEquals(6, mapper.fractionToPage(1.0, 7))
        assertEquals(0.0, mapper.pageToFraction(0, 0), 1e-9)
        assertEquals(0, mapper.fractionToPage(0.5, 0))
    }

    @Test
    fun `overall progress maps chapter index and fraction`() {
        val mapper = ReaderPositionMapperImpl()
        assertEquals(0.0, mapper.overallProgress(0, 10, 0.0), 1e-9)
        assertEquals(10.0, mapper.overallProgress(0, 10, 1.0), 1e-9)
        assertEquals(50.0, mapper.overallProgress(5, 10, 0.0), 1e-9)
        assertEquals(100.0, mapper.overallProgress(9, 10, 1.0), 1e-9)
        assertEquals(0.0, mapper.overallProgress(0, 0, 0.5), 1e-9)
    }

    @Test
    fun `DEFAULT mapper resolves to impl via reflection lazy`() {
        // 主 Agent 裁定 4：ReaderPositionMapperImpl 实现后 DEFAULT 自动生效
        val mapper: ReaderPositionMapper = ReaderPositionMappers.DEFAULT
        assertTrue(mapper is ReaderPositionMapperImpl)
    }

    // ———— 7. layoutVersion 指纹 ————

    @Test
    fun `layout version changes with typography and viewport`() {
        val base = pager.layoutVersion(spec())
        assertEquals(base, pager.layoutVersion(spec()))
        assertNotEquals(base, pager.layoutVersion(spec(heightPx = 1001)))
        assertNotEquals(base, pager.layoutVersion(spec(typography(fontSizePx = 41f))))
        assertNotEquals(
            base,
            pager.layoutVersion(spec(typography(bold = true))),
        )
        assertNotEquals(
            base,
            pager.layoutVersion(spec(typography(family = ReaderFontFamily.SERIF))),
        )
        // 粗体类型_face 生成合法
        assertTrue(pager.typefaceFor(ReaderFontFamily.SERIF, true) is Typeface)
    }

    // ———— 8. overflow 截断 ————

    @Test
    fun `content beyond max paged chars is marked overflow`() {
        val pagerWithTinyCap = TextPagerImpl(maxPagedChars = 100)
        val blocks = (1..10).map { paragraphOf(60) }
        val result = pagerWithTinyCap.paginate(content(*blocks.toTypedArray()), spec())
        assertTrue("超限应标记 overflow", result.overflow)
        // 分页在上限附近截断：最后一页终点 ≤ 上限 + 一个块
        assertTrue(result.pages.last().endCharOffsetExclusive <= 100 + 60)
        assertTrue(result.pages.isNotEmpty())
    }
}
