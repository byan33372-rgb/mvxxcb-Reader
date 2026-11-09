package com.mvxxcb.reader.integration

import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.common.time.TimeProvider
import com.mvxxcb.reader.domain.error.NetworkFailure
import com.mvxxcb.reader.domain.error.ParseFailure
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StateFailure
import com.mvxxcb.reader.domain.model.Book
import com.mvxxcb.reader.domain.model.BookUpsertResult
import com.mvxxcb.reader.domain.model.CatalogMergeReport
import com.mvxxcb.reader.domain.model.Chapter
import com.mvxxcb.reader.domain.model.ChapterMeta
import com.mvxxcb.reader.domain.model.ChapterRef
import com.mvxxcb.reader.domain.model.Confidence
import com.mvxxcb.reader.domain.model.ContentBlock
import com.mvxxcb.reader.domain.model.DetailField
import com.mvxxcb.reader.domain.model.ParsedBookDetail
import com.mvxxcb.reader.domain.model.ParsedCatalog
import com.mvxxcb.reader.domain.model.ParsedChapterContent
import com.mvxxcb.reader.domain.model.ParseLayer
import com.mvxxcb.reader.domain.model.ReaderContent
import com.mvxxcb.reader.domain.model.ReadingProgress
import com.mvxxcb.reader.domain.repository.BookRepository
import com.mvxxcb.reader.domain.repository.ChapterRepository
import com.mvxxcb.reader.domain.repository.ReadingProgressRepository
import com.mvxxcb.reader.domain.service.ReadingService
import com.mvxxcb.reader.parser.core.ParseOutcome
import com.mvxxcb.reader.parser.core.ParseRequest
import com.mvxxcb.reader.parser.pipeline.ParserPipeline
import com.mvxxcb.reader.service.DefaultReadingService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读流集成测试（WP-Q，对应 04 §6 / 契约 02 §6.2 ReadingService 语义）。
 *
 * 【C1 切换口径（主 Agent 裁定，阶段 6）】
 * 被测对象 = 真实 `service/DefaultReadingService`（语义基线 = 本文件骨架阶段的参考实现，
 * 见 docs/05 整合日志；语义澄清见契约 v1.0.3 §13.2.2/§13.2.3/§13.2.4）。
 * 测试边界仍使用测试内内存 Fake（FakeParserPipeline / FakeChapterRepository /
 * FakeBookRepository / FakeReadingProgressRepository / FakeLogger / 固定 TimeProvider）。
 *
 * 目录合并（refreshCatalog）语义已迁移至
 * `test/integration/RefreshCatalogC1IntegrationTest.kt`（Robolectric + Room 真实仓库版，
 * 原 @Ignore 桩位已处置，不再保留跳过项）。
 * ON_STOP 强制存进度 / 500ms 防抖 / 临近章尾预取 属 ViewModel/Screen 层行为（WP-U），
 * 不在本文件断言，见回归清单 REG-READER-*。
 */
class ReaderFlowIntegrationTest {

    private val log = mutableListOf<String>()

    private val bookId = 1L
    private val catalogUrl = "https://www.example-novel.com/book/1/catalog"

    private fun ch(index: Int) = "https://www.example-novel.com/book/1/c$index.html"

    // ------------------------------------------------------------------
    // 用例（缓存优先 / 失败重试 / 进度含 inChapterFraction）
    // ------------------------------------------------------------------

    @Test
    fun loadChapterPrefersCacheWithoutTouchingPipeline() = runTest {
        val h = harness()
        seedCatalog(h, chapterCount = 2)
        h.chapters.saveContent(1L, contentDataOf(ch(1)))

        val result = h.service.loadChapter(bookId = bookId, chapterIndex = 0, preferCache = true)
        val content = (result as AppResult.Success).value

        // 缓存优先：命中缓存 → 不触发网络解析（02 §6.2）
        assertTrue(h.pipeline.contentRequests.isEmpty())
        assertEquals(1L, content.chapterId)
        assertEquals(0, content.chapterIndex)
        assertEquals("第1章", content.title)
        assertEquals(
            listOf("第一段。", "第二段。"),
            content.blocks.map { (it as ContentBlock.Paragraph).text },
        )
    }

    @Test
    fun loadChapterCacheMissFetchesParsesAndPersists() = runTest {
        val h = harness()
        seedCatalog(h, chapterCount = 2)
        h.pipeline.contentScript = contentOf(ch(2))

        val result = h.service.loadChapter(bookId = bookId, chapterIndex = 1, preferCache = true)
        val content = (result as AppResult.Success).value

        // 无缓存 → pipeline 抓取解析一次并入库
        assertEquals(1, h.pipeline.contentRequests.size)
        assertEquals(ch(2), h.pipeline.contentRequests.single().url)
        assertEquals(2L, content.chapterId)
        assertTrue(h.chapters.getChapterMeta(2L)!!.isCached)
        val stored = h.chapters.loadContent(2L)
        assertNotNull(stored)
        assertEquals(content.blocks, stored!!.blocks)
        // 第二次读取走缓存，不再触发解析
        h.service.loadChapter(bookId, 1, preferCache = true)
        assertEquals(1, h.pipeline.contentRequests.size)
    }

    @Test
    fun loadChapterPreferCacheFalseBypassesCache() = runTest {
        val h = harness()
        seedCatalog(h, chapterCount = 1)
        h.chapters.saveContent(1L, contentDataOf(ch(1)))
        h.pipeline.contentScript =
            contentOf(ch(1), blocks = listOf(ContentBlock.Paragraph("重取后的正文。")))

        val content = (h.service.loadChapter(bookId, 0, preferCache = false) as AppResult.Success).value

        // preferCache=false → 即使有缓存也重新抓取解析（02 §6.2）
        assertEquals(1, h.pipeline.contentRequests.size)
        assertEquals("重取后的正文。", (content.blocks.single() as ContentBlock.Paragraph).text)
    }

    @Test
    fun loadChapterFailureReturnsErrorDataAndRetryCanSucceed() = runTest {
        val h = harness()
        seedCatalog(h, chapterCount = 1)
        h.pipeline.contentScript = ParseOutcome.Failure(
            ReaderError.Network(NetworkFailure.CONNECT_TIMEOUT, url = ch(1), statusCode = null),
            emptyList(),
        )

        // 第一次：失败以 AppResult.Failure 数据返回，不抛异常跨层（02 §0 第 3 条）
        val first = h.service.loadChapter(bookId, 0, preferCache = true)
        assertTrue(first is AppResult.Failure)
        val error = (first as AppResult.Failure).error
        assertTrue(error is ReaderError.Network)
        assertEquals(NetworkFailure.CONNECT_TIMEOUT, (error as ReaderError.Network).failure)
        assertFalse(h.chapters.getChapterMeta(1L)!!.isCached) // 失败不落缓存

        // 重试（管线脚本翻转为成功）：第二次调用成功
        h.pipeline.contentScript = contentOf(ch(1))
        val second = h.service.loadChapter(bookId, 0, preferCache = true)
        assertTrue(second is AppResult.Success)
        assertEquals(2, h.pipeline.contentRequests.size)
    }

    @Test
    fun loadChapterUnknownIndexReturnsStateFailure() = runTest {
        val h = harness()
        seedCatalog(h, chapterCount = 1)

        val result = h.service.loadChapter(bookId, 9, preferCache = true)
        val error = (result as AppResult.Failure).error

        assertTrue(error is ReaderError.State)
        assertEquals(StateFailure.CHAPTER_NOT_FOUND, (error as ReaderError.State).failure)
    }

    @Test
    fun loadChapterByUrlResolvesCatalogEntryAndReturnsContent() = runTest {
        val h = harness()
        seedCatalog(h, chapterCount = 2)
        h.pipeline.contentScript = contentOf(ch(2))

        val content = (h.service.loadChapterByUrl(bookId, ch(2)) as AppResult.Success).value

        assertEquals(2L, content.chapterId)
        assertEquals("第2章", content.title)
        assertEquals(1, h.pipeline.contentRequests.size)
    }

    @Test
    fun loadChapterByUrlUnknownUrlReturnsStateFailure() = runTest {
        val h = harness()
        seedCatalog(h, chapterCount = 1)

        val result = h.service.loadChapterByUrl(bookId, "https://www.example-novel.com/book/1/ghost.html")
        val error = (result as AppResult.Failure).error

        assertEquals(StateFailure.CHAPTER_NOT_FOUND, (error as ReaderError.State).failure)
    }

    @Test
    fun progressRoundTripPreservesInChapterFraction() = runTest {
        val h = harness()
        val progress = ReadingProgress(
            bookId = bookId,
            chapterId = 1L,
            chapterIndex = 0,
            scrollPosition = 0.75,
            pageIndex = 3,
            inChapterFraction = 0.42,
            progressPercentage = 12.5,
            updatedAt = FAKE_NOW,
        )

        assertTrue(h.progress.saveProgress(progress) is AppResult.Success)
        val restored = h.progress.getProgress(bookId)

        assertNotNull(restored)
        // 契约 02 §8.6：进度持久化必须含章内位置；只存章节号视为实现缺陷
        assertEquals(0.42, restored!!.inChapterFraction, 0.0)
        assertEquals(0.75, restored.scrollPosition!!, 0.0)
        assertEquals(3, restored.pageIndex)
        assertEquals(12.5, restored.progressPercentage, 0.0)
        assertEquals(0, restored.chapterIndex)
        assertNull(h.progress.getProgress(2L))
    }

    @Test
    fun refreshBookDetailOverwritesNonNullFieldsOnly() = runTest {
        val h = harness()
        h.books.books[bookId] = Book(
            id = bookId, title = "旧书名", author = null, coverUrl = "旧封面", description = null,
            sourceUrl = "https://www.example-novel.com/book/1.html",
            sourceDomain = "www.example-novel.com", catalogUrl = null,
            latestChapterTitle = null, totalChapters = 0, currentChapterId = null, lastReadAt = null,
            createdAt = FAKE_NOW, updatedAt = FAKE_NOW, isTemporary = false, sourceEncoding = null,
        )
        h.pipeline.detailScript = detailOf(title = "新书名", author = "新作者", coverUrl = null)

        val refreshed = h.service.refreshBookDetail(bookId)
        assertTrue(refreshed is AppResult.Success)

        val book = h.books.books.getValue(bookId)
        // 02 §6.2：字段非空才覆盖，不动进度/缓存
        assertEquals("新书名", book.title)
        assertEquals("新作者", book.author)
        assertEquals("旧封面", book.coverUrl) // 解析结果为空 → 保留旧值（禁止伪造/清空）
    }

    @Test
    fun refreshBookDetailUnknownBookReturnsStateFailure() = runTest {
        val h = harness()
        h.pipeline.detailScript = detailOf()

        val result = h.service.refreshBookDetail(99L)
        val error = (result as AppResult.Failure).error

        assertEquals(StateFailure.BOOK_NOT_FOUND, (error as ReaderError.State).failure)
    }

    // ------------------------------------------------------------------
    // 测试内自建 Fake（仅实现冻结契约接口，内存实现）
    // ------------------------------------------------------------------

    private class Harness(val log: MutableList<String>) {
        val logger = FakeLogger()
        val pipeline = FakeParserPipeline(log)
        val chapters = FakeChapterRepository(log)
        val books = FakeBookRepository(log)
        val progress = FakeReadingProgressRepository()
        /** C1 切换：真实取数门面（构造注入见契约 v1.0.3 §13.1 / docs/05 阶段 5 整合记录） */
        val service: ReadingService =
            DefaultReadingService(books, chapters, pipeline, FixedTime(FAKE_NOW), logger)
    }

    /** 静默日志 Fake（收集告警便于排障；契约 02 §1 AppLogger） */
    private class FakeLogger : AppLogger {
        val warnings = mutableListOf<String>()
        override fun d(tag: String, message: String) {}
        override fun i(tag: String, message: String) {}
        override fun w(tag: String, message: String, throwable: Throwable?) {
            warnings += "$tag: $message"
        }

        override fun e(tag: String, message: String, throwable: Throwable?) {
            warnings += "$tag: $message"
        }

        override fun logError(error: ReaderError, context: String) {
            warnings += context
        }
    }

    /** 固定时间 Fake（触达更新时间戳确定性；契约 02 §1 TimeProvider） */
    private class FixedTime(private val now: Long) : TimeProvider {
        override fun nowMillis(): Long = now
    }

    private fun harness(): Harness = Harness(log)

    /** 预置目录：为 bookId=1 写入 count 个章节（id 从 1 递增） */
    private suspend fun seedCatalog(h: Harness, chapterCount: Int) {
        val report = h.chapters.replaceCatalog(
            bookId,
            ParsedCatalog(
                sourceUrl = catalogUrl,
                chapters = (1..chapterCount).map { i ->
                    ChapterRef(order = i - 1, title = "第${i}章", url = ch(i))
                },
                isLikelyReversed = false,
                paginationTraversed = 1,
                truncatedByLimit = false,
            ),
        )
        check(report is AppResult.Success) { "seedCatalog 失败" }
    }

    /** Fake 解析管线（契约 02 §5.2） */
    private class FakeParserPipeline(private val log: MutableList<String>) : ParserPipeline {
        var detailScript: ParseOutcome<ParsedBookDetail> =
            ParseOutcome.Failure(ReaderError.Parse(ParseFailure.UNSUPPORTED_STRUCTURE), emptyList())
        var contentScript: ParseOutcome<ParsedChapterContent> =
            ParseOutcome.Failure(ReaderError.Parse(ParseFailure.UNSUPPORTED_STRUCTURE), emptyList())
        val detailRequests = mutableListOf<ParseRequest>()
        val contentRequests = mutableListOf<ParseRequest>()

        override suspend fun parseDetail(request: ParseRequest): ParseOutcome<ParsedBookDetail> {
            log += "parseDetail"; detailRequests += request; return detailScript
        }

        override suspend fun parseCatalog(request: ParseRequest): ParseOutcome<ParsedCatalog> {
            log += "parseCatalog"
            return ParseOutcome.Failure(ReaderError.Parse(ParseFailure.UNSUPPORTED_STRUCTURE), emptyList())
        }

        override suspend fun parseChapterContent(request: ParseRequest): ParseOutcome<ParsedChapterContent> {
            log += "parseChapterContent"; contentRequests += request; return contentScript
        }
    }

    /** Fake 章节仓库（契约 02 §7 ChapterRepository；简化合并见类注释） */
    private class FakeChapterRepository(private val log: MutableList<String>) : ChapterRepository {
        val chapters = LinkedHashMap<Long, Chapter>()
        private val contents = HashMap<Long, ParsedChapterContent>()
        private var nextId = 1L

        /** v1.0.3 §13.3 单章补录（Fake 简化语义：URL 幂等 / 末章追加） */
        override suspend fun appendChapter(bookId: Long, ref: ChapterRef): AppResult<ChapterMeta> {
            getChapterByUrl(bookId, ref.url)?.let { return AppResult.Success(it.toMeta()) }
            val index = (chapters.values.filter { it.bookId == bookId }.maxOfOrNull { it.chapterIndex } ?: -1) + 1
            val id = nextId++
            chapters[id] = Chapter(
                id = id, bookId = bookId, chapterIndex = index,
                title = ref.title, url = ref.url,
                content = null, contentHash = null,
                isCached = false, isMissing = false, fetchedAt = null, wordCount = 0,
            )
            return AppResult.Success(chapters.getValue(id).toMeta())
        }

        override fun observeChapterMetas(bookId: Long): Flow<List<ChapterMeta>> = flow {
            emit(chapters.values.filter { it.bookId == bookId }.sortedBy { it.chapterIndex }.map { it.toMeta() })
        }

        override suspend fun getChapterMeta(chapterId: Long): ChapterMeta? = chapters[chapterId]?.toMeta()

        override suspend fun getChapterByIndex(bookId: Long, chapterIndex: Int): Chapter? =
            chapters.values.firstOrNull { it.bookId == bookId && it.chapterIndex == chapterIndex }

        override suspend fun getChapterByUrl(bookId: Long, normalizedUrl: String): Chapter? =
            chapters.values.firstOrNull { it.bookId == bookId && it.url == normalizedUrl }

        override suspend fun replaceCatalog(
            bookId: Long,
            catalog: ParsedCatalog,
        ): AppResult<CatalogMergeReport> {
            log += "replaceCatalog"
            val existingByUrl = chapters.values.filter { it.bookId == bookId }.associateBy { it.url }
            var added = 0
            var unchanged = 0
            var markedMissing = 0
            val knownUrls = HashSet<String>()
            catalog.chapters.forEachIndexed { index, ref ->
                val existing = existingByUrl[ref.url]
                if (existing != null) {
                    knownUrls += ref.url
                    chapters[existing.id] = existing.copy(chapterIndex = index, title = ref.title)
                    unchanged++
                } else {
                    knownUrls += ref.url
                    val id = nextId++
                    chapters[id] = Chapter(
                        id = id, bookId = bookId, chapterIndex = index,
                        title = ref.title, url = ref.url,
                        content = null, contentHash = null,
                        isCached = false, isMissing = false, fetchedAt = null, wordCount = 0,
                    )
                    added++
                }
            }
            chapters.values.filter { it.bookId == bookId && it.url !in knownUrls }.forEach { stale ->
                chapters[stale.id] = stale.copy(isMissing = true)
                markedMissing++
            }
            val total = maxOf(existingByUrl.size, catalog.chapters.size)
            return AppResult.Success(
                CatalogMergeReport(
                    added = added,
                    unchanged = unchanged,
                    updatedUrl = 0,
                    markedMissing = markedMissing,
                    matchedRatio = if (total == 0) 1.0 else unchanged.toDouble() / total,
                    detectedReversal = catalog.isLikelyReversed,
                    requiresReview = false,
                )
            )
        }

        override suspend fun saveContent(chapterId: Long, content: ParsedChapterContent): AppResult<Unit> {
            log += "saveContent"
            val chapter = chapters[chapterId]
                ?: return AppResult.Failure(ReaderError.State(StateFailure.CHAPTER_NOT_FOUND))
            contents[chapterId] = content
            chapters[chapterId] =
                chapter.copy(isCached = true, wordCount = content.wordCount, fetchedAt = FAKE_NOW)
            return AppResult.Success(Unit)
        }

        override suspend fun loadContent(chapterId: Long): ReaderContent? {
            val content = contents[chapterId] ?: return null
            val chapter = chapters[chapterId] ?: return null
            return ReaderContent(
                chapterId = chapterId,
                chapterIndex = chapter.chapterIndex,
                bookId = chapter.bookId,
                title = chapter.title,
                blocks = content.blocks,
            )
        }

        override suspend fun deleteContent(chapterId: Long): AppResult<Unit> {
            val chapter = chapters[chapterId]
                ?: return AppResult.Failure(ReaderError.State(StateFailure.CHAPTER_NOT_FOUND))
            contents.remove(chapterId)
            chapters[chapterId] = chapter.copy(
                content = null, contentHash = null, isCached = false, wordCount = 0, fetchedAt = null,
            )
            return AppResult.Success(Unit)
        }

        private fun Chapter.toMeta(): ChapterMeta = ChapterMeta(
            id = id, bookId = bookId, chapterIndex = chapterIndex, title = title, url = url,
            isCached = isCached, isMissing = isMissing, wordCount = wordCount, fetchedAt = fetchedAt,
        )
    }

    /** Fake 书仓库（契约 02 §7 BookRepository） */
    private class FakeBookRepository(private val log: MutableList<String>) : BookRepository {
        val books = LinkedHashMap<Long, Book>()
        private var nextId = 1L

        override fun observeShelf(): Flow<List<Book>> = flow {
            emit(books.values.filter { !it.isTemporary })
        }

        override suspend fun getBook(bookId: Long): Book? = books[bookId]

        override suspend fun findByNormalizedSourceUrl(normalizedUrl: String): Book? =
            books.values.firstOrNull { it.sourceUrl == normalizedUrl }

        override suspend fun upsertFromImport(
            detail: ParsedBookDetail?,
            sourceUrl: String,
            catalog: ParsedCatalog?,
            isTemporary: Boolean,
        ): AppResult<BookUpsertResult> {
            log += "upsertFromImport"
            val existing = findByNormalizedSourceUrl(sourceUrl)
            return if (existing != null) {
                books[existing.id] = existing.copy(
                    title = detail?.title ?: existing.title,
                    author = detail?.author ?: existing.author,
                    coverUrl = detail?.coverUrl ?: existing.coverUrl,
                    description = detail?.description ?: existing.description,
                    catalogUrl = detail?.catalogUrl ?: existing.catalogUrl,
                    totalChapters = catalog?.chapters?.size ?: existing.totalChapters,
                    updatedAt = FAKE_NOW,
                )
                AppResult.Success(BookUpsertResult(existing.id, isNew = false, mergedExisting = true))
            } else {
                val id = nextId++
                books[id] = Book(
                    id = id,
                    title = detail?.title ?: "",
                    author = detail?.author,
                    coverUrl = detail?.coverUrl,
                    description = detail?.description,
                    sourceUrl = sourceUrl,
                    sourceDomain = hostOf(sourceUrl),
                    catalogUrl = detail?.catalogUrl,
                    latestChapterTitle = null,
                    totalChapters = catalog?.chapters?.size ?: 0,
                    currentChapterId = null,
                    lastReadAt = null,
                    createdAt = FAKE_NOW,
                    updatedAt = FAKE_NOW,
                    isTemporary = isTemporary,
                    sourceEncoding = null,
                )
                AppResult.Success(BookUpsertResult(id, isNew = true, mergedExisting = false))
            }
        }

        override suspend fun confirmTemporaryBook(
            bookId: Long,
            detail: ParsedBookDetail?,
            catalog: ParsedCatalog?,
        ): AppResult<Long> {
            val book = books[bookId]
                ?: return AppResult.Failure(ReaderError.State(StateFailure.BOOK_NOT_FOUND))
            books[bookId] = book.copy(isTemporary = false, updatedAt = FAKE_NOW)
            return AppResult.Success(bookId)
        }

        override suspend fun updateBookFields(
            bookId: Long,
            transform: (Book) -> Book,
        ): AppResult<Unit> {
            val book = books[bookId]
                ?: return AppResult.Failure(ReaderError.State(StateFailure.BOOK_NOT_FOUND))
            books[bookId] = transform(book)
            return AppResult.Success(Unit)
        }

        override suspend fun deleteBook(bookId: Long): AppResult<Unit> =
            if (books.remove(bookId) != null) AppResult.Success(Unit)
            else AppResult.Failure(ReaderError.State(StateFailure.BOOK_NOT_FOUND))
    }

    /** Fake 进度仓库（契约 02 §7 ReadingProgressRepository） */
    private class FakeReadingProgressRepository : ReadingProgressRepository {
        val store = HashMap<Long, ReadingProgress>()
        val saved = mutableListOf<ReadingProgress>()

        override fun observeProgress(bookId: Long): Flow<ReadingProgress?> = flow {
            emit(store[bookId])
        }

        override suspend fun getProgress(bookId: Long): ReadingProgress? = store[bookId]

        override suspend fun saveProgress(progress: ReadingProgress): AppResult<Unit> {
            store[progress.bookId] = progress
            saved += progress
            return AppResult.Success(Unit)
        }

        override suspend fun clearProgress(bookId: Long): AppResult<Unit> {
            store.remove(bookId)
            return AppResult.Success(Unit)
        }
    }

    // ------------------------------------------------------------------
    // 脚本构造辅助
    // ------------------------------------------------------------------

    private fun detailOf(
        title: String? = "测试书名",
        author: String? = "测试作者",
        coverUrl: String? = null,
    ): ParseOutcome.Success<ParsedBookDetail> = ParseOutcome.Success(
        data = ParsedBookDetail(
            sourceUrl = "https://www.example-novel.com/book/1.html",
            title = title,
            author = author,
            coverUrl = coverUrl,
            description = "测试简介",
            catalogUrl = null,
            fieldSources = if (title != null) mapOf(DetailField.TITLE to ParseLayer.STRUCTURED) else emptyMap(),
            fieldConfidence = if (title != null) mapOf(DetailField.TITLE to Confidence.HIGH) else emptyMap(),
        ),
        overallConfidence = Confidence.HIGH,
        layerTrace = emptyList(),
        warnings = emptyList(),
    )

    private fun contentDataOf(
        sourceUrl: String,
        blocks: List<ContentBlock> = listOf(
            ContentBlock.Paragraph("第一段。"),
            ContentBlock.Paragraph("第二段。"),
        ),
    ): ParsedChapterContent = ParsedChapterContent(
        sourceUrl = sourceUrl,
        title = null,
        blocks = blocks,
        nextPageUrl = null,
        nextChapterUrl = null,
        previousChapterUrl = null,
        isTruncated = false,
        wordCount = blocks.sumOf { (it as? ContentBlock.Paragraph)?.text?.length ?: 0 },
    )

    private fun contentOf(
        sourceUrl: String,
        blocks: List<ContentBlock> = listOf(
            ContentBlock.Paragraph("第一段。"),
            ContentBlock.Paragraph("第二段。"),
        ),
    ): ParseOutcome.Success<ParsedChapterContent> = ParseOutcome.Success(
        data = contentDataOf(sourceUrl, blocks),
        overallConfidence = Confidence.HIGH,
        layerTrace = emptyList(),
        warnings = emptyList(),
    )

    private companion object {
        const val FAKE_NOW = 1_700_000_000_000L

        fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/')
    }
}
