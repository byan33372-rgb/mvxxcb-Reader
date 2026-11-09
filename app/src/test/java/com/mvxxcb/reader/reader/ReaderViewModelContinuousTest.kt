package com.mvxxcb.reader.reader

// WP-CR（2.0 B2，07 §5）：连续阅读引擎单测（纯 JVM + 假实现，模式参照 ReaderViewModelNavStateTest）。
// 覆盖：初始窗口 ±1、章尾自动扩展加载、同章不重复请求（单 Job 串行）、
//       LRU 裁剪（Ready >5 回 PENDING，Meta 保留）、失败→手动重试、
//       charOffset 主锚点保存、charOffset/fraction 恢复映射、
//       连续模式跳章（工具栏下一章/上一章）、SCROLL↔CONTINUOUS 模式互切零破坏。

import com.mvxxcb.reader.common.coroutine.DispatcherProvider
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.domain.cache.CacheBatchEvent
import com.mvxxcb.reader.domain.cache.CacheStats
import com.mvxxcb.reader.domain.cache.ContentCacheManager
import com.mvxxcb.reader.domain.cache.IntegrityReport
import com.mvxxcb.reader.domain.error.NetworkFailure
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.model.Bookmark
import com.mvxxcb.reader.domain.model.CatalogMergeReport
import com.mvxxcb.reader.domain.model.Chapter
import com.mvxxcb.reader.domain.model.ChapterMeta
import com.mvxxcb.reader.domain.model.ContentBlock
import com.mvxxcb.reader.domain.model.ParsedBookDetail
import com.mvxxcb.reader.domain.model.ParsedCatalog
import com.mvxxcb.reader.domain.model.ReaderContent
import com.mvxxcb.reader.domain.model.ReaderSettings
import com.mvxxcb.reader.domain.model.ReadingMode
import com.mvxxcb.reader.domain.model.ReadingProgress
import com.mvxxcb.reader.domain.repository.BookmarkRepository
import com.mvxxcb.reader.domain.repository.ChapterRepository
import com.mvxxcb.reader.domain.repository.ReaderSettingsRepository
import com.mvxxcb.reader.domain.repository.ReadingProgressRepository
import com.mvxxcb.reader.domain.service.ReadingService
import com.mvxxcb.reader.presentation.reader.ChapterWindowEntry
import com.mvxxcb.reader.presentation.reader.ContinuousChapterState
import com.mvxxcb.reader.presentation.reader.ContinuousRestoreTarget
import com.mvxxcb.reader.presentation.reader.ReaderContentState
import com.mvxxcb.reader.presentation.reader.ReaderItem
import com.mvxxcb.reader.presentation.reader.ReaderViewModel
import com.mvxxcb.reader.reader.engine.PageSpan
import com.mvxxcb.reader.reader.engine.PaginationResult
import com.mvxxcb.reader.reader.engine.ReaderLayoutSpec
import com.mvxxcb.reader.reader.engine.TextPager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelContinuousTest {

    private val testDispatcher = StandardTestDispatcher()

    private val bookId = 1L
    private val totalChapters = 8

    /** 章内块长口径：第 j 块长度 = 10 + j（累计字符偏移可手算验证） */
    private val blocksPerChapter = 5

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ———— 假体装配 ————

    private class Fixture(
        val service: FakeReadingService,
        val settingsRepo: FakeReaderSettingsRepository,
        val progressRepo: FakeProgressRepository,
        val chapterRepo: FakeChapterRepository,
        val cacheManager: FakeCacheManager,
    )

    private fun newFixture(
        initialMode: ReadingMode = ReadingMode.CONTINUOUS,
        failingChapters: Set<Int> = emptySet(),
        savedProgress: ReadingProgress? = null,
    ): Fixture {
        val service = FakeReadingService(failingChapters.toMutableSet())
        val settingsRepo = FakeReaderSettingsRepository(
            MutableStateFlow(ReaderSettings(readingMode = initialMode)),
        )
        val progressRepo = FakeProgressRepository(savedProgress)
        val chapterRepo = FakeChapterRepository()
        return Fixture(service, settingsRepo, progressRepo, chapterRepo, FakeCacheManager())
    }

    private fun TestScope.createViewModel(
        fixture: Fixture,
        initialChapterIndex: Int = 0,
    ): ReaderViewModel = ReaderViewModel(
        bookId = bookId,
        initialChapterIndex = initialChapterIndex,
        readingService = fixture.service,
        settingsRepository = fixture.settingsRepo,
        progressRepository = fixture.progressRepo,
        bookmarkRepository = FakeBookmarkRepository(),
        chapterRepository = fixture.chapterRepo,
        cacheManager = fixture.cacheManager,
        textPager = FakeTextPager(),
        dispatchers = FakeDispatcherProvider(testDispatcher),
        bookRepository = null,
    )

    private fun TestScope.newContinuousViewModel(
        initialChapterIndex: Int = 0,
        failingChapters: Set<Int> = emptySet(),
        savedProgress: ReadingProgress? = null,
    ): Pair<ReaderViewModel, Fixture> {
        val fixture = newFixture(
            failingChapters = failingChapters,
            savedProgress = savedProgress,
        )
        val vm = createViewModel(fixture, initialChapterIndex)
        advanceUntilIdle()
        return vm to fixture
    }

    private fun entry(vm: ReaderViewModel, chapterIndex: Int): ChapterWindowEntry =
        vm.chapterWindow.value.first { it.chapterIndex == chapterIndex }

    /** 第 j 块之前的累计字符数（与 VM 口径一致：段落=文本长度，图片=1） */
    private fun offsetBefore(blockIndex: Int): Long =
        (0 until blockIndex).sumOf { (10 + it).toLong() }

    private fun totalChars(): Long = (0 until blocksPerChapter).sumOf { (10 + it).toLong() }

    private fun progressAt(
        chapterIndex: Int,
        charOffset: Long,
        fraction: Double,
    ): ReadingProgress = ReadingProgress(
        bookId = bookId,
        chapterId = chapterIndex + 1L,
        chapterIndex = chapterIndex,
        scrollPosition = null,
        pageIndex = null,
        inChapterFraction = fraction,
        progressPercentage = 0.0,
        charOffset = charOffset,
        updatedAt = 0L,
    )

    // ———— 1. 初始窗口：当前章 ±1，当前章优先就绪 ————

    @Test
    fun `初始窗口为当前章±1且元数据完整`() = runTest(testDispatcher) {
        val (vm, _) = newContinuousViewModel(initialChapterIndex = 3)

        val window = vm.chapterWindow.value
        assertEquals(listOf(2, 3, 4), window.map { it.chapterIndex })
        window.forEach { entry ->
            assertEquals("窗口条目应全部就绪（全缓存假体）", ContinuousChapterState.READY, entry.state)
            assertEquals("标题取自 ChapterMeta", "第${entry.chapterIndex + 1}章", entry.title)
            assertEquals("chapterId 取自 ChapterMeta", entry.chapterIndex + 1L, entry.chapterId)
            assertTrue("Ready 条目应含正文内容", entry.content != null)
        }
        // 条目 items 结构：Divider + 段落块
        val current = entry(vm, 3)
        assertEquals(ReaderItem.ChapterDivider::class, current.items.first()::class)
        assertEquals(
            blocksPerChapter,
            current.items.count { it is ReaderItem.Paragraph },
        )
        assertEquals("第4章", (current.items.first() as ReaderItem.ChapterDivider).title)

        // 可见章状态面同步（工具栏/进度共用）
        assertEquals(3, vm.uiState.value.chapterIndex)
        assertEquals(3, vm.visibleChapterIndex.value)
        assertTrue(vm.uiState.value.contentState is ReaderContentState.Ready)
        assertTrue(vm.uiState.value.hasPrevious)
        assertTrue(vm.uiState.value.hasNext)
    }

    // ———— 2. 章尾自动加载下一章 + 临近重复触发不重复请求 ————

    @Test
    fun `章尾自动加载下一章且Loading期重复触发不重复请求`() = runTest(testDispatcher) {
        val (vm, fixture) = newContinuousViewModel(initialChapterIndex = 0)

        // 窗口尾触发两次（未推进协程 = 第 5 章仍 LOADING）：同章只请求一次
        vm.onContinuousNearWindowEnd()
        vm.onContinuousNearWindowEnd()
        advanceUntilIdle()

        assertEquals(1, fixture.service.loadCalls[2]) // 同章不重复请求
        assertEquals(ContinuousChapterState.READY, entry(vm, 2).state)
        // 窗口扩展自然触发预取（07 §5.2）
        assertTrue(fixture.cacheManager.prefetchCalls.contains(2))
    }

    // ———— 3. 锚点上报不触发重复加载，章内分数同步 ————

    @Test
    fun `锚点重复上报不触发额外章节请求且章内分数同步`() = runTest(testDispatcher) {
        val (vm, fixture) = newContinuousViewModel(initialChapterIndex = 1)

        val before = fixture.service.loadCalls.toMap()
        repeat(3) { vm.onContinuousAnchorChanged(1, 2) }
        advanceUntilIdle()

        assertEquals(before, fixture.service.loadCalls)
        assertEquals(
            offsetBefore(2).toDouble() / totalChars(),
            vm.uiState.value.inChapterFraction,
            1e-9,
        )
    }

    // ———— 4. LRU 裁剪：Ready >5 → 最远章回 PENDING（Meta 保留，items 移除） ————

    @Test
    fun `Ready超过5章时LRU裁剪最远章回Pending`() = runTest(testDispatcher) {
        val (vm, fixture) = newContinuousViewModel(initialChapterIndex = 0)

        // 模拟顺序前读：锚点推进 → 章尾触发扩展，各步推进协程
        for (i in 0..5) {
            vm.onContinuousAnchorChanged(i, 0)
            vm.onContinuousNearWindowEnd()
            advanceUntilIdle()
        }

        val window = vm.chapterWindow.value
        assertTrue("窗口应覆盖到第 7 章", window.any { it.chapterIndex == 7 })
        assertTrue(
            "Ready 驻留不得超过 5 章（红线）",
            window.count { it.state == ContinuousChapterState.READY } <= 5,
        )
        // 被裁剪章（0/1/2）：content/items 释放，Meta（chapterId/title）保留
        listOf(0, 1, 2).forEach { idx ->
            val trimmed = window.first { it.chapterIndex == idx }
            assertEquals("被裁剪章应回 PENDING", ContinuousChapterState.PENDING, trimmed.state)
            assertNull("裁剪后内容必须释放（不整本进内存）", trimmed.content)
            assertTrue("裁剪后 items 必须移除", trimmed.items.isEmpty())
            assertEquals("Meta（chapterId）保留", idx + 1L, trimmed.chapterId)
            assertEquals("Meta（标题）保留", "第${idx + 1}章", trimmed.title)
        }
        // 末章 Ready → items 含 BookEnd
        val last = window.first { it.chapterIndex == 7 }
        assertTrue(last.items.last() is ReaderItem.BookEnd)
        // 全程同章不重复请求
        window.forEach { e ->
            assertEquals("第 ${e.chapterIndex} 章应恰好请求一次", 1, fixture.service.loadCalls[e.chapterIndex])
        }
    }

    // ———— 5. 失败 → FAILED 占位（可重试）→ 重试成功 ————

    @Test
    fun `失败章呈FAILED占位且手动重试成功`() = runTest(testDispatcher) {
        val (vm, fixture) = newContinuousViewModel(
            initialChapterIndex = 0,
            failingChapters = setOf(2),
        )

        vm.onContinuousNearWindowEnd()
        advanceUntilIdle()

        val failed = entry(vm, 2)
        assertEquals(ContinuousChapterState.FAILED, failed.state)
        assertTrue("OFFLINE 应可重试", failed.canRetry)
        assertEquals(1, fixture.service.loadCalls[2])
        // items 结构：Divider + ChapterFailed
        assertTrue(failed.items[0] is ReaderItem.ChapterDivider)
        val failedItem = failed.items[1] as ReaderItem.ChapterFailed
        assertEquals(2, failedItem.chapterIndex)
        assertTrue(failedItem.canRetry)

        // 网络恢复 → 手动重试 → READY（第二次请求）
        fixture.service.failingChapters.clear()
        vm.retryContinuousChapter(2)
        advanceUntilIdle()

        assertEquals(ContinuousChapterState.READY, entry(vm, 2).state)
        assertEquals("重试应重新发起一次请求", 2, fixture.service.loadCalls[2])
    }

    // ———— 6. charOffset 主锚点保存 ————

    @Test
    fun `锚点变化后强存进度携带charOffset与fraction`() = runTest(testDispatcher) {
        val (vm, fixture) = newContinuousViewModel(initialChapterIndex = 0)

        vm.onContinuousAnchorChanged(0, 2)
        vm.forceSaveProgress()
        advanceUntilIdle()

        val saved = fixture.progressRepo.saved.last()
        assertEquals(0, saved.chapterIndex)
        assertEquals(1L, saved.chapterId)
        assertEquals("charOffset = 前 2 块累计字符", offsetBefore(2), saved.charOffset)
        assertEquals(
            "fraction = charOffset / 全章字符数",
            offsetBefore(2).toDouble() / totalChars(),
            saved.inChapterFraction,
            1e-9,
        )
    }

    // ———— 7. charOffset 恢复映射（主锚点） ————

    @Test
    fun `charOffset恢复映射到目标块并保持锚点不回退`() = runTest(testDispatcher) {
        // 进度锚点 = 第 1 块末尾（第 0 块长 10）→ 应恢复到第 1 块（首个累计 > 10）
        val (vm, fixture) = newContinuousViewModel(
            initialChapterIndex = 0,
            savedProgress = progressAt(0, charOffset = 10L, fraction = 10.0 / totalChars()),
        )

        assertEquals(
            ContinuousRestoreTarget(0, 1),
            vm.continuousRestoreTarget.value,
        )
        // 恢复落位期间强存：锚点不回退到章首（charOffset 保持恢复值）
        vm.forceSaveProgress()
        advanceUntilIdle()
        assertEquals(10L, fixture.progressRepo.saved.last().charOffset)
    }

    // ———— 8. 旧数据 fraction 兜底恢复（charOffset = -1） ————

    @Test
    fun `旧数据charOffset为-1时按fraction兜底恢复`() = runTest(testDispatcher) {
        // fraction=0.9 × 全章 60 字符 = 54 → 首个累计 > 54 的块 = 第 4 块（累计 60）
        val (vm, _) = newContinuousViewModel(
            initialChapterIndex = 0,
            savedProgress = progressAt(0, charOffset = -1L, fraction = 0.9),
        )

        assertEquals(ContinuousRestoreTarget(0, 4), vm.continuousRestoreTarget.value)
    }

    // ———— 9. 连续模式跳章（工具栏/目录共用链路） ————

    @Test
    fun `连续模式跳章重建窗口并发出定位目标`() = runTest(testDispatcher) {
        val (vm, _) = newContinuousViewModel(initialChapterIndex = 0)

        vm.goToNextChapter()
        advanceUntilIdle()

        assertEquals(1, vm.visibleChapterIndex.value)
        assertEquals(1, vm.uiState.value.chapterIndex)
        assertEquals(listOf(0, 1, 2), vm.chapterWindow.value.map { it.chapterIndex })
        assertEquals("跳章应发出 Divider 定位目标", 1, vm.continuousJumpTarget.value)

        vm.consumeContinuousJumpTarget()
        assertNull(vm.continuousJumpTarget.value)

        // 末章边界：下一章不可越界
        repeat(6) {
            vm.goToNextChapter()
            advanceUntilIdle()
        }
        assertEquals(7, vm.visibleChapterIndex.value)
        assertFalse(vm.uiState.value.hasNext)
        vm.goToNextChapter()
        advanceUntilIdle()
        assertEquals("末章不越界", 7, vm.visibleChapterIndex.value)
    }

    // ———— 10. 模式互切：SCROLL → CONTINUOUS → SCROLL 零破坏 ————

    @Test
    fun `模式互切保持双模式既有链路可用`() = runTest(testDispatcher) {
        val fixture = newFixture(initialMode = ReadingMode.SCROLL)
        val vm = createViewModel(fixture, initialChapterIndex = 0)
        advanceUntilIdle()
        vm.onViewportChanged(widthPx = 800, heightPx = 1200, density = 2f, fontScale = 1f)
        advanceUntilIdle()
        assertTrue("前置：SCROLL 模式首章 Ready", vm.uiState.value.contentState is ReaderContentState.Ready)
        val callsBeforeSwitch = fixture.service.loadCalls[0] ?: 0

        // SCROLL → CONTINUOUS：窗口初始化，当前章播种不重复请求
        fixture.settingsRepo.flow.value = ReaderSettings(readingMode = ReadingMode.CONTINUOUS)
        advanceUntilIdle()
        assertEquals(listOf(0, 1), vm.chapterWindow.value.map { it.chapterIndex })
        assertEquals(ContinuousChapterState.READY, entry(vm, 0).state)
        assertEquals("播种当前章不应重复请求", callsBeforeSwitch, fixture.service.loadCalls[0])
        assertEquals(ContinuousChapterState.READY, entry(vm, 1).state)
        assertTrue(vm.uiState.value.contentState is ReaderContentState.Ready)

        // CONTINUOUS → SCROLL：回到既有分页链路（Ready + 重排版完成）
        fixture.settingsRepo.flow.value = ReaderSettings(readingMode = ReadingMode.SCROLL)
        advanceUntilIdle()
        assertTrue("切回滚动模式应恢复 Ready", vm.uiState.value.contentState is ReaderContentState.Ready)
        assertTrue("切回后应完成分页（pagination 就绪）", vm.pagination.value != null)
        assertTrue(vm.chapterWindow.value.isEmpty())
        assertEquals(0, vm.uiState.value.chapterIndex)
        assertFalse(vm.uiState.value.hasPrevious)
    }

    // ———— 11. 目录更新：窗口元数据刷新，越界钳制 ————

    @Test
    fun `目录更新刷新窗口标题并钳制越界可见章`() = runTest(testDispatcher) {
        val (vm, fixture) = newContinuousViewModel(initialChapterIndex = 6)

        // 目录缩减至 5 章 → 可见章 6 越界 → 钳制到末章 4
        fixture.chapterRepo.emitMetas(count = 5)
        advanceUntilIdle()

        assertEquals(4, vm.visibleChapterIndex.value)
        assertEquals(4, vm.uiState.value.chapterIndex)
        assertTrue(vm.chapterWindow.value.all { it.chapterIndex < 5 })
        assertFalse(vm.uiState.value.hasNext)
    }

    // ———— 假实现 ————

    private inner class FakeReadingService(
        val failingChapters: MutableSet<Int>,
    ) : ReadingService {
        val loadCalls = mutableMapOf<Int, Int>()

        override suspend fun loadChapter(
            bookId: Long,
            chapterIndex: Int,
            preferCache: Boolean,
        ): AppResult<ReaderContent> {
            loadCalls[chapterIndex] = (loadCalls[chapterIndex] ?: 0) + 1
            if (chapterIndex !in 0 until totalChapters) {
                return AppResult.Failure(
                    ReaderError.State(com.mvxxcb.reader.domain.error.StateFailure.CHAPTER_NOT_FOUND),
                )
            }
            if (chapterIndex in failingChapters) {
                return AppResult.Failure(
                    ReaderError.Network(
                        failure = NetworkFailure.OFFLINE,
                        url = null,
                        statusCode = null,
                    ),
                )
            }
            return AppResult.Success(fakeContent(chapterIndex))
        }

        override suspend fun loadChapterByUrl(bookId: Long, url: String): AppResult<ReaderContent> =
            AppResult.Failure(ReaderError.Unknown("not used in this test"))

        override suspend fun refreshCatalog(bookId: Long): AppResult<CatalogMergeReport> =
            AppResult.Success(
                CatalogMergeReport(
                    added = 0, unchanged = totalChapters, updatedUrl = 0, markedMissing = 0,
                    matchedRatio = 1.0, detectedReversal = false, requiresReview = false,
                ),
            )

        override suspend fun refreshBookDetail(bookId: Long): AppResult<ParsedBookDetail> =
            AppResult.Failure(ReaderError.Unknown("not used in this test"))

        private fun fakeContent(chapterIndex: Int): ReaderContent {
            val blocks = (0 until blocksPerChapter).map { j ->
                ContentBlock.Paragraph("c${chapterIndex}b$j".padEnd(10 + j, 'x'))
            }
            return ReaderContent(
                chapterId = chapterIndex + 1L,
                chapterIndex = chapterIndex,
                bookId = bookId,
                title = "第${chapterIndex + 1}章",
                blocks = blocks,
            )
        }
    }

    private class FakeReaderSettingsRepository(
        val flow: MutableStateFlow<ReaderSettings>,
    ) : ReaderSettingsRepository {
        override val settings: Flow<ReaderSettings> = flow
        override suspend fun current(): ReaderSettings = flow.value
        override suspend fun update(
            transform: (ReaderSettings) -> ReaderSettings,
        ): AppResult<Unit> {
            flow.value = transform(flow.value)
            return AppResult.Success(Unit)
        }
    }

    private class FakeProgressRepository(
        var progressToReturn: ReadingProgress?,
    ) : ReadingProgressRepository {
        val saved = mutableListOf<ReadingProgress>()

        override fun observeProgress(bookId: Long): Flow<ReadingProgress?> =
            MutableStateFlow(progressToReturn)

        override suspend fun getProgress(bookId: Long): ReadingProgress? = progressToReturn

        override suspend fun saveProgress(progress: ReadingProgress): AppResult<Unit> {
            saved += progress
            return AppResult.Success(Unit)
        }

        override suspend fun clearProgress(bookId: Long): AppResult<Unit> = AppResult.Success(Unit)
    }

    private class FakeBookmarkRepository : BookmarkRepository {
        override fun observeBookmarks(bookId: Long): Flow<List<Bookmark>> =
            MutableStateFlow(emptyList())

        override suspend fun addBookmark(bookmark: Bookmark): AppResult<Long> =
            AppResult.Success(1L)

        override suspend fun removeBookmark(bookmarkId: Long): AppResult<Unit> =
            AppResult.Success(Unit)
    }

    private inner class FakeChapterRepository : ChapterRepository {
        private val metasFlow = MutableStateFlow(buildMetas(totalChapters))

        fun emitMetas(count: Int) {
            metasFlow.value = buildMetas(count)
        }

        private fun buildMetas(count: Int): List<ChapterMeta> =
            (0 until count).map { index ->
                ChapterMeta(
                    id = index + 1L,
                    bookId = bookId,
                    chapterIndex = index,
                    title = "第${index + 1}章",
                    url = "https://example.com/ch${index + 1}",
                    isCached = true,
                    isMissing = false,
                    wordCount = 100,
                    fetchedAt = null,
                )
            }

        override fun observeChapterMetas(bookId: Long): Flow<List<ChapterMeta>> = metasFlow
        override suspend fun getChapterMeta(chapterId: Long): ChapterMeta? =
            metasFlow.value.find { it.id == chapterId }

        override suspend fun getChapterByIndex(bookId: Long, chapterIndex: Int): Chapter? {
            val metas = metasFlow.value
            if (chapterIndex !in metas.indices) return null
            val meta = metas[chapterIndex]
            return Chapter(
                id = meta.id, bookId = bookId, chapterIndex = chapterIndex,
                title = meta.title, url = meta.url,
                content = null, contentHash = null,
                isCached = meta.isCached, isMissing = false,
                fetchedAt = null, wordCount = meta.wordCount,
            )
        }

        override suspend fun getChapterByUrl(bookId: Long, normalizedUrl: String): Chapter? = null
        override suspend fun replaceCatalog(
            bookId: Long,
            catalog: ParsedCatalog,
        ): AppResult<CatalogMergeReport> = AppResult.Failure(ReaderError.Unknown("not used"))

        override suspend fun saveContent(
            chapterId: Long,
            content: com.mvxxcb.reader.domain.model.ParsedChapterContent,
        ): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun loadContent(chapterId: Long): ReaderContent? = null
        override suspend fun deleteContent(chapterId: Long): AppResult<Unit> =
            AppResult.Success(Unit)

        override suspend fun appendChapter(
            bookId: Long,
            ref: com.mvxxcb.reader.domain.model.ChapterRef,
        ): AppResult<ChapterMeta> = AppResult.Failure(ReaderError.Unknown("not used"))
    }

    private class FakeCacheManager : ContentCacheManager {
        val prefetchCalls = mutableListOf<Int>()

        override fun cacheChapters(bookId: Long, chapterIds: List<Long>): Flow<CacheBatchEvent> =
            MutableStateFlow(CacheBatchEvent.Started(0))

        override suspend fun prefetchNeighborhood(
            bookId: Long,
            centerChapterIndex: Int,
            radius: Int,
        ): AppResult<Int> {
            prefetchCalls += centerChapterIndex
            return AppResult.Success(0)
        }

        override fun observeCacheStats(bookId: Long): Flow<CacheStats> =
            MutableStateFlow(CacheStats(0, 0, 0))

        override suspend fun clearBookCache(bookId: Long): AppResult<Unit> =
            AppResult.Success(Unit)

        override suspend fun clearAllCaches(): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun verifyBookIntegrity(bookId: Long): AppResult<IntegrityReport> =
            AppResult.Success(IntegrityReport(0, emptyList()))

        override suspend fun cancelActiveBatch(bookId: Long) = Unit
    }

    /** 单页覆盖全部块的分页假结果（模式互切测试只关心分页完成态） */
    private class FakeTextPager : TextPager {
        override fun paginate(content: ReaderContent, spec: ReaderLayoutSpec): PaginationResult {
            var chars = 0
            for (block in content.blocks) {
                chars += when (block) {
                    is ContentBlock.Paragraph -> block.text.length
                    is ContentBlock.Image -> 1
                }
            }
            return PaginationResult(
                pages = listOf(PageSpan(0, content.blocks.size, 0, chars)),
                layoutVersion = 42L,
                overflow = false,
            )
        }
    }

    private class FakeDispatcherProvider(
        private val dispatcher: CoroutineDispatcher,
    ) : DispatcherProvider {
        override val main: CoroutineDispatcher = dispatcher
        override val io: CoroutineDispatcher = dispatcher
        override val default: CoroutineDispatcher = dispatcher
    }
}
