package com.mvxxcb.reader.ui.catalog

// WP-U（04 §4）：目录。正倒序切换/搜索/定位当前章/缓存标记/200 条窗口分页加载；
// refreshCatalog + requiresReview 人工确认；缺章（isMissing）置灰展示。

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mvxxcb.reader.di.AppContainer
import com.mvxxcb.reader.domain.model.ChapterMeta
import com.mvxxcb.reader.presentation.catalog.CatalogEvent
import com.mvxxcb.reader.presentation.catalog.CatalogSort
import com.mvxxcb.reader.presentation.catalog.CatalogViewModel
import com.mvxxcb.reader.ui.components.EmptyState
import com.mvxxcb.reader.ui.components.ErrorBanner
import com.mvxxcb.reader.ui.components.LoadingState
import com.mvxxcb.reader.ui.components.WindowAdaptiveLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.mvxxcb.reader.ui.theme.spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    container: AppContainer,
    bookId: Long,
    focusChapterId: Long?,
    onBack: () -> Unit,
    onOpenReader: ((bookId: Long, chapterIndex: Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val viewModel: CatalogViewModel = viewModel(
        key = "catalog_$bookId",
        factory = CatalogViewModel.factory(container, bookId),
    )
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    // 接近列表底部 → 自动 loadMore（200 条窗口，01 §8）
    val shouldLoadMore by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 10
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    // 定位当前章 / focusChapterId：窗口加载到位后滚动定位
    val targetChapterId = focusChapterId ?: state.currentChapterId
    LaunchedEffect(targetChapterId, state.chapters.size, state.sort) {
        if (targetChapterId == null) return@LaunchedEffect
        val index = state.chapters.indexOfFirst { it.id == targetChapterId }
        if (index >= 0 && state.chapters.size > index) {
            listState.scrollToItem(index)
        }
    }

    // WP-INS（07 §6.2）：AdaptiveScaffold 接入；搜索框输入由 imePadding 顶起（scaffold 已排除 ime，无重复 insets）
    WindowAdaptiveLayout(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.bookTitle.ifEmpty { "目录" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "共 ${state.totalCount} 章" + (state.lastCatalogUpdatedAt?.let {
                                " · 更新于 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(it))
                            } ?: ""),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleSort() }) {
                        Icon(Icons.Filled.SwapVert, contentDescription = if (state.sort == CatalogSort.ASC) "切换倒序" else "切换正序")
                    }
                    IconButton(onClick = { viewModel.refreshCatalog() }, enabled = !state.isRefreshing) {
                        if (state.isRefreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "更新目录")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            // 搜索框
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = viewModel::onSearchChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.screenH, vertical = MaterialTheme.spacing.xs),
                placeholder = { Text("搜索章节标题") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearSearch() }) { Text("清除") }
                    }
                },
                singleLine = true,
            )

            // requiresReview 人工确认（契约 02 §8.5：matchedRatio < 0.6）
            if (state.requiresReview) {
                ErrorBanner(
                    error = com.mvxxcb.reader.domain.error.ReaderError.Parse(
                        failure = com.mvxxcb.reader.domain.error.ParseFailure.DUPLICATE_CHAPTER_CONFLICT,
                    ),
                    secondaryText = "我已核对",
                    onSecondary = { viewModel.confirmReview() },
                    modifier = Modifier.padding(MaterialTheme.spacing.md),
                )
            }

            if (state.error != null) {
                ErrorBanner(
                    error = state.error!!,
                    onRetry = { viewModel.refreshCatalog() },
                    modifier = Modifier.padding(MaterialTheme.spacing.md),
                )
            }

            when {
                state.isLoading -> LoadingState(text = "正在加载目录…")

                state.totalCount == 0 -> EmptyState(
                    title = "目录为空",
                    description = "可尝试更新目录或重新导入",
                    actionText = "更新目录",
                    onAction = { viewModel.refreshCatalog() },
                )

                else -> {
                    val displayList = state.searchResults ?: state.chapters
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(displayList.size, key = { displayList[it].id }) { index ->
                            val chapter = displayList[index]
                            CatalogChapterItem(
                                chapter = chapter,
                                isCurrent = chapter.id == state.currentChapterId,
                                onClick = { viewModel.openChapter(chapter.chapterIndex) },
                            )
                        }
                        if (state.searchResults == null && state.chapters.size < state.totalCount) {
                            item(key = "load_more_hint") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(MaterialTheme.spacing.md),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "上滑加载更多（${state.chapters.size}/${state.totalCount}）",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 章节跳转事件（契约 02 §8.5）
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is CatalogEvent.NavigateToReader -> {
                    // 由宿主传入的跳转回调处理
                    onOpenReader?.invoke(event.bookId, event.chapterIndex)
                }
            }
        }
    }
}

@Composable
private fun CatalogChapterItem(
    chapter: ChapterMeta,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(
                text = chapter.title.ifEmpty { "（无标题）" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else null,
                color = if (chapter.isMissing) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCurrent) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("当前阅读", style = MaterialTheme.typography.labelMedium)
                } else if (chapter.isMissing) {
                    Text("站点已缺失", style = MaterialTheme.typography.labelMedium)
                } else if (chapter.wordCount > 0) {
                    Text("约 ${chapter.wordCount} 字", style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        trailingContent = {
            if (chapter.isCached) {
                Icon(
                    Icons.Filled.DownloadDone,
                    contentDescription = "已缓存",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )
}
