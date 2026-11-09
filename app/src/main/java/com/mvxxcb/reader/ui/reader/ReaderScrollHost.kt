package com.mvxxcb.reader.ui.reader

// WP-U（04 §4）：滚动模式宿主。滚动 fraction 节流上报（Screen 侧 ~120ms，VM 内 500ms 防抖存进度）；
// 分页 layoutVersion 变化 → 按 inChapterFraction 映射回滚动位置（契约 02 §8.6 注）。
// 分数估算基于 LazyColumn 可见项均值，MVP 精度（页码级恢复以 PageSpan 定位为准）。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mvxxcb.reader.domain.model.ReaderContent
import com.mvxxcb.reader.domain.model.ReaderSettings
import com.mvxxcb.reader.reader.engine.PaginationResult
import com.mvxxcb.reader.reader.engine.ReaderPositionMappers
import com.mvxxcb.reader.reader.theme.ReaderTheme
import kotlinx.coroutines.flow.collectLatest

internal const val SCROLL_REPORT_THROTTLE_MS = 120L

@Composable
fun ReaderScrollHost(
    content: ReaderContent,
    settings: ReaderSettings,
    theme: ReaderTheme,
    pagination: PaginationResult?,
    restoreFraction: Double,
    onScrolled: (Double) -> Unit,
    onToggleToolbar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // —— 位置恢复：layoutVersion 变化（首次分页/重排版）按分数定位到对应页起始块 ——
    LaunchedEffect(pagination?.layoutVersion) {
        val currentPagination = pagination ?: return@LaunchedEffect
        if (currentPagination.pages.isEmpty()) return@LaunchedEffect
        val fraction = restoreFraction.coerceIn(0.0, 1.0)
        if (fraction <= 0.0) {
            listState.scrollToItem(0)
        } else {
            val page = ReaderPositionMappers.DEFAULT.fractionToPage(
                fraction, currentPagination.pages.size,
            )
            listState.scrollToItem(currentPagination.pages[page].blockStartIndex)
        }
    }

    // —— 滚动上报（节流 ~120ms；VM 内再做 500ms 防抖持久化） ——
    LaunchedEffect(listState, content, pagination?.layoutVersion) {
        var lastEmitAt = 0L
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collectLatest { (_, _) ->
                val now = System.currentTimeMillis()
                if (now - lastEmitAt >= SCROLL_REPORT_THROTTLE_MS) {
                    lastEmitAt = now
                    onScrolled(estimateScrollFraction(listState))
                }
            }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(settings.paragraphSpacingDp.dp),
    ) {
        itemsIndexed(content.blocks) { _, block ->
            ReaderBlock(
                block = block,
                settings = settings,
                theme = theme,
                onClick = onToggleToolbar,
                modifier = Modifier.padding(horizontal = settings.horizontalPaddingDp.dp),
            )
        }
        // 尾部留白：末页可完整滚出（预取由 >80% 分数触发）
        item(key = "tail_spacer") {
            Spacer(modifier = Modifier.padding(bottom = 48.dp))
        }
    }
}

/** 滚动分数估算：可见项均值 × 总项数（MVP 精度，详见文件头注记） */
internal fun estimateScrollFraction(listState: LazyListState): Double {
    val info = listState.layoutInfo
    if (info.totalItemsCount <= 0) return 0.0
    val visibleItems = info.visibleItemsInfo
    if (visibleItems.isEmpty()) return 0.0
    val viewportHeight = (info.viewportEndOffset - info.viewportStartOffset).coerceAtLeast(1)
    val avgItemHeight = visibleItems.sumOf { it.size } / visibleItems.size
    if (avgItemHeight <= 0) return 0.0
    val totalContentHeight = avgItemHeight * info.totalItemsCount
    if (totalContentHeight <= viewportHeight) return 0.0
    val consumed = listState.firstVisibleItemIndex * avgItemHeight +
        listState.firstVisibleItemScrollOffset
    val fraction = consumed.toDouble() / (totalContentHeight - viewportHeight).toDouble()
    return fraction.coerceIn(0.0, 1.0)
}
