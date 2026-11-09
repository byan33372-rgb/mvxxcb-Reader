package com.mvxxcb.reader.ui.shelf

// WP-U（04 §4）：书架。真实 UiState；空/加载/错误三态；封面 Coil；继续阅读角标；
// 长按删除确认；本地排序入口（最近阅读/加入时间/书名，仅视图层重排真实数据）。

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.mvxxcb.reader.di.AppContainer
import com.mvxxcb.reader.domain.model.Book
import com.mvxxcb.reader.presentation.shelf.ShelfUiState
import com.mvxxcb.reader.presentation.shelf.ShelfViewModel
import com.mvxxcb.reader.ui.components.ConfirmDialog
import com.mvxxcb.reader.ui.components.EmptyState
import com.mvxxcb.reader.ui.components.ErrorBanner
import com.mvxxcb.reader.ui.components.LoadingState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.mvxxcb.reader.ui.theme.spacing

/** 本地排序选项（视图层重排；书架流本身按最近阅读倒序） */
private enum class ShelfSortOption(val label: String) {
    RECENT("最近阅读"),
    ADDED("加入时间"),
    TITLE("书名"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ShelfScreen(
    container: AppContainer,
    onOpenBook: (Long) -> Unit,
    onGoDiscover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ShelfViewModel = viewModel(factory = ShelfViewModel.factory(container))
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var sortExpanded by remember { mutableStateOf(false) }
    var sortOption by remember { mutableStateOf(ShelfSortOption.RECENT) }
    var pendingDelete by remember { mutableStateOf<Book?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("书架") },
                actions = {
                    IconButton(onClick = { sortExpanded = true }) {
                        Icon(Icons.Filled.SortByAlpha, contentDescription = "排序")
                    }
                    DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                        ShelfSortOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (option == sortOption) {
                                            Icon(
                                                Icons.Filled.ArrowUpward,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(Modifier.width(4.dp))
                                        }
                                        Text(option.label)
                                    }
                                },
                                onClick = {
                                    sortOption = option
                                    sortExpanded = false
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.isLoading -> LoadingState(text = "正在加载书架…")

                state.error != null -> Column {
                    ErrorBanner(
                        error = state.error!!,
                        onRetry = { viewModel.refresh() },
                        modifier = Modifier.padding(MaterialTheme.spacing.md),
                    )
                }

                state.books.isEmpty() -> EmptyState(
                    title = "书架还是空的",
                    description = "粘贴小说网址，一键导入开始阅读",
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    actionText = "去发现·导入",
                    onAction = onGoDiscover,
                )

                else -> {
                    val books = rememberSortedBooks(state.books, sortOption)
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = MaterialTheme.spacing.screenH,
                            vertical = MaterialTheme.spacing.screenV,
                        ),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.cardGap),
                    ) {
                        items(books, key = { it.id }) { book ->
                            ShelfBookCard(
                                book = book,
                                onClick = { onOpenBook(book.id) },
                                onLongClick = { pendingDelete = book },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { book ->
        ConfirmDialog(
            title = "删除确认",
            text = "确定要删除《${book.title.ifEmpty { "未命名书籍" }}》吗？章节缓存、阅读进度与书签将一并删除。",
            confirmText = "删除",
            onConfirm = {
                viewModel.removeBook(book.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun rememberSortedBooks(
    books: List<Book>,
    option: ShelfSortOption,
): List<Book> = remember(books, option) {
    when (option) {
        ShelfSortOption.RECENT -> books.sortedByDescending { it.lastReadAt ?: it.updatedAt }
        ShelfSortOption.ADDED -> books.sortedByDescending { it.createdAt }
        ShelfSortOption.TITLE -> books.sortedBy { it.title.ifEmpty { "~" } }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfBookCard(
    book: Book,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(modifier = Modifier.padding(MaterialTheme.spacing.md)) {
            Box(
                modifier = Modifier
                    .width(64.dp)
                    .aspectRatio(0.72f),
            ) {
                if (book.coverUrl.isNullOrBlank()) {
                    // 空封面占位（不伪造图片）
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .aspectRatio(0.72f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    AsyncImage(
                        model = book.coverUrl,
                        contentDescription = "封面：${book.title}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.width(MaterialTheme.spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = book.title.ifEmpty { "（未识别书名）" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (book.lastReadAt != null) {
                        Spacer(Modifier.width(MaterialTheme.spacing.xs))
                        // 继续阅读角标
                        Badge { Text("继续阅读") }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = book.author?.takeIf { it.isNotBlank() } ?: "作者未知",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                book.latestChapterTitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = "最新：$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                val progressText = if (book.totalChapters > 0) {
                    "共 ${book.totalChapters} 章"
                } else {
                    "尚无目录"
                }
                Text(
                    text = progressText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (book.lastReadAt != null) Icons.Filled.AccessTime else Icons.Filled.Today,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = formatTime(book.lastReadAt ?: book.updatedAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun formatTime(epochMillis: Long): String = remember(epochMillis) {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    sdf.format(Date(epochMillis))
}
