package com.mvxxcb.reader.presentation.reader

// 实现归属 WP-U（04 §4）。契约依据：02 §8.6。
// 核心：章节加载（缓存优先）→ TextPager 后台分页 → 按模式恢复位置（inChapterFraction 映射）；
// 滚动/翻页进度 500ms 防抖保存（01 §5.6）；临近章尾预取（01 §5.7，静默失败）；
// ON_STOP 强制保存（Screen 必须调用 forceSaveProgress）。
//
// 契约补充 API（不改变契约成员，为装配所需的新增接口，见汇报偏差项）：
//   - onViewportChanged(widthPx, heightPx, density, fontScale)：Screen 首帧布局后上报视口，
//     分页必须在已知视口后进行（契约未提供等价入口）。
//   - bookmarks: StateFlow<List<Bookmark>>：工具栏简版书签列表展示（主 Agent 裁定 2）。
// 构造注记：契约构造器无 BookRepository / TimeProvider。追加可选参数 bookRepository（默认 null，
// 仅用于取书名展示）；时间戳一期用 System.currentTimeMillis()，均不影响契约调用形态。

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mvxxcb.reader.common.coroutine.DispatcherProvider
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.di.AppContainer
import com.mvxxcb.reader.domain.cache.ContentCacheManager
import com.mvxxcb.reader.domain.error.NetworkFailure
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StateFailure
import com.mvxxcb.reader.domain.model.Bookmark
import com.mvxxcb.reader.domain.model.ReaderContent
import com.mvxxcb.reader.domain.model.ReaderSettings
import com.mvxxcb.reader.domain.model.ReadingMode
import com.mvxxcb.reader.domain.model.ReadingProgress
import com.mvxxcb.reader.domain.repository.BookRepository
import com.mvxxcb.reader.domain.repository.BookmarkRepository
import com.mvxxcb.reader.domain.repository.ChapterRepository
import com.mvxxcb.reader.domain.repository.ReaderSettingsRepository
import com.mvxxcb.reader.domain.repository.ReadingProgressRepository
import com.mvxxcb.reader.domain.service.ReadingService
import com.mvxxcb.reader.reader.engine.PaginationResult
import com.mvxxcb.reader.reader.engine.ReaderLayoutSpec
import com.mvxxcb.reader.reader.engine.ReaderPositionMappers
import com.mvxxcb.reader.reader.engine.ReaderTypography
import com.mvxxcb.reader.reader.engine.TextPager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ReaderContentState {
    data object Loading : ReaderContentState
    data class Ready(
        val content: ReaderContent,
        val fromCache: Boolean,
    ) : ReaderContentState
    data class Failed(
        val error: ReaderError,
        val canRetry: Boolean,
        val cachedAvailable: Boolean,        // 断网时如有旧缓存可切换
    ) : ReaderContentState
}

data class ReaderUiState(
    val bookId: Long = 0L,
    val chapterIndex: Int = 0,
    val bookTitle: String = "",
    val chapterTitle: String = "",
    val contentState: ReaderContentState = ReaderContentState.Loading,
    val settings: ReaderSettings,
    val toolbarVisible: Boolean = false,
    val scrollFraction: Double = 0.0,        // 滚动模式实时章内位置
    val currentPage: Int = 0,                // 翻页模式
    val pageCount: Int = 0,
    val inChapterFraction: Double = 0.0,     // 统一（翻页模式由页码换算）
    val overallProgress: Double = 0.0,       // 0..100
    val hasPrevious: Boolean = true,
    val hasNext: Boolean = true,
    val isPrefetchingNext: Boolean = false,
    val savingProgressHint: Boolean = false, // 自动保存的弱反馈
)

sealed interface ReaderEvent {
    data class BookmarkAdded(val bookmarkId: Long) : ReaderEvent
    data class ShowMessage(val error: ReaderError?) : ReaderEvent   // 弱提示
}

class ReaderViewModel(
    private val bookId: Long,
    initialChapterIndex: Int,
    private val readingService: ReadingService,
    private val settingsRepository: ReaderSettingsRepository,
    private val progressRepository: ReadingProgressRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val chapterRepository: ChapterRepository,
    private val cacheManager: ContentCacheManager,
    private val textPager: TextPager,
    private val dispatchers: DispatcherProvider,
    private val bookRepository: BookRepository? = null, // 契约外补充：书名展示
) : ViewModel() {

    private val mapper = ReaderPositionMappers.DEFAULT

    private val _uiState = MutableStateFlow(
        ReaderUiState(
            bookId = bookId,
            chapterIndex = initialChapterIndex.coerceAtLeast(0),
            settings = ReaderSettings(),
        ),
    )
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ReaderEvent>()
    val events: SharedFlow<ReaderEvent> = _events.asSharedFlow()

    /** 当前内容分页结果（Ready 后计算，layoutVersion 变化时重算并恢复位置） */
    private val _pagination = MutableStateFlow<PaginationResult?>(null)
    val pagination: StateFlow<PaginationResult?> = _pagination.asStateFlow()

    /** 工具栏简版书签列表（主 Agent 裁定 2） */
    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    private var totalChapters = 0
    private var currentChapterId: Long? = null

    /** 下一次分页完成时要恢复的章内分数（章节加载 = 存档进度；设置变更 = 当前位置） */
    private var pendingRestoreFraction: Double? = null

    /** 视口（Screen 首帧上报；density/fontScale 用于 sp/dp → px 换算） */
    private var viewport: Viewport? = null

    private var loadJob: Job? = null
    private var paginateJob: Job? = null
    private var saveDebounceJob: Job? = null

    /** 是否已触发本章预取 */
    private var prefetchTriggered = false

    private data class Viewport(
        val widthPx: Int,
        val heightPx: Int,
        val density: Float,
        val fontScale: Float,
    )

    init {
        observeSettings()
        observeChapterCount()
        loadBookTitle()
        observeBookmarks()
        loadChapter(preferCache = true, restoreFromProgress = true)
    }

    // ———— 契约方法 ————

    fun toggleToolbar() {
        _uiState.update { it.copy(toolbarVisible = !it.toolbarVisible) }
    }

    fun hideToolbar() {
        _uiState.update { it.copy(toolbarVisible = false) }
    }

    /** 滚动模式（Screen 节流后回调，VM 内再防抖存进度） */
    fun onScrolled(fraction: Double) {
        val f = fraction.coerceIn(0.0, 1.0)
        _uiState.update {
            it.copy(
                scrollFraction = f,
                inChapterFraction = f,
                overallProgress = overallProgress(f),
            )
        }
        maybePrefetch(scrollFraction = f)
        scheduleProgressSave()
    }

    /** 翻页模式 */
    fun onPageChanged(pageIndex: Int) {
        val pageCount = _pagination.value?.pages?.size ?: return
        if (pageCount <= 0) return
        if (pageIndex !in 0 until pageCount) return
        val fraction = mapper.pageToFraction(pageIndex, pageCount)
        _uiState.update {
            it.copy(
                currentPage = pageIndex,
                scrollFraction = fraction,
                inChapterFraction = fraction,
                overallProgress = overallProgress(fraction),
            )
        }
        maybePrefetch(pageIndex = pageIndex, pageCount = pageCount)
        scheduleProgressSave()
    }

    fun goToPreviousChapter() {
        val index = _uiState.value.chapterIndex
        if (index <= 0) return
        switchChapter(index - 1)
    }

    fun goToNextChapter() {
        val index = _uiState.value.chapterIndex
        if (totalChapters in 1..index + 1) return
        switchChapter(index + 1)
    }

    fun jumpToChapter(chapterIndex: Int) {
        if (chapterIndex < 0) return
        if (totalChapters > 0 && chapterIndex >= totalChapters) return
        if (chapterIndex == _uiState.value.chapterIndex) return
        switchChapter(chapterIndex)
    }

    fun retryLoad() {
        loadChapter(preferCache = true, restoreFromProgress = true)
    }

    /** Failed 且 cachedAvailable 时：切换到本地缓存版本 */
    fun loadCachedVersion() {
        val state = _uiState.value
        if (state.contentState !is ReaderContentState.Failed) return
        viewModelScope.launch {
            val chapter = runCatching { chapterRepository.getChapterByIndex(bookId, state.chapterIndex) }
                .getOrNull()
            val cached = chapter?.let { runCatching { chapterRepository.loadContent(it.id) }.getOrNull() }
            if (chapter == null || cached == null) {
                _events.emit(
                    ReaderEvent.ShowMessage(ReaderError.State(StateFailure.CHAPTER_NOT_CACHED)),
                )
                return@launch
            }
            currentChapterId = chapter.id
            pendingRestoreFraction = 0.5
            _uiState.update {
                it.copy(
                    contentState = ReaderContentState.Ready(cached, fromCache = true),
                    chapterTitle = cached.title,
                )
            }
            repaginate()
        }
    }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch {
            settingsRepository.update(transform)
        }
    }

    fun addBookmark(selectedText: String?) {
        val state = _uiState.value
        if (state.contentState !is ReaderContentState.Ready) return
        val chapterId = currentChapterId ?: return
        viewModelScope.launch {
            val bookmark = Bookmark(
                id = 0,
                bookId = bookId,
                chapterId = chapterId,
                chapterIndex = state.chapterIndex,
                scrollPosition = if (state.settings.readingMode == ReadingMode.SCROLL) {
                    state.scrollFraction
                } else {
                    null
                },
                pageIndex = if (state.settings.readingMode == ReadingMode.PAGE_SIMULATED) {
                    state.currentPage
                } else {
                    null
                },
                inChapterFraction = state.inChapterFraction,
                selectedText = selectedText,
                createdAt = System.currentTimeMillis(), // 契约构造器无 TimeProvider，见文件头注记
            )
            when (val result = bookmarkRepository.addBookmark(bookmark)) {
                is AppResult.Success -> _events.emit(ReaderEvent.BookmarkAdded(result.value))
                is AppResult.Failure -> _events.emit(ReaderEvent.ShowMessage(result.error))
            }
        }
    }

    fun removeBookmark(bookmarkId: Long) {
        viewModelScope.launch {
            val result = bookmarkRepository.removeBookmark(bookmarkId)
            if (result is AppResult.Failure) {
                _events.emit(ReaderEvent.ShowMessage(result.error))
            }
        }
    }

    /** ON_STOP 时 Screen 必须调用：立即强制保存进度 */
    fun forceSaveProgress() {
        saveDebounceJob?.cancel()
        saveProgressNow()
    }

    // ———— 视口与分页（契约补充内部 API） ————

    /** Screen 首帧/尺寸变化后上报（契约未提供等价入口，属必要补充） */
    fun onViewportChanged(widthPx: Int, heightPx: Int, density: Float, fontScale: Float) {
        if (widthPx <= 0 || heightPx <= 0 || density <= 0f) return
        val next = Viewport(widthPx, heightPx, density, fontScale)
        if (viewport == next) return
        viewport = next
        repaginate()
    }

    // ———— 初始化观察 ————

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.settings
                .collect { settings ->
                    val previous = _uiState.value.settings
                    _uiState.update { it.copy(settings = settings) }
                    if (settings != previous) {
                        onSettingsChanged()
                    }
                }
        }
    }

    private fun observeChapterCount() {
        viewModelScope.launch {
            chapterRepository.observeChapterMetas(bookId).collect { metas ->
                val count = metas.size
                if (count != totalChapters) {
                    totalChapters = count
                    _uiState.update { st ->
                        st.copy(
                            hasNext = totalChapters <= 0 || st.chapterIndex < totalChapters - 1,
                            overallProgress = mapper.overallProgress(
                                st.chapterIndex, totalChapters, st.inChapterFraction,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun loadBookTitle() {
        val repo = bookRepository ?: return
        viewModelScope.launch {
            val book = runCatching { repo.getBook(bookId) }.getOrNull()
            if (book != null) {
                _uiState.update { it.copy(bookTitle = book.title) }
            }
        }
    }

    private fun observeBookmarks() {
        viewModelScope.launch {
            bookmarkRepository.observeBookmarks(bookId).collect { list ->
                _bookmarks.value = list
            }
        }
    }

    // ———— 章节加载 ————

    private fun loadChapter(preferCache: Boolean, restoreFromProgress: Boolean) {
        loadJob?.cancel()
        prefetchTriggered = false
        _uiState.update {
            it.copy(contentState = ReaderContentState.Loading, isPrefetchingNext = false)
        }
        loadJob = viewModelScope.launch {
            val chapterIndex = _uiState.value.chapterIndex
            val meta = runCatching { chapterRepository.getChapterByIndex(bookId, chapterIndex) }
                .getOrNull()
            val cachedAvailable = meta?.isCached == true
            when (val result = readingService.loadChapter(bookId, chapterIndex, preferCache)) {
                is AppResult.Success -> {
                    val content = result.value
                    currentChapterId = content.chapterId
                    if (restoreFromProgress) {
                        pendingRestoreFraction = loadSavedFraction(content.chapterId)
                    }
                    _uiState.update {
                        it.copy(
                            contentState = ReaderContentState.Ready(
                                content,
                                fromCache = cachedAvailable && preferCache,
                            ),
                            chapterTitle = content.title,
                        )
                    }
                    repaginate()
                }
                is AppResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            contentState = ReaderContentState.Failed(
                                error = result.error,
                                canRetry = isRetryable(result.error),
                                cachedAvailable = cachedAvailable,
                            ),
                            // 防御性兜底：任何进入 Failed 的路径都保证导航标志与当前章一致
                            //（canRetry 语义独立于章节切换，错误态下导航必须可用）。
                            hasPrevious = it.chapterIndex > 0,
                            hasNext = totalChapters <= 0 || it.chapterIndex < totalChapters - 1,
                        )
                    }
                }
            }
        }
    }

    private suspend fun loadSavedFraction(chapterId: Long): Double {
        val progress = runCatching { progressRepository.getProgress(bookId) }.getOrNull()
        return if (progress != null && progress.chapterId == chapterId) {
            progress.inChapterFraction.coerceIn(0.0, 1.0)
        } else {
            0.0
        }
    }

    private fun switchChapter(newIndex: Int) {
        saveProgressNow() // 切章前强制保存当前章进度（01 §5.6）
        _uiState.update {
            it.copy(
                chapterIndex = newIndex,
                currentPage = 0,
                scrollFraction = 0.0,
                pageCount = 0,
                // 章节导航标志随 chapterIndex 即时刷新：Failed 态（无分页成功）下也必须可导航。
                // 此前仅在 updatePosition（分页成功后）重算，连续加载失败时残留旧值导致
                // 「上一章/下一章」按钮 enabled 错误禁用（阶段 7 P2 缺陷）。
                hasPrevious = newIndex > 0,
                hasNext = totalChapters <= 0 || newIndex < totalChapters - 1,
            )
        }
        loadChapter(preferCache = true, restoreFromProgress = false)
    }

    // ———— 分页与位置恢复（契约 §8.6 注） ————

    private fun repaginate() {
        val state = _uiState.value
        val ready = state.contentState as? ReaderContentState.Ready ?: return
        val vp = viewport ?: return
        val spec = buildSpec(state.settings, vp)
        paginateJob?.cancel()
        paginateJob = viewModelScope.launch(dispatchers.default) {
            val result = textPager.paginate(ready.content, spec)
            _pagination.value = result
            applyPagination(result)
        }
    }

    /** 排版规格：sp/dp → px 由调用方按 density+fontScale 换算（契约 02 §9 注） */
    private fun buildSpec(settings: ReaderSettings, vp: Viewport): ReaderLayoutSpec = ReaderLayoutSpec(
        typography = ReaderTypography(
            fontFamily = settings.fontFamily,
            fontSizeSp = settings.fontSizeSp * vp.fontScale * vp.density,   // sp → px
            lineHeightMultiplier = settings.lineHeightMultiplier,
            paragraphSpacingDp = settings.paragraphSpacingDp * vp.density,  // dp → px
            bold = settings.boldText,
            horizontalPaddingDp = (settings.horizontalPaddingDp * vp.density).toInt(), // px（指纹用）
        ),
        viewportWidthPx = vp.widthPx,
        viewportHeightPx = vp.heightPx,
        fontScale = vp.fontScale,
    )

    /** 分页完成后：恢复位置或按当前 inChapterFraction 重映射（契约 §8.6 注） */
    private fun applyPagination(result: PaginationResult) {
        val pageCount = result.pages.size
        val settings = _uiState.value.settings
        val restore = pendingRestoreFraction
        if (restore != null) {
            pendingRestoreFraction = null
        }
        val targetFraction = restore ?: _uiState.value.inChapterFraction
        when (settings.readingMode) {
            ReadingMode.PAGE_SIMULATED -> {
                val page = mapper.fractionToPage(targetFraction, pageCount)
                updatePosition(pageIndex = page, pageCount = pageCount)
            }
            ReadingMode.SCROLL -> {
                updatePosition(scrollFraction = targetFraction, pageCount = pageCount)
            }
        }
    }

    private fun updatePosition(
        pageIndex: Int? = null,
        scrollFraction: Double? = null,
        pageCount: Int,
    ) {
        val safePageCount = pageCount.coerceAtLeast(0)
        val fraction = when {
            pageIndex != null -> mapper.pageToFraction(pageIndex, safePageCount)
            scrollFraction != null -> scrollFraction.coerceIn(0.0, 1.0)
            else -> 0.0
        }
        _uiState.update { st ->
            st.copy(
                pageCount = safePageCount,
                currentPage = pageIndex ?: st.currentPage.coerceIn(0, (safePageCount - 1).coerceAtLeast(0)),
                scrollFraction = scrollFraction
                    ?: mapper.pageToFraction(st.currentPage, safePageCount),
                inChapterFraction = fraction,
                overallProgress = overallProgress(fraction),
                hasPrevious = st.chapterIndex > 0,
                hasNext = totalChapters <= 0 || st.chapterIndex < totalChapters - 1,
            )
        }
    }

    private fun onSettingsChanged() {
        // 契约 §8.6 注：设置变化 → 依当前 inChapterFraction 重算排版后映射回新位置
        pendingRestoreFraction = _uiState.value.inChapterFraction
        repaginate()
    }

    // ———— 进度保存（01 §5.6：防抖 500ms，fire-and-forget + 失败重试一次） ————

    private fun scheduleProgressSave() {
        if (!_uiState.value.settings.autoSaveProgress) return
        saveDebounceJob?.cancel()
        saveDebounceJob = viewModelScope.launch {
            delay(PROGRESS_SAVE_DEBOUNCE_MS)
            saveProgressNow()
        }
    }

    private fun saveProgressNow() {
        val state = _uiState.value
        if (state.contentState !is ReaderContentState.Ready) return
        val chapterId = currentChapterId ?: return
        _uiState.update { it.copy(savingProgressHint = true) }
        viewModelScope.launch {
            val progress = ReadingProgress(
                bookId = bookId,
                chapterId = chapterId,
                chapterIndex = state.chapterIndex,
                scrollPosition = if (state.settings.readingMode == ReadingMode.SCROLL) {
                    state.scrollFraction
                } else {
                    null
                },
                pageIndex = if (state.settings.readingMode == ReadingMode.PAGE_SIMULATED) {
                    state.currentPage
                } else {
                    null
                },
                inChapterFraction = state.inChapterFraction,
                progressPercentage = overallProgress(state.inChapterFraction),
                updatedAt = System.currentTimeMillis(), // 契约构造器无 TimeProvider，见文件头注记
            )
            val first = runCatching { progressRepository.saveProgress(progress) }.getOrNull()
            if (first is AppResult.Failure) {
                // 失败重试一次（01 §5.6）
                runCatching { delay(SAVE_RETRY_DELAY_MS) }
                val second = runCatching { progressRepository.saveProgress(progress) }.getOrNull()
                if (second is AppResult.Failure) {
                    _events.tryEmit(ReaderEvent.ShowMessage(second.error))
                }
            }
            _uiState.update { it.copy(savingProgressHint = false) }
        }
    }

    // ———— 预取（01 §5.7：滚动 >80% 或倒数第 2 页触发；静默失败） ————

    private fun maybePrefetch(
        scrollFraction: Double? = null,
        pageIndex: Int? = null,
        pageCount: Int = Int.MAX_VALUE,
    ) {
        if (!_uiState.value.settings.autoPrefetchNext) return
        if (prefetchTriggered) return
        val nearEnd = when {
            scrollFraction != null -> scrollFraction > PREFETCH_SCROLL_THRESHOLD
            pageIndex != null -> pageCount > 0 && pageIndex >= pageCount - 2
            else -> false
        }
        if (!nearEnd) return
        prefetchTriggered = true
        val nextIndex = _uiState.value.chapterIndex + 1
        if (totalChapters in 1..nextIndex) return
        _uiState.update { it.copy(isPrefetchingNext = true) }
        viewModelScope.launch {
            runCatching {
                cacheManager.prefetchNeighborhood(bookId, nextIndex, radius = 1)
            }.onFailure {
                // 静默失败（01 §5.7）：不打扰当前阅读；日志由实现层记录
            }
            _uiState.update { it.copy(isPrefetchingNext = false) }
        }
    }

    private fun overallProgress(inChapterFraction: Double): Double =
        mapper.overallProgress(_uiState.value.chapterIndex, totalChapters, inChapterFraction)

    private fun isRetryable(error: ReaderError): Boolean = when (error) {
        is ReaderError.Network -> error.failure != NetworkFailure.CANCELLED
        is ReaderError.Security -> false
        is ReaderError.State -> error.failure == StateFailure.CHAPTER_NOT_CACHED ||
            error.failure == StateFailure.CHAPTER_NOT_FOUND
        else -> true
    }

    companion object {
        private const val PROGRESS_SAVE_DEBOUNCE_MS = 500L
        private const val SAVE_RETRY_DELAY_MS = 200L
        private const val PREFETCH_SCROLL_THRESHOLD = 0.8

        /** 手动 DI 创建入口（契约 02 §0.6） */
        fun factory(
            container: AppContainer,
            bookId: Long,
            initialChapterIndex: Int,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ReaderViewModel(
                        bookId = bookId,
                        initialChapterIndex = initialChapterIndex,
                        readingService = container.readingService,
                        settingsRepository = container.readerSettingsRepository,
                        progressRepository = container.readingProgressRepository,
                        bookmarkRepository = container.bookmarkRepository,
                        chapterRepository = container.chapterRepository,
                        cacheManager = container.contentCacheManager,
                        textPager = container.textPager,
                        dispatchers = container.dispatchers,
                        bookRepository = container.bookRepository,
                    ) as T
            }
    }
}
