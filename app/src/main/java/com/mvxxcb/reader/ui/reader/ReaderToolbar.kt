package com.mvxxcb.reader.ui.reader

// WP-U（04 §4）：阅读器工具栏（顶部：返回/书名·章名/书签入口；底部：上下章/目录/进度/设置）
// + 简版书签面板（主 Agent 裁定 2：添加/查看列表/删除，复用 BookmarkRepository，不做独立页）。

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mvxxcb.reader.domain.model.Bookmark
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.mvxxcb.reader.ui.theme.ElevationTokens
import com.mvxxcb.reader.ui.theme.spacing

/** 顶部工具栏 */
@Composable
fun ReaderTopBar(
    visible: Boolean,
    bookTitle: String,
    chapterTitle: String,
    onBack: () -> Unit,
    onToggleBookmarkSheet: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
    ) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = ElevationTokens.Level2) {
            // WP-INS（07 §6.2）：工具栏展开（系统栏 show）时顶栏避开状态栏/挖孔；沉浸隐藏时该 padding 自然为 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = MaterialTheme.spacing.xs, vertical = MaterialTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = bookTitle.ifEmpty { "阅读中" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = chapterTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 简版书签入口（主 Agent 裁定 2）
                IconButton(onClick = onToggleBookmarkSheet) {
                    Icon(Icons.Filled.Bookmarks, contentDescription = "书签")
                }
            }
        }
    }
}

/** 底部工具栏：上下章 / 目录 / 进度 / 设置 */
@Composable
fun ReaderBottomBar(
    visible: Boolean,
    progressText: String,
    hasPrevious: Boolean,
    hasNext: Boolean,
    isPrefetchingNext: Boolean,
    savingProgressHint: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onOpenCatalog: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = ElevationTokens.Level2) {
            // WP-INS（07 §6.2）：底栏避开导航栏/手势区（三键导航与手势导航均由 navigationBars insets 覆盖）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            ) {
                Text(
                    text = buildString {
                        append(progressText)
                        if (savingProgressHint) append(" · 保存进度中…")
                        if (isPrefetchingNext) append(" · 预取下一章中…")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = MaterialTheme.spacing.md, vertical = MaterialTheme.spacing.xs),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onPrevChapter, enabled = hasPrevious) {
                        Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = null)
                        Text("上一章")
                    }
                    IconButton(onClick = onOpenCatalog) {
                        Icon(Icons.Filled.FormatListBulleted, contentDescription = "目录")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Tune, contentDescription = "阅读设置")
                    }
                    TextButton(onClick = onNextChapter, enabled = hasNext) {
                        Text("下一章")
                        Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = null)
                    }
                }
            }
        }
    }
}

/** 简版书签面板：添加当前位置书签 + 列表（章名/位置/时间）+ 删除 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkSheet(
    visible: Boolean,
    bookmarks: List<Bookmark>,
    chapterTitle: String,
    currentChapterIndex: Int,
    onDismiss: () -> Unit,
    onAddBookmark: () -> Unit,
    onRemoveBookmark: (Long) -> Unit,
    onJumpToBookmark: (Bookmark) -> Unit,
) {
    if (!visible) return
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = MaterialTheme.spacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "书签",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onAddBookmark) {
                    Icon(Icons.Filled.BookmarkAdd, contentDescription = null)
                    Spacer(Modifier.size(MaterialTheme.spacing.xs))
                    Text("添加当前位置")
                }
            }
            Text(
                text = "当前：$chapterTitle",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = MaterialTheme.spacing.sm),
            )
            if (bookmarks.isEmpty()) {
                Text(
                    "本章尚无书签",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = MaterialTheme.spacing.lg),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.height(320.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(bookmarks, key = { it.id }) { bookmark ->
                        ListItem(
                            modifier = Modifier.clickable { onJumpToBookmark(bookmark) },
                            headlineContent = {
                                Text(
                                    "第${bookmark.chapterIndex + 1}章 · " +
                                        "${(bookmark.inChapterFraction * 100).toInt()}%",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Text(
                                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                        .format(Date(bookmark.createdAt)),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = { onRemoveBookmark(bookmark.id) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "删除书签",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
