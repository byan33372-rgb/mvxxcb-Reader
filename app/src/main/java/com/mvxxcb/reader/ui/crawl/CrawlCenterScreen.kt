package com.mvxxcb.reader.ui.crawl

// WP-L（07 §6.2 / B3）：采集任务管理页。真实 UiState；空/加载/错误三态；
// 任务卡片：封面（书名首字占位，Coil 红线：不加载远端图）/书名/来源域名/总数/成功/失败/
// 当前章/状态 chip/文件生成态；操作按钮按 K9 状态机按需出现，全部真实接业务（K10）。
// WP-INS：AdaptiveScaffold 接入（scaffoldPadding 在容器层消费一次，禁写死系统栏高度）。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mvxxcb.reader.di.AppContainer
import com.mvxxcb.reader.domain.model.CrawlState
import com.mvxxcb.reader.presentation.crawl.CrawlCenterEvent
import com.mvxxcb.reader.presentation.crawl.CrawlCenterViewModel
import com.mvxxcb.reader.presentation.crawl.CrawlTaskItem
import com.mvxxcb.reader.ui.components.EmptyState
import com.mvxxcb.reader.ui.components.ErrorBanner
import com.mvxxcb.reader.ui.components.LoadingState
import com.mvxxcb.reader.ui.components.WindowAdaptiveLayout
import com.mvxxcb.reader.ui.theme.spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrawlCenterScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: CrawlCenterViewModel = viewModel(factory = CrawlCenterViewModel.factory(container))
    val state by viewModel.uiState.collectAsState()

    // 一次性导航事件（契约 02 §8.4）
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is CrawlCenterEvent.OpenBook -> onOpenBook(event.bookId)
            }
        }
    }

    WindowAdaptiveLayout(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("采集中心") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        // scaffoldPadding 在容器层消费一次；列表/横幅不再重复取 insets（07 §6.1.3 禁重复 insets）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (state.actionError != null) {
                    ErrorBanner(
                        error = state.actionError!!,
                        onDismiss = { viewModel.clearActionError() },
                        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.md),
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    when {
                        state.isLoading -> LoadingState(text = "正在加载采集任务…")

                        state.error != null -> ErrorBanner(
                            error = state.error!!,
                            onRetry = { viewModel.refresh() },
                            modifier = Modifier.padding(MaterialTheme.spacing.md),
                        )

                        state.items.isEmpty() -> EmptyState(
                            title = "暂无采集任务",
                            description = "在书籍详情页点击「下载整本小说」即可开始整本下载",
                            icon = Icons.Filled.CloudDownload,
                        )

                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                horizontal = MaterialTheme.spacing.screenH,
                                vertical = MaterialTheme.spacing.screenV,
                            ),
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.cardGap),
                        ) {
                            items(state.items, key = { it.task.id }) { item ->
                                CrawlTaskCard(
                                    item = item,
                                    onPause = { viewModel.pause(item.task.id) },
                                    onResume = { viewModel.resume(item.task.id) },
                                    onCancel = { viewModel.cancel(item.task.id) },
                                    onRetryFailures = { viewModel.retryFailures(item.task.id) },
                                    onDelete = { viewModel.deleteRecord(item.task.id) },
                                    onOpenBook = { viewModel.openBook(item.task.bookId) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CrawlTaskCard(
    item: CrawlTaskItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetryFailures: () -> Unit,
    onDelete: () -> Unit,
    onOpenBook: () -> Unit,
) {
    val task = item.task
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 封面占位：书名首字（不加载远端图片，Coil 红线）
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item.bookTitle.take(1).ifEmpty { "书" },
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(MaterialTheme.spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.bookTitle,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.bookDomain.isNotBlank()) {
                        Text(
                            text = item.bookDomain,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(MaterialTheme.spacing.sm))
                StateChip(state = item.state)
            }

            Spacer(Modifier.height(MaterialTheme.spacing.sm))

            // 进度：非终态显示进度条与当前章；终态显示统计
            val total = task.totalChapters
            val done = task.succeededCount + task.failedCount
            if (!item.isActive) {
                Text(
                    text = "成功 ${task.succeededCount} · 失败 ${task.failedCount} · 共 $total 章",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LinearProgressIndicator(
                    progress = { if (total > 0) done.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "已推进 $done / $total（成功 ${task.succeededCount} · 失败 ${task.failedCount}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            task.currentChapterTitle?.takeIf { it.isNotBlank() }?.let { title ->
                Text(
                    text = "当前：第${task.currentChapterIndex + 1}章 $title",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 文件生成态（Q3：COMPLETED 自动生成 TXT）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (task.txtGenerated) Icons.Filled.CheckCircle else Icons.Filled.Description,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (task.txtGenerated) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (task.txtGenerated) "TXT 已生成" else "TXT 未生成",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 操作行：按 K9 状态机出现可用按钮（全部真实接业务：K10 / 仓库终态删除契约）
            Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                TextButton(onClick = onOpenBook) { Text("打开小说") }
                if (item.canPause) {
                    OutlinedButton(onClick = onPause) { Text("暂停") }
                }
                if (item.canResume) {
                    Button(onClick = onResume) { Text("继续") }
                }
                if (item.canCancel) {
                    OutlinedButton(onClick = onCancel) { Text("取消") }
                }
                if (item.canRetryFailures) {
                    Button(onClick = onRetryFailures) { Text("重试失败章") }
                }
                if (item.canDelete) {
                    OutlinedButton(onClick = onDelete) { Text("清除记录") }
                }
            }
        }
    }
}

/** 状态 chip（M3 色对随状态；文案直读枚举语义，QA 断言状态而非文案，契约 02 §11.6） */
@Composable
private fun StateChip(state: CrawlState) {
    val container = when (state) {
        CrawlState.WAITING -> MaterialTheme.colorScheme.secondaryContainer
        CrawlState.RUNNING -> MaterialTheme.colorScheme.primaryContainer
        CrawlState.PAUSED -> MaterialTheme.colorScheme.tertiaryContainer
        CrawlState.FAILED -> MaterialTheme.colorScheme.errorContainer
        CrawlState.COMPLETED -> MaterialTheme.colorScheme.primaryContainer
        CrawlState.CANCELLED -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (state) {
        CrawlState.WAITING -> MaterialTheme.colorScheme.onSecondaryContainer
        CrawlState.RUNNING -> MaterialTheme.colorScheme.onPrimaryContainer
        CrawlState.PAUSED -> MaterialTheme.colorScheme.onTertiaryContainer
        CrawlState.FAILED -> MaterialTheme.colorScheme.onErrorContainer
        CrawlState.COMPLETED -> MaterialTheme.colorScheme.onPrimaryContainer
        CrawlState.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .background(container, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = stateLabel(state),
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
    }
}

private fun stateLabel(state: CrawlState): String = when (state) {
    CrawlState.WAITING -> "排队中"
    CrawlState.RUNNING -> "进行中"
    CrawlState.PAUSED -> "已暂停"
    CrawlState.FAILED -> "有失败"
    CrawlState.COMPLETED -> "已完成"
    CrawlState.CANCELLED -> "已取消"
}
