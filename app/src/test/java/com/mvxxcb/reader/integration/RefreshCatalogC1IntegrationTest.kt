package com.mvxxcb.reader.integration

import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.data.TestSupport
import com.mvxxcb.reader.data.db.AppDatabase
import com.mvxxcb.reader.data.repository.BookRepositoryImpl
import com.mvxxcb.reader.data.repository.CatalogMerger
import com.mvxxcb.reader.data.repository.ChapterRepositoryImpl
import com.mvxxcb.reader.domain.error.ParseFailure
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StateFailure
import com.mvxxcb.reader.domain.model.ChapterRef
import com.mvxxcb.reader.domain.model.Confidence
import com.mvxxcb.reader.domain.model.ParsedBookDetail
import com.mvxxcb.reader.domain.model.ParsedCatalog
import com.mvxxcb.reader.domain.model.ParsedChapterContent
import com.mvxxcb.reader.domain.repository.BookRepository
import com.mvxxcb.reader.domain.repository.ChapterRepository
import com.mvxxcb.reader.domain.service.ReadingService
import com.mvxxcb.reader.parser.core.ParseOutcome
import com.mvxxcb.reader.parser.core.ParseRequest
import com.mvxxcb.reader.parser.pipeline.ParserPipeline
import com.mvxxcb.reader.service.DefaultReadingService
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

/**
 * refreshCatalog 真实仓库集成测试（WP-Q 阶段 6，C1 切换产物）。
 *
 * 【@Ignore 桩位处置记录】骨架阶段的 refreshCatalogMergeKeepsChapterIdsAndReportsChanges
 * 桩位（原位于 ReaderFlowIntegrationTest）已按主 Agent 裁定改写为本文件——
 * Robolectric + Room 内存库 + 真实 BookRepositoryImpl/ChapterRepositoryImpl/CatalogMerger
 * （基建复用 WP-D test/data/TestSupport，与 test/data/db/MergeAlgorithmTest.kt 同通道），
 * 经真实 DefaultReadingService.refreshCatalog 端到端执行，@Ignore 已移除、无跳过项。
 *
 * 覆盖语义：书行前置（02 §6.2 + v1.0.3 §13.2.4 目标取 book.catalogUrl ?: book.sourceUrl）、
 * 合并算法七场景中的换址保 id / 缺失不删 / requiresReview 阈值（03 §7.3 全量矩阵由
 * test/data/db/MergeAlgorithmTest.kt 10 用例覆盖，互为补充）、绝不盲目覆盖、错误透传。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RefreshCatalogC1IntegrationTest {

    private lateinit var db: AppDatabase
    private lateinit var bookRepo: BookRepository
    private lateinit var chapterRepo: ChapterRepository
    private lateinit var service: ReadingService
    private val logger = TestSupport.FakeLogger()
    private val time = TestSupport.FakeTime()
    private val pipeline = RefreshCatalogPipeline()

    private val sourceUrl = "https://www.example-novel.com/book/1.html"
    private val catalogUrl = "https://www.example-novel.com/book/1/catalog"

    private fun ch(index: Int) = "https://www.example-novel.com/book/1/c$index.html"

    @Before
    fun setUp() {
        db = TestSupport.buildDb()
        val merger = TestSupport.newMerger(db, logger)
        chapterRepo = ChapterRepositoryImpl(db, db.chapterDao(), merger, time, logger)
        bookRepo = BookRepositoryImpl(db, db.bookDao(), merger, time, logger)
        service = DefaultReadingService(bookRepo, chapterRepo, pipeline, time, logger)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 种子：真实 upsertFromImport 建书 + v1 目录（第1..N章）；返回 bookId */
    private suspend fun seedBookWithCatalog(count: Int = 3): Long {
        val catalog = ParsedCatalog(
            sourceUrl = catalogUrl,
            chapters = (1..count).map { i -> ChapterRef(order = i - 1, title = "第${i}章", url = ch(i)) },
            isLikelyReversed = false,
            paginationTraversed = 1,
            truncatedByLimit = false,
        )
        val result = bookRepo.upsertFromImport(null, sourceUrl, catalog, isTemporary = false)
        check(result is AppResult.Success) { "seed 失败：$result" }
        return result.value.bookId
    }

    @Test
    fun refreshCatalogMergesByFingerprintKeepsIdsAndMarksMissing() = runBlocking {
        val bookId = seedBookWithCatalog(count = 3)
        val oldC2Id = db.chapterDao().getByIndex(bookId, 1)!!.id

        // 新目录：第1章不变；第2章换址（URL 变、标题指纹同）；第4章新增；第3章消失
        pipeline.catalogScript = ParseOutcome.Success(
            ParsedCatalog(
                sourceUrl = catalogUrl,
                chapters = listOf(
                    ChapterRef(order = 0, title = "第1章", url = ch(1)),
                    ChapterRef(order = 1, title = "第2章", url = "https://www.example-novel.com/book/1/c2-v2.html"),
                    ChapterRef(order = 2, title = "第4章", url = ch(4)),
                ),
                isLikelyReversed = false,
                paginationTraversed = 1,
                truncatedByLimit = false,
            ),
            overallConfidence = Confidence.HIGH,
            layerTrace = emptyList(),
            warnings = emptyList(),
        )

        val report = (service.refreshCatalog(bookId) as AppResult.Success).value

        // 重抓目标 = book.catalogUrl（upsertFromImport 落库的目录页，v1.0.3 §13.2.4）
        assertEquals(catalogUrl, pipeline.catalogRequests.single().url)
        // 真实合并算法（03 §7.3）：URL 命中=unchanged、指纹命中换址=updatedUrl、新章=added、缺失=markedMissing
        assertFalse(report.requiresReview)
        assertEquals(1, report.added)
        assertEquals(1, report.updatedUrl)
        assertEquals(1, report.unchanged)
        assertEquals(1, report.markedMissing)
        assertEquals(2.0 / 3.0, report.matchedRatio, 1e-9)

        // id 稳定 → 进度/书签无损（03 §7.3 保证）
        val moved = chapterRepo.getChapterByUrl(bookId, "https://www.example-novel.com/book/1/c2-v2.html")
        assertTrue(moved != null)
        assertEquals(oldC2Id, moved!!.id)

        // 缺失章标记 isMissing、行未删除（v1.0.3 §13.2.7：索引挪至目录尾部区段）
        val rows = db.chapterDao().getAllForBook(bookId)
        assertEquals(4, rows.size) // 3 个有效 + 1 个缺失
        val missing = rows.filter { it.isMissing }
        assertEquals(1, missing.size)
        assertEquals("第3章", missing.single().title)
    }

    @Test
    fun refreshCatalogUnknownBookReturnsStateFailure() = runBlocking {
        val result = service.refreshCatalog(9999L)

        val error = (result as AppResult.Failure).error
        assertTrue(error is ReaderError.State)
        assertEquals(StateFailure.BOOK_NOT_FOUND, (error as ReaderError.State).failure)
    }

    @Test
    fun refreshCatalogPipelineFailurePropagatesWithoutTouchingCatalog() = runBlocking {
        val bookId = seedBookWithCatalog(count = 1)
        val before = db.chapterDao().getAllForBook(bookId).map { it.id to it.title }
        pipeline.catalogScript =
            ParseOutcome.Failure(ReaderError.Parse(ParseFailure.CATALOG_EMPTY), emptyList())

        val result = service.refreshCatalog(bookId)

        val error = (result as AppResult.Failure).error
        assertTrue(error is ReaderError.Parse)
        assertEquals(ParseFailure.CATALOG_EMPTY, (error as ReaderError.Parse).failure)
        // 目录原样（绝不盲目覆盖）
        assertEquals(before, db.chapterDao().getAllForBook(bookId).map { it.id to it.title })
    }

    /** refreshCatalog 边界 Fake：parseCatalog 脚本化；detail/content 不在本测试范围 */
    private class RefreshCatalogPipeline : ParserPipeline {
        var catalogScript: ParseOutcome<ParsedCatalog> =
            ParseOutcome.Failure(ReaderError.Parse(ParseFailure.UNSUPPORTED_STRUCTURE), emptyList())
        val catalogRequests = mutableListOf<ParseRequest>()

        override suspend fun parseDetail(request: ParseRequest): ParseOutcome<ParsedBookDetail> =
            ParseOutcome.Failure(ReaderError.Parse(ParseFailure.UNSUPPORTED_STRUCTURE), emptyList())

        override suspend fun parseCatalog(request: ParseRequest): ParseOutcome<ParsedCatalog> {
            catalogRequests += request
            return catalogScript
        }

        override suspend fun parseChapterContent(request: ParseRequest): ParseOutcome<ParsedChapterContent> =
            ParseOutcome.Failure(ReaderError.Parse(ParseFailure.UNSUPPORTED_STRUCTURE), emptyList())
    }
}
