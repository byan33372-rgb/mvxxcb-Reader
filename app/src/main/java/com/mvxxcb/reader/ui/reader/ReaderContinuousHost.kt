package com.mvxxcb.reader.ui.reader

// WP-CR（07 §5，2.0 B2）：连续阅读宿主。LazyColumn 渲染 ReaderItem 流：
//  - 章间 ChapterDivider（简洁分隔样式）+ 段落/图片块（复用 ReaderBlock，与滚动模式同口径）
//  - 章尾倒数 3 项可见 → 自动扩展窗口加载下一章；窗口顶 2 项内 → 向前扩展上一章
//  - 滚动锚点（首可见段落块）节流上报 → VM 推导 charOffset 主锚点 + 500ms 防抖保存
//  - charOffset/fraction 恢复目标 → scrollToItem 定位（章内偏移附近 item）
//  - ChapterLoading/ChapterFailed（可重试）/BookEnd 占位；网络慢不阻塞已渲染内容

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mvxxcb.reader.R
import com.mvxxcb.reader.domain.model.ContentBlock
import com.mvxxcb.reader.domain.model.ReaderSettings
import com.mvxxcb.reader.presentation.reader.ChapterWindowEntry
import com.mvxxcb.reader.presentation.reader.ContinuousRestoreTarget
import com.mvxxcb.reader.presentation.reader.ReaderItem
import com.mvxxcb.reader.reader.theme.ReaderTheme
import com.mvxxcb.reader.ui.components.ErrorText
import kotlinx.coroutines.flow.collectLatest

/** 章尾自动加载触发阈值：倒数 3 项可见（07 §5.2 末章倒数 3 屏语义，按 item 计） */
private const val NEAR_END_ITEMS = 3

/** 顶部向前扩展触发阈值：窗口顶 2 项内（07 §5.2 回溯 ≤1 章/次） */
private const val NEAR_START_ITEMS = 1

@Composable
fun ReaderContinuousHost(
    entries: List<ChapterWindowEntry>,
    settings: ReaderSettings,
    theme: ReaderTheme,
    restoreTarget: ContinuousRestoreTarget?,
    jumpTarget: Int?,
    onAnchorChanged: (chapterIndex: Int, blockIndex: Int) -> Unit,
    onNearWindowEnd: () -> Unit,
    onNearWindowStart: () -> Unit,
    onRetryChapter: (chapterIndex: Int) -> Unit,
    onRestoreConsumed: () -> Unit,
    onJumpConsumed: () -> Unit,
    onToggleToolbar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // 扁平渲染列表：PENDING（窗口外/已裁剪）条目 items 为空，天然不驻留内存
    val items = remember(entries) { entries.flatMap { entry -> entry.items } }

    if (items.isEmpty()) {
        // 初始窗口尚未就绪（元数据/首章加载中）
        Box(
            modifier = modifier
                .fillMaxSize()
                .clickable(onClick = onToggleToolbar),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.reader_continuous_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(theme.textSecondary),
            )
        }
        return
    }

    // —— 恢复定位（charOffset 主锚点 → 块序号 → item；07 §5.4） ——
    LaunchedEffect(restoreTarget, entries) {
        val target = restoreTarget ?: return@LaunchedEffect
        val index = items.indexOfFirst {
            it is ReaderItem.Paragraph &&
                it.chapterIndex == target.chapterIndex &&
                it.blockIndex == target.blockIndex
        }
        if (index >= 0) {
            listState.scrollToItem(index)
        }
        onRestoreConsumed()
    }

    // —— 跳章定位（上一/下一章、目录、书签 → 该章 Divider） ——
    LaunchedEffect(jumpTarget, entries) {
        val target = jumpTarget ?: return@LaunchedEffect
        val index = items.indexOfFirst {
            it is ReaderItem.ChapterDivider && it.chapterIndex == target
        }
        if (index >= 0) {
            listState.scrollToItem(index)
        }
        onJumpConsumed()
    }

    // —— 可见性上报：章内锚点（节流）+ 窗口两端扩展触发 ——
    LaunchedEffect(listState, items) {
        var lastEmitAt = 0L
        snapshotFlow {
            val first = listState.firstVisibleItemIndex
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: first
            Triple(first, last, listState.layoutInfo.totalItemsCount)
        }.collectLatest { (first, last, total) ->
            val now = System.currentTimeMillis()
            if (now - lastEmitAt >= SCROLL_REPORT_THROTTLE_MS) {
                lastEmitAt = now
                when (val firstItem = items.getOrNull(first)) {
                    is ReaderItem.Paragraph -> onAnchorChanged(firstItem.chapterIndex, firstItem.blockIndex)
                    is ReaderItem.Image -> onAnchorChanged(firstItem.chapterIndex, firstItem.blockIndex)
                    // 分隔符/占位可见 = 章首锚点
                    is ReaderItem.ChapterDivider -> onAnchorChanged(firstItem.chapterIndex, 0)
                    is ReaderItem.ChapterLoading -> onAnchorChanged(firstItem.chapterIndex, 0)
                    is ReaderItem.ChapterFailed -> onAnchorChanged(firstItem.chapterIndex, 0)
                    ReaderItem.BookEnd -> Unit
                    null -> Unit
                }
            }
            if (total - (last + 1) <= NEAR_END_ITEMS) {
                onNearWindowEnd()
            }
            if (first <= NEAR_START_ITEMS) {
                onNearWindowStart()
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(settings.paragraphSpacingDp.dp),
    ) {
        items(items, key = { it.lazyKey() }, contentType = { it.contentType() }) { item ->
            when (item) {
                is ReaderItem.ChapterDivider -> ContinuousChapterDivider(
                    title = item.title,
                    settings = settings,
                    theme = theme,
                    onClick = onToggleToolbar,
                )

                is ReaderItem.Paragraph -> ReaderBlock(
                    block = ContentBlock.Paragraph(item.text),
                    settings = settings,
                    theme = theme,
                    onClick = onToggleToolbar,
                    modifier = Modifier.padding(horizontal = settings.horizontalPaddingDp.dp),
                )

                is ReaderItem.Image -> ReaderBlock(
                    block = ContentBlock.Image(item.url, item.alt),
                    settings = settings,
                    theme = theme,
                    onClick = onToggleToolbar,
                    modifier = Modifier.padding(horizontal = settings.horizontalPaddingDp.dp),
                )

                is ReaderItem.ChapterLoading -> ContinuousChapterLoadingItem(
                    theme = theme,
                    onClick = onToggleToolbar,
                )

                is ReaderItem.ChapterFailed -> ContinuousChapterFailedItem(
                    item = item,
                    onRetryChapter = onRetryChapter,
                    onToggleToolbar = onToggleToolbar,
                )

                ReaderItem.BookEnd -> ContinuousBookEndItem(
                    theme = theme,
                    onClick = onToggleToolbar,
                )
            }
        }
        // 尾部留白（与滚动模式一致）
        item(key = "continuous_tail_spacer") {
            Spacer(modifier = Modifier.padding(bottom = 48.dp))
        }
    }
}

/** 章间分隔（简洁样式）：细分隔线 + 章标题（次级色） */
@Composable
private fun ContinuousChapterDivider(
    title: String,
    settings: ReaderSettings,
    theme: ReaderTheme,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = settings.horizontalPaddingDp.dp)
            .padding(top = 40.dp, bottom = 4.dp),
    ) {
        HorizontalDivider(
            color = Color(theme.textSecondary).copy(alpha = 0.35f),
            thickness = 0.5.dp,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = Color(theme.textSecondary),
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun ContinuousChapterLoadingItem(theme: ReaderTheme, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.reader_continuous_loading_next),
            style = MaterialTheme.typography.bodySmall,
            color = Color(theme.textSecondary),
        )
    }
}

@Composable
private fun ContinuousChapterFailedItem(
    item: ReaderItem.ChapterFailed,
    onRetryChapter: (Int) -> Unit,
    onToggleToolbar: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleToolbar)
            .padding(horizontal = 0.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.reader_continuous_chapter_failed),
            style = MaterialTheme.typography.titleSmall,
        )
        ErrorText(error = item.error)
        if (item.canRetry) {
            TextButton(onClick = { onRetryChapter(item.chapterIndex) }) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

@Composable
private fun ContinuousBookEndItem(theme: ReaderTheme, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.reader_book_end),
            style = MaterialTheme.typography.titleSmall,
            color = Color(theme.textSecondary),
        )
    }
}

/** LazyColumn 稳定 key：章节/块身份不随窗口增删漂移（位置保锚的必要条件） */
private fun ReaderItem.lazyKey(): String = when (this) {
    is ReaderItem.ChapterDivider -> "c_div_$chapterIndex"
    is ReaderItem.Paragraph -> "c_${chapterIndex}_p_$blockIndex"
    is ReaderItem.Image -> "c_${chapterIndex}_img_$blockIndex"
    is ReaderItem.ChapterLoading -> "c_${chapterIndex}_loading"
    is ReaderItem.ChapterFailed -> "c_${chapterIndex}_failed"
    ReaderItem.BookEnd -> "c_book_end"
}

/** WP-PERF：异构行 contentType（段落/图片/分隔/占位复用各自组合槽，跨项复用不混淆类型） */
private fun ReaderItem.contentType(): String = when (this) {
    is ReaderItem.ChapterDivider -> "chapter-divider"
    is ReaderItem.Paragraph -> "paragraph"
    is ReaderItem.Image -> "image"
    is ReaderItem.ChapterLoading -> "chapter-loading"
    is ReaderItem.ChapterFailed -> "chapter-failed"
    ReaderItem.BookEnd -> "book-end"
}
