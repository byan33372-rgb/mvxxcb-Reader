package com.mvxxcb.reader.reader.engine

// 实现归属 WP-U（04 §4）。契约依据：02 §9（TextPager）。
//
// 实现要点：
// 1. 基于 android.graphics.TextPaint + StaticLayout 逐行测量分页；不依赖 Compose TextMeasurer，
//    可在 Dispatchers.Default 线程执行（StaticLayout/TextPaint 构造与 measureText 线程安全）。
// 2. 单位约定（契约 02 §9 注释）：ReaderTypography 各数值由调用方按 density+fontScale
//    换算为像素后传入，本实现不再做二次换算；ReaderLayoutSpec.fontScale 仅参与
//    layoutVersion 指纹（系统字体缩放变化 → 缓存失效）。
// 3. 行高精确对齐：StaticLayout 用 lineSpacingAdd 强制行高 = fontSpacing * lineHeightMultiplier，
//    与 Compose 绘制侧 `lineHeight = fontSize * lineHeightMultiplier` 近似同一行盒高度，
//    保证分页边界与绘制基本一致（风险 R5 缓解）。
// 4. 图片块占位：字符数 = 1，高度 = 一个正文行高（UI 侧按同一约定渲染占位）。
// 5. 字符偏移口径：段落 = 文本字符数；图片 = 1；跨块累计，供页码↔分数换算与进度恢复。
// 6. overflow：分页推进超过内部分页字符上限（200_000，与 ParseOptions.maxContentChars 默认一致）
//    即停止并置 overflow=true，如实告知截断，不静默。

import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.mvxxcb.reader.domain.model.ContentBlock
import com.mvxxcb.reader.domain.model.ReaderContent
import com.mvxxcb.reader.domain.model.ReaderFontFamily

class TextPagerImpl(
    /** 一期分页字符上限（与 parser ParseOptions.maxContentChars 默认值一致；测试可注入小值） */
    internal val maxPagedChars: Int = 200_000,
) : TextPager {

    override fun paginate(
        content: ReaderContent,
        spec: ReaderLayoutSpec,
    ): PaginationResult {
        val version = layoutVersion(spec)
        if (spec.viewportWidthPx <= 0 || spec.viewportHeightPx <= 0 || content.blocks.isEmpty()) {
            // 视口无效或空内容：返回单空页，避免上层除零/索引越界
            return PaginationResult(listOf(EMPTY_SPAN), version, overflow = false)
        }

        val paint = buildPaint(spec.typography)
        val widthPx = spec.viewportWidthPx.coerceAtLeast(1)
        val viewportHeightPx = spec.viewportHeightPx.toFloat()
        val paragraphSpacingPx = spec.typography.paragraphSpacingDp.coerceAtLeast(0f)
        val imageLineHeightPx = lineHeightPx(paint, spec.typography)

        // 每块的累计字符基址（段落 = 文本长度，图片 = 1）
        val blockBaseOffsets = IntArray(content.blocks.size)
        var totalChars = 0
        for (i in content.blocks.indices) {
            blockBaseOffsets[i] = totalChars
            totalChars += blockCharCount(content.blocks[i])
        }

        val pages = ArrayList<PageSpan>()
        var currentY = 0f
        var pageHasContent = false
        var pageStartBlock = -1
        var pageStartChar = -1
        var lastEndChar = 0 // 已放置内容的章内累计结束字符（exclusive）
        var overflow = false

        fun closePage(endCharExclusive: Int, endBlockExclusive: Int) {
            pages.add(
                PageSpan(
                    blockStartIndex = pageStartBlock,
                    blockEndIndexExclusive = endBlockExclusive,
                    startCharOffset = pageStartChar,
                    endCharOffsetExclusive = endCharExclusive,
                )
            )
            currentY = 0f
            pageHasContent = false
            pageStartBlock = -1
            pageStartChar = -1
        }

        blockLoop@ for (blockIndex in content.blocks.indices) {
            val base = blockBaseOffsets[blockIndex]
            if (base >= maxPagedChars) {
                overflow = totalChars > maxPagedChars
                break
            }
            when (val block = content.blocks[blockIndex]) {
                is ContentBlock.Paragraph -> {
                    val layout = buildParagraphLayout(block.text, paint, widthPx, spec.typography)
                    val lineCount = layout.lineCount
                    for (line in 0 until lineCount) {
                        val lineH = (layout.getLineBottom(line) - layout.getLineTop(line))
                            .toFloat().coerceAtLeast(1f)
                        val spacingBefore =
                            if (line == 0 && pageHasContent) paragraphSpacingPx else 0f
                        val needed = spacingBefore + lineH
                        val mustBreak = currentY > 0f && currentY + needed > viewportHeightPx
                        if (mustBreak) {
                            if (line == 0) {
                                // 页尾在上一块结束处
                                closePage(lastEndChar, blockIndex)
                                pageStartBlock = blockIndex
                                pageStartChar = base + layout.getLineStart(0)
                            } else {
                                // 页尾在本块上一行结束处（段内分页）
                                closePage(base + layout.getLineEnd(line - 1), blockIndex + 1)
                                pageStartBlock = blockIndex
                                pageStartChar = base + layout.getLineStart(line)
                            }
                            currentY = lineH
                            pageHasContent = true
                        } else {
                            currentY += needed
                            if (pageStartBlock == -1) pageStartBlock = blockIndex
                            if (pageStartChar == -1) pageStartChar = base + layout.getLineStart(line)
                            pageHasContent = true
                        }
                    }
                    lastEndChar = base + block.text.length
                }

                is ContentBlock.Image -> {
                    val spacingBefore = if (pageHasContent) paragraphSpacingPx else 0f
                    val needed = spacingBefore + imageLineHeightPx
                    if (currentY > 0f && currentY + needed > viewportHeightPx) {
                        closePage(lastEndChar, blockIndex)
                        pageStartBlock = blockIndex
                        pageStartChar = base
                        currentY = imageLineHeightPx
                        pageHasContent = true
                    } else {
                        currentY += needed
                        if (pageStartBlock == -1) pageStartBlock = blockIndex
                        if (pageStartChar == -1) pageStartChar = base
                        pageHasContent = true
                    }
                    lastEndChar = base + 1
                }
            }

            if (lastEndChar > maxPagedChars) {
                overflow = true
                break@blockLoop
            }
        }

        if (pageHasContent) {
            closePage(lastEndChar, content.blocks.size)
        }

        if (pages.isEmpty()) {
            return PaginationResult(listOf(EMPTY_SPAN), version, overflow = overflow)
        }
        return PaginationResult(pages, version, overflow = overflow)
    }

    // ———— 内部工具 ————

    private fun buildPaint(typography: ReaderTypography): TextPaint {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG)
        paint.textSize = typography.fontSizeSp.coerceAtLeast(1f)
        paint.typeface = typefaceFor(typography.fontFamily, typography.bold)
        return paint
    }

    internal fun typefaceFor(family: ReaderFontFamily, bold: Boolean): Typeface {
        return when (family) {
            ReaderFontFamily.SYSTEM ->
                if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            ReaderFontFamily.SANS ->
                Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
            ReaderFontFamily.SERIF ->
                Typeface.create(Typeface.SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
            ReaderFontFamily.MONOSPACE ->
                Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    /** 统一行高（px）：fontSize * lineHeightMultiplier —— 与 Compose 侧
     *  `lineHeight = (fontSizeSp * multiplier).sp` 完全一致（分页/绘制度量对齐，风险 R5） */
    private fun lineHeightPx(paint: TextPaint, typography: ReaderTypography): Float =
        paint.textSize * typography.lineHeightMultiplier.coerceAtLeast(1f)

    private fun buildParagraphLayout(
        text: String,
        paint: TextPaint,
        widthPx: Int,
        typography: ReaderTypography,
    ): StaticLayout {
        // 强制行盒高度 = fontSize * multiplier：
        // spacingAdd = 目标行高 - 字体自然行高（可为负；乘数下限 1.2 时视觉正常）
        val desiredLineHeight = paint.textSize * typography.lineHeightMultiplier.coerceAtLeast(1f)
        val spacingAdd = desiredLineHeight - paint.fontSpacing
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(spacingAdd, 1f)
            .build()
    }

    private fun blockCharCount(block: ContentBlock): Int = when (block) {
        is ContentBlock.Paragraph -> block.text.length
        is ContentBlock.Image -> 1
    }

    /** 排版参数指纹：任何影响分页的参数变化 → 版本变化 → 缓存失效 */
    internal fun layoutVersion(spec: ReaderLayoutSpec): Long {
        val t = spec.typography
        val canonical = buildString {
            append(t.fontFamily.name).append('|')
            append(t.fontSizeSp).append('|')
            append(t.lineHeightMultiplier).append('|')
            append(t.paragraphSpacingDp).append('|')
            append(t.bold).append('|')
            append(t.horizontalPaddingDp).append('|')
            append(spec.viewportWidthPx).append('|')
            append(spec.viewportHeightPx).append('|')
            append(spec.fontScale).append('|')
            append(ENGINE_VERSION)
        }
        return fnv1a64(canonical)
    }

    private fun fnv1a64(s: String): Long {
        var hash = -0x340d631b7bdddcdbL // FNV-1a 64 offset basis
        for (i in s.indices) {
            hash = hash xor s[i].code.toLong()
            hash *= 0x100000001b3L
        }
        return hash
    }

    companion object {
        private val EMPTY_SPAN = PageSpan(0, 0, 0, 0)

        /** 引擎算法版本号（分页算法变更时 +1，联动 layoutVersion 失效缓存） */
        private const val ENGINE_VERSION = 1
    }
}
