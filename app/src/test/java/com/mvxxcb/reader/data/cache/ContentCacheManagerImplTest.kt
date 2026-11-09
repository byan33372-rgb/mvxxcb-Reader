package com.mvxxcb.reader.data.cache

import com.mvxxcb.reader.data.TestSupport
import com.mvxxcb.reader.data.repository.CatalogMerger
import com.mvxxcb.reader.data.repository.ChapterRepositoryImpl
import com.mvxxcb.reader.common.coroutine.DefaultDispatcherProvider
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.domain.cache.CacheBatchEvent
import com.mvxxcb.reader.domain.cache.ChapterFetcher
import com.mvxxcb.reader.domain.error.NetworkFailure
import com.mvxxcb.reader.domain.error.ParseFailure
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StateFailure
import com.mvxxcb.reader.domain.model.ContentBlock
import com.mvxxcb.reader.domain.model.ParsedChapterContent
import com.mvxxcb.reader.parser.core.ParseOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ContentCacheManagerImpl 单测（DoD 04 §3.5 / 契约 02 §7.1）：
 * 批量事件流顺序（Started→(Progress→ChapterFinished)*→Completed）、取消即时、
 * 完整性校验（篡改样本报 corrupted）、预取与统计。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContentCacheManagerImplTest {

    private lateinit var db: com.mvxxcb.reader.data.db.AppDatabase
    private lateinit var chapterRepo: ChapterRepositoryImpl
    private lateinit var manager: ContentCacheManagerImpl
    private val logger = TestSupport.FakeLogger()
    private val time = TestSupport.FakeTime()

    @Before
    fun setUp() {
        db = TestSupport.buildDb()
        val merger = TestSupport.newMerger(db, logger)
        chapterRepo = ChapterRepositoryImpl(db, db.chapterDao(), merger, time, logger)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 成功解析固定内容；failureUrls 中的 URL 返回解析失败 */
    private fun successFetcher(failureUrls: Set<String> = emptySet()): ChapterFetcher =
        ChapterFetcher { url ->
            if (url in failureUrls) {
                ParseOutcome.Failure(
                    ReaderError.Parse(ParseFailure.CONTENT_EMPTY, debugDetail = "test: $url"),
                    layerTrace = emptyList(),
                )
            } else {
                ParseOutcome.Success(
                    data = ParsedChapterContent(
                        sourceUrl = url,
                        title = null,
                        blocks = listOf(
                            ContentBlock.Paragraph("第一段落，用于 $url"),
                            ContentBlock.Image("https://www.example.com/pic.jpg", null),
                            ContentBlock.Paragraph("第二段落"),
                        ),
                        nextPageUrl = null, nextChapterUrl = null, previousChapterUrl = null,
                        isTruncated = false, wordCount = 0,
                    ),
                    overallConfidence = com.mvxxcb.reader.domain.model.Confidence.HIGH,
                    layerTrace = emptyList(),
                    warnings = emptyList(),
                )
            }
        }

    private suspend fun setupBookWithChapters(count: Int): Pair<Long, List<Long>> {
        val bookId = TestSupport.insertBook(db)
        chapterRepo.replaceCatalog(
            bookId,
            TestSupport.catalogOf((1..count).map { i ->
                TestSupport.chapterRef(i - 1, "第${i}章", "https://www.example.com/c/$i")
            }),
        )
        val ids = db.chapterDao().getAllForBook(bookId).map { it.id }
        return bookId to ids
    }

    private fun newManager(fetcher: ChapterFetcher): ContentCacheManagerImpl =
        ContentCacheManagerImpl(chapterRepo, db.chapterDao(), fetcher, DefaultDispatcherProvider(), logger)

    // ———— 事件流顺序 ————

    @Test
    fun `批量缓存事件流顺序正确且全部成功`() = runBlocking {
        val (bookId, ids) = setupBookWithChapters(3)
        manager = newManager(successFetcher())

        val events = mutableListOf<CacheBatchEvent>()
        val job = launch { manager.cacheChapters(bookId, ids).collect { events += it } }
        job.join()
        assertFalse(job.isCancelled)

        // 顺序：Started → (Progress → ChapterFinished)* → Completed
        assertTrue(events.first() is CacheBatchEvent.Started)
        assertEquals(3, (events.first() as CacheBatchEvent.Started).total)
        assertTrue(events.last() is CacheBatchEvent.Completed)
        val report = (events.last() as CacheBatchEvent.Completed).report
        assertEquals(3, report.succeeded)
        assertEquals(0, report.failed)
        assertFalse(report.cancelled)

        var cursor = 1
        for ((i, chapterId) in ids.withIndex()) {
            val progress = events[cursor++] as CacheBatchEvent.Progress
            assertEquals(i, progress.done)
            assertEquals(3, progress.total)
            assertEquals("第${i + 1}章", progress.currentTitle)
            val finished = events[cursor++] as CacheBatchEvent.ChapterFinished
            assertEquals(chapterId, finished.chapterId)
            assertTrue(finished.success)
        }
        assertEquals(events.size, cursor + 1)

        // 落库验证：isCached=1，wordCount 由 data 层按段落字符重算（不含图片、不含连接符 \n）
        val rows = db.chapterDao().getAllForBook(bookId)
        assertTrue(rows.all { it.isCached })
        val p1 = "第一段落，用于 https://www.example.com/c/1"
        val p2 = "第二段落"
        assertEquals(p1.length + p2.length, rows[0].wordCount)
    }

    @Test
    fun `章节解析失败与章节不存在分别上报错误`() = runBlocking {
        val (bookId, ids) = setupBookWithChapters(3)
        // 第一章 URL 解析失败；第二章 id 不存在（从目录删除后传入旧 id）
        manager = newManager(successFetcher(failureUrls = setOf("https://www.example.com/c/1")))
        val bogusId = ids[1] + 10_000

        val events = mutableListOf<CacheBatchEvent>()
        launch { manager.cacheChapters(bookId, ids.take(1) + bogusId + listOf(ids[2])).collect { events += it } }
            .join()

        val finished = events.filterIsInstance<CacheBatchEvent.ChapterFinished>()
        assertEquals(3, finished.size)
        assertFalse(finished[0].success)
        assertEquals(ParseFailure.CONTENT_EMPTY, (finished[0].error as ReaderError.Parse).failure)
        assertFalse(finished[1].success)
        assertEquals(StateFailure.CHAPTER_NOT_FOUND, (finished[1].error as ReaderError.State).failure)
        assertTrue(finished[2].success)

        val report = (events.last() as CacheBatchEvent.Completed).report
        assertEquals(1, report.succeeded)
        assertEquals(2, report.failed)
        assertEquals(2, report.failures.size)
    }

    // ———— 取消即时 ————

    @Test
    fun `cancelActiveBatch即时中断批次`() = runBlocking {
        val (bookId, ids) = setupBookWithChapters(3)
        val secondChapterStarted = CompletableDeferred<Unit>()
        val fetcher = ChapterFetcher { url ->
            if (url.endsWith("/c/2")) {
                secondChapterStarted.complete(Unit)
                awaitCancellation() // 模拟长请求
            } else {
                successFetcher().fetch(url)
            }
        }
        manager = newManager(fetcher)

        val events = mutableListOf<CacheBatchEvent>()
        val collector = launch { manager.cacheChapters(bookId, ids).collect { events += it } }

        // 等到第一章完成、第二章抓取挂起
        withTimeout(10_000) { secondChapterStarted.await() }
        while (events.filterIsInstance<CacheBatchEvent.ChapterFinished>().size < 1) {
            delay(10)
        }

        // 取消 → 事件流即时收尾（生产者协程被取消）
        manager.cancelActiveBatch(bookId)
        withTimeout(10_000) { collector.join() }

        // 第二章没有 ChapterFinished（抓取被中断）
        val finished = events.filterIsInstance<CacheBatchEvent.ChapterFinished>()
        assertEquals(1, finished.size)
        assertEquals(ids[0], finished[0].chapterId)
        // 收尾：取消报告（尽力送达）或流以中断处关闭；
        // 取消落点可能在第二章 Progress 之后（契约顺序内合法），故最后事件允许 Progress
        val last = events.lastOrNull()
        assertFalse(last is CacheBatchEvent.ChapterFinished && last.chapterId == ids[1])
        (last as? CacheBatchEvent.Completed)?.let { assertTrue(it.report.cancelled) }

        // 二次取消无副作用
        manager.cancelActiveBatch(bookId)
    }

    // ———— 预取 ————

    @Test
    fun `prefetchNeighborhood串行预取未缓存邻章`() = runBlocking {
        val (bookId, ids) = setupBookWithChapters(5)
        // 缓存中心章（index 2）
        chapterRepo.saveContent(
            ids[2],
            ParsedChapterContent(
                sourceUrl = "https://www.example.com/c/3", title = null,
                blocks = listOf(ContentBlock.Paragraph("中心章")), nextPageUrl = null,
                nextChapterUrl = null, previousChapterUrl = null, isTruncated = false, wordCount = 4,
            ),
        )
        manager = newManager(successFetcher())

        val result = manager.prefetchNeighborhood(bookId, centerChapterIndex = 2, radius = 1)
        val okCount = (result as AppResult.Success).value
        assertEquals(2, okCount) // index 1 与 3 被预取
        val rows = db.chapterDao().getAllForBook(bookId)
        assertEquals(listOf(false, true, true, true, false), rows.map { it.isCached })
    }

    // ———— 统计 / 清理 ————

    @Test
    fun `observeCacheStats与clearBookCache`() = runBlocking {
        val (bookId, ids) = setupBookWithChapters(2)
        manager = newManager(successFetcher())
        launch { manager.cacheChapters(bookId, listOf(ids[0])).collect {} }.join()

        val stats = manager.observeCacheStats(bookId).first()
        assertEquals(1, stats.cachedCount)
        assertEquals(2, stats.totalCount)
        assertTrue(stats.totalChars > 0)

        assertTrue(manager.clearBookCache(bookId) is AppResult.Success)
        val cleared = manager.observeCacheStats(bookId).first()
        assertEquals(0, cleared.cachedCount)
        assertEquals(0L, cleared.totalChars)
        assertEquals(2, cleared.totalCount) // 目录保留，仅清正文
    }

    // ———— 完整性校验 ————

    @Test
    fun `verifyBookIntegrity篡改样本报corrupted`() = runBlocking {
        val (bookId, ids) = setupBookWithChapters(2)
        manager = newManager(successFetcher())
        launch { manager.cacheChapters(bookId, ids).collect {} }.join()

        // 未篡改：全部通过
        val clean = (manager.verifyBookIntegrity(bookId) as AppResult.Success).value
        assertEquals(2, clean.checked)
        assertTrue(clean.corruptedChapterIds.isEmpty())

        // 篡改：改写第一章正文 JSON 但保留原 contentHash
        val row = db.chapterDao().getById(ids[0])!!
        db.chapterDao().saveContent(
            chapterId = ids[0],
            json = com.mvxxcb.reader.data.mapper.ContentBlockCodec.blocksToJson(
                listOf(ContentBlock.Paragraph("被篡改的内容"))
            ),
            hash = row.contentHash, // 原哈希不变 → 重算不匹配
            words = row.wordCount,
            now = time.nowMillis(),
        )
        val report = (manager.verifyBookIntegrity(bookId) as AppResult.Success).value
        assertEquals(2, report.checked)
        assertEquals(listOf(ids[0]), report.corruptedChapterIds)
    }
}
