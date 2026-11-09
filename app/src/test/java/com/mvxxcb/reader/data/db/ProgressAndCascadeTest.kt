package com.mvxxcb.reader.data.db

import com.mvxxcb.reader.data.TestSupport
import com.mvxxcb.reader.data.repository.CatalogMerger
import com.mvxxcb.reader.data.repository.BookRepositoryImpl
import com.mvxxcb.reader.data.repository.ChapterRepositoryImpl
import com.mvxxcb.reader.data.repository.BookmarkRepositoryImpl
import com.mvxxcb.reader.data.repository.ReadingProgressRepositoryImpl
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.domain.model.Bookmark
import com.mvxxcb.reader.domain.model.ContentBlock
import com.mvxxcb.reader.domain.model.ParsedBookDetail
import com.mvxxcb.reader.domain.model.ParsedChapterContent
import com.mvxxcb.reader.domain.model.ReadingProgress
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
 * 进度保存-恢复往返一致性（chapterId + inChapterFraction，契约 02 §8.6）
 * 与 deleteBook FK 级联清理验证（DoD 04 §3.3）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgressAndCascadeTest {

    private lateinit var db: com.mvxxcb.reader.data.db.AppDatabase
    private lateinit var bookRepo: BookRepositoryImpl
    private lateinit var chapterRepo: ChapterRepositoryImpl
    private lateinit var progressRepo: ReadingProgressRepositoryImpl
    private lateinit var bookmarkRepo: BookmarkRepositoryImpl
    private val logger = TestSupport.FakeLogger()
    private val time = TestSupport.FakeTime()

    @Before
    fun setUp() {
        db = TestSupport.buildDb()
        val merger = TestSupport.newMerger(db, logger)
        bookRepo = BookRepositoryImpl(db, db.bookDao(), merger, time, logger)
        chapterRepo = ChapterRepositoryImpl(db, db.chapterDao(), merger, time, logger)
        progressRepo = ReadingProgressRepositoryImpl(db.readingProgressDao(), time, logger)
        bookmarkRepo = BookmarkRepositoryImpl(db.bookmarkDao(), time, logger)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun setupBookWithChapters(): Pair<Long, List<Long>> {
        val upsert = bookRepo.upsertFromImport(
            detail = ParsedBookDetail(
                sourceUrl = "https://www.example.com/book/1",
                title = "测试书", author = "作者", coverUrl = null, description = null,
                catalogUrl = null,
            ),
            sourceUrl = "https://www.example.com/book/1",
            catalog = TestSupport.catalogOf(
                listOf(
                    TestSupport.chapterRef(0, "第一章", "https://www.example.com/c/1"),
                    TestSupport.chapterRef(1, "第二章", "https://www.example.com/c/2"),
                    TestSupport.chapterRef(2, "第三章", "https://www.example.com/c/3"),
                )
            ),
            isTemporary = false,
        )
        val bookId = (upsert as AppResult.Success).value.bookId
        val chapterIds = db.chapterDao().getAllForBook(bookId).map { it.id }
        return bookId to chapterIds
    }

    @Test
    fun `进度保存恢复往返一致含章内分数`() = runBlocking {
        val (bookId, chapterIds) = setupBookWithChapters()

        val progress = ReadingProgress(
            bookId = bookId,
            chapterId = chapterIds[1],
            chapterIndex = 1,
            scrollPosition = 0.73,
            pageIndex = null,
            inChapterFraction = 0.4234567,
            progressPercentage = 57.3,
            updatedAt = 0L, // 由 TimeProvider 重写
        )
        assertTrue(progressRepo.saveProgress(progress) is AppResult.Success)

        val restored = progressRepo.getProgress(bookId)
        assertNotNull(restored)
        // 往返一致（chapterId + inChapterFraction 为恢复锚点，02 §8.6）
        assertEquals(chapterIds[1], restored!!.chapterId)
        assertEquals(0.4234567, restored.inChapterFraction, 1e-12)
        assertEquals(0.73, restored.scrollPosition!!, 1e-12)
        assertEquals(1, restored.chapterIndex)
        assertEquals(57.3, restored.progressPercentage, 1e-12)
        assertTrue(restored.updatedAt > 0) // TimeProvider 时间戳

        // 一书一条：二次保存覆盖
        progressRepo.saveProgress(progress.copy(chapterId = chapterIds[2], inChapterFraction = 0.9))
        val second = progressRepo.getProgress(bookId)!!
        assertEquals(chapterIds[2], second.chapterId)
        assertEquals(0.9, second.inChapterFraction, 1e-12)

        // Flow 观察
        val observed = progressRepo.observeProgress(bookId).first()!!
        assertEquals(chapterIds[2], observed.chapterId)
    }

    @Test
    fun `deleteBook级联清理章节进度与书签`() = runBlocking {
        val (bookId, chapterIds) = setupBookWithChapters()

        // 正文缓存 + 进度 + 书签
        chapterRepo.saveContent(
            chapterIds[0],
            ParsedChapterContent(
                sourceUrl = "https://www.example.com/c/1",
                title = "第一章",
                blocks = listOf(ContentBlock.Paragraph("段落文本")),
                nextPageUrl = null, nextChapterUrl = null, previousChapterUrl = null,
                isTruncated = false, wordCount = 4,
            ),
        )
        progressRepo.saveProgress(
            ReadingProgress(
                bookId = bookId, chapterId = chapterIds[0], chapterIndex = 0,
                scrollPosition = null, pageIndex = 0, inChapterFraction = 0.1,
                progressPercentage = 5.0, updatedAt = 0L,
            )
        )
        bookmarkRepo.addBookmark(
            Bookmark(
                id = 0, bookId = bookId, chapterId = chapterIds[0], chapterIndex = 0,
                scrollPosition = null, pageIndex = null, inChapterFraction = 0.1,
                selectedText = "摘录文本", createdAt = 0L,
            )
        )

        assertTrue(bookRepo.deleteBook(bookId) is AppResult.Success)

        // FK CASCADE：chapters / reading_progress / bookmarks 全部级联清理
        assertTrue(db.chapterDao().getAllForBook(bookId).isEmpty())
        assertNull(db.readingProgressDao().getByBookId(bookId))
        assertEquals(
            emptyList<com.mvxxcb.reader.data.db.entities.BookmarkEntity>(),
            db.bookmarkDao().observeByBookId(bookId).first(),
        )
        assertNull(db.bookDao().getById(bookId))
    }

    @Test
    fun `upsertFromImport按规范化URL去重合并不重复建书`() = runBlocking {
        val detail = ParsedBookDetail(
            sourceUrl = "https://www.example.com/book/1",
            title = "测试书", author = "作者甲", coverUrl = null, description = "简介",
            catalogUrl = null,
        )
        val first = bookRepo.upsertFromImport(
            detail, "https://www.example.com/book/1",
            TestSupport.catalogOf(
                listOf(TestSupport.chapterRef(0, "第一章", "https://www.example.com/c/1"))
            ),
            isTemporary = false,
        )
        val r1 = (first as AppResult.Success).value
        assertTrue(r1.isNew)
        assertFalse(r1.mergedExisting)

        // 同一 URL（带跟踪参数与 fragment 的变体）再次导入 → 合并不新建
        val second = bookRepo.upsertFromImport(
            detail.copy(title = "测试书（更新名）"),
            "https://www.EXAMPLE.com/book/1/?utm_source=share#top",
            catalog = null,
            isTemporary = false,
        )
        val r2 = (second as AppResult.Success).value
        assertFalse(r2.isNew)
        assertTrue(r2.mergedExisting)
        assertEquals(r1.bookId, r2.bookId)

        val book = bookRepo.getBook(r1.bookId)!!
        assertEquals("测试书（更新名）", book.title)
        // 书仍只有 1 章（目录未变，未重复合并）
        assertEquals(1, db.chapterDao().getAllForBook(r1.bookId).size)
    }
}
