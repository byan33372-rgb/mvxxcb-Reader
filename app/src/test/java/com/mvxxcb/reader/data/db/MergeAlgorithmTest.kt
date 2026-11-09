package com.mvxxcb.reader.data.db

import com.mvxxcb.reader.data.TestSupport
import com.mvxxcb.reader.data.repository.CatalogMerger
import com.mvxxcb.reader.data.repository.ChapterRepositoryImpl
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.domain.error.ParseFailure
import com.mvxxcb.reader.domain.error.StorageFailure
import com.mvxxcb.reader.domain.model.ContentBlock
import com.mvxxcb.reader.domain.model.ParsedChapterContent
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
 * 目录更新合并算法单测（数据模型 03 §7.3，DoD 04 §3.2）。
 * 覆盖七场景：新增 / 改址（URL 变指纹同）/ 整表反转 / 部分缺失 / 低匹配率 requiresReview /
 * 事务回滚 / id 稳定保进度；另补标题变更与空目录拒绝。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MergeAlgorithmTest {

    private lateinit var db: com.mvxxcb.reader.data.db.AppDatabase
    private lateinit var merger: CatalogMerger
    private lateinit var chapterRepo: ChapterRepositoryImpl
    private val logger = TestSupport.FakeLogger()
    private val time = TestSupport.FakeTime()

    @Before
    fun setUp() {
        db = TestSupport.buildDb()
        merger = TestSupport.newMerger(db, logger)
        chapterRepo = ChapterRepositoryImpl(db, db.chapterDao(), merger, time, logger)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertBook(): Long = TestSupport.insertBook(db)

    // ———— 场景 1：新增（首建目录） ————

    @Test
    fun `首建目录全部新增且索引重排书籍汇总同步`() = runBlocking {
        val bookId = insertBook()
        val report = chapterRepo.replaceCatalog(
            bookId,
            TestSupport.catalogOf(
                listOf(
                    TestSupport.chapterRef(0, "第一章 起点", "https://www.example.com/c/1"),
                    TestSupport.chapterRef(1, "第二章 风起", "https://www.example.com/c/2"),
                    TestSupport.chapterRef(2, "第三章 云涌", "https://www.example.com/c/3"),
                )
            ),
        ).getOrNull()!!

        assertEquals(3, report.added)
        assertEquals(0, report.unchanged)
        assertEquals(0, report.updatedUrl)
        assertEquals(0, report.markedMissing)
        assertEquals(false, report.detectedReversal)
        // |O|=0 无保护对象 → matchedRatio=1.0，不得误报 requiresReview
        assertEquals(1.0, report.matchedRatio, 1e-9)
        assertFalse(report.requiresReview)

        val rows = db.chapterDao().getAllForBook(bookId)
        assertEquals(listOf(0, 1, 2), rows.map { it.chapterIndex })
        assertEquals("第三章 云涌", rows.last().title)
        val book = db.bookDao().getById(bookId)!!
        assertEquals(3, book.totalChapters)
        assertEquals("第三章 云涌", book.latestChapterTitle)
        assertEquals("https://www.example.com/catalog", book.catalogUrl)
    }

    // ———— 场景 2：改址（URL 变、指纹同 → 保 id 换址） ————

    @Test
    fun `URL变更指纹相同保留id并更新地址`() = runBlocking {
        val bookId = insertBook()
        val v1 = TestSupport.catalogOf(
            listOf(
                TestSupport.chapterRef(0, "第一章", "https://www.example.com/a/1"),
                TestSupport.chapterRef(1, "第二章", "https://www.example.com/a/2"),
            )
        )
        chapterRepo.replaceCatalog(bookId, v1)
        val oldIds = db.chapterDao().getAllForBook(bookId).map { it.id }

        val v2 = TestSupport.catalogOf(
            listOf(
                TestSupport.chapterRef(0, "第一章", "https://www.example.com/b/1"),
                TestSupport.chapterRef(1, "第二章", "https://www.example.com/b/2"),
            )
        )
        val report = chapterRepo.replaceCatalog(bookId, v2).getOrNull()!!

        assertEquals(0, report.added)
        assertEquals(0, report.unchanged)
        assertEquals(2, report.updatedUrl)
        assertEquals(0, report.markedMissing)
        assertFalse(report.requiresReview)
        assertEquals(1.0, report.matchedRatio, 1e-9)

        val rows = db.chapterDao().getAllForBook(bookId)
        assertEquals(oldIds, rows.map { it.id }) // id 稳定
        assertEquals("https://www.example.com/b/1", rows[0].url)
        assertEquals("https://www.example.com/b/1", rows[0].normalizedUrl)
        assertFalse(rows.any { it.isMissing })
    }

    // ———— 场景 3：整表反转 ————

    @Test
    fun `整表反转检测后按读取顺序映射id与规范顺序不变`() = runBlocking {
        val bookId = insertBook()
        chapterRepo.replaceCatalog(
            bookId,
            TestSupport.catalogOf(
                listOf(
                    TestSupport.chapterRef(0, "第一章", "https://www.example.com/c/1"),
                    TestSupport.chapterRef(1, "第二章", "https://www.example.com/c/2"),
                    TestSupport.chapterRef(2, "第三章", "https://www.example.com/c/3"),
                    TestSupport.chapterRef(3, "第四章", "https://www.example.com/c/4"),
                    TestSupport.chapterRef(4, "第五章", "https://www.example.com/c/5"),
                )
            ),
        )
        val before = db.chapterDao().getAllForBook(bookId)

        // 站点改为倒序展示（最新章在前）：新目录 order 0=第五章 … order 4=第一章
        val reversedCatalog = TestSupport.catalogOf(
            listOf(
                TestSupport.chapterRef(0, "第五章", "https://www.example.com/c/5"),
                TestSupport.chapterRef(1, "第四章", "https://www.example.com/c/4"),
                TestSupport.chapterRef(2, "第三章", "https://www.example.com/c/3"),
                TestSupport.chapterRef(3, "第二章", "https://www.example.com/c/2"),
                TestSupport.chapterRef(4, "第一章", "https://www.example.com/c/1"),
            )
        )
        val report = chapterRepo.replaceCatalog(bookId, reversedCatalog).getOrNull()!!

        assertTrue(report.detectedReversal)
        assertEquals(0, report.added)
        assertEquals(0, report.markedMissing)
        assertFalse(report.requiresReview)

        val after = db.chapterDao().getAllForBook(bookId)
        // id 与规范顺序（chapterIndex）不变，仅目录展示排序交给 UI CatalogSort
        assertEquals(before.map { it.id to it.chapterIndex }, after.map { it.id to it.chapterIndex })
        assertEquals("第一章", after.first { it.chapterIndex == 0 }.title)
        assertEquals("第五章", after.last { it.chapterIndex == 4 }.title)
    }

    // ———— 场景 4：部分缺失（isMissing 不删除，保护正文与进度） ————

    @Test
    fun `部分缺失章节标记isMissing并保留正文与进度`() = runBlocking {
        val bookId = insertBook()
        chapterRepo.replaceCatalog(
            bookId,
            TestSupport.catalogOf(
                listOf(
                    TestSupport.chapterRef(0, "第一章", "https://www.example.com/c/1"),
                    TestSupport.chapterRef(1, "第二章", "https://www.example.com/c/2"),
                    TestSupport.chapterRef(2, "第三章", "https://www.example.com/c/3"),
                    TestSupport.chapterRef(3, "第四章", "https://www.example.com/c/4"),
                )
            ),
        )
        val chapter2 = db.chapterDao().getByIndex(bookId, 1)!!
        // 第二章缓存正文
        val save = chapterRepo.saveContent(
            chapter2.id,
            ParsedChapterContent(
                sourceUrl = chapter2.url,
                title = "第二章",
                blocks = listOf(ContentBlock.Paragraph("正文段落一"), ContentBlock.Paragraph("正文段落二")),
                nextPageUrl = null, nextChapterUrl = null, previousChapterUrl = null,
                isTruncated = false, wordCount = 10,
            ),
        )
        assertTrue(save is AppResult.Success)
        // 进度记录在第二章
        db.readingProgressDao().upsert(
            com.mvxxcb.reader.data.db.entities.ReadingProgressEntity(
                bookId = bookId, chapterId = chapter2.id, chapterIndex = 1,
                inChapterFraction = 0.5, progressPercentage = 25.0, updatedAt = 1L,
            )
        )

        // 新目录缺第二章
        val report = chapterRepo.replaceCatalog(
            bookId,
            TestSupport.catalogOf(
                listOf(
                    TestSupport.chapterRef(0, "第一章", "https://www.example.com/c/1"),
                    TestSupport.chapterRef(2, "第三章", "https://www.example.com/c/3"),
                    TestSupport.chapterRef(3, "第四章", "https://www.example.com/c/4"),
                )
            ),
        ).getOrNull()!!

        assertEquals(1, report.markedMissing)
        assertFalse(report.requiresReview) // 3/4 匹配率 0.75 ≥ 0.6

        val missing = db.chapterDao().getById(chapter2.id)!!
        assertTrue(missing.isMissing)
        // 正文与缓存标记保留（不删除，保护进度/缓存，03 §7.3 第 4 步）
        assertNotNull(missing.content)
        assertTrue(missing.isCached)
        // 缺失章索引挪至目录尾部区段，不与新目录冲突
        assertEquals(3, missing.chapterIndex)
        // 进度仍指向同一章节 id
        val progress = db.readingProgressDao().getByBookId(bookId)!!
        assertEquals(chapter2.id, progress.chapterId)
    }

    // ———— 场景 5：低匹配率 requiresReview ————

    @Test
    fun `低匹配率标记requiresReview且仍写入`() = runBlocking {
        val bookId = insertBook()
        chapterRepo.replaceCatalog(
            bookId,
            TestSupport.catalogOf((1..10).map { i ->
                TestSupport.chapterRef(i - 1, "旧第${i}章", "https://www.example.com/old/$i")
            }),
        )
        // 全换：10 个完全不同的标题与 URL
        val report = chapterRepo.replaceCatalog(
            bookId,
            TestSupport.catalogOf((1..10).map { i ->
                TestSupport.chapterRef(i - 1, "全新第${i}章", "https://www.example.com/new/$i")
            }),
        ).getOrNull()!!

        assertTrue(report.matchedRatio < 0.6)
        assertTrue(report.requiresReview)
        assertEquals(10, report.added)
        assertEquals(10, report.markedMissing)
        // 仍写入（id 与进度已保护，回滚成本低）
        val rows = db.chapterDao().getAllForBook(bookId)
        assertEquals(20, rows.size)
        assertEquals(10, rows.count { it.isMissing })
        assertEquals(10, db.bookDao().getById(bookId)!!.totalChapters)
    }

    // ———— 场景 6：事务回滚 ————

    @Test
    fun `合并失败整批回滚不落库`() = runBlocking {
        val bookId = insertBook()
        chapterRepo.replaceCatalog(
            bookId,
            TestSupport.catalogOf(
                listOf(
                    TestSupport.chapterRef(0, "第一章", "https://www.example.com/c/1"),
                    TestSupport.chapterRef(1, "第二章", "https://www.example.com/c/2"),
                )
            ),
        )
        val before = db.chapterDao().getAllForBook(bookId).sortedBy { it.id }

        // 对不存在的书执行合并 → 章节插入违反 FK → 事务回滚
        val result = chapterRepo.replaceCatalog(
            9999L,
            TestSupport.catalogOf(
                listOf(
                    TestSupport.chapterRef(0, "幽灵章", "https://www.example.com/x/1"),
                )
            ),
        )

        val failure = result.errorOrNull()
        assertNotNull(failure)
        assertTrue(failure is com.mvxxcb.reader.domain.error.ReaderError.Storage)
        assertEquals(StorageFailure.DB_ERROR, (failure as com.mvxxcb.reader.domain.error.ReaderError.Storage).failure)
        // 回滚验证：幽灵章不落库，既有书目数据原封不动
        assertNull(db.chapterDao().getByIndex(9999L, 0))
        val after = db.chapterDao().getAllForBook(bookId).sortedBy { it.id }
        assertEquals(before.map { it.id to it.title }, after.map { it.id to it.title })
    }

    // ———— 场景 7：id 稳定保进度（中段插章） ————

    @Test
    fun `中段插入新章节旧章id稳定进度无损`() = runBlocking {
        val bookId = insertBook()
        chapterRepo.replaceCatalog(
            bookId,
            TestSupport.catalogOf(
                listOf(
                    TestSupport.chapterRef(0, "第一章", "https://www.example.com/c/1"),
                    TestSupport.chapterRef(1, "第二章", "https://www.example.com/c/2"),
                    TestSupport.chapterRef(2, "第三章", "https://www.example.com/c/3"),
                )
            ),
        )
        val chapter2Id = db.chapterDao().getByIndex(bookId, 1)!!.id
        db.readingProgressDao().upsert(
            com.mvxxcb.reader.data.db.entities.ReadingProgressEntity(
                bookId = bookId, chapterId = chapter2Id, chapterIndex = 1,
                inChapterFraction = 0.42, progressPercentage = 50.0, updatedAt = 1L,
            )
        )

        // 目录在第二章后新增一章（站点更新）
        val report = chapterRepo.replaceCatalog(
            bookId,
            TestSupport.catalogOf(
                listOf(
                    TestSupport.chapterRef(0, "第一章", "https://www.example.com/c/1"),
                    TestSupport.chapterRef(1, "第二章", "https://www.example.com/c/2"),
                    TestSupport.chapterRef(2, "第二章五", "https://www.example.com/c/2-5"),
                    TestSupport.chapterRef(3, "第三章", "https://www.example.com/c/3"),
                )
            ),
        ).getOrNull()!!

        assertEquals(1, report.added)
        assertEquals(3, report.unchanged)
        assertEquals(0, report.markedMissing)

        // id 稳定 → 进度无损
        val reloaded = db.chapterDao().getById(chapter2Id)!!
        assertEquals("第二章", reloaded.title)
        assertEquals(1, reloaded.chapterIndex)
        val progress = db.readingProgressDao().getByBookId(bookId)!!
        assertEquals(chapter2Id, progress.chapterId)
        assertEquals(0.42, progress.inChapterFraction, 1e-9)
        // 新章插在 index 2，第三章顺延
        assertEquals("第二章五", db.chapterDao().getByIndex(bookId, 2)!!.title)
        assertEquals("第三章", db.chapterDao().getByIndex(bookId, 3)!!.title)
    }

    // ———— 补充：同 URL 标题变更 → 更新标题与指纹 ————

    @Test
    fun `同URL标题变更更新标题与指纹`() = runBlocking {
        val bookId = insertBook()
        chapterRepo.replaceCatalog(
            bookId,
            TestSupport.catalogOf(
                listOf(TestSupport.chapterRef(0, "第一章", "https://www.example.com/c/1"))
            ),
        )
        val idBefore = db.chapterDao().getByIndex(bookId, 0)!!.id
        val fpBefore = db.chapterDao().getByIndex(bookId, 0)!!.titleFingerprint

        val report = chapterRepo.replaceCatalog(
            bookId,
            TestSupport.catalogOf(
                listOf(TestSupport.chapterRef(0, "第一章（修订）", "https://www.example.com/c/1"))
            ),
        ).getOrNull()!!

        assertEquals(1, report.unchanged) // URL 命中即 unchanged++
        assertEquals(0, report.updatedUrl)
        val row = db.chapterDao().getById(idBefore)!!
        assertEquals("第一章（修订）", row.title)
        assertTrue(row.titleFingerprint != fpBefore)
    }

    // ———— 补充：空目录拒绝（禁止盲目覆盖） ————

    @Test
    fun `空目录拒绝写入返回CATALOG_EMPTY`() = runBlocking {
        val bookId = insertBook()
        chapterRepo.replaceCatalog(
            bookId,
            TestSupport.catalogOf(
                listOf(TestSupport.chapterRef(0, "第一章", "https://www.example.com/c/1"))
            ),
        )
        val result = chapterRepo.replaceCatalog(bookId, TestSupport.catalogOf(emptyList()))
        val error = result.errorOrNull()
        assertNotNull(error)
        assertTrue(error is com.mvxxcb.reader.domain.error.ReaderError.Parse)
        assertEquals(ParseFailure.CATALOG_EMPTY, (error as com.mvxxcb.reader.domain.error.ReaderError.Parse).failure)
        // 旧目录原样保留
        assertEquals(1, db.chapterDao().getAllForBook(bookId).size)
    }

    // ———— 补充：指纹归一（03 §7.2：全角/空白/装饰差异） ————

    @Test
    fun `指纹对全角空白装饰编号差异归一`() {
        val fp1 = com.mvxxcb.reader.data.util.TitleFingerprint.of("第12章 大结局！")
        val fp2 = com.mvxxcb.reader.data.util.TitleFingerprint.of("第 12 章 大结局！")
        val fp3 = com.mvxxcb.reader.data.util.TitleFingerprint.of("第１２章　大结局！")
        val fp4 = com.mvxxcb.reader.data.util.TitleFingerprint.of("最新章节 第12章 大结局！")
        assertEquals(fp1, fp2)
        assertEquals(fp1, fp3)
        assertEquals(fp1, fp4)
        assertEquals(32, fp1.length)
    }
}
