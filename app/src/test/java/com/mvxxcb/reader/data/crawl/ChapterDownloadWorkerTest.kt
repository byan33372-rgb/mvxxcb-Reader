package com.mvxxcb.reader.data.crawl

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.mvxxcb.reader.common.coroutine.DefaultDispatcherProvider
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.data.TestSupport
import com.mvxxcb.reader.data.db.AppDatabase
import com.mvxxcb.reader.data.export.TxtExporterImpl
import com.mvxxcb.reader.data.export.TxtFilePaths
import com.mvxxcb.reader.data.repository.BookRepositoryImpl
import com.mvxxcb.reader.data.repository.ChapterRepositoryImpl
import com.mvxxcb.reader.data.repository.CrawlTaskRepositoryImpl
import com.mvxxcb.reader.data.work.ChapterDownloadWorker
import com.mvxxcb.reader.domain.cache.ChapterFetcher
import com.mvxxcb.reader.domain.error.ParseFailure
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.model.CrawlState
import com.mvxxcb.reader.domain.model.ParsedChapterContent
import com.mvxxcb.reader.domain.model.ContentBlock
import com.mvxxcb.reader.parser.core.ParseOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * ChapterDownloadWorker 激活改造单测（设计 07 / 任务书 WP-C，19 项采集功能核心路径）：
 * 逐章即时持久化、失败计数与继续（不重试不绕过）、断点续传（已缓存章不重采）、
 * 暂停标志位安全退出 + 继续从剩余清单续采、取消停止、WAITING→RUNNING 对齐、
 * 全成自动 TXT + COMPLETED、失败章 FAILED 不生成 TXT、空目录 FAILED、
 * 任务不存在 / bookId 冗余校验拒绝。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChapterDownloadWorkerTest {

    private lateinit var db: AppDatabase
    private lateinit var chapterRepo: ChapterRepositoryImpl
    private lateinit var bookRepo: BookRepositoryImpl
    private lateinit var crawlRepo: CrawlTaskRepositoryImpl
    private lateinit var exporter: TxtExporterImpl
    private val logger = TestSupport.FakeLogger()
    private val time = TestSupport.FakeTime()

    /** fetcher 调用顺序记录（串行性/不重采断言用） */
    private val fetchLog = mutableListOf<String>()

    @Before
    fun setUp() {
        db = TestSupport.buildDb()
        val merger = TestSupport.newMerger(db, logger)
        chapterRepo = ChapterRepositoryImpl(db, db.chapterDao(), merger, time, logger)
        bookRepo = BookRepositoryImpl(db, db.bookDao(), merger, time, logger)
        crawlRepo = CrawlTaskRepositoryImpl(db.crawlTaskDao(), time, logger)
        exporter = TxtExporterImpl(bookRepo, chapterRepo, DefaultDispatcherProvider(), time, logger)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ———— 脚手架 ————

    /** 脚本化假 fetcher：记录调用顺序；blockOn 非空时该 URL 挂起直到对应 Deferred 释放 */
    private fun scriptedFetcher(
        failureUrls: Set<String> = emptySet(),
        blockOn: Map<String, CompletableDeferred<Unit>> = emptyMap(),
    ): ChapterFetcher = ChapterFetcher { url ->
        fetchLog += url
        blockOn[url]?.await()
        if (url in failureUrls) {
            ParseOutcome.Failure(
                ReaderError.Parse(ParseFailure.CONTENT_EMPTY, debugDetail = "test-fail: $url"),
                layerTrace = emptyList(),
            )
        } else {
            ParseOutcome.Success(
                data = ParsedChapterContent(
                    sourceUrl = url, title = null,
                    blocks = listOf(ContentBlock.Paragraph("第X章段落，来自 $url")),
                    nextPageUrl = null, nextChapterUrl = null, previousChapterUrl = null,
                    isTruncated = false, wordCount = 0,
                ),
                overallConfidence = com.mvxxcb.reader.domain.model.Confidence.HIGH,
                layerTrace = emptyList(),
                warnings = emptyList(),
            )
        }
    }

    private fun newDeps(fetcher: ChapterFetcher): ChapterDownloadWorker.Deps =
        ChapterDownloadWorker.Deps(crawlRepo, chapterRepo, bookRepo, fetcher, exporter, localBookRepository = null, timeProvider = time, logger = logger)

    private suspend fun setupBookWithChapters(count: Int): Triple<Long, List<Long>, List<String>> {
        val bookId = TestSupport.insertBook(db, title = "测试之书")
        chapterRepo.replaceCatalog(
            bookId,
            TestSupport.catalogOf((1..count).map { i ->
                TestSupport.chapterRef(i - 1, "第${i}章", "https://www.example.com/c/$i")
            }),
        )
        val ids = db.chapterDao().getAllForBook(bookId).map { it.id }
        val urls = (1..count).map { "https://www.example.com/c/$it" }
        return Triple(bookId, ids, urls)
    }

    private suspend fun newTask(bookId: Long, total: Int): Long {
        val result = crawlRepo.createTask(bookId, total)
        assertTrue(result is AppResult.Success)
        return (result as AppResult.Success).value.id
    }

    private fun buildWorker(taskId: Long, bookId: Long, deps: ChapterDownloadWorker.Deps): ChapterDownloadWorker =
        TestListenableWorkerBuilder<ChapterDownloadWorker>(TestSupport.appContext())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = ChapterDownloadWorker(appContext, workerParameters, deps)
            })
            .setInputData(ChapterDownloadWorker.buildInputData(taskId, bookId))
            .build()

    private fun txtFileOf(bookId: Long): File =
        TxtFilePaths.txtFileFor(TestSupport.appContext().filesDir, bookId, "测试之书")

    // ———— 成功主路径 ————

    @Test
    fun `成功采集_逐章即时持久化_串行升序_终态COMPLETED_自动TXT`() = runBlocking {
        val (bookId, ids, urls) = setupBookWithChapters(3)
        val taskId = newTask(bookId, total = 3)

        // 抓第 2 章时第 1 章必须已落库（即时持久化，不等批次完成）
        var ch1CachedWhenCh2Fetched: Boolean? = null
        val fetcher = ChapterFetcher { url ->
            fetchLog += url
            if (url == urls[1]) ch1CachedWhenCh2Fetched = db.chapterDao().getById(ids[0])?.isCached
            ParseOutcome.Success(
                data = ParsedChapterContent(
                    sourceUrl = url, title = null,
                    blocks = listOf(ContentBlock.Paragraph("段落 $url")),
                    nextPageUrl = null, nextChapterUrl = null, previousChapterUrl = null,
                    isTruncated = false, wordCount = 0,
                ),
                overallConfidence = com.mvxxcb.reader.domain.model.Confidence.HIGH,
                layerTrace = emptyList(), warnings = emptyList(),
            )
        }

        val result = buildWorker(taskId, bookId, newDeps(fetcher)).doWork()

        assertTrue("Worker 应成功结束：$result", result is ListenableWorker.Result.Success)
        assertEquals(listOf(urls[0], urls[1], urls[2]), fetchLog.toList()) // 串行升序，无并行无重复
        assertEquals(true, ch1CachedWhenCh2Fetched)
        (1..3).forEach { i -> assertTrue("第${i}章应即时缓存", db.chapterDao().getById(ids[i - 1])!!.isCached) }

        val task = crawlRepo.observeTask(taskId).first()!!
        assertEquals(CrawlState.COMPLETED, task.state)
        assertEquals(3, task.succeededCount)
        assertEquals(0, task.failedCount)
        assertNotNull("WAITING→RUNNING 应落 startedAt", task.startedAt)
        assertNotNull("COMPLETED 应落 finishedAt", task.finishedAt)
        assertTrue("COMPLETED 自动生成 TXT（07 Q3）", task.txtGenerated)
        assertNotNull(task.txtPath)
        val txtFile = File(task.txtPath!!)
        assertTrue(txtFile.exists())
        assertEquals(txtFileOf(bookId).absolutePath, txtFile.absolutePath)
        assertTrue(txtFile.readText(Charsets.UTF_8).contains("第1章"))
    }

    @Test
    fun `失败章计数并继续_终态FAILED_不生成TXT`() = runBlocking {
        val (bookId, ids, urls) = setupBookWithChapters(3)
        val taskId = newTask(bookId, total = 3)

        val result = buildWorker(taskId, bookId, newDeps(scriptedFetcher(failureUrls = setOf(urls[1])))).doWork()

        // 失败章记录后继续采集（不中断批次、不重试该章）
        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(listOf(urls[0], urls[1], urls[2]), fetchLog.toList())
        assertTrue(db.chapterDao().getById(ids[0])!!.isCached)
        assertFalse(db.chapterDao().getById(ids[1])!!.isCached)
        assertTrue(db.chapterDao().getById(ids[2])!!.isCached)

        val task = crawlRepo.observeTask(taskId).first()!!
        assertEquals(CrawlState.FAILED, task.state)
        assertEquals(2, task.succeededCount)
        assertEquals(1, task.failedCount)
        assertFalse("FAILED 任务不生成 TXT（07 Q3）", task.txtGenerated)
        assertNull(task.txtPath)
        assertFalse(txtFileOf(bookId).exists())
    }

    // ———— 断点续传 / 已下载章不重采 ————

    @Test
    fun `断点续传_预置已缓存章不重采_只抓剩余`() = runBlocking {
        val (bookId, ids, urls) = setupBookWithChapters(3)
        // 预置第 1 章已缓存（上一次任务/上一段运行的成果）
        chapterRepo.saveContent(
            ids[0],
            ParsedChapterContent(
                sourceUrl = urls[0], title = null,
                blocks = listOf(ContentBlock.Paragraph("上次已采段落")),
                nextPageUrl = null, nextChapterUrl = null, previousChapterUrl = null,
                isTruncated = false, wordCount = 0,
            ),
        )
        val taskId = newTask(bookId, total = 3)

        val result = buildWorker(taskId, bookId, newDeps(scriptedFetcher())).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("已缓存章不得重采", listOf(urls[1], urls[2]), fetchLog.toList())
        assertTrue(db.chapterDao().getById(ids[0])!!.isCached)
        val task = crawlRepo.observeTask(taskId).first()!!
        assertEquals(CrawlState.COMPLETED, task.state)
        assertEquals(3, task.succeededCount) // 计数含预置缓存章
        assertEquals(0, task.failedCount)
        assertTrue(task.txtGenerated)
    }

    @Test
    fun `失败章重试路径_FAILED到WAITING后重跑清单只抓失败章`() = runBlocking {
        val (bookId, ids, urls) = setupBookWithChapters(3)
        val taskId = newTask(bookId, total = 3)
        // 第一轮：第 2 章失败
        assertTrue(
            buildWorker(taskId, bookId, newDeps(scriptedFetcher(failureUrls = setOf(urls[1])))).doWork()
                is ListenableWorker.Result.Success,
        )
        assertEquals(CrawlState.FAILED, crawlRepo.observeTask(taskId).first()!!.state)
        // 用户重试失败章节：FAILED→WAITING（合法转移），重跑派生清单
        assertTrue(crawlRepo.updateState(taskId, CrawlState.WAITING) is AppResult.Success)
        // 第二轮：全部成功
        assertTrue(buildWorker(taskId, bookId, newDeps(scriptedFetcher())).doWork() is ListenableWorker.Result.Success)
        // 第一轮成功缓存 c1/c3、仅 c2 失败；重试轮派生清单只剩 c2 → 只重抓失败章（已缓存不重采）
        assertEquals(listOf(urls[0], urls[1], urls[2], urls[1]), fetchLog.toList())
        val task = crawlRepo.observeTask(taskId).first()!!
        assertEquals(CrawlState.COMPLETED, task.state)
        assertEquals(3, task.succeededCount)
        assertEquals(0, task.failedCount)
        assertTrue(task.txtGenerated)
    }

    // ———— 暂停 / 取消 ————

    @Test
    fun `暂停标志位_章间安全退出_继续后从剩余清单续采`() = runBlocking {
        val (bookId, ids, urls) = setupBookWithChapters(3)
        val taskId = newTask(bookId, total = 3)
        val ch2Gate = CompletableDeferred<Unit>()
        val deps = newDeps(scriptedFetcher(blockOn = mapOf(urls[1] to ch2Gate)))

        withTimeout(10_000) {
            val job = launch { buildWorker(taskId, bookId, deps).doWork() }
            // 等第 2 章进入抓取，然后置 PAUSED（RUNNING→PAUSED 合法）
            while (!fetchLog.contains(urls[1])) kotlinx.coroutines.yield()
            assertTrue(crawlRepo.updateState(taskId, CrawlState.PAUSED) is AppResult.Success)
            ch2Gate.complete(Unit) // 第 2 章抓完（含即时持久化）
            job.join()
        }

        // 第 2 章已缓存（抓取先于暂停感知），第 3 章未抓；状态停在 PAUSED，Worker 不回写
        assertEquals(listOf(urls[0], urls[1]), fetchLog.toList())
        assertTrue(db.chapterDao().getById(ids[0])!!.isCached)
        assertTrue(db.chapterDao().getById(ids[1])!!.isCached)
        assertFalse(db.chapterDao().getById(ids[2])!!.isCached)
        assertEquals(CrawlState.PAUSED, crawlRepo.observeTask(taskId).first()!!.state)

        // 继续：PAUSED→RUNNING（门面 resumeCrawl），Worker 重新入队，从 DB 派生剩余清单
        assertTrue(crawlRepo.updateState(taskId, CrawlState.RUNNING) is AppResult.Success)
        assertTrue(buildWorker(taskId, bookId, newDeps(scriptedFetcher())).doWork() is ListenableWorker.Result.Success)
        assertEquals("续采只抓剩余章", listOf(urls[0], urls[1], urls[2]), fetchLog.toList())
        val task = crawlRepo.observeTask(taskId).first()!!
        assertEquals(CrawlState.COMPLETED, task.state)
        assertTrue(task.txtGenerated)
    }

    @Test
    fun `取消_停止续采_状态保持CANCELLED且Worker不回写`() = runBlocking {
        val (bookId, ids, urls) = setupBookWithChapters(3)
        val taskId = newTask(bookId, total = 3)
        val ch2Gate = CompletableDeferred<Unit>()
        val deps = newDeps(scriptedFetcher(blockOn = mapOf(urls[1] to ch2Gate)))

        withTimeout(10_000) {
            val job = launch { buildWorker(taskId, bookId, deps).doWork() }
            while (!fetchLog.contains(urls[1])) kotlinx.coroutines.yield()
            // 门面 cancelCrawl：先置 CANCELLED（RUNNING→CANCELLED 合法），再 cancelUniqueWork
            assertTrue(crawlRepo.updateState(taskId, CrawlState.CANCELLED) is AppResult.Success)
            ch2Gate.complete(Unit)
            job.join()
        }

        assertEquals(listOf(urls[0], urls[1]), fetchLog.toList())
        assertTrue(db.chapterDao().getById(ids[0])!!.isCached)
        assertFalse("取消后不得续抓", db.chapterDao().getById(ids[2])!!.isCached)
        assertEquals(CrawlState.CANCELLED, crawlRepo.observeTask(taskId).first()!!.state)
        // 终态后进度回写被仓储拒绝（INVALID_TASK_STATE），Worker 仅容忍不崩溃
    }

    @Test
    fun `任务预先PAUSED或终态时Worker无操作安全退出`() = runBlocking {
        val (bookId, _, urls) = setupBookWithChapters(2)
        val pausedTaskId = newTask(bookId, total = 2)
        assertTrue(crawlRepo.updateState(pausedTaskId, CrawlState.RUNNING) is AppResult.Success)
        assertTrue(crawlRepo.updateState(pausedTaskId, CrawlState.PAUSED) is AppResult.Success)
        assertTrue(buildWorker(pausedTaskId, bookId, newDeps(scriptedFetcher())).doWork() is ListenableWorker.Result.Success)
        assertTrue("PAUSED 任务不得抓取", fetchLog.isEmpty())

        val cancelledBookId = TestSupport.insertBook(db, title = "另一本", url = "https://www.example.com/b2")
        chapterRepo.replaceCatalog(
            cancelledBookId,
            TestSupport.catalogOf((1..2).map { i ->
                TestSupport.chapterRef(i - 1, "第${i}章", "https://www.example.com/b2/$i")
            }),
        )
        val cancelledTaskId = newTask(cancelledBookId, total = 2)
        assertTrue(crawlRepo.updateState(cancelledTaskId, CrawlState.CANCELLED) is AppResult.Success)
        assertTrue(buildWorker(cancelledTaskId, cancelledBookId, newDeps(scriptedFetcher())).doWork() is ListenableWorker.Result.Success)
        assertEquals("终态任务不得抓取", 0, fetchLog.size)
    }

    // ———— 空目录 / 不存在 / 冗余校验 ————

    @Test
    fun `空目录任务_置FAILED且不生成TXT`() = runBlocking {
        val bookId = TestSupport.insertBook(db, title = "空书", url = "https://www.example.com/empty")
        val taskId = newTask(bookId, total = 0)

        val result = buildWorker(taskId, bookId, newDeps(scriptedFetcher())).doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(CrawlState.FAILED, crawlRepo.observeTask(taskId).first()!!.state)
        assertFalse(txtFileOf(bookId).exists())
    }

    @Test
    fun `任务不存在返回failure`() = runBlocking {
        val result = buildWorker(999_999L, 1L, newDeps(scriptedFetcher())).doWork()
        assertTrue(result is ListenableWorker.Result.Failure)
    }

    @Test
    fun `bookId冗余校验不匹配拒绝且不触碰任务状态`() = runBlocking {
        val bookId = TestSupport.insertBook(db, title = "正确书", url = "https://www.example.com/right")
        chapterRepo.replaceCatalog(
            bookId,
            TestSupport.catalogOf(listOf(TestSupport.chapterRef(0, "第1章", "https://www.example.com/right/1"))),
        )
        val taskId = newTask(bookId, total = 1)
        // 投递了别的 bookId
        val worker = TestListenableWorkerBuilder<ChapterDownloadWorker>(TestSupport.appContext())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = ChapterDownloadWorker(appContext, workerParameters, newDeps(scriptedFetcher()))
            })
            .setInputData(ChapterDownloadWorker.buildInputData(taskId, bookId = bookId + 1))
            .build()

        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Failure)
        assertEquals("状态不得被触碰", CrawlState.WAITING, crawlRepo.observeTask(taskId).first()!!.state)
        assertTrue(fetchLog.isEmpty())
    }
}
