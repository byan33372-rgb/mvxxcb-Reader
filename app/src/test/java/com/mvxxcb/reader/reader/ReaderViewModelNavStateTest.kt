package com.mvxxcb.reader.reader

// WP-U 回归单测（阶段 7 P2 缺陷修复验证，纯 JVM + 假实现，无需 Robolectric）：
// 缺陷：hasPrevious/hasNext 仅在分页成功后的 updatePosition 里重算；连续章节加载失败
//       （Failed 态，无分页）时残留旧值，导致「上一章」按钮 enabled=false（stale），
//       用户卡死在错误页。修复：switchChapter / Failed 分支即时重算导航标志。
// 覆盖：Failed→上一章（核心回归）、Failed→下一章（加载失败继续显示错误态）、
//       Failed→目录跳转 jumpToChapter、末章边界 hasNext=false。

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
import com.mvxxcb.reader.domain.model.ReadingProgress
import com.mvxxcb.reader.domain.repository.BookmarkRepository
import com.mvxxcb.reader.domain.repository.ChapterRepository
import com.mvxxcb.reader.domain.repository.ReaderSettingsRepository
import com.mvxxcb.reader.domain.repository.ReadingProgressRepository
import com.mvxxcb.reader.domain.service.ReadingService
import com.mvxxcb.reader.presentation.reader.ReaderContentState
import com.mvxxcb.reader.presentation.reader.ReaderViewModel
import com.mvxxcb.reader.reader.engine.PageSpan
import com.mvxxcb.reader.reader.engine.PaginationResult
import com.mvxxcb.reader.reader.engine.ReaderLayoutSpec
import com.mvxxcb.reader.reader.engine.TextPager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelNavStateTest {

    private val testDispatcher = StandardTestDispatcher()

    private val bookId = 1L
    private val totalChapters = 8

    /** 仅这些章节已缓存；其余离线加载失败（Network.OFFLINE，可重试） */
    private val cachedChapters = setOf(0, 3)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ———— 被测辅助 ————

    private fun TestScope.createViewModel(): ReaderViewModel = ReaderViewModel(
        bookId = bookId,
        initialChapterIndex = 0,
        readingService = FakeReadingService(),
        settingsRepository = FakeReaderSettingsRepository(),
        progressRepository = FakeProgressRepository(),
        bookmarkRepository = FakeBookmarkRepository(),
        chapterRepository = FakeChapterRepository(),
        cacheManager = FakeCacheManager(),
        textPager = FakeTextPager(),
        dispatchers = FakeDispatcherProvider(testDispatcher),
        bookRepository = null,
    )

    private fun TestScope.newViewModelAndReady(): ReaderViewModel {
        val vm = createViewModel()
        advanceUntilIdle()
        // 上报视口 → 首章分页成功 → updatePosition 落定导航标志（第 0 章 → hasPrevious=false）
        vm.onViewportChanged(widthPx = 800, heightPx = 1200, density = 2f, fontScale = 1f)
        advanceUntilIdle()
        val state = vm.uiState.value
        assertTrue("前置条件：第 0 章应为 Ready", state.contentState is ReaderContentState.Ready)
        assertEquals(0, state.chapterIndex)
        assertFalse("前置条件：第 0 章无上一章", state.hasPrevious)
        return vm
    }

    // ———— 1. 核心回归：Failed→上一章 可导航 ————

    @Test
    fun `failed chapter keeps previous chapter navigation available`() = runTest(testDispatcher) {
        val vm = newViewModelAndReady()

        // 下一章（未缓存，离线）→ Failed
        vm.goToNextChapter()
        advanceUntilIdle()
        val failed = vm.uiState.value.contentState as ReaderContentState.Failed
        assertEquals(1, vm.uiState.value.chapterIndex)
        assertTrue("Network.OFFLINE 应可重试（canRetry 语义独立于导航）", failed.canRetry)
        assertFalse("未缓存章 cachedAvailable=false", failed.cachedAvailable)

        // 修复点：switchChapter 后导航标志必须与当前章一致（修复前 hasPrevious 残留 false）
        assertTrue("Failed 态 hasPrevious 必须为 true（stale 标志缺陷回归）", vm.uiState.value.hasPrevious)
        assertTrue(vm.uiState.value.hasNext)

        // 上一章 → 回到已缓存第 0 章 → Ready（修复前按钮 enabled=false 用户无响应）
        vm.goToPreviousChapter()
        advanceUntilIdle()
        val restored = vm.uiState.value
        assertTrue("上一章应成功加载并回到 Ready", restored.contentState is ReaderContentState.Ready)
        assertEquals(0, restored.chapterIndex)
        assertFalse("回到第 0 章后 hasPrevious 应为 false", restored.hasPrevious)
    }

    // ———— 2. Failed→下一章：加载失败继续显示错误态，可继续前进 ————

    @Test
    fun `failed chapter allows next chapter navigation with error continuity`() = runTest(testDispatcher) {
        val vm = newViewModelAndReady()

        vm.goToNextChapter() // 第 1 章：未缓存 → Failed
        advanceUntilIdle()
        assertTrue(vm.uiState.value.contentState is ReaderContentState.Failed)

        vm.goToNextChapter() // 第 2 章：仍未缓存 → 继续显示错误态
        advanceUntilIdle()
        val failed2 = vm.uiState.value.contentState
        assertTrue("加载失败应继续显示错误态", failed2 is ReaderContentState.Failed)
        assertEquals(2, vm.uiState.value.chapterIndex)
        assertTrue(vm.uiState.value.hasPrevious)

        vm.goToNextChapter() // 第 3 章：已缓存 → Ready
        advanceUntilIdle()
        val ready = vm.uiState.value
        assertTrue("前进到已缓存章应恢复 Ready", ready.contentState is ReaderContentState.Ready)
        assertEquals(3, ready.chapterIndex)
        assertTrue(ready.hasPrevious)
    }

    // ———— 3. Failed→目录跳转 jumpToChapter 同病同修 ————

    @Test
    fun `failed chapter allows catalog jump`() = runTest(testDispatcher) {
        val vm = newViewModelAndReady()

        vm.goToNextChapter()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.contentState is ReaderContentState.Failed)

        vm.jumpToChapter(0) // 目录跳转回第 0 章
        advanceUntilIdle()
        assertTrue("Failed 态目录跳转应可用", vm.uiState.value.contentState is ReaderContentState.Ready)
        assertEquals(0, vm.uiState.value.chapterIndex)
    }

    // ———— 4. 末章边界：hasNext=false 且下一章无效 ————

    @Test
    fun `last chapter sets hasNext false and ignores next`() = runTest(testDispatcher) {
        val vm = newViewModelAndReady()

        repeat(7) { vm.goToNextChapter() } // 0 → 7（途中 1/2/4/5/6 失败为 Failed，3 成功）
        advanceUntilIdle()
        assertEquals(7, vm.uiState.value.chapterIndex)
        assertFalse("末章 hasNext 必须为 false", vm.uiState.value.hasNext)
        assertTrue(vm.uiState.value.hasPrevious)

        vm.goToNextChapter() // 末章再点下一章：无副作用
        advanceUntilIdle()
        assertEquals("末章不应越界", 7, vm.uiState.value.chapterIndex)
    }

    // ———— 假实现 ————

    private inner class FakeReadingService : ReadingService {
        override suspend fun loadChapter(
            bookId: Long,
            chapterIndex: Int,
            preferCache: Boolean,
        ): AppResult<ReaderContent> {
            if (chapterIndex !in 0 until totalChapters) {
                return AppResult.Failure(
                    ReaderError.State(
                        com.mvxxcb.reader.domain.error.StateFailure.CHAPTER_NOT_FOUND,
                    ),
                )
            }
            if (chapterIndex in cachedChapters) {
                return AppResult.Success(fakeContent(chapterIndex))
            }
            return AppResult.Failure(
                ReaderError.Network(
                    failure = NetworkFailure.OFFLINE,
                    url = null,
                    statusCode = null,
                ),
            )
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
    }

    private fun fakeContent(chapterIndex: Int): ReaderContent = ReaderContent(
        chapterId = chapterIndex + 1L,
        chapterIndex = chapterIndex,
        bookId = bookId,
        title = "第${chapterIndex + 1}章",
        blocks = listOf(ContentBlock.Paragraph("第${chapterIndex + 1}章正文内容。")),
    )

    private class FakeReaderSettingsRepository : ReaderSettingsRepository {
        override val settings: Flow<ReaderSettings> = MutableStateFlow(ReaderSettings())
        override suspend fun current(): ReaderSettings = ReaderSettings()
        override suspend fun update(
            transform: (ReaderSettings) -> ReaderSettings,
        ): AppResult<Unit> = AppResult.Success(Unit)
    }

    private class FakeProgressRepository : ReadingProgressRepository {
        override fun observeProgress(bookId: Long): Flow<ReadingProgress?> = flowOf(null)
        override suspend fun getProgress(bookId: Long): ReadingProgress? = null
        override suspend fun saveProgress(progress: ReadingProgress): AppResult<Unit> =
            AppResult.Success(Unit)

        override suspend fun clearProgress(bookId: Long): AppResult<Unit> = AppResult.Success(Unit)
    }

    private class FakeBookmarkRepository : BookmarkRepository {
        override fun observeBookmarks(bookId: Long): Flow<List<Bookmark>> = flowOf(emptyList())
        override suspend fun addBookmark(bookmark: Bookmark): AppResult<Long> =
            AppResult.Success(1L)

        override suspend fun removeBookmark(bookmarkId: Long): AppResult<Unit> =
            AppResult.Success(Unit)
    }

    private inner class FakeChapterRepository : ChapterRepository {
        private val metas = (0 until totalChapters).map { index ->
            ChapterMeta(
                id = index + 1L,
                bookId = bookId,
                chapterIndex = index,
                title = "第${index + 1}章",
                url = "https://example.com/ch${index + 1}",
                isCached = index in cachedChapters,
                isMissing = false,
                wordCount = 100,
                fetchedAt = null,
            )
        }

        override fun observeChapterMetas(bookId: Long): Flow<List<ChapterMeta>> = flowOf(metas)
        override suspend fun getChapterMeta(chapterId: Long): ChapterMeta? =
            metas.find { it.id == chapterId }

        override suspend fun getChapterByIndex(bookId: Long, chapterIndex: Int): Chapter? {
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
        override fun cacheChapters(bookId: Long, chapterIds: List<Long>): Flow<CacheBatchEvent> =
            flowOf()

        override suspend fun prefetchNeighborhood(
            bookId: Long,
            centerChapterIndex: Int,
            radius: Int,
        ): AppResult<Int> = AppResult.Success(0)

        override fun observeCacheStats(bookId: Long): Flow<CacheStats> =
            flowOf(CacheStats(0, 0, 0))

        override suspend fun clearBookCache(bookId: Long): AppResult<Unit> =
            AppResult.Success(Unit)

        override suspend fun clearAllCaches(): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun verifyBookIntegrity(bookId: Long): AppResult<IntegrityReport> =
            AppResult.Success(IntegrityReport(0, emptyList()))

        override suspend fun cancelActiveBatch(bookId: Long) = Unit
    }

    /** 单页覆盖全部块的分页假结果（导航测试不关心页内容） */
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
