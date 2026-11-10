package com.mvxxcb.reader.service

import android.content.Context
import android.content.ContextWrapper
import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.common.log.LogcatLogger
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.data.TestSupport
import com.mvxxcb.reader.data.db.AppDatabase
import com.mvxxcb.reader.data.db.entities.BookEntity
import com.mvxxcb.reader.data.repository.CrawlTaskRepositoryImpl
import com.mvxxcb.reader.data.repository.LocalBookRepositoryImpl
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StorageFailure
import com.mvxxcb.reader.domain.model.CrawlState
import com.mvxxcb.reader.domain.model.ReaderSettings
import com.mvxxcb.reader.domain.repository.CrawlTaskRepository
import com.mvxxcb.reader.domain.repository.LocalBookRepository
import com.mvxxcb.reader.domain.repository.ReaderSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * DiagnosticsService 单测（2.0 WP-DIAG）：
 * 反馈文件生成成功且分节齐全、空库不崩溃、URL 脱敏（query → ?…）、
 * 失败路径（IO 异常 → Storage(TXT_WRITE_FAILED)）；
 * 以及 LogcatLogger 环形缓冲（600 上限淘汰 / 快照裁剪 / 接口默认体零破坏）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiagnosticsServiceTest {

    private lateinit var db: AppDatabase
    private val serviceLogger = TestSupport.FakeLogger()
    private val logBuffer = LogcatLogger(TestSupport.FakeTime())
    private val time = TestSupport.FakeTime()
    private lateinit var crawlRepo: CrawlTaskRepository
    private lateinit var service: DiagnosticsService

    /** 阅读设置仓储假实现（快照分节可测；不引 DataStore 依赖） */
    private class FakeSettingsRepository : ReaderSettingsRepository {
        val state = MutableStateFlow(ReaderSettings(fontSizeSp = 21f, boldText = true))
        override val settings: Flow<ReaderSettings> = state
        override suspend fun current(): ReaderSettings = state.value
        override suspend fun update(
            transform: (ReaderSettings) -> ReaderSettings,
        ): AppResult<Unit> {
            state.value = transform(state.value)
            return AppResult.Success(Unit)
        }
    }

    @Before
    fun setUp() {
        db = TestSupport.buildDb()
        crawlRepo = CrawlTaskRepositoryImpl(db.crawlTaskDao(), time, serviceLogger)
        service = newService(TestSupport.appContext())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun newService(
        context: Context,
        clock: com.mvxxcb.reader.common.time.TimeProvider = time,
    ): DiagnosticsService = DiagnosticsService(
        appContext = context,
        database = db,
        readerSettingsRepository = FakeSettingsRepository(),
        crawlTaskRepository = crawlRepo,
        localBookRepository = LocalBookRepositoryImpl(db.localBookDao(), time, serviceLogger),
        logBuffer = logBuffer,
        timeProvider = clock,
        logger = serviceLogger,
    )

    private suspend fun insertBook(url: String, title: String = "测试书"): Long = db.bookDao().upsert(
        BookEntity(
            title = title,
            sourceUrl = url,
            normalizedSourceUrl = url,
            sourceDomain = "www.example.com",
            totalChapters = 12,
            createdAt = 1L,
            updatedAt = 1L,
        ),
    )

    // ———— 成功路径：分节齐全 ————

    @Test
    fun `generateFeedback 成功生成文件且六节标记齐全`() = runBlocking {
        val bookId = insertBook("https://www.example.com/book/1")
        val task = (crawlRepo.createTask(bookId, 12) as AppResult.Success).value
        assertTrue(crawlRepo.updateState(task.id, CrawlState.RUNNING) is AppResult.Success)
        logBuffer.w("TestTag", "操作日志样例")

        val result = service.generateFeedback()

        assertTrue("期望 Success，实际 $result", result is AppResult.Success)
        val file = (result as AppResult.Success).value
        assertTrue(file.exists())
        assertTrue(file.name.startsWith("反馈数据-"))
        assertTrue(file.name.endsWith(".txt"))
        assertEquals("feedback", file.parentFile?.name)
        val text = file.readText(Charsets.UTF_8)
        // 六节标记齐全
        listOf("【设备与应用】", "【数据库统计】", "【采集任务】", "【阅读设置】", "【最近操作日志】", "【反馈生成时间】")
            .forEach { assertTrue("缺分节 $it", text.contains(it)) }
        // 设备节：真实 Build 与版本信息
        assertTrue(text.contains("型号: ${android.os.Build.MODEL}"))
        assertTrue(text.contains("数据库版本: 2"))
        // 数据库统计节：真实 SQL 计数
        assertTrue(text.contains("books 总数: 1"))
        assertTrue(text.contains("chapters 已缓存: 0"))
        assertTrue(text.contains("local_books 行数: 0"))
        // 书籍明细：标题在、简介/正文类字段不在（隐私红线）
        assertTrue(text.contains("标题=测试书"))
        assertFalse(text.contains("简介"))
        // 采集任务节：任务行与状态
        assertTrue(text.contains("state=RUNNING"))
        assertTrue(text.contains("id=${task.id}"))
        // 阅读设置节：全字段快照
        assertTrue(text.contains("fontSizeSp=21.0"))
        assertTrue(text.contains("boldText=true"))
        // 日志节：缓冲行进入报告
        assertTrue(text.contains("操作日志样例"))
    }

    @Test
    fun `generateFeedback 空库不崩溃且计数为零`() = runBlocking {
        val result = service.generateFeedback()
        assertTrue("空库也应成功，实际 $result", result is AppResult.Success)
        val text = (result as AppResult.Success).value.readText(Charsets.UTF_8)
        assertTrue(text.contains("books 总数: 0"))
        assertTrue(text.contains("reading_progress 行数: 0"))
        assertTrue(text.contains("bookmarks 行数: 0"))
        assertTrue(text.contains("source_rules 行数: 0"))
        assertTrue(text.contains("local_books 行数: 0"))
        // 采集任务空节显示占位
        assertTrue(text.contains("【采集任务】"))
    }

    // ———— 隐私红线：URL 脱敏 ————

    @Test
    fun `URL 脱敏 query与fragment被替换为省略号`() = runBlocking {
        insertBook("https://www.example.com/book/1?token=SECRET&page=2", title = "带Query")
        insertBook("https://www.example.com/book/2#fragment-x", title = "带Fragment")

        val result = service.generateFeedback()

        assertTrue(result is AppResult.Success)
        val text = (result as AppResult.Success).value.readText(Charsets.UTF_8)
        assertTrue(text.contains("https://www.example.com/book/1?…"))
        assertFalse("query 原文不得落盘", text.contains("SECRET"))
        assertFalse(text.contains("page=2"))
        assertTrue(text.contains("https://www.example.com/book/2"))
        assertFalse(text.contains("fragment-x"))
    }

    // ———— 失败路径 ————

    @Test
    fun `IO 异常返回 Storage TXT_WRITE_FAILED`() = runBlocking {
        // filesDir 指向一个已存在的普通文件 → mkdirs 失败 → 写文件抛 IOException
        val blocked = File(TestSupport.appContext().cacheDir, "not-a-dir")
        blocked.writeText("occupy")
        val brokenContext = object : ContextWrapper(TestSupport.appContext()) {
            override fun getFilesDir(): File = blocked
        }

        val result = newService(brokenContext).generateFeedback()

        assertTrue("期望 Failure，实际 $result", result is AppResult.Failure)
        val error = (result as AppResult.Failure).error
        assertTrue("期望 Storage，实际 $error", error is ReaderError.Storage)
        assertEquals(StorageFailure.TXT_WRITE_FAILED, (error as ReaderError.Storage).failure)
    }

    // ———— 历史反馈文件保留（反馈文件夹语义） ————

    @Test
    fun `重复生成保留历史反馈文件`() = runBlocking {
        // 秒级文件名 + FakeTime 每次 +1ms 会撞名，改用每次 +5s 的假时钟验证「文件名唯一 + 历史保留」
        var tick = 1_700_000_000_000L
        val jumpClock = object : com.mvxxcb.reader.common.time.TimeProvider {
            override fun nowMillis(): Long {
                tick += 5_000L
                return tick - 5_000L
            }
        }
        val svc = newService(TestSupport.appContext(), jumpClock)
        val first = (svc.generateFeedback() as AppResult.Success).value
        val second = (svc.generateFeedback() as AppResult.Success).value
        assertFalse(first.name == second.name)
        assertTrue(first.exists())
        assertTrue(second.exists())
        assertEquals(2, second.parentFile?.listFiles()?.size)
    }

    // ———— 纯函数：报告装配 ————

    @Test
    fun `buildFeedbackText 纯装配含空节占位`() {
        val text = DiagnosticsService.buildFeedbackText(
            DiagnosticsService.FeedbackSections(
                device = listOf("型号: X"),
                dbStats = listOf("books 总数: 0"),
                crawlTasks = emptyList(),
                settings = listOf("fontSizeSp=18.0"),
                recentLogs = listOf("2026-01-01 00:00:00.000 I/Tag: hello"),
                generatedAt = "2026-01-01 00:00:00",
            ),
        )
        assertTrue(text.contains("【设备与应用】"))
        assertTrue(text.contains("（空）")) // 采集任务空节占位
        assertTrue(text.contains("I/Tag: hello"))
        assertTrue(text.contains("【反馈生成时间】"))
    }

    // ———— 脱敏工具 ————

    @Test
    fun `redactUrl 边界`() {
        assertEquals("", DiagnosticsService.redactUrl(""))
        // 有 query → 保留 scheme://host/path，query/fragment 以 ?… 代替
        assertEquals("https://a.com/p?…", DiagnosticsService.redactUrl("https://a.com/p?x=1#f"))
        assertEquals("https://a.com/p", DiagnosticsService.redactUrl("  https://a.com/p  "))
        assertEquals("https://a.com/p?…", DiagnosticsService.redactUrl("https://a.com/p?token=s"))
    }
}

/**
 * LogcatLogger 环形缓冲单测（2.0 WP-DIAG）：
 * 600 行上限环形淘汰、snapshotRecent(400) 默认裁剪、接口默认体/AppLogger 引用零破坏、
 * 缓冲行格式（时间戳+级别+tag+消息）与异常摘要。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogcatLoggerBufferTest {

    private val time = TestSupport.FakeTime()

    @Test
    fun `缓冲600行上限环形淘汰最旧`() {
        val logger = LogcatLogger(time)
        repeat(650) { logger.d("T", "msg-$it") }

        val snap = logger.snapshotRecent(400)
        assertEquals(400, snap.size)
        assertTrue(snap.first().contains("msg-250")) // 最旧 250 行已被淘汰
        assertTrue(snap.last().contains("msg-649"))
        assertFalse(snap.any { it.contains("msg-249") })

        val full = logger.snapshotRecent(600)
        assertEquals(600, full.size)
        assertTrue(full.first().contains("msg-50"))
        // 超量请求裁剪到缓冲上限
        assertEquals(600, logger.snapshotRecent(10_000).size)
    }

    @Test
    fun `snapshotRecent默认400且AppLogger接口引用可见`() {
        val logger = LogcatLogger(time)
        repeat(10) { logger.i("T", "i-$it") }
        assertEquals(10, logger.snapshotRecent().size) // 无参默认 400 行语义

        val asInterface: AppLogger = logger
        assertEquals(10, asInterface.snapshotRecent().size) // 接口覆写返回真实缓冲

        val pureFake = TestSupport.FakeLogger()
        assertEquals(0, pureFake.snapshotRecent().size) // 既有 Fake 继承默认体，零破坏
    }

    @Test
    fun `缓冲行格式含时间戳级别tag且异常单行摘要`() {
        val logger = LogcatLogger(time)
        logger.w("T1", "警告内容", IllegalStateException("boom"))

        val lines = logger.snapshotRecent(10)
        assertEquals(1, lines.size)
        val line = lines[0]
        assertTrue(line.contains(" W/T1: "))
        assertTrue(line.contains("警告内容"))
        assertTrue(line.contains("IllegalStateException"))
        assertTrue(line.contains("boom"))
        assertFalse("缓冲行必须单行", line.contains('\n'))
        // 时间戳前缀形如 yyyy-MM-dd HH:mm:ss.SSS
        assertTrue(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}""").containsMatchIn(line))
    }

    @Test
    fun `logError 同样进入缓冲`() {
        val logger = LogcatLogger(time)
        logger.logError(ReaderError.Storage(StorageFailure.DISK_IO), "TestCtx")
        val lines = logger.snapshotRecent(10)
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("E/ReaderError"))
        assertTrue(lines[0].contains("[TestCtx] Storage(DISK_IO)"))
    }

    @Test
    fun `空缓冲与零行请求返回空`() {
        val logger = LogcatLogger(time)
        assertTrue(logger.snapshotRecent().isEmpty())
        logger.i("T", "one")
        assertTrue(logger.snapshotRecent(0).isEmpty())
        assertTrue(logger.snapshotRecent(-1).isEmpty())
        assertEquals(1, logger.snapshotRecent(5).size)
    }
}
