package com.mvxxcb.reader.presentation.catalog

// 实现归属 WP-U（04 §4）。契约依据：02 §8.5。
// 目录：observeChapterMetas 全量流 + 本地 200 条窗口（loadMore），正倒序/搜索在流结果上计算；
// 定位当前章由 progressRepository 驱动；refreshCatalog 走 ReadingService 合并接口。

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mvxxcb.reader.di.AppContainer
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.model.CatalogMergeReport
import com.mvxxcb.reader.domain.model.ChapterMeta
import com.mvxxcb.reader.domain.repository.BookRepository
import com.mvxxcb.reader.domain.repository.ChapterRepository
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

enum class CatalogSort { ASC, DESC }

data class CatalogUiState(
    val isLoading: Boolean = true,
    val bookId: Long = 0L,
    val bookTitle: String = "",
    val chapters: List<ChapterMeta> = emptyList(),  // 分页加载的窗口
    val totalCount: Int = 0,
    val sort: CatalogSort = CatalogSort.ASC,
    val searchQuery: String = "",
    val searchResults: List<ChapterMeta>? = null,   // null = 未搜索
    val currentChapterId: Long? = null,          // 定位当前章节
    val lastCatalogUpdatedAt: Long? = null,
    val isRefreshing: Boolean = false,
    val requiresReview: Boolean = false,          // 上次合并 matchedRatio < 0.6
    val error: ReaderError? = null,
)

sealed interface CatalogEvent {
    data class NavigateToReader(val bookId: Long, val chapterIndex: Int) : CatalogEvent
}

class CatalogViewModel(
    private val bookId: Long,
    private val chapterRepository: ChapterRepository,
    private val bookRepository: BookRepository,
    private val progressRepository: ReadingProgressRepository,
    private val readingService: ReadingService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CatalogUiState(bookId = bookId))
    val uiState: StateFlow<CatalogUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<CatalogEvent>()
    val events: SharedFlow<CatalogEvent> = _events.asSharedFlow()

    /** 已加载窗口条数（每页 200，01 §8 性能预算） */
    private var windowSize = WINDOW_PAGE_SIZE

    /** 全量章节（排序前，按 chapterIndex 规范顺序） */
    private var allChapters: List<ChapterMeta> = emptyList()

    init {
        observeChapters()
        observeCurrentChapter()
        loadBookTitle()
    }

    /** 每页 200 条窗口加载 */
    fun loadMore() {
        if (windowSize >= allChapters.size && _uiState.value.searchResults == null) return
        windowSize += WINDOW_PAGE_SIZE
        applyWindow()
    }

    fun toggleSort() {
        _uiState.update {
            it.copy(sort = if (it.sort == CatalogSort.ASC) CatalogSort.DESC else CatalogSort.ASC)
        }
        windowSize = WINDOW_PAGE_SIZE
        applyWindow()
    }

    fun onSearchChange(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            clearSearch()
            return
        }
        val results = allChapters.filter { it.title.contains(trimmed, ignoreCase = true) }
        _uiState.update { it.copy(searchQuery = query, searchResults = results) }
    }

    fun clearSearch() {
        _uiState.update { it.copy(searchQuery = "", searchResults = null) }
        windowSize = WINDOW_PAGE_SIZE
        applyWindow()
    }

    /** ReadingService.refreshCatalog（完成后如 requiresReview 展示确认） */
    fun refreshCatalog() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            val result = readingService.refreshCatalog(bookId)
            result.onSuccess { report: CatalogMergeReport ->
                _uiState.update {
                    it.copy(isRefreshing = false, requiresReview = report.requiresReview)
                }
                if (!report.requiresReview && report.detectedReversal) {
                    // 检测到倒序目录已自动纠正：章节流会刷新，无需额外处理
                }
            }.onFailure { error ->
                _uiState.update { it.copy(isRefreshing = false, error = error) }
            }
        }
    }

    fun confirmReview() {
        _uiState.update { it.copy(requiresReview = false) }
    }

    /** 点击章节进入阅读 */
    fun openChapter(chapterIndex: Int) {
        viewModelScope.launch {
            _events.emit(CatalogEvent.NavigateToReader(bookId, chapterIndex))
        }
    }

    // ———— 内部 ————

    private fun observeChapters() {
        viewModelScope.launch {
            chapterRepository.observeChapterMetas(bookId)
                .catch { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = ReaderError.Unknown(cause = e))
                    }
                }
                .collect { metas ->
                    allChapters = metas
                    val lastUpdated = metas.mapNotNull { it.fetchedAt }.maxOrNull()
                    _uiState.update {
                        it.copy(isLoading = false, totalCount = metas.size, lastCatalogUpdatedAt = lastUpdated)
                    }
                    applyWindow()
                }
        }
    }

    private fun observeCurrentChapter() {
        viewModelScope.launch {
            progressRepository.observeProgress(bookId).collect { progress ->
                _uiState.update { it.copy(currentChapterId = progress?.chapterId) }
            }
        }
    }

    private fun loadBookTitle() {
        viewModelScope.launch {
            val book = bookRepository.getBook(bookId)
            if (book != null) {
                _uiState.update { it.copy(bookTitle = book.title) }
            }
        }
    }

    /** 依 sort/窗口重建 chapters 列表 */
    private fun applyWindow() {
        val state = _uiState.value
        if (state.searchResults != null) {
            // 搜索态：直接展示结果（不做窗口，搜索结果通常有限）
            return
        }
        val ordered = when (state.sort) {
            CatalogSort.ASC -> allChapters
            CatalogSort.DESC -> allChapters.asReversed()
        }
        val window = if (ordered.size <= windowSize) ordered else ordered.take(windowSize)
        _uiState.update { it.copy(chapters = window) }
    }

    companion object {
        /** 万章级目录分页窗口（01 §8：每页 200 条） */
        const val WINDOW_PAGE_SIZE = 200

        /** 手动 DI 创建入口（契约 02 §0.6） */
        fun factory(container: AppContainer, bookId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CatalogViewModel(
                        bookId,
                        container.chapterRepository,
                        container.bookRepository,
                        container.readingProgressRepository,
                        container.readingService,
                    ) as T
            }
    }
}
