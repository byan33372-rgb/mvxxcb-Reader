package com.mvxxcb.reader.ui.bookdetail

// WP-L（07 §6.2 / B3）：书详情「下载整本小说」入口 + 任务态 + SAF 导出。
// 采集任务流（observeTasks 按书取最近任务）+ 本地书登记流（observeForBook）驱动四态：
// 空闲（无目录→引导更新目录）/ 进行中（K9 进度）/ 终态（导出 TXT(SAF) + 打开采集中心）。
// 操作全部真实接业务：downloadWholeBook / cancelCrawl / resumeCrawl / retryCrawlFailures /
// exportBookTxt（K10）；「导出 TXT（SAF）」= CreateDocument(text/plain) → contentResolver
// openOutputStream 复制受控 TXT（LocalBook.filePath）到用户选择位置（受控文件不动）。
// 错误经 ErrorText 映射（02 §10）；本组件为可选拆分文件，容器直取 AppContainer 单例，
// 一次性 suspend 操作经 rememberCoroutineScope 触发（与 K10 单发语义一致）。

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.di.AppContainer
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StateFailure
import com.mvxxcb.reader.domain.error.StorageFailure
import com.mvxxcb.reader.domain.model.CrawlState
import com.mvxxcb.reader.domain.model.CrawlTask
import com.mvxxcb.reader.domain.model.LocalBook
import com.mvxxcb.reader.presentation.crawl.isTerminal
import com.mvxxcb.reader.ui.components.ConfirmDialog
import com.mvxxcb.reader.ui.components.ErrorText
import com.mvxxcb.reader.ui.theme.spacing
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BookDownloadSection(
    container: AppContainer,
    bookId: Long,
    bookTitle: String,
    totalChapters: Int,
    sourceDomain: String,
    onOpenCrawlCenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var latestTask by remember(bookId) { mutableStateOf<CrawlTask?>(null) }
    var localBook by remember(bookId) { mutableStateOf<LocalBook?>(null) }
    var actionError by remember(bookId) { mutableStateOf<ReaderError?>(null) }
    var confirmDownload by remember(bookId) { mutableStateOf(false) }
    var isExporting by remember(bookId) { mutableStateOf(false) }

    // 采集任务态：按书取最近任务（同书非终态唯一 K9；终态后新建任务的 updatedAt 恒更新）
    LaunchedEffect(bookId) {
        container.crawlTaskRepository.observeTasks()
            .map { tasks -> tasks.filter { it.bookId == bookId }.maxByOrNull { it.updatedAt } }
            .collect { latestTask = it }
    }
    // 本地 TXT 登记态（生成/覆盖/删除登记实时可见）
    LaunchedEffect(bookId) {
        container.localBookRepository.observeForBook(bookId).collect { localBook = it }
    }

    // SAF 导出（任务书第 1 项）：CreateDocument("text/plain") → 受控 TXT 复制到用户位置
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isExporting = true
            try {
                val target = localBook
                    ?: (container.readingService.exportBookTxt(bookId) as? AppResult.Success)?.value
                val file = target?.let { File(it.filePath) }
                when {
                    target == null || file == null || !file.exists() -> actionError =
                        ReaderError.State(
                            StateFailure.LOCAL_BOOK_NOT_FOUND,
                            debugDetail = "SAF 导出：受控 TXT 缺失 bookId=$bookId",
                        )
                    else -> withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                file.inputStream().use { input -> input.copyTo(out) }
                            } ?: error("openOutputStream 返回 null")
                        }.onFailure { e ->
                            actionError = ReaderError.Storage(
                                StorageFailure.DISK_IO,
                                debugDetail = "SAF 导出失败：${e.message}",
                                cause = e,
                            )
                        }
                    }
                }
            } finally {
                isExporting = false
            }
        }
    }

    fun downloadWholeBook() {
        scope.launch {
            when (val result = container.readingService.downloadWholeBook(bookId)) {
                is AppResult.Failure -> actionError = result.error
                is AppResult.Success -> Unit // 任务流自动刷新进度态
            }
        }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        ) {
            Text("整本下载", style = MaterialTheme.typography.titleMedium)

            val task = latestTask
            when {
                isExporting -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(MaterialTheme.spacing.sm))
                    Text("正在导出 TXT…", style = MaterialTheme.typography.bodyMedium)
                }

                task != null && !task.state.isTerminal -> ActiveTaskView(
                    task = task,
                    onCancel = { scope.launch { container.readingService.cancelCrawl(bookId) } },
                    onResume = { scope.launch { container.readingService.resumeCrawl(task.id) } },
                )

                task != null && task.state == CrawlState.COMPLETED -> TerminalTaskView(
                    task = task,
                    localBook = localBook,
                    isExporting = isExporting,
                    onExport = { exportLauncher.launch("${bookTitle.ifBlank { "book-$bookId" }}.txt") },
                    onRedownload = { confirmDownload = true },
                    onOpenCrawlCenter = onOpenCrawlCenter,
                )

                task != null && task.state == CrawlState.FAILED -> FailedTaskView(
                    task = task,
                    localBook = localBook,
                    isExporting = isExporting,
                    onRetryFailures = { scope.launch { container.readingService.retryCrawlFailures(task.id) } },
                    onExport = { exportLauncher.launch("${bookTitle.ifBlank { "book-$bookId" }}.txt") },
                    onOpenCrawlCenter = onOpenCrawlCenter,
                )

                task != null && task.state == CrawlState.CANCELLED -> TerminalTaskView(
                    task = task,
                    localBook = localBook,
                    isExporting = isExporting,
                    onExport = { exportLauncher.launch("${bookTitle.ifBlank { "book-$bookId" }}.txt") },
                    onRedownload = { confirmDownload = true },
                    onOpenCrawlCenter = onOpenCrawlCenter,
                )

                totalChapters <= 0 -> {
                    // 无目录：引导先更新目录（真实接 refreshCatalog，成功后本区随书架流自动重排）
                    Text(
                        text = "尚无目录，请先更新目录后再下载整本",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                when (val result = container.readingService.refreshCatalog(bookId)) {
                                    is AppResult.Failure -> actionError = result.error
                                    is AppResult.Success -> Unit
                                }
                            }
                        },
                    ) { Text("更新目录") }
                }

                else -> {
                    Button(onClick = { confirmDownload = true }) { Text("下载整本小说") }
                    Text(
                        text = "共 $totalChapters 章，逐章抓取并生成 TXT；期间可暂停/继续",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (actionError != null) {
                ErrorText(error = actionError!!)
                TextButton(onClick = { actionError = null }) { Text("知道了") }
            }
        }
    }

    if (confirmDownload) {
        ConfirmDialog(
            title = "下载整本小说",
            text = "书名：${bookTitle.ifEmpty { "（未识别书名）" }}\n" +
                "章节数：$totalChapters 章\n" +
                "来源：$sourceDomain\n\n" +
                "将逐章抓取全书并生成本地 TXT，可在采集中心暂停、继续或取消。",
            confirmText = "开始下载",
            destructive = false,
            onConfirm = {
                confirmDownload = false
                downloadWholeBook()
            },
            onDismiss = { confirmDownload = false },
        )
    }
}

/** 进行中态（WAITING/RUNNING/PAUSED）：进度条 + 成功/失败/总数 + 当前章 */
@Composable
private fun ActiveTaskView(
    task: CrawlTask,
    onCancel: () -> Unit,
    onResume: () -> Unit,
) {
    val total = task.totalChapters
    val done = task.succeededCount + task.failedCount
    LinearProgressIndicator(
        progress = { if (total > 0) done.toFloat() / total else 0f },
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = when (task.state) {
            CrawlState.WAITING -> "排队中 · 共 $total 章"
            CrawlState.RUNNING -> "下载中 · 成功 ${task.succeededCount} · 失败 ${task.failedCount} / 共 $total"
            else -> "已暂停 · 成功 ${task.succeededCount} · 失败 ${task.failedCount} / 共 $total"
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    task.currentChapterTitle?.takeIf { it.isNotBlank() }?.let { title ->
        Text(
            text = "当前：第${task.currentChapterIndex + 1}章 $title",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
        // K9：WAITING/RUNNING→CANCELLED 合法；PAUSED→CANCELLED 非法，暂停态只提供「继续」
        if (task.state == CrawlState.RUNNING || task.state == CrawlState.WAITING) {
            OutlinedButton(onClick = onCancel) { Text("取消下载") }
        }
        if (task.state == CrawlState.PAUSED) {
            Button(onClick = onResume) { Text("继续下载") }
        }
    }
}

/** COMPLETED / CANCELLED 终态：导出 TXT（SAF）+ 打开采集中心 + 再次下载 */
@Composable
private fun TerminalTaskView(
    task: CrawlTask,
    localBook: LocalBook?,
    isExporting: Boolean,
    onExport: () -> Unit,
    onRedownload: () -> Unit,
    onOpenCrawlCenter: () -> Unit,
) {
    val total = task.totalChapters
    Text(
        text = when (task.state) {
            CrawlState.COMPLETED ->
                "下载完成 · 成功 ${task.succeededCount} / 共 $total" +
                    if (task.failedCount > 0) "（失败 ${task.failedCount}）" else ""
            else -> "已取消 · 成功 ${task.succeededCount} / 共 $total"
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    TxtStateRow(localBook)
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
        OutlinedButton(onClick = onExport, enabled = !isExporting) { Text("导出 TXT") }
        OutlinedButton(onClick = onRedownload) { Text("重新下载") }
        TextButton(onClick = onOpenCrawlCenter) { Text("打开采集中心") }
    }
}

/** FAILED 终态：重试失败章（K10 FAILED→WAITING）+ 打开采集中心（Q3：不静默整本成功） */
@Composable
private fun FailedTaskView(
    task: CrawlTask,
    localBook: LocalBook?,
    isExporting: Boolean,
    onRetryFailures: () -> Unit,
    onExport: () -> Unit,
    onOpenCrawlCenter: () -> Unit,
) {
    Text(
        text = "下载中断 · 成功 ${task.succeededCount} · 失败 ${task.failedCount} / 共 ${task.totalChapters}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
    TxtStateRow(localBook)
    Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
        Button(onClick = onRetryFailures) { Text("重试失败章") }
        if (localBook != null) {
            OutlinedButton(onClick = onExport, enabled = !isExporting) { Text("导出 TXT") }
        }
        TextButton(onClick = onOpenCrawlCenter) { Text("打开采集中心") }
    }
}

@Composable
private fun TxtStateRow(localBook: LocalBook?) {
    Text(
        text = if (localBook != null) {
            "TXT 已生成（${formatFileSize(localBook.fileSize)}${if (localBook.isComplete) "" else "，含缺失章清单"}）"
        } else {
            "TXT 未生成"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576f)
    bytes >= 1 shl 10 -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}
