package com.mvxxcb.reader.data.db

import com.mvxxcb.reader.data.TestSupport
import com.mvxxcb.reader.data.repository.CatalogMerger
import com.mvxxcb.reader.data.repository.ChapterRepositoryImpl
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.domain.error.StateFailure
import com.mvxxcb.reader.domain.model.ChapterRef
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
 * appendChapter 单章补录单测（契约 v1.0.3 变更，主 Agent 批准）：
 * 新章追加为末章、重复 URL 幂等返回（不写库）、既有卷标题保留、books 汇总同步。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChapterAppendTest {

    private lateinit var db: com.mvxxcb.reader.data.db.AppDatabase
    private lateinit var chapterRepo: ChapterRepositoryImpl
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

    private suspend fun setupBookWithCatalog(): Long {
        val bookId = TestSupport.insertBook(db)
        val report = chapterRepo.replaceCatalog(
            bookId,
            TestSupport.catalogOf(
                listOf(
                    TestSupport.chapterRef(0, "第一章", "https://www.example.com/c/1"),
                    TestSupport.chapterRef(1, "第二章", "https://www.example.com/c/2"),
                    TestSupport.chapterRef(2, "第三章", "https://www.example.com/c/3"),
                )
            ),
        ).getOrNull()!!
        assertEquals(3, report.added)
        return bookId
    }

    @Test
    fun `新章追加为末章并同步books汇总`() = runBlocking {
        val bookId = setupBookWithCatalog()

        val result = chapterRepo.appendChapter(
            bookId,
            ChapterRef(order = 0, title = "第四章 番外", url = "https://www.example.com/c/4"),
        )

        val meta = (result as AppResult.Success).value
        assertEquals(3, meta.chapterIndex) // 末章 = max+1
        assertEquals("第四章 番外", meta.title)
        assertEquals("https://www.example.com/c/4", meta.url)
        assertFalse(meta.isMissing)
        assertFalse(meta.isCached)

        val row = db.chapterDao().getById(meta.id)!!
        assertNull(row.content) // content 空
        assertEquals(0, row.wordCount)
        assertNull(row.volumeTitle) // ChapterRef 未带卷标题

        val book = db.bookDao().getById(bookId)!!
        assertEquals(4, book.totalChapters) // totalChapters+1
        assertEquals("第四章 番外", book.latestChapterTitle)
    }

    @Test
    fun `新章追加携带卷标题落库`() = runBlocking {
        val bookId = setupBookWithCatalog()
        val meta = chapterRepo.appendChapter(
            bookId,
            ChapterRef(order = 0, title = "第四章", url = "https://www.example.com/c/4", volumeTitle = "第二卷"),
        ).getOrNull()!!
        assertEquals("第二卷", db.chapterDao().getById(meta.id)!!.volumeTitle)
    }

    @Test
    fun `重复URL幂等返回不写库`() = runBlocking {
        val bookId = setupBookWithCatalog()
        val first = chapterRepo.appendChapter(
            bookId,
            ChapterRef(order = 0, title = "第四章", url = "https://www.example.com/c/4"),
        ).getOrNull()!!

        // 同 URL、不同标题再次补录 → 返回既有条目，标题/行数/汇总均不变
        val second = chapterRepo.appendChapter(
            bookId,
            ChapterRef(order = 0, title = "第四章（改名尝试）", url = "https://www.example.com/c/4"),
        ).getOrNull()!!

        assertEquals(first.id, second.id)
        assertEquals(first.chapterIndex, second.chapterIndex)
        assertEquals("第四章", second.title) // 未被覆盖
        assertEquals(4, db.chapterDao().getAllForBook(bookId).size) // 未新增行
        assertEquals(4, db.bookDao().getById(bookId)!!.totalChapters)
        assertEquals("第四章", db.bookDao().getById(bookId)!!.latestChapterTitle)
    }

    @Test
    fun `重复URL幂等返回保留既有卷标题`() = runBlocking {
        val bookId = setupBookWithCatalog()
        // 先给第一章设置卷标题（模拟合并/编辑后的既有数据）
        val c1 = db.chapterDao().getByIndex(bookId, 0)!!
        db.chapterDao().updateCatalogEntry(
            id = c1.id, title = c1.title, fingerprint = c1.titleFingerprint,
            url = c1.url, normalizedUrl = c1.normalizedUrl, volumeTitle = "第一卷",
        )

        // 同 URL 补录携带不同卷标题 → 返回既有条目，卷标题保留
        val meta = chapterRepo.appendChapter(
            bookId,
            ChapterRef(order = 0, title = c1.title, url = c1.url, volumeTitle = "被覆盖尝试"),
        ).getOrNull()!!

        assertEquals(c1.id, meta.id)
        assertEquals("第一卷", db.chapterDao().getById(c1.id)!!.volumeTitle)
    }

    @Test
    fun `补录URL先规范化变体URL幂等命中`() = runBlocking {
        val bookId = setupBookWithCatalog()
        val first = chapterRepo.appendChapter(
            bookId,
            ChapterRef(order = 0, title = "第四章", url = "https://www.example.com/c/4"),
        ).getOrNull()!!

        // 带 fragment 与跟踪参数的变体 → 规范化后同 URL → 幂等命中
        val second = chapterRepo.appendChapter(
            bookId,
            ChapterRef(order = 0, title = "第四章", url = "https://www.example.com/c/4?utm_source=share#top"),
        ).getOrNull()!!

        assertEquals(first.id, second.id)
        assertEquals("https://www.example.com/c/4", second.url)
        assertEquals(4, db.chapterDao().getAllForBook(bookId).size)
    }

    @Test
    fun `书不存在返回BOOK_NOT_FOUND`() = runBlocking {
        val result = chapterRepo.appendChapter(
            9999L,
            ChapterRef(order = 0, title = "幽灵章", url = "https://www.example.com/x/1"),
        )
        val error = result.errorOrNull()
        assertNotNull(error)
        assertTrue(error is com.mvxxcb.reader.domain.error.ReaderError.State)
        assertEquals(StateFailure.BOOK_NOT_FOUND, (error as com.mvxxcb.reader.domain.error.ReaderError.State).failure)
        assertNull(db.chapterDao().getByIndex(9999L, 0))
    }
}
