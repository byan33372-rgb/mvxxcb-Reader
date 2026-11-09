package com.mvxxcb.reader.data.crawl

import com.mvxxcb.reader.data.TestSupport
import com.mvxxcb.reader.data.db.AppDatabase
import com.mvxxcb.reader.data.repository.CrawlTaskRepositoryImpl
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StateFailure
import com.mvxxcb.reader.domain.model.CrawlState
import com.mvxxcb.reader.domain.model.CrawlTask
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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

/**
 * CrawlTaskRepositoryImpl 单测（契约 K9 状态机单点校验 / 设计 07 §4.1-5）：
 * 合法转移矩阵全路径贯通、非法转移全矩阵拒绝（INVALID_TASK_STATE）、
 * 同书非终态唯一（createTask 幂等复用）、终态再入队=新建、
 * 进度回写/TXT 登记/终态清除约束、清单排序。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrawlTaskRepositoryImplTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: CrawlTaskRepositoryImpl
    private val logger = TestSupport.FakeLogger()
    private val time = TestSupport.FakeTime()

    @Before
    fun setUp() {
        db = TestSupport.buildDb()
        repo = CrawlTaskRepositoryImpl(db.crawlTaskDao(), time, logger)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** TestSupport.insertBook 默认 URL 唯一索引去重——同测试内多书需独立 URL */
    private var bookSeq = 0

    private suspend fun insertUniqueBook(): Long =
        TestSupport.insertBook(db, title = "书${++bookSeq}", url = "https://www.example.com/book/$bookSeq")

    private suspend fun newTask(bookId: Long = -1L, total: Int = 10): CrawlTask {
        val resolvedBookId = if (bookId > 0) bookId else insertUniqueBook()
        val result = repo.createTask(resolvedBookId, total)
        assertTrue(result is AppResult.Success)
        return (result as AppResult.Success).value
    }

    /** 建任务后沿合法路径推进到目标状态，返回 taskId */
    private suspend fun taskIn(vararg path: CrawlState, bookId: Long = -1L): Long {
        val task = newTask(bookId)
        for (state in path) {
            val r = repo.updateState(task.id, state)
            assertTrue("路径推进失败 ${path.toList()} @ $state: $r", r is AppResult.Success)
        }
        return task.id
    }

    private suspend fun stateOf(taskId: Long): CrawlState =
        repo.observeTask(taskId).first()!!.state

    private fun assertStateError(result: AppResult<*>) {
        assertTrue("期望 Failure，实际 $result", result is AppResult.Failure)
        val error = (result as AppResult.Failure).error
        assertTrue("期望 State(INVALID_TASK_STATE)，实际 $error", error is ReaderError.State)
        assertEquals(StateFailure.INVALID_TASK_STATE, (error as ReaderError.State).failure)
    }

    // ———— createTask：幂等复用 / 同书唯一 / 终态再入队新建 ————

    @Test
    fun `createTask 新建任务为WAITING且时间戳齐全`() = runBlocking {
        val task = newTask(total = 42)
        assertTrue(task.id > 0)
        assertEquals(CrawlState.WAITING, task.state)
        assertEquals(42, task.totalChapters)
        assertEquals(0, task.succeededCount)
        assertEquals(0, task.failedCount)
        assertNull(task.startedAt)
        assertNull(task.finishedAt)
        assertFalse(task.txtGenerated)
        assertNull(task.txtPath)
        assertTrue(task.createdAt > 0)
        assertTrue(task.updatedAt >= task.createdAt)
    }

    @Test
    fun `createTask 同书非终态幂等复用既有任务`() = runBlocking {
        val bookId = TestSupport.insertBook(db)
        val first = newTask(bookId, total = 10)
        val second = newTask(bookId, total = 12) // 参数不同也复用
        assertEquals(first.id, second.id)
        assertEquals(10, second.totalChapters) // 保留既有任务字段，不覆盖
    }

    @Test
    fun `createTask 同书暂停中任务同样幂等复用`() = runBlocking {
        val bookId = TestSupport.insertBook(db)
        val task = newTask(bookId)
        assertTrue(repo.updateState(task.id, CrawlState.RUNNING) is AppResult.Success)
        assertTrue(repo.updateState(task.id, CrawlState.PAUSED) is AppResult.Success)
        val again = newTask(bookId, total = 10)
        assertEquals(task.id, again.id)
        assertEquals(CrawlState.PAUSED, again.state)
    }

    @Test
    fun `createTask 终态再入队等于新建任务`() = runBlocking {
        val bookId = TestSupport.insertBook(db)
        val first = newTask(bookId)
        assertTrue(repo.updateState(first.id, CrawlState.RUNNING) is AppResult.Success)
        assertTrue(repo.updateState(first.id, CrawlState.COMPLETED) is AppResult.Success)
        val second = newTask(bookId, total = 10)
        assertTrue("终态后应新建任务", second.id != first.id)
        assertEquals(CrawlState.WAITING, second.state)
        // 同书活动任务查询只认新任务
        assertEquals(second.id, repo.getActiveTaskForBook(bookId)!!.id)
    }

    @Test
    fun `createTask 不同书各自建任务`() = runBlocking {
        val bookA = TestSupport.insertBook(db, title = "书A", url = "https://www.example.com/a")
        val bookB = TestSupport.insertBook(db, title = "书B", url = "https://www.example.com/b")
        val taskA = newTask(bookA)
        val taskB = newTask(bookB)
        assertTrue(taskA.id != taskB.id)
    }

    @Test
    fun `createTask 书不存在报BOOK_NOT_FOUND`() = runBlocking {
        val result = repo.createTask(bookId = 999_999L, totalChapters = 5)
        assertTrue(result is AppResult.Failure)
        val error = (result as AppResult.Failure).error
        assertTrue(error is ReaderError.State)
        assertEquals(StateFailure.BOOK_NOT_FOUND, (error as ReaderError.State).failure)
    }

    // ———— 合法转移矩阵（K9 冻结） ————

    @Test
    fun `合法路径 WAITING到RUNNING到COMPLETED`() = runBlocking {
        val taskId = taskIn(CrawlState.RUNNING, CrawlState.COMPLETED)
        assertEquals(CrawlState.COMPLETED, stateOf(taskId))
        val task = repo.observeTask(taskId).first()!!
        assertNotNull("首进 RUNNING 落 startedAt", task.startedAt)
        assertNotNull("终态落 finishedAt", task.finishedAt)
    }

    @Test
    fun `合法路径 RUNNING到PAUSED到RUNNING到COMPLETED`() = runBlocking {
        val taskId = taskIn(CrawlState.RUNNING, CrawlState.PAUSED, CrawlState.RUNNING, CrawlState.COMPLETED)
        assertEquals(CrawlState.COMPLETED, stateOf(taskId))
    }

    @Test
    fun `合法路径 FAILED到WAITING重试后CANCELLED`() = runBlocking {
        val taskId = taskIn(
            CrawlState.RUNNING, CrawlState.FAILED, CrawlState.WAITING, CrawlState.RUNNING, CrawlState.CANCELLED,
        )
        assertEquals(CrawlState.CANCELLED, stateOf(taskId))
    }

    @Test
    fun `合法路径 WAITING直接CANCELLED`() = runBlocking {
        val taskId = taskIn(CrawlState.CANCELLED)
        assertEquals(CrawlState.CANCELLED, stateOf(taskId))
    }

    @Test
    fun `合法路径 RUNNING到FAILED重试失败章节`() = runBlocking {
        val taskId = taskIn(CrawlState.RUNNING, CrawlState.FAILED, CrawlState.WAITING)
        assertEquals(CrawlState.WAITING, stateOf(taskId))
    }

    // ———— 非法转移：全矩阵拒绝 ————

    @Test
    fun `非法转移全矩阵拒绝且状态不被改变`() = runBlocking {
        val all = CrawlState.entries.toList()
        val legal: Map<CrawlState, Set<CrawlState>> = mapOf(
            CrawlState.WAITING to setOf(CrawlState.RUNNING, CrawlState.CANCELLED),
            CrawlState.RUNNING to setOf(
                CrawlState.COMPLETED, CrawlState.FAILED, CrawlState.CANCELLED, CrawlState.PAUSED,
            ),
            CrawlState.PAUSED to setOf(CrawlState.RUNNING),
            CrawlState.FAILED to setOf(CrawlState.WAITING),
            CrawlState.COMPLETED to emptySet(),
            CrawlState.CANCELLED to emptySet(),
        )
        // 用一条真实合法路径准备每个「from」状态，再穷举非法「to」
        val setup: Map<CrawlState, List<CrawlState>> = mapOf(
            CrawlState.WAITING to emptyList(),
            CrawlState.RUNNING to listOf(CrawlState.RUNNING),
            CrawlState.PAUSED to listOf(CrawlState.RUNNING, CrawlState.PAUSED),
            CrawlState.FAILED to listOf(CrawlState.RUNNING, CrawlState.FAILED, CrawlState.WAITING, CrawlState.RUNNING, CrawlState.FAILED),
            CrawlState.COMPLETED to listOf(CrawlState.RUNNING, CrawlState.COMPLETED),
            CrawlState.CANCELLED to listOf(CrawlState.CANCELLED),
        )
        for ((from, path) in setup) {
            val taskId = taskIn(*path.toTypedArray())
            assertEquals(from, stateOf(taskId))
            for (to in all) {
                if (to in (legal[from] ?: emptySet())) continue
                assertStateError(repo.updateState(taskId, to))
                assertEquals("非法转移 $from→$to 后状态不得改变", from, stateOf(taskId))
            }
        }
    }

    @Test
    fun `非法转移报INVALID_TASK_STATE错误码`() = runBlocking {
        val taskId = newTask().id
        assertStateError(repo.updateState(taskId, CrawlState.PAUSED))    // WAITING→PAUSED 非法
        assertStateError(repo.updateState(taskId, CrawlState.COMPLETED)) // WAITING→COMPLETED 非法
        assertStateError(repo.updateState(taskId, CrawlState.FAILED))    // WAITING→FAILED 非法
        assertStateError(repo.updateState(taskId, CrawlState.WAITING))   // 自转移非 K9 矩阵成员
    }

    // ———— updateState 不存在的任务 ————

    @Test
    fun `操作不存在的任务报CRAWL_TASK_NOT_FOUND`() = runBlocking {
        val missing = 987_654L
        listOf(
            repo.updateState(missing, CrawlState.RUNNING),
            repo.updateProgress(missing, 1, 0, 0, null),
            repo.markTxtGenerated(missing, "/tmp/x.txt"),
            repo.deleteTask(missing),
        ).forEach { result ->
            assertTrue("期望 Failure，实际 $result", result is AppResult.Failure)
            val error = (result as AppResult.Failure).error
            assertTrue("期望 CRAWL_TASK_NOT_FOUND，实际 $error", error is ReaderError.State)
            assertEquals(StateFailure.CRAWL_TASK_NOT_FOUND, (error as ReaderError.State).failure)
        }
    }

    // ———— updateProgress ————

    @Test
    fun `updateProgress 进度回写持久化`() = runBlocking {
        val taskId = taskIn(CrawlState.RUNNING).also { id ->
            assertTrue(repo.updateProgress(id, 3, 1, 4, "第5章 试炼") is AppResult.Success)
        }
        val task = repo.observeTask(taskId).first()!!
        assertEquals(3, task.succeededCount)
        assertEquals(1, task.failedCount)
        assertEquals(4, task.currentChapterIndex)
        assertEquals("第5章 试炼", task.currentChapterTitle)
    }

    @Test
    fun `updateProgress 终态任务拒绝回写`() = runBlocking {
        val taskId = taskIn(CrawlState.RUNNING, CrawlState.COMPLETED)
        assertStateError(repo.updateProgress(taskId, 9, 0, 9, "x"))
        val cancelled = taskIn(CrawlState.CANCELLED)
        assertStateError(repo.updateProgress(cancelled, 9, 0, 9, "x"))
    }

    // ———— markTxtGenerated ————

    @Test
    fun `markTxtGenerated 登记路径与生成态`() = runBlocking {
        val taskId = taskIn(CrawlState.RUNNING)
        assertTrue(repo.markTxtGenerated(taskId, "/data/txt/测试.txt") is AppResult.Success)
        val task = repo.observeTask(taskId).first()!!
        assertEquals("/data/txt/测试.txt", task.txtPath)
        assertTrue(task.txtGenerated)
    }

    // ———— deleteTask：仅终态 ————

    @Test
    fun `deleteTask 非终态拒绝`() = runBlocking {
        val waiting = newTask().id
        assertStateError(repo.deleteTask(waiting))
        val running = taskIn(CrawlState.RUNNING)
        assertStateError(repo.deleteTask(running))
        val paused = taskIn(CrawlState.RUNNING, CrawlState.PAUSED)
        assertStateError(repo.deleteTask(paused))
    }

    @Test
    fun `deleteTask 终态清除后不可再查`() = runBlocking {
        val taskId = taskIn(CrawlState.RUNNING, CrawlState.COMPLETED)
        assertTrue(repo.deleteTask(taskId) is AppResult.Success)
        assertNull(repo.observeTask(taskId).first())
        // 再删 → 任务不存在
        assertTrue(
            ((repo.deleteTask(taskId) as AppResult.Failure).error as ReaderError.State).failure ==
                StateFailure.CRAWL_TASK_NOT_FOUND,
        )
    }

    // ———— observeTasks ————

    @Test
    fun `observeTasks 按updatedAt倒序`() = runBlocking {
        val taskA = newTask(total = 1)
        val taskB = newTask(total = 2) // FakeTime 递增 → updatedAt 更晚
        assertTrue(repo.updateProgress(taskA.id, 0, 0, 0, null) is AppResult.Success) // 触碰 taskA → updatedAt 最新
        val tasks = repo.observeTasks().first()
        assertEquals(taskA.id, tasks.first().id)
        assertEquals(taskB.id, tasks.last().id)
    }
}
