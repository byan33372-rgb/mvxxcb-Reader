package com.mvxxcb.reader.presentation.bookdetail

// 实现归属 WP-U（04 §4）。契约依据：02 §8.4。
// 数据来源：observeShelf 流内定位本 id（书架书，实时更新）；临时书不在书架流内，
// 用 getBook 一次性快照。进度经 observeProgress 驱动「继续阅读」。

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mvxxcb.reader.di.AppContainer
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StateFailure
import com.mvxxcb.reader.domain.model.Book
import com.mvxxcb.reader.domain.repository.BookRepository
import com.mvxxcb.reader.domain.repository.ReadingProgressRepository
import com.mvxxcb.reader.domain.service.ReadingService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BookDetailUiState(
    val isLoading: Boolean = true,
    val book: Book? = null,
    val isRefreshing: Boolean = false,
    val isTemporary: Boolean = false,
    val error: ReaderError? = null,
)

sealed interface BookDetailEvent {
    data class NavigateToCatalog(val bookId: Long, val focusChapterId: Long?) : BookDetailEvent
    data class NavigateToReader(val bookId: Long, val chapterIndex: Int) : BookDetailEvent
}

class BookDetailViewModel(
    private val bookId: Long,
    private val bookRepository: BookRepository,
    private val progressRepository: ReadingProgressRepository,
    private val readingService: ReadingService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BookDetailUiState())
    val uiState: StateFlow<BookDetailUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<BookDetailEvent>()
    val events: SharedFlow<BookDetailEvent> = _events.asSharedFlow()

    /** 继续阅读章节（进度章索引；null = 从未阅读） */
    private val _progressChapterIndex = MutableStateFlow<Int?>(null)
    val progressChapterIndex: StateFlow<Int?> = _progressChapterIndex.asStateFlow()

    /** 已删除标记：Screen 收到后返回上级 */
    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    init {
        observeBook()
        observeProgress()
    }

    /** 有进度 → 进度章；否则第一章；无目录提示 */
    fun startReading() {
        val book = _uiState.value.book
        if (book == null) {
            _uiState.update { it.copy(error = ReaderError.State(StateFailure.BOOK_NOT_FOUND)) }
            return
        }
        if (book.totalChapters <= 0) {
            // 无目录：提示（INVALID_ARGUMENT 语义桶；QA 断言类型而非文案，契约 02 §11.6）
            _uiState.update { it.copy(error = ReaderError.State(StateFailure.INVALID_ARGUMENT)) }
            return
        }
        viewModelScope.launch {
            val chapterIndex = _progressChapterIndex.value ?: 0
            _events.emit(BookDetailEvent.NavigateToReader(bookId, chapterIndex))
        }
    }

    fun openCatalog() {
        val book = _uiState.value.book ?: return
        val focus = _progressChapterIndex.value
        viewModelScope.launch {
            _events.emit(BookDetailEvent.NavigateToCatalog(bookId, book.currentChapterId))
        }
    }

    /** 重新解析详情（非空字段才覆盖） */
    fun refreshDetail() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            when (val result = readingService.refreshBookDetail(bookId)) {
                is com.mvxxcb.reader.common.result.AppResult.Success -> {
                    // 书架流自动更新；临时书需重新快照
                    if (_uiState.value.book == null || _uiState.value.isTemporary) {
                        snapshotBook()
                    }
                    _uiState.update { it.copy(isRefreshing = false) }
                }
                is com.mvxxcb.reader.common.result.AppResult.Failure -> {
                    _uiState.update { it.copy(isRefreshing = false, error = result.error) }
                }
            }
        }
    }

    fun removeBook() {
        viewModelScope.launch {
            bookRepository.deleteBook(bookId)
                .onSuccess { _deleted.value = true }
                .onFailure { error -> _uiState.update { it.copy(error = error) } }
        }
    }

    fun consumeError() {
        _uiState.update { it.copy(error = null) }
    }

    // ———— 内部 ————

    private fun observeBook() {
        viewModelScope.launch {
            bookRepository.observeShelf()
                .catch { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = ReaderError.Unknown(cause = e))
                    }
                }
                .collect { shelf ->
                    val found = shelf.find { it.id == bookId }
                    if (found != null) {
                        _uiState.update {
                            it.copy(isLoading = false, book = found, isTemporary = false)
                        }
                    } else if (_uiState.value.book == null) {
                        // 书架流未含（临时书或不存在）：一次性快照
                        snapshotBook()
                    }
                }
        }
    }

    private suspend fun snapshotBook() {
        val book = bookRepository.getBook(bookId)
        if (book != null) {
            _uiState.update {
                it.copy(isLoading = false, book = book, isTemporary = book.isTemporary)
            }
        } else {
            _uiState.update {
                it.copy(isLoading = false, error = ReaderError.State(StateFailure.BOOK_NOT_FOUND))
            }
        }
    }

    private fun observeProgress() {
        viewModelScope.launch {
            progressRepository.observeProgress(bookId).collect { progress ->
                _progressChapterIndex.value = progress?.chapterIndex
            }
        }
    }

    companion object {
        /** 手动 DI 创建入口（契约 02 §0.6） */
        fun factory(container: AppContainer, bookId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    BookDetailViewModel(
                        bookId,
                        container.bookRepository,
                        container.readingProgressRepository,
                        container.readingService,
                    ) as T
            }
    }
}
