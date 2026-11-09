package com.mvxxcb.reader.ui.reader

// WP-U（04 §4）：模拟翻页模式宿主。HorizontalPager 分页渲染（页内容 = TextPager 分页结果）；
// 左/中/右三区点击（上一页 | 工具栏 | 下一页），页边界点击切换上下章；
// layoutVersion 变化 → 按 inChapterFraction 映射回页码（契约 02 §8.6 注）。

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import com.mvxxcb.reader.domain.model.ReaderContent
import com.mvxxcb.reader.domain.model.ReaderSettings
import com.mvxxcb.reader.reader.engine.PaginationResult
import com.mvxxcb.reader.reader.theme.ReaderTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReaderPageHost(
    content: ReaderContent,
    settings: ReaderSettings,
    theme: ReaderTheme,
    pagination: PaginationResult?,
    restorePage: Int,
    onPageChanged: (Int) -> Unit,
    onToggleToolbar: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spans = pagination?.pages
    if (spans.isNullOrEmpty()) return
    val pageCount = spans.size

    val pagerState = rememberPagerState(
        initialPage = restorePage.coerceIn(0, pageCount - 1),
    ) { pageCount }
    val tapScope = rememberCoroutineScope()

    // —— 页码恢复：layoutVersion 变化后跳到映射页 ——
    LaunchedEffect(pagination?.layoutVersion) {
        val target = restorePage.coerceIn(0, pageCount - 1)
        if (pagerState.currentPage != target) {
            pagerState.scrollToPage(target)
        }
    }

    // —— 页码变化上报（滑动/程序跳转统一入口；VM 内换算分数并防抖保存） ——
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .collect { page -> onPageChanged(page) }
    }

    HorizontalPager(
        state = pagerState,
        modifier = modifier
            .fillMaxSize()
            .background(Color(theme.background)),
    ) { page ->
        val span = spans[page]
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pageTapZones(
                    onPrev = {
                        if (page > 0) {
                            tapScope.launch { pagerState.animateScrollToPage(page - 1) }
                        } else {
                            onPrevChapter()
                        }
                    },
                    onNext = {
                        if (page < pageCount - 1) {
                            tapScope.launch { pagerState.animateScrollToPage(page + 1) }
                        } else {
                            onNextChapter()
                        }
                    },
                    onCenter = onToggleToolbar,
                ),
            contentAlignment = Alignment.TopStart,
        ) {
            ReaderPageContent(
                content = content,
                span = span,
                settings = settings,
                theme = theme,
                onToggleToolbar = onToggleToolbar,
            )
        }
    }
}

/** 三区点击翻页（左右 1/3 各翻一页，中央 1/3 显隐工具栏） */
private fun Modifier.pageTapZones(
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onCenter: () -> Unit,
): Modifier = pointerInput(Unit) {
    detectTapGestures { offset ->
        val width = size.width
        when {
            offset.x < width / 3f -> onPrev()
            offset.x > width * 2f / 3f -> onNext()
            else -> onCenter()
        }
    }
}

/** 兼容未来扩展：暴露 PagerState 供页码指示器使用 */
@Composable
internal fun currentPagerPage(pagerState: PagerState): Int = pagerState.currentPage
