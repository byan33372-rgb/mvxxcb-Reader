package com.mvxxcb.reader.presentation.crawl

// WP-L（07 §6.2 / B3）：采集中心 VM 假实现测试（纯 JVM，模式参照 ReaderViewModelContinuousTest）。
// 覆盖：列表组合（任务×书联表/未知书兜底/顺序保持/操作可用性按 K9）、
//       操作转发（K10 pause/resume/cancel/retryFailures）、清除记录（终态/非终态）、
//       错误态（列表流异常/操作失败→actionError/clearActionError）、openBook 事件。

import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StateFailure
import com.mvxxcb.reader.domain.model.Book
import com.mvxxcb.reader.domain.model.BookUpsertResult
import com.mvxxcb.reader.domain.model.CatalogMergeReport
import com.mvxxcb.reader.domain.model.CrawlState
import com.mvxxcb.reader.domain.model.CrawlTask
import com.mvxxcb.reader.domain.model.ParsedBookDetail
import com.mvxxcb.reader.domain.model.ReaderContent
import com.mvxxcb.reader.domain.repository.BookRepository
import com.mvxxcb.reader.domain.repository.CrawlTaskRepository
import com.mvxxcb.reader.domain.service.ReadingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CrawlCenterViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ———— 假体 ————

    private class FakeCrawlTaskRepository : CrawlTaskRepository {
        val flow = MutableStateFlow<List<CrawlTask>>(emptyList())

        override fun observeTasks(): Flow<List<CrawlTask>> = flow

        override fun observeTask(taskId: Long): Flow<CrawlTask?> =
            flow.map { list -> list.find { it.id == taskId } }

        override suspend fun getActiveTaskForBook(bookId: Long): CrawlTask? =
            flow.value.firstOrNull { it.bookId == bookId && !it.state.isTerminal }

        override suspend fun createTask(bookId: Long, totalChapters: Int): AppResult<CrawlTask> {
            val now = flow.value.size + 1L
            val task = CrawlTask(
                id = now, bookId = bookId, state = CrawlState.WAITING,
                totalChapters = totalChapters, succeededCount = 0, failedCount = 0,
                currentChapterIndex = 0, currentChapterTitle = null,
                txtPath = null, txtGenerated = false,
                createdAt = now, updatedAt = now, startedAt = null, finishedAt = null,
            )
            flow.value = flow.value + task
            return AppResult.Success(task)
        }

        override suspend fun updateState(taskId: Long, state: CrawlState): AppResult<Unit> {
            val current = flow.value.find { it.id == taskId }
                ?: return AppResult.Failure(
                    ReaderError.State(StateFailure.CRAWL_TASK_NOT_FOUND, "taskId=$taskId"),
                )
            flow.value = flow.value.map {
                if (it.id == taskId) it.copy(state = state, updatedAt = it.updatedAt + 1) else it
            }
            return AppResult.Success(Unit)
        }

        override suspend fun updateProgress(
            taskId: Long, succeeded: Int, failed: Int, currentIndex: Int, currentTitle: String?,
        ): AppResult<Unit> {
            flow.value = flow.value.map {
                if (it.id == taskId) {
                    it.copy(succeededCount = succeeded, failedCount = failed, currentChapterIndex = currentIndex)
                } else {
                    it
                }
            }
            return AppResult.Success(Unit)
        }

        override suspend fun markTxtGenerated(taskId: Long, path: String): AppResult<Unit> {
            flow.value = flow.value.map {
                if (it.id == taskId) it.copy(txtGenerated = true, txtPath = path) else it
            }
            return AppResult.Success(Unit)
        }

        override suspend fun deleteTask(taskId: Long): AppResult<Unit> {
            val task = flow.value.find { it.id == taskId }
                ?: return AppResult.Failure(
                    ReaderError.State(StateFailure.CRAWL_TASK_NOT_FOUND, "taskId=$taskId"),
                )
            // 仓库层终态单点校验（契约 K9：仅终态可清除）
            if (!task.state.isTerminal) {
                return AppResult.Failure(
                    ReaderError.State(StateFailure.INVALID_TASK_STATE, "非终态不可清除 id=$taskId"),
                )
            }
            flow.value = flow.value.filterNot { it.id == taskId }
            return AppResult.Success(Unit)
        }
    }

    private class FakeBookRepository(val shelfFlow: MutableStateFlow<List<Book>>) : BookRepository {
        override fun observeShelf(): Flow<List<Book>> = shelfFlow
        override suspend fun getBook(bookId: Long): Book? = shelfFlow.value.find { it.id == bookId }
        override suspend fun findByNormalizedSourceUrl(normalizedUrl: String): Book? = null
        override suspend fun upsertFromImport(
            detail: ParsedBookDetail?, sourceUrl: String,
            catalog: com.mvxxcb.reader.domain.model.ParsedCatalog?, isTemporary: Boolean,
        ): AppResult<BookUpsertResult> = AppResult.Failure(
            ReaderError.State(StateFailure.INVALID_ARGUMENT, "test fake"),
        )
        override suspend fun confirmTemporaryBook(
            bookId: Long, detail: ParsedBookDetail?,
            catalog: com.mvxxcb.reader.domain.model.ParsedCatalog?,
        ): AppResult<Long> = AppResult.Failure(
            ReaderError.State(StateFailure.INVALID_ARGUMENT, "test fake"),
        )
        override suspend fun updateBookFields(
            bookId: Long, transform: (Book) -> Book,
        ): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun deleteBook(bookId: Long): AppResult<Unit> {
            shelfFlow.value = shelfFlow.value.filterNot { it.id == bookId }
            return AppResult.Success(Unit)
        }
    }

    /** K10 门面假体：只记录转发调用；可注入统一失败 */
    private class FakeReadingService : ReadingService {
        val paused = mutableListOf<Long>()
        val resumed = mutableListOf<Long>()
        val cancelled = mutableListOf<Long>()
        val retriedFailures = mutableListOf<Long>()
        var failure: ReaderError? = null

        private fun result(): AppResult<Unit> =
            failure?.let { AppResult.Failure(it) } ?: AppResult.Success(Unit)

        override suspend fun loadChapter(
            bookId: Long, chapterIndex: Int, preferCache: Boolean,
        ): AppResult<ReaderContent> = AppResult.Failure(
            ReaderError.State(StateFailure.INVALID_ARGUMENT, "test fake"),
        )
        override suspend fun loadChapterByUrl(
            bookId: Long, url: String,
        ): AppResult<ReaderContent> = AppResult.Failure(
            ReaderError.State(StateFailure.INVALID_ARGUMENT, "test fake"),
        )
        override suspend fun refreshCatalog(
            bookId: Long,
        ): AppResult<CatalogMergeReport> = AppResult.Failure(
            ReaderError.State(StateFailure.INVALID_ARGUMENT, "test fake"),
        )
        override suspend fun refreshBookDetail(
            bookId: Long,
        ): AppResult<ParsedBookDetail> = AppResult.Failure(
            ReaderError.State(StateFailure.INVALID_ARGUMENT, "test fake"),
        )
        override suspend fun pauseCrawl(taskId: Long): AppResult<Unit> {
            paused += taskId
            return result()
        }
        override suspend fun resumeCrawl(taskId: Long): AppResult<Unit> {
            resumed += taskId
            return result()
        }
        override suspend fun cancelCrawl(taskId: Long): AppResult<Unit> {
            cancelled += taskId
            return result()
        }
        override suspend fun retryCrawlFailures(taskId: Long): AppResult<Unit> {
            retriedFailures += taskId
            return result()
        }
    }

    // ———— 样本 ————

    private fun book(id: Long, title: String, domain: String = "www.example.com"): Book = Book(
        id = id, title = title, author = null, coverUrl = null, description = null,
        sourceUrl = "https://$domain/book/$id", sourceDomain = domain, catalogUrl = null,
        latestChapterTitle = null, totalChapters = 10, currentChapterId = null,
        lastReadAt = null, createdAt = 1L, updatedAt = 1L, isTemporary = false, sourceEncoding = null,
    )

    private fun task(
        id: Long, bookId: Long, state: CrawlState, updatedAt: Long,
        ok: Int = 0, fail: Int = 0, total: Int = 10, txt: Boolean = false,
        currentTitle: String? = null,
    ): CrawlTask = CrawlTask(
        id = id, bookId = bookId, state = state, totalChapters = total,
        succeededCount = ok, failedCount = fail, currentChapterIndex = ok + fail - 1,
        currentChapterTitle = currentTitle, txtPath = if (txt) "/tmp/t.txt" else null,
        txtGenerated = txt, createdAt = 1L, updatedAt = updatedAt,
        startedAt = null, finishedAt = null,
    )

    // ———— 用例 ————

    @Test
    fun `列表组合_任务联表书名域名与操作可用性`() = runTest {
        val crawlRepo = FakeCrawlTaskRepository()
        val bookRepo = FakeBookRepository(
            MutableStateFlow(listOf(book(1, "书甲", "www.a.com"), book(2, "书乙", "www.b.com"))),
        )
        crawlRepo.flow.value = listOf(
            task(11, 1, CrawlState.RUNNING, updatedAt = 3, ok = 4, fail = 1, currentTitle = "第5章"),
            task(12, 2, CrawlState.PAUSED, updatedAt = 2, ok = 2),
            task(13, 99, CrawlState.FAILED, updatedAt = 1, fail = 3), // 书已缺席（防御性兜底）
        )
        val vm = CrawlCenterViewModel(crawlRepo, bookRepo, FakeReadingService())
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.error)
        assertEquals(3, state.items.size)
        // 顺序保持（observeTasks updatedAt DESC 由仓库保证，VM 不重排）
        assertEquals(listOf(11L, 12L, 13L), state.items.map { it.task.id })
        // 联表字段
        assertEquals("书甲", state.items[0].bookTitle)
        assertEquals("www.a.com", state.items[0].bookDomain)
        assertEquals("书乙", state.items[1].bookTitle)
        assertEquals("未知书籍 #99", state.items[2].bookTitle)
        // K9 操作可用性
        with(state.items[0]) {
            assertTrue(isActive)
            assertTrue(canPause)
            assertTrue(canCancel)
            assertFalse(canResume)
            assertFalse(canRetryFailures)
            assertFalse(canDelete)
        }
        with(state.items[1]) {
            assertTrue(isActive)
            assertTrue(canResume)
            assertFalse(canPause)  // WAITING→PAUSED/RUNNING→PAUSED 合法，PAUSED 不再暂停
            assertFalse(canCancel) // K9：PAUSED→CANCELLED 非法
            assertFalse(canDelete)
        }
        with(state.items[2]) {
            assertFalse(isActive)
            assertTrue(canRetryFailures)
            assertTrue(canDelete)
            assertFalse(canPause)
        }
    }

    @Test
    fun `操作转发_K10四方法如实到达`() = runTest {
        val crawlRepo = FakeCrawlTaskRepository()
        crawlRepo.flow.value = listOf(task(21, 1, CrawlState.RUNNING, updatedAt = 1))
        val bookRepo = FakeBookRepository(MutableStateFlow(listOf(book(1, "书甲"))))
        val service = FakeReadingService()
        val vm = CrawlCenterViewModel(crawlRepo, bookRepo, service)
        advanceUntilIdle()

        vm.pause(21)
        vm.resume(21)
        vm.cancel(21)
        vm.retryFailures(21)
        advanceUntilIdle()

        assertEquals(listOf(21L), service.paused)
        assertEquals(listOf(21L), service.resumed)
        assertEquals(listOf(21L), service.cancelled)
        assertEquals(listOf(21L), service.retriedFailures)
        assertNull(vm.uiState.value.actionError)
    }

    @Test
    fun `清除记录_终态成功移除_非终态报INVALID_TASK_STATE`() = runTest {
        val crawlRepo = FakeCrawlTaskRepository()
        crawlRepo.flow.value = listOf(
            task(31, 1, CrawlState.FAILED, updatedAt = 2),
            task(32, 2, CrawlState.RUNNING, updatedAt = 3),
        )
        val vm = CrawlCenterViewModel(crawlRepo, FakeBookRepository(MutableStateFlow(emptyList())), FakeReadingService())
        advanceUntilIdle()

        // 非终态：仓库单点校验拒绝 → actionError
        vm.deleteRecord(32)
        advanceUntilIdle()
        val err = vm.uiState.value.actionError
        assertNotNull(err)
        assertTrue(err is ReaderError.State && err.failure == StateFailure.INVALID_TASK_STATE)

        // 终态：成功移除
        vm.clearActionError()
        advanceUntilIdle()
        vm.deleteRecord(31)
        advanceUntilIdle()
        assertNull(vm.uiState.value.actionError)
        assertEquals(listOf(32L), vm.uiState.value.items.map { it.task.id })
    }

    @Test
    fun `列表流异常_错误态并可重试`() = runTest {
        val failing = flow<List<CrawlTask>> { throw RuntimeException("db boom") }
        val crawlRepo = object : CrawlTaskRepository by FakeCrawlTaskRepository() {
            override fun observeTasks(): Flow<List<CrawlTask>> = failing
        }
        val vm = CrawlCenterViewModel(crawlRepo, FakeBookRepository(MutableStateFlow(emptyList())), FakeReadingService())
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.error)
        assertTrue(state.error is ReaderError.Unknown)
        assertTrue(state.items.isEmpty())
    }

    @Test
    fun `操作失败_写入actionError可清除`() = runTest {
        val crawlRepo = FakeCrawlTaskRepository()
        crawlRepo.flow.value = listOf(task(41, 1, CrawlState.RUNNING, updatedAt = 1))
        val service = FakeReadingService().apply {
            failure = ReaderError.State(StateFailure.INVALID_TASK_STATE, "非法转移")
        }
        val vm = CrawlCenterViewModel(crawlRepo, FakeBookRepository(MutableStateFlow(emptyList())), service)
        advanceUntilIdle()

        vm.pause(41)
        advanceUntilIdle()
        val err = vm.uiState.value.actionError
        assertNotNull(err)
        assertTrue(err is ReaderError.State && err.failure == StateFailure.INVALID_TASK_STATE)

        vm.clearActionError()
        advanceUntilIdle()
        assertNull(vm.uiState.value.actionError)
    }

    @Test
    fun `openBook_发出一次性导航事件`() = runTest {
        val vm = CrawlCenterViewModel(
            FakeCrawlTaskRepository(),
            FakeBookRepository(MutableStateFlow(emptyList())),
            FakeReadingService(),
        )
        advanceUntilIdle()
        val received = mutableListOf<CrawlCenterEvent>()
        val job = launch { vm.events.collect { received += it } }
        advanceUntilIdle()
        vm.openBook(7L)
        advanceUntilIdle()
        assertEquals(listOf(CrawlCenterEvent.OpenBook(7L)), received)
        job.cancel()
    }

    @Test
    fun `任务流更新_列表实时刷新`() = runTest {
        val crawlRepo = FakeCrawlTaskRepository()
        val vm = CrawlCenterViewModel(crawlRepo, FakeBookRepository(MutableStateFlow(emptyList())), FakeReadingService())
        advanceUntilIdle()
        assertTrue(vm.uiState.value.items.isEmpty())

        crawlRepo.createTask(1, 100)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.items.size)
        assertEquals(CrawlState.WAITING, vm.uiState.value.items[0].state)

        crawlRepo.updateState(crawlRepo.flow.value[0].id, CrawlState.RUNNING)
        advanceUntilIdle()
        assertEquals(CrawlState.RUNNING, vm.uiState.value.items[0].state)
    }
}
