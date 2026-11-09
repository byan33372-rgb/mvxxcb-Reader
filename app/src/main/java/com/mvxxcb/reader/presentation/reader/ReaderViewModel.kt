package com.mvxxcb.reader.presentation.reader

// 实现归属 WP-U（04 §4）。契约依据：02 §8.6。
// 核心：章节加载（缓存优先）→ TextPager 后台分页 → 按模式恢复位置（inChapterFraction 映射）；
// 滚动/翻页进度 500ms 防抖保存（01 §5.6）；临近章尾预取（01 §5.7，静默失败）；
// ON_STOP 强制保存（Screen 必须调用 forceSaveProgress）。
//
// 2.0 连续阅读分支（07 §5，WP-CR）：ReadingMode.CONTINUOUS → ReaderItem 流 + 章节窗口
//（当前章 ±1 起步，Ready ≤5 章 LRU 驻留）+ 单 Job 串行加载（同章不重复请求）+
// charOffset 主锚点进度（fraction 兜底）。SCROLL/PAGE_SIMULATED 既有链路零改动；
// 模式互切经 onSettingsChanged 桥接（exit → pendingRestoreFraction → 既有分页恢复链路）。
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
import com.mvxxcb.reader.domain.model.ChapterMeta
import com.mvxxcb.reader.domain.model.ContentBlock
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
import kotlin.math.abs
import kotlin.math.roundToLong
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

// ———— 2.0 连续阅读（07 §5 冻结契约；类型归属 presentation 层，Host 直消费） ————

/** 连续滚动模式渲染项（07 §5.1 ReaderItem 流）。 */
sealed interface ReaderItem {
    data class ChapterDivider(
        val chapterId: Long,
        val chapterIndex: Int,
        val title: String,
    ) : ReaderItem

    data class Paragraph(
        val chapterIndex: Int,
        val blockIndex: Int,
        val text: String,
    ) : ReaderItem

    data class Image(
        val chapterIndex: Int,
        val blockIndex: Int,
        val url: String,
        val alt: String?,
    ) : ReaderItem

    /** 下一章加载占位 */
    data class ChapterLoading(val chapterIndex: Int) : ReaderItem

    /** 失败占位（可点重试） */
    data class ChapterFailed(
        val chapterIndex: Int,
        val error: ReaderError,
        val canRetry: Boolean,
    ) : ReaderItem

    data object BookEnd : ReaderItem
}

/** 连续模式章节槽状态（07 §5.1 ChapterSlot：Pending→Loading→Ready|Failed，Failed 手动重试） */
enum class ContinuousChapterState { PENDING, LOADING, READY, FAILED }

/**
 * 章节窗口条目（07 §5.1 ChapterSlot 的窗口实现）。
 * PENDING = 窗口外/已 LRU 裁剪：content/items 清空（不驻留内存），Meta（chapterId/title）保留。
 * items 由 state 派生：PENDING 为空；LOADING/FAILED 为 Divider+占位；READY 为 Divider+段落块
 *（末章再追加 BookEnd）。
 */
data class ChapterWindowEntry(
    val chapterIndex: Int,
    val chapterId: Long?,
    val title: String,
    val state: ContinuousChapterState,
    val content: ReaderContent?,
    val error: ReaderError?,
    val canRetry: Boolean,
    val items: List<ReaderItem>,
)

/** 连续模式恢复目标（charOffset 主锚点/fraction 兜底 → 章内块序号；Host scrollToItem 后消费） */
data class ContinuousRestoreTarget(
    val chapterIndex: Int,
    val blockIndex: Int,
)

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

    // ———— 2.0 连续阅读状态（07 §5.3；SCROLL/PAGE_SIMULATED 链路不读取） ————

    private val _chapterWindow = MutableStateFlow<List<ChapterWindowEntry>>(emptyList())

    /** 章节窗口（chapterIndex 升序、连续区间，含 PENDING 裁剪占位） */
    val chapterWindow: StateFlow<List<ChapterWindowEntry>> = _chapterWindow.asStateFlow()

    private val _visibleChapterIndex = MutableStateFlow(initialChapterIndex.coerceAtLeast(0))

    /** 当前阅读章（Host 按首个可见项上报；LRU 距离/进度/工具栏标志的基准） */
    val visibleChapterIndex: StateFlow<Int> = _visibleChapterIndex.asStateFlow()

    /** charOffset/fraction → 章内块序号恢复目标（Host scrollToItem 后调 consumeContinuousRestoreTarget） */
    private val _continuousRestoreTarget = MutableStateFlow<ContinuousRestoreTarget?>(null)
    val continuousRestoreTarget: StateFlow<ContinuousRestoreTarget?> =
        _continuousRestoreTarget.asStateFlow()

    /** 跳章目标（Host 滚动到该章 Divider 后调 consumeContinuousJumpTarget） */
    private val _continuousJumpTarget = MutableStateFlow<Int?>(null)
    val continuousJumpTarget: StateFlow<Int?> = _continuousJumpTarget.asStateFlow()

    private var continuousActive = false

    /** 目录元数据缓存（observeChapterMetas 真值；窗口条目/标题/BookEnd 的来源） */
    private var chapterMetas: List<ChapterMeta> = emptyList()

    /** 当前章内锚点 = 首可见块序号（charOffset 由它逐块累计求得；07 §5.4） */
    private var currentAnchorBlockIndex = 0

    private var pendingContinuousRestore: ContinuousRestore? = null

    private data class ContinuousRestore(
        val chapterIndex: Int,
        val charOffset: Long,
        val fraction: Double,
    )

    private data class ContinuousLoadRequest(val chapterIndex: Int, val prefetch: Boolean)

    /** 全局单加载 Job 串行队列（同书串行原则；同一章不重复请求） */
    private val continuousLoadQueue = ArrayDeque<ContinuousLoadRequest>()
    private var continuousLoadWorker: Job? = null

    init {
        observeSettings()
        observeChapterCount()
        loadBookTitle()
        observeBookmarks()
        viewModelScope.launch {
            // 2.0（07 §5.3）：CONTINUOUS → 连续分支（ReaderItem 流 + 章节窗口 + charOffset 锚点）；
            // SCROLL/PAGE_SIMULATED → 既有单章加载链路（零改动）。
            val initialMode = runCatching { settingsRepository.current().readingMode }
                .getOrDefault(ReadingMode.SCROLL)
            if (initialMode == ReadingMode.CONTINUOUS) {
                startContinuous(initialChapterIndex.coerceAtLeast(0))
            } else {
                loadChapter(preferCache = true, restoreFromProgress = true)
            }
        }
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
        // 2.0：连续模式 = 跳转到上一章 Divider 锚点（07 §5.3 手动入口保留）
        if (continuousActive) {
            val target = _visibleChapterIndex.value - 1
            if (target >= 0) continuousJumpTo(target)
            return
        }
        val index = _uiState.value.chapterIndex
        if (index <= 0) return
        switchChapter(index - 1)
    }

    fun goToNextChapter() {
        if (continuousActive) {
            val target = _visibleChapterIndex.value + 1
            if (!(totalChapters in 1..target)) continuousJumpTo(target)
            return
        }
        val index = _uiState.value.chapterIndex
        if (totalChapters in 1..index + 1) return
        switchChapter(index + 1)
    }

    fun jumpToChapter(chapterIndex: Int) {
        if (chapterIndex < 0) return
        if (totalChapters > 0 && chapterIndex >= totalChapters) return
        if (chapterIndex == _uiState.value.chapterIndex) return
        // 2.0：连续模式 = 重建窗口并滚动到该章 Divider
        if (continuousActive) {
            continuousJumpTo(chapterIndex)
            return
        }
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
                chapterMetas = metas
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
                // 2.0：目录更新 → 连续窗口元数据刷新/初始化（07 §5.4 恢复矩阵「目录更新」场景）
                if (continuousActive) onContinuousMetasChanged()
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
        if (continuousActive) return // 连续模式按段落直渲，无需 TextPager 分页（07 §5.3）
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
            // 2.0：连续模式不走分页恢复（repaginate 已被连续分支短路，防御性保留穷尽分支）
            ReadingMode.CONTINUOUS -> Unit
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
        val newMode = _uiState.value.settings.readingMode
        val wasContinuous = continuousActive
        if (newMode == ReadingMode.CONTINUOUS) {
            // 2.0（07 §5.3）：进入/保持连续分支。字号/主题/行距变更由段落直渲自适应，
            // 位置由 LazyColumn item key 保持，无需重排版。
            if (!wasContinuous) startContinuous(_uiState.value.chapterIndex)
            return
        }
        if (wasContinuous) {
            exitContinuousMode()
        }
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
        val progress = if (continuousActive) {
            buildContinuousProgress()
        } else {
            buildPagedProgress()
        } ?: return
        _uiState.update { it.copy(savingProgressHint = true) }
        viewModelScope.launch {
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

    // ———— 2.0 连续阅读引擎（07 §5：ReaderItem 流 / 窗口驻留 / 串行加载 / charOffset 锚点） ————

    /** 进度对象（翻页/滚动模式）：语义与改造前逐字段一致，charOffset 保持 -1 sentinel */
    private fun buildPagedProgress(): ReadingProgress? {
        val state = _uiState.value
        if (state.contentState !is ReaderContentState.Ready) return null
        val chapterId = currentChapterId ?: return null
        return ReadingProgress(
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
            charOffset = -1L, // 非连续模式不写字符锚点（07 §5.4：旧数据 sentinel 语义）
            updatedAt = System.currentTimeMillis(), // 契约构造器无 TimeProvider，见文件头注记
        )
    }

    /** 进度对象（连续模式）：charOffset 主锚点 + fraction 兜底（07 §5.4） */
    private fun buildContinuousProgress(): ReadingProgress? {
        val visible = _visibleChapterIndex.value
        val entry = _chapterWindow.value.firstOrNull { it.chapterIndex == visible }
        val content = entry?.takeIf { it.state == ContinuousChapterState.READY }?.content
            ?: return null // 加载中/失败不落锚
        val anchorBlock = currentAnchorBlockIndex
            .coerceAtLeast(0)
            .coerceAtMost((content.blocks.size - 1).coerceAtLeast(0))
        val offset = charOffsetFor(content, anchorBlock)
        val total = contentCharCount(content.blocks)
        val fraction = if (total > 0) offset.toDouble() / total else _uiState.value.inChapterFraction
        return ReadingProgress(
            bookId = bookId,
            chapterId = content.chapterId,
            chapterIndex = visible,
            scrollPosition = fraction,
            pageIndex = null,
            inChapterFraction = fraction,
            progressPercentage = overallProgress(fraction),
            charOffset = offset,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** 进入连续模式（初始化/SCROLL·PAGE → CONTINUOUS 切换共用；幂等） */
    private fun startContinuous(centerIndex: Int) {
        if (continuousActive) return
        continuousActive = true
        _visibleChapterIndex.value = centerIndex
        currentAnchorBlockIndex = 0
        ensureContinuousWindow(centerIndex)
        restoreContinuousFromProgress(centerIndex)
        syncVisibleChapterState()
    }

    /** 退出连续模式（CONTINUOUS → SCROLL/PAGE 切换）：charOffset→fraction 换算后走既有恢复链路 */
    private fun exitContinuousMode() {
        // 先按 charOffset 主锚点落盘（此时仍处连续分支）
        saveProgressNow()
        val visible = _visibleChapterIndex.value
        val entry = _chapterWindow.value.firstOrNull { it.chapterIndex == visible }
        val readyContent = entry?.content?.takeIf { entry.state == ContinuousChapterState.READY }
        // 章内分数（charOffset 主锚点换算）：交由既有 pendingRestoreFraction → 分页 → 映射链路恢复
        val anchorFraction = if (readyContent != null) {
            val total = contentCharCount(readyContent.blocks)
            val offset = charOffsetFor(readyContent, currentAnchorBlockIndex.coerceAtLeast(0))
            if (total > 0) offset.toDouble() / total else _uiState.value.inChapterFraction
        } else {
            _uiState.value.inChapterFraction
        }
        continuousActive = false
        _uiState.update { st ->
            st.copy(
                chapterIndex = visible,
                inChapterFraction = anchorFraction,
                hasPrevious = visible > 0,
                hasNext = totalChapters <= 0 || visible < totalChapters - 1,
            )
        }
        if (readyContent != null) {
            currentChapterId = readyContent.chapterId
            _uiState.update {
                it.copy(
                    contentState = ReaderContentState.Ready(readyContent, fromCache = isMetaCached(visible)),
                    chapterTitle = entry?.title?.takeIf { t -> t.isNotEmpty() } ?: readyContent.title,
                )
            }
        } else {
            // 可见章未就绪（被裁剪/加载失败）：按既有单章链路重载
            loadChapter(preferCache = true, restoreFromProgress = false)
        }
        // 清理连续状态（窗口/队列/待恢复目标）
        _chapterWindow.value = emptyList()
        _continuousRestoreTarget.value = null
        _continuousJumpTarget.value = null
        continuousLoadQueue.clear()
    }

    /** 窗口初始化（当前章 ±1 起步，07 §5.2）：metas 未就绪时挂起，待 observeChapterMetas 回调 */
    private fun ensureContinuousWindow(center: Int) {
        if (_chapterWindow.value.isNotEmpty()) return
        if (totalChapters <= 0) return
        val start = (center - 1).coerceAtLeast(0)
        val end = (center + 1).coerceAtMost(totalChapters - 1)
        _chapterWindow.value = (start..end).map { idx -> pendingEntry(idx) }
        // 模式切换场景：既有单章 Ready 内容直接播种当前章条目，避免同章重复请求
        val seeded = _uiState.value.contentState as? ReaderContentState.Ready
        if (seeded != null && seeded.content.chapterIndex == center) {
            updateEntry(center) { entry ->
                entry.copy(
                    state = ContinuousChapterState.READY,
                    content = seeded.content,
                    chapterId = seeded.content.chapterId,
                    items = buildReadyItems(entry, seeded.content),
                )
            }
        } else {
            enqueueContinuousLoad(center, prefetch = false)
        }
        // 加载顺序：当前章优先，再两侧（单 Job 串行 FIFO）
        (start..end).filter { it != center }.forEach { idx ->
            enqueueContinuousLoad(idx, prefetch = idx == end)
        }
    }

    private fun pendingEntry(chapterIndex: Int): ChapterWindowEntry {
        val meta = chapterMetas.getOrNull(chapterIndex)
        return ChapterWindowEntry(
            chapterIndex = chapterIndex,
            chapterId = meta?.id,
            title = meta?.title.orEmpty(),
            state = ContinuousChapterState.PENDING,
            content = null,
            error = null,
            canRetry = false,
            items = emptyList(),
        )
    }

    /** 章尾自动加载（Host：倒数 3 项可见触发；07 §5.2 临近章尾追加，窗口向前扩展自然预取） */
    fun onContinuousNearWindowEnd() {
        if (!continuousActive) return
        val window = _chapterWindow.value
        val last = window.lastOrNull() ?: return
        if (last.state == ContinuousChapterState.PENDING) {
            // 末尾是已裁剪章：重新加载即可
            enqueueContinuousLoad(last.chapterIndex, prefetch = false)
            return
        }
        val target = last.chapterIndex + 1
        if (totalChapters in 1..target) return
        if (window.any { it.chapterIndex == target }) return
        appendEntry(target)
        enqueueContinuousLoad(target, prefetch = true)
    }

    /** 顶部向前扩展（Host：窗口顶 2 项内触发；07 §5.2 回溯 ≤1 章/次） */
    fun onContinuousNearWindowStart() {
        if (!continuousActive) return
        val window = _chapterWindow.value
        val first = window.firstOrNull() ?: return
        if (first.state == ContinuousChapterState.PENDING) {
            enqueueContinuousLoad(first.chapterIndex, prefetch = false)
            return
        }
        val target = first.chapterIndex - 1
        if (target < 0) return
        if (window.any { it.chapterIndex == target }) return
        _chapterWindow.update { list -> listOf(pendingEntry(target)) + list }
        enqueueContinuousLoad(target, prefetch = false)
    }

    /** 失败章手动重试（ChapterFailed 占位点击） */
    fun retryContinuousChapter(chapterIndex: Int) {
        if (!continuousActive) return
        val entry = _chapterWindow.value.firstOrNull { it.chapterIndex == chapterIndex } ?: return
        if (entry.state == ContinuousChapterState.LOADING || entry.state == ContinuousChapterState.READY) {
            return // 同章不重复请求
        }
        enqueueContinuousLoad(chapterIndex, prefetch = false)
    }

    /**
     * 滚动锚点上报（Host 节流 ~120ms）：章节切换 → 可见章状态同步 + LRU 复算；
     * 章内块锚点 → charOffset 主锚点 + 章内分数 + 500ms 防抖保存。
     */
    fun onContinuousAnchorChanged(chapterIndex: Int, blockIndex: Int) {
        if (!continuousActive) return
        if (chapterIndex != _visibleChapterIndex.value) {
            _visibleChapterIndex.value = chapterIndex
            currentAnchorBlockIndex = 0
            syncVisibleChapterState()
        }
        val entry = _chapterWindow.value.firstOrNull { it.chapterIndex == chapterIndex }
        val readyContent = entry?.takeIf { it.state == ContinuousChapterState.READY }?.content
        if (readyContent != null) {
            val block = blockIndex.coerceIn(0, (readyContent.blocks.size - 1).coerceAtLeast(0))
            currentAnchorBlockIndex = block
            val total = contentCharCount(readyContent.blocks)
            val offset = charOffsetFor(readyContent, block)
            val fraction = if (total > 0) offset.toDouble() / total else 0.0
            _uiState.update {
                it.copy(
                    scrollFraction = fraction,
                    inChapterFraction = fraction,
                    overallProgress = overallProgress(fraction),
                )
            }
        }
        scheduleProgressSave()
    }

    /** 连续模式跳章（工具栏上一/下一章、目录、书签共用）：先存后跳，窗口重围绕目标章 */
    private fun continuousJumpTo(target: Int) {
        if (totalChapters > 0 && (target < 0 || target >= totalChapters)) return
        if (target == _visibleChapterIndex.value) return
        saveProgressNow() // 切章前强制保存当前章（01 §5.6）
        _visibleChapterIndex.value = target
        currentAnchorBlockIndex = 0
        rebuildWindowAround(target)
        syncVisibleChapterState()
        _continuousJumpTarget.value = target
    }

    /** 跳章/初始化窗口重建：保留目标 ±1 内既有条目状态，其余释放（不驻留内存） */
    private fun rebuildWindowAround(target: Int) {
        if (totalChapters <= 0) {
            _chapterWindow.value = listOf(pendingEntry(target))
            enqueueContinuousLoad(target, prefetch = false)
            return
        }
        val start = (target - 1).coerceAtLeast(0)
        val end = (target + 1).coerceAtMost(totalChapters - 1)
        val old = _chapterWindow.value.associateBy { it.chapterIndex }
        _chapterWindow.value = (start..end).map { idx -> old[idx] ?: pendingEntry(idx) }
        // 当前章优先入队，再入队两侧未就绪章
        enqueueContinuousLoad(target, prefetch = false)
        (start..end).filter { it != target }.forEach { idx ->
            val entry = _chapterWindow.value.first { it.chapterIndex == idx }
            if (entry.state == ContinuousChapterState.PENDING) {
                enqueueContinuousLoad(idx, prefetch = idx == end)
            }
        }
    }

    /** 目录更新：刷新窗口元数据（标题/ID/BookEnd 归属），越界条目移除，可见章钳制 */
    private fun onContinuousMetasChanged() {
        val window = _chapterWindow.value
        if (window.isEmpty()) {
            ensureContinuousWindow(_visibleChapterIndex.value)
            return
        }
        _chapterWindow.value = window.mapNotNull { entry ->
            if (totalChapters > 0 && entry.chapterIndex >= totalChapters) {
                null // 目录缩减：越界条目释放
            } else {
                val meta = chapterMetas.getOrNull(entry.chapterIndex)
                val updated = entry.copy(
                    chapterId = meta?.id ?: entry.chapterId,
                    title = meta?.title ?: entry.title,
                )
                if (updated.state == ContinuousChapterState.READY && updated.content != null) {
                    updated.copy(items = buildReadyItems(updated, updated.content))
                } else {
                    updated
                }
            }
        }
        if (totalChapters in 1.._visibleChapterIndex.value) {
            _visibleChapterIndex.value = totalChapters - 1
        }
        // 目录缩减导致可见章不在窗口内 → 重围绕（07 §5.4 恢复矩阵「目录更新」钳制语义）
        if (_chapterWindow.value.none { it.chapterIndex == _visibleChapterIndex.value }) {
            rebuildWindowAround(_visibleChapterIndex.value)
        }
        syncVisibleChapterState()
    }

    /** 可见章状态 → 既有单章状态面（工具栏/书签/进度保存共用，07 §5.3） */
    private fun syncVisibleChapterState() {
        val visible = _visibleChapterIndex.value
        val entry = _chapterWindow.value.firstOrNull { it.chapterIndex == visible }
        _uiState.update { st ->
            st.copy(
                chapterIndex = visible,
                chapterTitle = entry?.title?.takeIf { t -> t.isNotEmpty() }
                    ?: (entry?.content?.title ?: st.chapterTitle),
                contentState = when {
                    entry == null -> ReaderContentState.Loading
                    entry.state == ContinuousChapterState.READY && entry.content != null ->
                        ReaderContentState.Ready(entry.content, fromCache = isMetaCached(visible))

                    entry.state == ContinuousChapterState.FAILED && entry.error != null ->
                        ReaderContentState.Failed(entry.error, entry.canRetry, isMetaCached(visible))

                    else -> ReaderContentState.Loading
                },
                hasPrevious = visible > 0,
                hasNext = totalChapters <= 0 || visible < totalChapters - 1,
            )
        }
        if (entry?.state == ContinuousChapterState.READY && entry.content != null) {
            currentChapterId = entry.content.chapterId
        }
        trimReadyEntries()
    }

    // ———— 窗口加载（单 Job 串行；同一章不重复请求） ————

    private fun enqueueContinuousLoad(chapterIndex: Int, prefetch: Boolean) {
        val entry = _chapterWindow.value.firstOrNull { it.chapterIndex == chapterIndex } ?: return
        if (entry.state == ContinuousChapterState.LOADING || entry.state == ContinuousChapterState.READY) {
            return // 同章不重复请求：Loading 复用既有请求，Ready 不重载
        }
        updateEntry(chapterIndex) {
            it.copy(
                state = ContinuousChapterState.LOADING,
                error = null,
                items = buildLoadingItems(it),
            )
        }
        continuousLoadQueue.addLast(ContinuousLoadRequest(chapterIndex, prefetch))
        ensureContinuousLoadWorker()
    }

    private fun ensureContinuousLoadWorker() {
        if (continuousLoadWorker?.isActive == true) return
        continuousLoadWorker = viewModelScope.launch {
            while (continuousLoadQueue.isNotEmpty()) {
                val request = continuousLoadQueue.removeFirst()
                if (request.prefetch && _uiState.value.settings.autoPrefetchNext) {
                    // 07 §5.2：追加前预写缓存（静默失败；串行避免与正文加载重复请求同一章）
                    runCatching {
                        cacheManager.prefetchNeighborhood(bookId, request.chapterIndex, radius = 1)
                    }
                }
                val entry = _chapterWindow.value.firstOrNull {
                    it.chapterIndex == request.chapterIndex
                }
                if (entry == null || entry.state != ContinuousChapterState.LOADING) {
                    continue // 窗口已重建/裁剪/退出：丢弃过期请求
                }
                when (
                    val result = readingService.loadChapter(bookId, request.chapterIndex, preferCache = true)
                ) {
                    is AppResult.Success -> markChapterReady(request.chapterIndex, result.value)
                    is AppResult.Failure -> markChapterFailed(request.chapterIndex, result.error)
                }
            }
        }
    }

    private fun markChapterReady(chapterIndex: Int, content: ReaderContent) {
        updateEntry(chapterIndex) { entry ->
            entry.copy(
                state = ContinuousChapterState.READY,
                content = content,
                chapterId = content.chapterId,
                error = null,
                items = buildReadyItems(entry, content),
            )
        }
        if (chapterIndex == _visibleChapterIndex.value) syncVisibleChapterState()
        trimReadyEntries()
        maybeEmitRestoreTarget(chapterIndex)
    }

    private fun markChapterFailed(chapterIndex: Int, error: ReaderError) {
        updateEntry(chapterIndex) { entry ->
            entry.copy(
                state = ContinuousChapterState.FAILED,
                error = error,
                canRetry = isRetryable(error),
                items = buildFailedItems(entry, error, isRetryable(error)),
            )
        }
        if (chapterIndex == _visibleChapterIndex.value) syncVisibleChapterState()
    }

    /** LRU 裁剪：Ready 章节数 >5 → 距可见章最远者回 PENDING（content/items 释放，Meta 保留） */
    private fun trimReadyEntries() {
        var window = _chapterWindow.value
        val visible = _visibleChapterIndex.value
        while (window.count { it.state == ContinuousChapterState.READY } > MAX_READY_CHAPTERS) {
            val farthest = window
                .filter {
                    it.state == ContinuousChapterState.READY && it.chapterIndex != visible
                }
                .maxByOrNull { abs(it.chapterIndex - visible) }
                ?: break
            window = window.map { entry ->
                if (entry.chapterIndex == farthest.chapterIndex) {
                    entry.copy(
                        state = ContinuousChapterState.PENDING,
                        content = null,
                        error = null,
                        items = emptyList(),
                    )
                } else {
                    entry
                }
            }
        }
        _chapterWindow.value = window
    }

    private fun updateEntry(chapterIndex: Int, transform: (ChapterWindowEntry) -> ChapterWindowEntry) {
        _chapterWindow.update { list ->
            list.map { if (it.chapterIndex == chapterIndex) transform(it) else it }
        }
    }

    private fun appendEntry(chapterIndex: Int) {
        _chapterWindow.update { list ->
            if (list.any { it.chapterIndex == chapterIndex }) list else list + pendingEntry(chapterIndex)
        }
    }

    // ———— 恢复（07 §5.4：charOffset 主锚点 + fraction 兜底；7 恢复场景同一链路） ————

    private fun restoreContinuousFromProgress(chapterIndex: Int) {
        viewModelScope.launch {
            val progress = runCatching { progressRepository.getProgress(bookId) }.getOrNull()
            if (!continuousActive || progress == null) return@launch
            if (progress.chapterIndex != chapterIndex) return@launch
            pendingContinuousRestore = ContinuousRestore(
                chapterIndex = chapterIndex,
                charOffset = progress.charOffset,
                fraction = progress.inChapterFraction.coerceIn(0.0, 1.0),
            )
            maybeEmitRestoreTarget(chapterIndex)
        }
    }

    /** 章就绪后把 charOffset/fraction 映射为「首个累计字符 > 锚点」的块序号（二分语义，顺序实现） */
    private fun maybeEmitRestoreTarget(chapterIndex: Int) {
        val pending = pendingContinuousRestore ?: return
        if (pending.chapterIndex != chapterIndex) return
        val entry = _chapterWindow.value.firstOrNull { it.chapterIndex == chapterIndex }
        val content = entry?.takeIf { it.state == ContinuousChapterState.READY }?.content ?: return
        val blockIndex = blockIndexForOffset(content, pending.charOffset, pending.fraction)
        currentAnchorBlockIndex = blockIndex // 恢复落位期间保存不回退到章首
        _continuousRestoreTarget.value = ContinuousRestoreTarget(chapterIndex, blockIndex)
        pendingContinuousRestore = null
    }

    fun consumeContinuousRestoreTarget() {
        _continuousRestoreTarget.value = null
    }

    fun consumeContinuousJumpTarget() {
        _continuousJumpTarget.value = null
    }

    // ———— ReaderItem 构建 ————

    private fun buildDividerItem(entry: ChapterWindowEntry): ReaderItem =
        ReaderItem.ChapterDivider(
            chapterId = entry.chapterId ?: 0L,
            chapterIndex = entry.chapterIndex,
            title = entry.title,
        )

    private fun buildLoadingItems(entry: ChapterWindowEntry): List<ReaderItem> = listOf(
        buildDividerItem(entry),
        ReaderItem.ChapterLoading(entry.chapterIndex),
    )

    private fun buildFailedItems(
        entry: ChapterWindowEntry,
        error: ReaderError,
        canRetry: Boolean,
    ): List<ReaderItem> = listOf(
        buildDividerItem(entry),
        ReaderItem.ChapterFailed(entry.chapterIndex, error, canRetry),
    )

    private fun buildReadyItems(entry: ChapterWindowEntry, content: ReaderContent): List<ReaderItem> =
        buildList {
            add(buildDividerItem(entry))
            content.blocks.forEachIndexed { blockIndex, block ->
                when (block) {
                    is ContentBlock.Paragraph -> add(
                        ReaderItem.Paragraph(entry.chapterIndex, blockIndex, block.text),
                    )

                    is ContentBlock.Image -> add(
                        ReaderItem.Image(entry.chapterIndex, blockIndex, block.url, block.alt),
                    )
                }
            }
            if (totalChapters > 0 && entry.chapterIndex == totalChapters - 1) {
                add(ReaderItem.BookEnd)
            }
        }

    // ———— 字符偏移口径（与 TextPagerImpl/ReaderContentRender 一致：段落=文本长度，图片=1） ————

    /** 块序号之前的累计字符数（07 §5.4：首可见段落块在本章内的累计字符偏移） */
    private fun charOffsetFor(content: ReaderContent, blockIndex: Int): Long {
        var acc = 0L
        val upper = blockIndex.coerceIn(0, content.blocks.size)
        for (i in 0 until upper) acc += blockCharCount(content.blocks[i])
        return acc
    }

    private fun contentCharCount(blocks: List<ContentBlock>): Long =
        blocks.sumOf { blockCharCount(it) }

    private fun blockCharCount(block: ContentBlock): Long = when (block) {
        is ContentBlock.Paragraph -> block.text.length.toLong()
        is ContentBlock.Image -> 1L
    }

    /**
     * charOffset/fraction → 目标块序号：首个累计字符 > 锚点的块；
     * charOffset=-1（旧数据）走 fraction 路径；越界钳制章尾（06 K6 / 07 §5.4）。
     */
    private fun blockIndexForOffset(content: ReaderContent, charOffset: Long, fraction: Double): Int {
        val total = contentCharCount(content.blocks)
        val target = if (charOffset >= 0) {
            charOffset
        } else {
            (fraction * total).roundToLong().coerceAtLeast(0L)
        }
        var acc = 0L
        content.blocks.forEachIndexed { index, block ->
            acc += blockCharCount(block)
            if (acc > target) return index
        }
        return (content.blocks.size - 1).coerceAtLeast(0)
    }

    private fun isMetaCached(chapterIndex: Int): Boolean =
        chapterMetas.getOrNull(chapterIndex)?.isCached == true

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

        /** 连续模式窗口驻留上限：Ready 章节数（07 §5.2，LRU 裁剪回 PENDING） */
        private const val MAX_READY_CHAPTERS = 5

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
