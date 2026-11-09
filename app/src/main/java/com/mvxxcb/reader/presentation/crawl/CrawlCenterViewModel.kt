package com.mvxxcb.reader.presentation.crawl

// WP-L（07 §6.2 / B3）：采集中心。列表 = observeTasks（updatedAt DESC，Room Flow）
// × observeShelf 按书名/域名联表（FK CASCADE 保证任务必有书；书缺席仅作防御性兜底）。
// 操作全部接 ReadingService K10 门面；「清除记录」K10 无对应方法，
// 走 CrawlTaskRepository.deleteTask（仓库层终态单点校验契约）。错误经 ErrorText 映射（02 §10）。

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.di.AppContainer
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.model.CrawlState
import com.mvxxcb.reader.domain.model.CrawlTask
import com.mvxxcb.reader.domain.repository.BookRepository
import com.mvxxcb.reader.domain.repository.CrawlTaskRepository
import com.mvxxcb.reader.domain.service.ReadingService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 终态判定（K9：COMPLETED/FAILED/CANCELLED；终态再入队=新建任务） */
val CrawlState.isTerminal: Boolean
    get() = this == CrawlState.COMPLETED || this == CrawlState.FAILED || this == CrawlState.CANCELLED

/** K9 操作可用性：`RUNNING→PAUSED`；`PAUSED→RUNNING`；`WAITING/RUNNING→CANCELLED`（PAUSED→CANCELLED 非法）；`FAILED→WAITING` */
data class CrawlTaskItem(
    val task: CrawlTask,
    val bookTitle: String,
    val bookDomain: String,
    val isActive: Boolean,          // 非终态（WAITING/RUNNING/PAUSED）
    val canPause: Boolean,          // 仅 RUNNING
    val canResume: Boolean,         // 仅 PAUSED
    val canCancel: Boolean,         // WAITING/RUNNING
    val canRetryFailures: Boolean,  // 仅 FAILED
    val canDelete: Boolean,         // 仅终态
) {
    val state: CrawlState get() = task.state
}

data class CrawlCenterUiState(
    val isLoading: Boolean = true,
    val items: List<CrawlTaskItem> = emptyList(),
    val error: ReaderError? = null,        // 列表流错误（ErrorBanner + 重试重连）
    val actionError: ReaderError? = null,  // 操作错误（ErrorText 行内展示）
)

sealed interface CrawlCenterEvent {
    /** 「打开小说」→ 跳书详情（AppNavHost 接线 Routes.bookDetail） */
    data class OpenBook(val bookId: Long) : CrawlCenterEvent
}

class CrawlCenterViewModel(
    private val crawlTaskRepository: CrawlTaskRepository,
    private val bookRepository: BookRepository,
    private val readingService: ReadingService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CrawlCenterUiState())
    val uiState: StateFlow<CrawlCenterUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<CrawlCenterEvent>()
    val events: SharedFlow<CrawlCenterEvent> = _events.asSharedFlow()

    private var observeJob: Job? = null

    init {
        refresh()
    }

    /** 重新订阅任务流（Room Flow 自动更新；refresh 用于错误后手动重连） */
    fun refresh() {
        observeJob?.cancel()
        _uiState.update { it.copy(isLoading = it.items.isEmpty(), error = null) }
        observeJob = viewModelScope.launch {
            combine(
                crawlTaskRepository.observeTasks(),
                bookRepository.observeShelf(),
            ) { tasks, books -> tasks to books }
                .catch { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = ReaderError.Unknown(cause = e))
                    }
                }
                .collect { (tasks, books) ->
                    val bookById = books.associateBy { it.id }
                    val items = tasks.map { task -> task.toItem(bookById[task.bookId]) }
                    _uiState.update { it.copy(isLoading = false, items = items, error = null) }
                }
        }
    }

    // ———— 操作（全部转发 K10 / 仓库终态删除契约；失败写入 actionError 经 ErrorText 展示） ————

    fun pause(taskId: Long) = perform { readingService.pauseCrawl(taskId) }

    fun resume(taskId: Long) = perform { readingService.resumeCrawl(taskId) }

    fun cancel(taskId: Long) = perform { readingService.cancelCrawl(taskId) }

    fun retryFailures(taskId: Long) = perform { readingService.retryCrawlFailures(taskId) }

    /** 清除记录（仅终态；非终态返回 State(INVALID_TASK_STATE) → actionError） */
    fun deleteRecord(taskId: Long) = perform { crawlTaskRepository.deleteTask(taskId) }

    fun openBook(bookId: Long) {
        viewModelScope.launch { _events.emit(CrawlCenterEvent.OpenBook(bookId)) }
    }

    fun clearActionError() {
        _uiState.update { it.copy(actionError = null) }
    }

    private fun perform(block: suspend () -> AppResult<Unit>) {
        viewModelScope.launch {
            when (val result = block()) {
                is AppResult.Success -> Unit
                is AppResult.Failure ->
                    _uiState.update { it.copy(actionError = result.error) }
            }
        }
    }

    // ———— 内部 ————

    /** 任务 → 列表项；书缺席（理论不可达，FK CASCADE）按「未知书籍」兜底展示 */
    private fun CrawlTask.toItem(book: com.mvxxcb.reader.domain.model.Book?): CrawlTaskItem {
        val active = !state.isTerminal
        return CrawlTaskItem(
            task = this,
            bookTitle = book?.title.orEmpty()
                .ifEmpty { if (book == null) "未知书籍 #$bookId" else "（未识别书名）" },
            bookDomain = book?.sourceDomain.orEmpty(),
            isActive = active,
            canPause = state == CrawlState.RUNNING,
            canResume = state == CrawlState.PAUSED,
            canCancel = state == CrawlState.WAITING || state == CrawlState.RUNNING,
            canRetryFailures = state == CrawlState.FAILED,
            canDelete = !active,
        )
    }

    companion object {
        /** 手动 DI 创建入口（契约 02 §0.6） */
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CrawlCenterViewModel(
                        container.crawlTaskRepository,
                        container.bookRepository,
                        container.readingService,
                    ) as T
            }
    }
}
