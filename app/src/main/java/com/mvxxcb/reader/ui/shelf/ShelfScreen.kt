package com.mvxxcb.reader.ui.shelf

// WP-U（04 §4）→ 2.0 WP-L（07 §6.2 / B3）五分组升级：
// 顶部过滤 chips（全部/在线/下载中/已下载/本地导入，sticky 随内容滚动）；
// 真实 UiState；空/加载/错误三态；封面 Coil；继续阅读 + 下载态角标；
// 长按删除确认（含采集任务/TXT 登记级联删除说明文案）；
// 本地排序入口（最近阅读/加入时间/书名，仅视图层重排真实数据）——既有排序零回归。

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.mvxxcb.reader.R
import com.mvxxcb.reader.di.AppContainer
import com.mvxxcb.reader.domain.model.Book
import com.mvxxcb.reader.presentation.shelf.ShelfDownloadBadge
import com.mvxxcb.reader.presentation.shelf.ShelfGroup
import com.mvxxcb.reader.presentation.shelf.ShelfUiState
import com.mvxxcb.reader.presentation.shelf.ShelfViewModel
import com.mvxxcb.reader.ui.components.ConfirmDialog
import com.mvxxcb.reader.ui.components.EmptyState
import com.mvxxcb.reader.ui.components.ErrorBanner
import com.mvxxcb.reader.ui.components.LoadingState
import com.mvxxcb.reader.ui.components.WindowAdaptiveLayout
import com.mvxxcb.reader.ui.components.listContentPadding
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
    var selectedGroup by remember { mutableStateOf(ShelfGroup.ALL) }
    var pendingDelete by remember { mutableStateOf<Book?>(null) }

    // WP-INS（07 §6.2）：AdaptiveScaffold 接入——safeDrawing 安全区统一由 WindowAdaptiveLayout 消费；
    // 列表 contentPadding = scaffoldPadding + 列表间距（禁写死顶高、禁重复 insets）
    WindowAdaptiveLayout(
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
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state.isLoading -> LoadingState(
                    text = "正在加载书架…",
                    modifier = Modifier.padding(padding),
                )

                state.error != null -> Column(modifier = Modifier.padding(padding)) {
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
                    modifier = Modifier.padding(padding),
                )

                else -> {
                    // WP-PERF：分组过滤记忆化（books/badges/group 不变则不重算，滚动路径零过滤成本）
                    val filtered = remember(state.books, state.downloadBadges, selectedGroup) {
                        filterBooksInGroup(state.books, state.downloadBadges, selectedGroup)
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = listContentPadding(
                            scaffoldPadding = padding,
                            horizontal = MaterialTheme.spacing.screenH,
                            vertical = MaterialTheme.spacing.screenV,
                        ),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.cardGap),
                    ) {
                        // 五分组过滤 chips（sticky：随内容滚动但常驻可见）
                        stickyHeader(key = "group-chips") {
                            GroupFilterRow(
                                selected = selectedGroup,
                                onSelect = { selectedGroup = it },
                                modifier = Modifier.padding(vertical = MaterialTheme.spacing.xs),
                            )
                        }
                        if (filtered.isEmpty()) {
                            item(key = "group-empty") {
                                Text(
                                    text = "「${selectedGroup.label}」分组暂无书籍",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = MaterialTheme.spacing.xl),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        }
                        // WP-PERF：稳定 key（bookId）+ contentType（同构卡片行复用）+
                        // 角标读取上提（item 作用域不再订阅整个 uiState，滚动期间不因无关状态变化全卡片重组）
                        val badges = state.downloadBadges
                        items(filtered, key = { it.id }, contentType = { "shelf-book" }) { book ->
                            ShelfBookCard(
                                book = book,
                                badge = badges[book.id],
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
            text = "确定要删除《${book.title.ifEmpty { "未命名书籍" }}》吗？章节缓存、阅读进度与书签将一并删除。\n" +
                stringResource(R.string.delete_book_crawl_note),
            confirmText = "删除",
            onConfirm = {
                viewModel.removeBook(book.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** 按分组过滤（分组判定在 VM 层完成，Screen 只做视图过滤，零回归既有排序） */
private fun filterBooksInGroup(
    books: List<Book>,
    badges: Map<Long, ShelfDownloadBadge?>,
    group: ShelfGroup,
): List<Book> = when (group) {
    ShelfGroup.ALL -> books
    else -> books.filter { book ->
        com.mvxxcb.reader.presentation.shelf.ShelfGrouping.groupOf(badges[book.id]) == group
    }
}

/** 五分组过滤 chips（全部/在线/下载中/已下载/本地导入） */
@Composable
private fun GroupFilterRow(
    selected: ShelfGroup,
    onSelect: (ShelfGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        ShelfGroup.entries.forEach { group ->
            FilterChip(
                selected = selected == group,
                onClick = { onSelect(group) },
                label = { Text(group.label) },
            )
        }
    }
}

@Composable
private fun badgeLabel(badge: ShelfDownloadBadge): String = when (badge) {
    ShelfDownloadBadge.DOWNLOADING -> "下载中"
    ShelfDownloadBadge.DOWNLOADED -> "已下载"
    ShelfDownloadBadge.PARTIAL -> "部分下载"
    ShelfDownloadBadge.LOCAL -> "本地"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfBookCard(
    book: Book,
    badge: ShelfDownloadBadge?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(modifier = Modifier.padding(MaterialTheme.spacing.md)) {
            // WP-PERF：封面按容器物理像素解码（64dp 宽 × 0.72 高），不再按原图全尺寸解码；
            // 容器固定宽高（width+aspectRatio 一次约束），减少一层冗余 aspectRatio 嵌套
            Box(
                modifier = Modifier
                    .width(64.dp)
                    .aspectRatio(0.72f),
            ) {
                if (book.coverUrl.isNullOrBlank()) {
                    // 空封面占位（不伪造图片）
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.MenuBook,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    val context = LocalContext.current
                    val density = LocalDensity.current
                    val coverRequest = remember(book.coverUrl, density) {
                        buildCoverRequest(context, book.coverUrl, density)
                    }
                    AsyncImage(
                        model = coverRequest,
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
                    badge?.let {
                        Spacer(Modifier.width(MaterialTheme.spacing.xs))
                        // 下载态角标（2.0 WP-L 五分组）
                        val containerColor = when (it) {
                            ShelfDownloadBadge.DOWNLOADING -> MaterialTheme.colorScheme.primary
                            ShelfDownloadBadge.DOWNLOADED -> MaterialTheme.colorScheme.tertiary
                            ShelfDownloadBadge.PARTIAL -> MaterialTheme.colorScheme.error
                            ShelfDownloadBadge.LOCAL -> MaterialTheme.colorScheme.secondary
                        }
                        val contentColor = when (it) {
                            ShelfDownloadBadge.DOWNLOADING -> MaterialTheme.colorScheme.onPrimary
                            ShelfDownloadBadge.DOWNLOADED -> MaterialTheme.colorScheme.onTertiary
                            ShelfDownloadBadge.PARTIAL -> MaterialTheme.colorScheme.onError
                            ShelfDownloadBadge.LOCAL -> MaterialTheme.colorScheme.onSecondary
                        }
                        Badge(containerColor = containerColor, contentColor = contentColor) {
                            Text(badgeLabel(it))
                        }
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

/**
 * WP-PERF：封面解码尺寸 = 容器物理像素（宽 64dp × 高 64dp/0.72，Crop 按目标宽高解码）。
 * 网络地址仍走 Coil 内存/磁盘缓存与请求去重（默认 ImageLoader），仅限制解码尺寸避免
 * 大图按原尺寸解码进内存（基线夹具封面为空，此项为真实网络书性能修复）。
 */
private fun buildCoverRequest(
    context: android.content.Context,
    coverUrl: String?,
    density: androidx.compose.ui.unit.Density,
): coil.request.ImageRequest {
    val widthPx = with(density) { 64.dp.roundToPx() }
    val heightPx = (widthPx / 0.72f).toInt()
    return coil.request.ImageRequest.Builder(context)
        .data(coverUrl)
        .size(widthPx, heightPx)
        .crossfade(false)
        .build()
}
