package com.mvxxcb.reader.ui.reader

// WP-U（04 §4）：阅读内容渲染（滚动与翻页两模式共用）。
// 排版参数与 TextPagerImpl 保持同一口径（契约 02 §9：同字体族/字号/行距）：
//   - Compose 行高 = fontSize * lineHeightMultiplier（与引擎强制行盒高度一致）
//   - 图片块占位 1 字符、一个正文行高（与引擎占位一致，R5 度量对齐）

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mvxxcb.reader.domain.model.ContentBlock
import com.mvxxcb.reader.domain.model.ReaderContent
import com.mvxxcb.reader.domain.model.ReaderFontFamily
import com.mvxxcb.reader.domain.model.ReaderSettings
import com.mvxxcb.reader.reader.engine.PageSpan
import com.mvxxcb.reader.reader.theme.ReaderTheme

/** 单块渲染（滚动模式：一 block 一项；点击整块 = 工具栏显隐） */
@Composable
fun ReaderBlock(
    block: ContentBlock,
    settings: ReaderSettings,
    theme: ReaderTheme,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (block) {
        is ContentBlock.Paragraph -> Text(
            text = block.text,
            style = readerBodyStyle(settings, Color(theme.textPrimary)),
            modifier = modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        )

        is ContentBlock.Image -> ReaderImagePlaceholder(
            url = block.url,
            alt = block.alt,
            settings = settings,
            theme = theme,
            modifier = modifier.fillMaxWidth(),
        )
    }
}

/** 翻页模式单页渲染：按 PageSpan 截取文本（首/尾块部分行用字符区间截断），块间渲染段距 */
@Composable
fun ReaderPageContent(
    content: ReaderContent,
    span: PageSpan,
    settings: ReaderSettings,
    theme: ReaderTheme,
    onToggleToolbar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = remember(content, span) { pageItems(content.blocks, span) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .clickable(onClick = onToggleToolbar)
            .padding(horizontal = settings.horizontalPaddingDp.dp),
        verticalArrangement = Arrangement.spacedBy(settings.paragraphSpacingDp.dp),
    ) {
        items.forEach { item ->
            if (item.isImage) {
                ReaderImagePlaceholder(
                    url = item.imageUrl.orEmpty(),
                    alt = item.imageAlt,
                    settings = settings,
                    theme = theme,
                )
            } else {
                Text(
                    text = item.text.orEmpty(),
                    style = readerBodyStyle(settings, Color(theme.textPrimary)),
                )
            }
        }
    }
}

/** 翻页单项（截断后的段落文本或图片占位） */
internal data class PageItem(
    val text: String? = null,
    val isImage: Boolean = false,
    val imageUrl: String? = null,
    val imageAlt: String? = null,
)

/**
 * 按 PageSpan 生成页内渲染项（与 TextPagerImpl 字符口径一致：段落=文本长度，图片=1）。
 * 首/尾块按 startCharOffset / endCharOffsetExclusive 局部截断。
 */
internal fun pageItems(blocks: List<ContentBlock>, span: PageSpan): List<PageItem> {
    if (span.blockStartIndex >= span.blockEndIndexExclusive) return emptyList()
    // 起始块的累计字符基址
    var base = 0
    for (i in 0 until span.blockStartIndex) {
        base += blockCharCount(blocks[i])
    }
    val items = ArrayList<PageItem>(span.blockEndIndexExclusive - span.blockStartIndex)
    for (i in span.blockStartIndex until span.blockEndIndexExclusive) {
        val block = blocks[i]
        val count = blockCharCount(block)
        val isFirst = i == span.blockStartIndex
        val isLast = i == span.blockEndIndexExclusive - 1
        val localStart = if (isFirst) (span.startCharOffset - base).coerceIn(0, count) else 0
        val localEnd = if (isLast) (span.endCharOffsetExclusive - base).coerceIn(localStart, count) else count
        when (block) {
            is ContentBlock.Paragraph -> {
                val text = block.text
                val slice = if (localStart == 0 && localEnd >= text.length) {
                    text
                } else {
                    text.substring(localStart.coerceAtMost(text.length), localEnd.coerceAtMost(text.length))
                }
                if (slice.isNotEmpty()) items.add(PageItem(text = slice))
            }

            is ContentBlock.Image -> items.add(
                PageItem(isImage = true, imageUrl = block.url, imageAlt = block.alt),
            )
        }
        base += count
    }
    return items
}

private fun blockCharCount(block: ContentBlock): Int = when (block) {
    is ContentBlock.Paragraph -> block.text.length
    is ContentBlock.Image -> 1
}

/** 正文样式：行高 = fontSize * multiplier，与引擎测量口径一致（02 §9 / R5 风险缓解） */
@Composable
internal fun readerBodyStyle(settings: ReaderSettings, color: Color): TextStyle = TextStyle(
    fontSize = settings.fontSizeSp.sp,
    lineHeight = (settings.fontSizeSp * settings.lineHeightMultiplier).sp,
    fontFamily = composeFontFamily(settings.fontFamily),
    fontWeight = if (settings.boldText) FontWeight.Bold else FontWeight.Normal,
    color = color,
)

/** 图片占位：高度 = 一个正文行高（与引擎分页占位一致）；真实图按宽填充 */
@Composable
internal fun ReaderImagePlaceholder(
    url: String,
    alt: String?,
    settings: ReaderSettings,
    theme: ReaderTheme,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val placeholderHeight: Dp = with(density) {
        (settings.fontSizeSp * settings.lineHeightMultiplier).sp.toDp()
    }
    if (url.isBlank()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(placeholderHeight),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.BrokenImage,
                contentDescription = alt,
                tint = Color(theme.textSecondary),
                modifier = Modifier.size(placeholderHeight * 0.7f),
            )
        }
        return
    }
    AsyncImage(
        model = url,
        contentDescription = alt,
        contentScale = ContentScale.FillWidth,
        modifier = modifier
            .fillMaxWidth()
            .height(placeholderHeight),
    )
}

internal fun composeFontFamily(family: ReaderFontFamily): FontFamily = when (family) {
    ReaderFontFamily.SYSTEM -> FontFamily.Default
    ReaderFontFamily.SANS -> FontFamily.SansSerif
    ReaderFontFamily.SERIF -> FontFamily.Serif
    ReaderFontFamily.MONOSPACE -> FontFamily.Monospace
}
