package com.mvxxcb.reader.ui.bookdetail

// WP-U（04 §4）：书详情。真实 UiState；空/加载/错误三态；封面 Coil；
// 继续阅读/开始阅读、目录入口、重新解析详情、删除确认、临时书标记。

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.mvxxcb.reader.R
import com.mvxxcb.reader.di.AppContainer
import com.mvxxcb.reader.presentation.bookdetail.BookDetailEvent
import com.mvxxcb.reader.presentation.bookdetail.BookDetailViewModel
import com.mvxxcb.reader.ui.components.ConfirmDialog
import com.mvxxcb.reader.ui.components.EmptyState
import com.mvxxcb.reader.ui.components.ErrorBanner
import com.mvxxcb.reader.ui.components.LoadingState
import com.mvxxcb.reader.ui.components.WindowAdaptiveLayout
import com.mvxxcb.reader.ui.theme.spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    container: AppContainer,
    bookId: Long,
    onBack: () -> Unit,
    onGoCatalog: ((Long) -> Unit)? = null,
    onGoReader: ((Long, Int) -> Unit)? = null,
    onOpenCrawlCenter: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val viewModel: BookDetailViewModel = viewModel(
        key = "book_$bookId",
        factory = BookDetailViewModel.factory(container, bookId),
    )
    val state by viewModel.uiState.collectAsState()
    val progressChapterIndex by viewModel.progressChapterIndex.collectAsState()
    val deleted by viewModel.deleted.collectAsState()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // 一次性导航事件（契约 02 §8.4）
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is BookDetailEvent.NavigateToCatalog ->
                    onGoCatalog?.invoke(event.bookId) // 目录页自定位当前章
                is BookDetailEvent.NavigateToReader -> onGoReader?.invoke(event.bookId, event.chapterIndex)
            }
        }
    }

    // 删除完成 → 返回上级
    LaunchedEffect(deleted) {
        if (deleted) onBack()
    }

    // WP-INS（07 §6.2）：AdaptiveScaffold 接入——文字内容位于 safeDrawing 安全区内
    // （封面延伸至状态栏后的背景图为 2.0 WP-L 范围，本页仅做 insets 适配）
    WindowAdaptiveLayout(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(state.book?.title?.ifEmpty { "书籍详情" } ?: "书籍详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshDetail() }, enabled = !state.isRefreshing) {
                        Icon(Icons.Filled.Refresh, contentDescription = "重新解析详情")
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除")
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
                state.isLoading -> LoadingState()

                state.book == null && state.error != null -> Column {
                    ErrorBanner(
                        error = state.error!!,
                        onDismiss = { viewModel.consumeError() },
                        modifier = Modifier.padding(MaterialTheme.spacing.md),
                    )
                }

                state.book == null -> EmptyState(title = "书籍不存在或已被删除")

                else -> {
                    val book = state.book!!
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(
                                horizontal = MaterialTheme.spacing.screenH,
                                vertical = MaterialTheme.spacing.screenV,
                            ),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
                    ) {
                        if (state.isTemporary) {
                            AssistChip(
                                onClick = {},
                                label = { Text("临时书：仅解析到正文，未建立完整目录") },
                            )
                        }
                        Row {
                            Box(
                                modifier = Modifier
                                    .width(96.dp)
                                    .aspectRatio(0.72f),
                            ) {
                                if (book.coverUrl.isNullOrBlank()) {
                                    Card(modifier = Modifier.fillMaxSize()) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text("无封面", style = MaterialTheme.typography.labelMedium)
                                        }
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
                                Text(
                                    text = book.title.ifEmpty { "（未识别书名）" },
                                    style = MaterialTheme.typography.headlineSmall,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = book.author?.takeIf { it.isNotBlank() } ?: "作者未知",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = if (book.totalChapters > 0) "共 ${book.totalChapters} 章" else "尚无目录",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                book.sourceEncoding?.let {
                                    Text(
                                        text = "站点编码：$it",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        if (state.error != null) {
                            ErrorBanner(
                                error = state.error!!,
                                onDismiss = { viewModel.consumeError() },
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                            Button(
                                onClick = { viewModel.startReading() },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    if (progressChapterIndex != null) {
                                        "继续阅读 · 第${(progressChapterIndex ?: 0) + 1}章"
                                    } else {
                                        "开始阅读"
                                    },
                                )
                            }
                            OutlinedButton(
                                onClick = { viewModel.openCatalog() },
                                enabled = book.totalChapters > 0,
                                modifier = Modifier.weight(1f),
                            ) { Text("查看目录") }
                        }

                        // WP-L（07 §6.2）：下载整本小说入口 + 任务态 + SAF 导出（可选拆分组件）
                        BookDownloadSection(
                            container = container,
                            bookId = bookId,
                            bookTitle = book.title,
                            totalChapters = book.totalChapters,
                            sourceDomain = book.sourceDomain,
                            onOpenCrawlCenter = { onOpenCrawlCenter?.invoke() },
                        )

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(MaterialTheme.spacing.md)) {
                                Text("简介", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = book.description?.takeIf { it.isNotBlank() } ?: "（未识别简介）",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Text(
                            text = "来源：${book.sourceDomain}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        ConfirmDialog(
            title = "删除确认",
            text = "删除后章节缓存、阅读进度与书签将一并清除，且不可恢复。\n" +
                stringResource(R.string.delete_book_crawl_note),
            confirmText = "删除",
            onConfirm = {
                showDeleteConfirm = false
                viewModel.removeBook()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}
