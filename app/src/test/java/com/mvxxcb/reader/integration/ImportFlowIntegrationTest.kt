package com.mvxxcb.reader.integration

import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.domain.error.NetworkFailure
import com.mvxxcb.reader.domain.error.ParseFailure
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StateFailure
import com.mvxxcb.reader.domain.error.StorageFailure
import com.mvxxcb.reader.domain.model.Book
import com.mvxxcb.reader.domain.model.BookUpsertResult
import com.mvxxcb.reader.domain.model.CatalogMergeReport
import com.mvxxcb.reader.domain.model.Chapter
import com.mvxxcb.reader.domain.model.ChapterMeta
import com.mvxxcb.reader.domain.model.ChapterRef
import com.mvxxcb.reader.domain.model.Confidence
import com.mvxxcb.reader.domain.model.ContentBlock
import com.mvxxcb.reader.domain.model.DetailField
import com.mvxxcb.reader.domain.model.PageKind
import com.mvxxcb.reader.domain.model.ParseLayer
import com.mvxxcb.reader.domain.model.ParsedBookDetail
import com.mvxxcb.reader.domain.model.ParsedCatalog
import com.mvxxcb.reader.domain.model.ParsedChapterContent
import com.mvxxcb.reader.domain.model.ReaderContent
import com.mvxxcb.reader.domain.model.UrlClassification
import com.mvxxcb.reader.domain.repository.BookRepository
import com.mvxxcb.reader.domain.repository.ChapterRepository
import com.mvxxcb.reader.domain.service.ImportOutcome
import com.mvxxcb.reader.domain.service.ImportPhase
import com.mvxxcb.reader.domain.service.ImportService
import com.mvxxcb.reader.domain.service.ImportStep
import com.mvxxcb.reader.domain.service.RecoveryAction
import com.mvxxcb.reader.domain.service.TempReadOutcome
import com.mvxxcb.reader.parser.core.ParseOutcome
import com.mvxxcb.reader.parser.core.ParseRequest
import com.mvxxcb.reader.parser.core.UrlClassifier
import com.mvxxcb.reader.parser.pipeline.ParserPipeline
import com.mvxxcb.reader.service.DefaultImportService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入流集成测试（WP-Q，对应 04 §6 / 契约 02 §6.1 编排语义 + §7 入库语义）。
 *
 * 【C1 切换口径（主 Agent 裁定，阶段 6）】
 * 被测对象 = 真实 `service/DefaultImportService`（编排语义以本文件骨架阶段的
 * 参考实现为 C1 验收基线，见 docs/05 整合日志）。测试边界仍使用测试内内存 Fake：
 *   - FakeUrlClassifier / FakeParserPipeline：脚本化解析边界（真实 UrlClassifierImpl /
 *     ParserPipelineImpl 的行为由 WP-P 单测覆盖）；
 *   - FakeBookRepository / FakeChapterRepository：内存仓库（真实 Room 仓库由 WP-D 单测
 *     与 test/integration/RefreshCatalogC1IntegrationTest 覆盖）；
 *   - FakeLogger：静默收集日志。
 *
 * 阶段 6 追加断言（契约 v1.0.3 §13.2.6）：章节页路径补齐单章内容持久化
 * （replaceCatalog/appendChapter + saveContent），临时书可直接阅读。
 * 若真实实现与本文件断言冲突 → 报主 Agent 走变更回路，不擅自改断言或实现。
 */
class ImportFlowIntegrationTest {

    /** 全链路调用记录：断言「classify → detail → catalog → upsert」顺序与未触碰的层 */
    private val log = mutableListOf<String>()

    private val rawUrl = "https://www.example-novel.com/book/1.html"
    private val detailUrl = "https://www.example-novel.com/book/1.html"
    private val catalogUrl = "https://www.example-novel.com/book/1/catalog"
    private val chapter3Url = "https://www.example-novel.com/book/1/c3.html"

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    fun happyPathEmitsPhasesInOrderAndCallsClassifyThenDetailThenCatalogThenUpsert() = runTest {
        val h = harness()
        h.classifier.script = AppResult.Success(classification(PageKind.BOOK_DETAIL))
        h.pipeline.detailScript = detailOf(confidence = Confidence.HIGH, warnings = listOf("d-warn"))
        h.pipeline.catalogScript = catalogOf(count = 5, confidence = Confidence.MEDIUM, warnings = listOf("c-warn"))

        val steps = h.service.importFromUrl(rawUrl).toList()

        // ① 编排调用顺序：classify → parseDetail → parseCatalog → upsert →（数据层）目录写入
        assertEquals(
            listOf("classify", "parseDetail", "parseCatalog", "upsertFromImport", "replaceCatalog"),
            h.log,
        )
        // ② Phase 上报顺序：CLASSIFYING → PARSING_DETAIL → PARSING_CATALOG → SAVING → SUCCESS → Finished
        assertEquals(
            listOf(
                ImportPhase.CLASSIFYING,
                ImportPhase.PARSING_DETAIL,
                ImportPhase.PARSING_CATALOG,
                ImportPhase.SAVING,
                ImportPhase.SUCCESS,
            ),
            steps.mapNotNull { (it as? ImportStep.PhaseChanged)?.phase },
        )
        // ③ 终态 Finished(Success)
        val success = (steps.last() as ImportStep.Finished).outcome as ImportOutcome.Success
        assertTrue(success.bookId > 0)
        assertFalse(success.createdTemporary)
        assertFalse(success.mergedExisting)
        // ④ 整体置信度取弱侧 min(HIGH, MEDIUM)
        assertEquals(Confidence.MEDIUM, success.confidence)
        // ⑤ warnings 透传
        assertEquals(listOf("d-warn", "c-warn"), success.warnings)
    }

    @Test
    fun successCarriesCatalogSizeFromParsedCatalog() = runTest {
        val h = harness()
        h.classifier.script = AppResult.Success(classification(PageKind.BOOK_DETAIL))
        h.pipeline.detailScript = detailOf()
        h.pipeline.catalogScript = catalogOf(count = 12)

        val steps = h.service.importFromUrl(rawUrl).toList()
        val success = (steps.last() as ImportStep.Finished).outcome as ImportOutcome.Success

        // catalogSize = 解析目录的章节数（02 §6.1 注释：null = 未解析目录）
        assertEquals(12, success.catalogSize)
        assertEquals(12, h.books.books.values.single().totalChapters)
    }

    @Test
    fun catalogParseUsesDetailCatalogUrlWhenProvidedOtherwiseClassificationUrl() = runTest {
        // 场景 A：详情页给出独立目录页 URL → 目录解析请求指向该 URL
        run {
            val h = harness()
            h.classifier.script = AppResult.Success(classification(PageKind.BOOK_DETAIL))
            h.pipeline.detailScript = detailOf(catalogUrl = catalogUrl)
            h.pipeline.catalogScript = catalogOf(sourceUrl = catalogUrl)

            h.service.importFromUrl(rawUrl).toList()
            assertEquals(catalogUrl, h.pipeline.catalogRequests.single().url)
        }
        // 场景 B：详情页未给出目录页 → 目录解析请求指向分类规范化 URL
        run {
            val h = harness()
            h.classifier.script = AppResult.Success(classification(PageKind.BOOK_DETAIL))
            h.pipeline.detailScript = detailOf(catalogUrl = null)
            h.pipeline.catalogScript = catalogOf()

            h.service.importFromUrl(rawUrl).toList()
            assertEquals(detailUrl, h.pipeline.catalogRequests.single().url)
        }
    }

    @Test
    fun importIntoExistingNormalizedUrlMergesInsteadOfDuplicating() = runTest {
        val h = harness()
        // 前置：书架已有同规范化 URL 的书（id = 7）
        h.books.books[7L] = Book(
            id = 7L, title = "旧书名", author = null, coverUrl = null, description = null,
            sourceUrl = detailUrl, sourceDomain = "www.example-novel.com", catalogUrl = null,
            latestChapterTitle = null, totalChapters = 0, currentChapterId = null, lastReadAt = null,
            createdAt = FAKE_NOW, updatedAt = FAKE_NOW, isTemporary = false, sourceEncoding = null,
        )
        h.classifier.script = AppResult.Success(classification(PageKind.BOOK_DETAIL))
        h.pipeline.detailScript = detailOf()
        h.pipeline.catalogScript = catalogOf(count = 3)

        val steps = h.service.importFromUrl(rawUrl).toList()
        val success = (steps.last() as ImportStep.Finished).outcome as ImportOutcome.Success

        // mergedExisting 语义（02 §6.1）：命中书架已有书 → 复用 id，不建新书
        assertEquals(7L, success.bookId)
        assertTrue(success.mergedExisting)
        assertEquals(1, h.books.books.size)
        // 非空字段覆盖、空字段不伪造
        assertEquals("测试书名", h.books.books.getValue(7L).title)
        assertEquals(3, h.books.books.getValue(7L).totalChapters)
    }

    @Test
    fun newUrlImportYieldsMergedExistingFalseAndNewBook() = runTest {
        val h = harness()
        h.classifier.script = AppResult.Success(classification(PageKind.BOOK_DETAIL))
        h.pipeline.detailScript = detailOf()
        h.pipeline.catalogScript = catalogOf(count = 2)

        val steps = h.service.importFromUrl(rawUrl).toList()
        val success = (steps.last() as ImportStep.Finished).outcome as ImportOutcome.Success

        assertFalse(success.mergedExisting)
        assertFalse(h.books.books.getValue(success.bookId).isTemporary)
    }

    @Test
    fun catalogFailureAfterDetailCarriesPartialDetailAndRecovery() = runTest {
        val h = harness()
        h.classifier.script = AppResult.Success(classification(PageKind.BOOK_DETAIL))
        h.pipeline.detailScript = detailOf()
        h.pipeline.catalogScript = ParseOutcome.Failure(ReaderError.Parse(ParseFailure.CATALOG_EMPTY), emptyList())

        val steps = h.service.importFromUrl(rawUrl).toList()
        val failure = (steps.last() as ImportStep.Finished).outcome as ImportOutcome.Failure

        assertEquals(ParseFailure.CATALOG_EMPTY, (failure.error as ReaderError.Parse).failure)
        assertNotNull(failure.classification)
        // partialDetail：已拿到的部分字段如实携带（02 §6.1）
        assertNotNull(failure.partialDetail)
        assertEquals("测试书名", failure.partialDetail?.title)
        // 02 §10：目录获取失败 → 重试/编辑规则
        assertTrue(RecoveryAction.RETRY in failure.recovery)
        assertTrue(RecoveryAction.EDIT_RULE in failure.recovery)
        // 未进入 SAVING / SUCCESS，且未触碰入库
        assertFalse(steps.mapNotNull { (it as? ImportStep.PhaseChanged)?.phase }.contains(ImportPhase.SAVING))
        assertTrue(h.log.none { it == "upsertFromImport" || it == "replaceCatalog" })
    }

    @Test
    fun classifyFailureCarriesNoClassificationNoDetailAndRecovery() = runTest {
        val h = harness()
        h.classifier.script =
            AppResult.Failure(ReaderError.Network(NetworkFailure.INVALID_URL, url = rawUrl, statusCode = null))

        val steps = h.service.importFromUrl(rawUrl).toList()
        val failure = (steps.last() as ImportStep.Finished).outcome as ImportOutcome.Failure

        assertEquals(NetworkFailure.INVALID_URL, (failure.error as ReaderError.Network).failure)
        assertNull(failure.classification)
        assertNull(failure.partialDetail)
        assertEquals(listOf(RecoveryAction.RETRY), failure.recovery)
        // 分类失败：不触碰解析与入库
        assertTrue(h.log.none { it.startsWith("parse") || it == "upsertFromImport" })
    }

    @Test
    fun chapterUrlImportCreatesTemporaryBookWithoutCatalog() = runTest {
        val h = harness()
        h.classifier.script = AppResult.Success(classification(PageKind.CHAPTER, url = chapter3Url))
        h.pipeline.contentScript = contentOf(chapter3Url, confidence = Confidence.HIGH)

        val steps = h.service.importFromUrl(chapter3Url).toList()
        val success = (steps.last() as ImportStep.Finished).outcome as ImportOutcome.Success

        // 章节页直达：CLASSIFYING → PARSING_CONTENT → SAVING → SUCCESS（不走 detail/catalog）
        assertEquals(
            listOf(ImportPhase.CLASSIFYING, ImportPhase.PARSING_CONTENT, ImportPhase.SAVING, ImportPhase.SUCCESS),
            steps.mapNotNull { (it as? ImportStep.PhaseChanged)?.phase },
        )
        // catalogSize = null（未解析目录）；createdTemporary = true（仅解析到正文 → 临时书）
        assertNull(success.catalogSize)
        assertNull(success.detail)
        assertTrue(success.createdTemporary)
        assertTrue(h.books.books.values.single().isTemporary)
        assertTrue(h.log.none { it == "parseDetail" || it == "parseCatalog" })
        // 契约 v1.0.3 §13.2.6：单章内容持久化（参考实现缺口补齐），临时书可直接阅读
        val chapter = h.chapters.chapters.values.single()
        assertTrue(chapter.isCached)
        val stored = h.chapters.loadContent(chapter.id)
        assertNotNull(stored)
        assertEquals(
            listOf("第一段。", "第二段。"),
            stored!!.blocks.map { (it as ContentBlock.Paragraph).text },
        )
    }

    @Test
    fun parseForTemporaryReadReturnsReadyWithTemporaryBook() = runTest {
        val h = harness()
        h.classifier.script =
            AppResult.Success(classification(PageKind.CHAPTER, url = chapter3Url, confidence = Confidence.MEDIUM))
        h.pipeline.contentScript = contentOf(chapter3Url, confidence = Confidence.HIGH)

        val outcome = h.service.parseForTemporaryRead(chapter3Url)
        val ready = outcome as TempReadOutcome.Ready

        assertTrue(ready.bookId > 0)
        assertEquals(0, ready.chapterIndex)
        // 一期不做目录回溯（v1.0.3 §13.2.6 明确标注）：backtracked=false
        assertFalse(ready.backtracked)
        // 置信度取弱侧：分类 MEDIUM × 正文 HIGH → MEDIUM
        assertEquals(Confidence.MEDIUM, ready.confidence)
        assertTrue(h.books.books.values.single().isTemporary)
        // v1.0.3 §13.2.6：临时阅读同样持久化单章正文
        val chapter = h.chapters.chapters.values.single()
        assertTrue(chapter.isCached)
        assertNotNull(h.chapters.loadContent(chapter.id))
    }

    @Test
    fun parseForTemporaryReadFailureReturnsFailedOutcomeWithoutPersisting() = runTest {
        val h = harness()
        h.classifier.script = AppResult.Success(classification(PageKind.CHAPTER, url = chapter3Url))
        h.pipeline.contentScript = ParseOutcome.Failure(ReaderError.Parse(ParseFailure.CONTENT_EMPTY), emptyList())

        val outcome = h.service.parseForTemporaryRead(chapter3Url)
        val failed = outcome as TempReadOutcome.Failed

        assertEquals(ParseFailure.CONTENT_EMPTY, (failed.error as ReaderError.Parse).failure)
        assertTrue(h.books.books.isEmpty())
    }

    @Test
    fun upsertStorageFailureSurfacesAsFailedStepWithRecovery() = runTest {
        val h = harness()
        h.classifier.script = AppResult.Success(classification(PageKind.BOOK_DETAIL))
        h.pipeline.detailScript = detailOf()
        h.pipeline.catalogScript = catalogOf()
        h.books.scriptedFailure = ReaderError.Storage(StorageFailure.DB_ERROR)

        val steps = h.service.importFromUrl(rawUrl).toList()
        val failure = (steps.last() as ImportStep.Finished).outcome as ImportOutcome.Failure

        assertEquals(StorageFailure.DB_ERROR, (failure.error as ReaderError.Storage).failure)
        // 02 §10：Storage.* → 重试/清理缓存 → 参考策略返回 RETRY
        assertEquals(listOf(RecoveryAction.RETRY), failure.recovery)
        assertNotNull(failure.classification)
        assertFalse(steps.mapNotNull { (it as? ImportStep.PhaseChanged)?.phase }.contains(ImportPhase.SUCCESS))
    }

    @Test
    fun importFlowIsCancellableBetweenPhasesWithoutTouchingStorage() = runTest {
        val h = harness()
        h.classifier.script = AppResult.Success(classification(PageKind.BOOK_DETAIL))
        h.pipeline.detailScript = detailOf()
        h.pipeline.catalogScript = catalogOf()

        // 契约：进度经 Flow 逐步上报、可取消 —— 消费 2 个事件后取消
        val steps = h.service.importFromUrl(rawUrl).take(2).toList()

        assertEquals(
            listOf(ImportPhase.CLASSIFYING, ImportPhase.PARSING_DETAIL),
            steps.mapNotNull { (it as? ImportStep.PhaseChanged)?.phase },
        )
        // 取消后不再触碰目录解析与入库
        assertTrue(h.log.none { it == "parseCatalog" || it == "upsertFromImport" || it == "replaceCatalog" })
        assertTrue(h.books.books.isEmpty())
    }

    @Test
    fun overallConfidenceTakesTheWeakerSide() = runTest {
        val h = harness()
        h.classifier.script = AppResult.Success(classification(PageKind.BOOK_DETAIL))
        h.pipeline.detailScript = detailOf(confidence = Confidence.LOW)
        h.pipeline.catalogScript = catalogOf(confidence = Confidence.HIGH)

        val steps = h.service.importFromUrl(rawUrl).toList()
        val success = (steps.last() as ImportStep.Finished).outcome as ImportOutcome.Success

        assertEquals(Confidence.LOW, success.confidence)
    }

    // ------------------------------------------------------------------
    // 测试内自建 Fake（仅实现冻结契约接口，内存实现）
    // ------------------------------------------------------------------

    private class Harness(val log: MutableList<String>) {
        val logger = FakeLogger()
        val classifier = FakeUrlClassifier(log)
        val pipeline = FakeParserPipeline(log)
        val chapters = FakeChapterRepository(log)
        val books = FakeBookRepository(log).apply { chapterStore = chapters }
        /** C1 切换：真实编排实现（构造注入见契约 v1.0.3 §13.1 / docs/05 阶段 5 整合记录） */
        val service: ImportService =
            DefaultImportService(classifier, pipeline, books, chapters, logger)
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

    private fun harness(): Harness = Harness(log)

    /** Fake 网址分类器（契约 02 §5.1） */
    private class FakeUrlClassifier(private val log: MutableList<String>) : UrlClassifier {
        var script: AppResult<UrlClassification> =
            AppResult.Success(UrlClassification("", PageKind.UNKNOWN, Confidence.LOW, null, emptyList()))
        val calls = mutableListOf<String>()

        override suspend fun classify(rawUrl: String): AppResult<UrlClassification> {
            log += "classify"
            calls += rawUrl
            return script
        }
    }

    /** Fake 解析管线（契约 02 §5.2；脚本化返回 + 请求记录） */
    private class FakeParserPipeline(private val log: MutableList<String>) : ParserPipeline {
        var detailScript: ParseOutcome<ParsedBookDetail> = failureDetail(ParseFailure.UNSUPPORTED_STRUCTURE)
        var catalogScript: ParseOutcome<ParsedCatalog> = failureCatalog(ParseFailure.UNSUPPORTED_STRUCTURE)
        var contentScript: ParseOutcome<ParsedChapterContent> = failureContent(ParseFailure.UNSUPPORTED_STRUCTURE)
        val detailRequests = mutableListOf<ParseRequest>()
        val catalogRequests = mutableListOf<ParseRequest>()
        val contentRequests = mutableListOf<ParseRequest>()

        override suspend fun parseDetail(request: ParseRequest): ParseOutcome<ParsedBookDetail> {
            log += "parseDetail"; detailRequests += request; return detailScript
        }

        override suspend fun parseCatalog(request: ParseRequest): ParseOutcome<ParsedCatalog> {
            log += "parseCatalog"; catalogRequests += request; return catalogScript
        }

        override suspend fun parseChapterContent(request: ParseRequest): ParseOutcome<ParsedChapterContent> {
            log += "parseChapterContent"; contentRequests += request; return contentScript
        }
    }

    /** Fake 书仓库（契约 02 §7 BookRepository；按规范化 URL 去重的内存实现） */
    private class FakeBookRepository(private val log: MutableList<String>) : BookRepository {
        val books = LinkedHashMap<Long, Book>()
        var scriptedFailure: ReaderError? = null
        var chapterStore: FakeChapterRepository? = null
        val upsertCalls = mutableListOf<UpsertCall>()
        private var nextId = 1L

        data class UpsertCall(
            val detail: ParsedBookDetail?,
            val sourceUrl: String,
            val catalog: ParsedCatalog?,
            val isTemporary: Boolean,
        )

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
            scriptedFailure?.let { return AppResult.Failure(it) }
            upsertCalls += UpsertCall(detail, sourceUrl, catalog, isTemporary)
            val existing = findByNormalizedSourceUrl(sourceUrl)
            return if (existing != null) {
                // 去重合并：非空字段覆盖，空字段保留旧值（禁止伪造/清空）
                books[existing.id] = existing.copy(
                    title = detail?.title ?: existing.title,
                    author = detail?.author ?: existing.author,
                    coverUrl = detail?.coverUrl ?: existing.coverUrl,
                    description = detail?.description ?: existing.description,
                    catalogUrl = detail?.catalogUrl ?: existing.catalogUrl,
                    totalChapters = catalog?.chapters?.size ?: existing.totalChapters,
                    updatedAt = FAKE_NOW,
                )
                writeCatalogOr(existing.id, catalog)?.let { return AppResult.Failure(it) }
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
                    latestChapterTitle = catalog?.chapters?.lastOrNull()?.title,
                    totalChapters = catalog?.chapters?.size ?: 0,
                    currentChapterId = null,
                    lastReadAt = null,
                    createdAt = FAKE_NOW,
                    updatedAt = FAKE_NOW,
                    isTemporary = isTemporary,
                    sourceEncoding = null,
                )
                writeCatalogOr(id, catalog)?.let { return AppResult.Failure(it) }
                AppResult.Success(BookUpsertResult(id, isNew = true, mergedExisting = false))
            }
        }

        /** 目录写入委托（真实系统中位于 data 层 upsert 事务内）；返回失败错误或 null */
        private suspend fun writeCatalogOr(bookId: Long, catalog: ParsedCatalog?): ReaderError? {
            val store = chapterStore ?: return null
            catalog ?: return null
            return when (val r = store.replaceCatalog(bookId, catalog)) {
                is AppResult.Failure -> r.error
                else -> null
            }
        }

        override suspend fun confirmTemporaryBook(
            bookId: Long,
            detail: ParsedBookDetail?,
            catalog: ParsedCatalog?,
        ): AppResult<Long> {
            val book = books[bookId]
                ?: return AppResult.Failure(ReaderError.State(StateFailure.BOOK_NOT_FOUND))
            books[bookId] = book.copy(
                title = detail?.title ?: book.title,
                author = detail?.author ?: book.author,
                coverUrl = detail?.coverUrl ?: book.coverUrl,
                description = detail?.description ?: book.description,
                catalogUrl = detail?.catalogUrl ?: book.catalogUrl,
                totalChapters = catalog?.chapters?.size ?: book.totalChapters,
                isTemporary = false,
                updatedAt = FAKE_NOW,
            )
            catalog?.let { chapterStore?.replaceCatalog(bookId, it) }
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

    /** Fake 章节仓库（契约 02 §7 ChapterRepository；简化合并：仅按 URL 匹配，03 §7.3 全量算法归 WP-D） */
    private class FakeChapterRepository(private val log: MutableList<String>) : ChapterRepository {
        val chapters = LinkedHashMap<Long, Chapter>()
        var scriptedFailure: ReaderError? = null
        val replaceCatalogCalls = mutableListOf<Pair<Long, ParsedCatalog>>()
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
            scriptedFailure?.let { return AppResult.Failure(it) }
            replaceCatalogCalls += bookId to catalog
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
            // 03 §7.3 第 4 步：旧章节未出现在新目录 → isMissing=1（不删除，保护进度/缓存）
            chapters.values.filter { it.bookId == bookId && it.url !in knownUrls }.forEach { stale ->
                chapters[stale.id] = stale.copy(isMissing = true)
                markedMissing++
            }
            val total = maxOf(existingByUrl.size, catalog.chapters.size)
            val report = CatalogMergeReport(
                added = added,
                unchanged = unchanged,
                updatedUrl = 0, // 参考实现未做指纹换址匹配；03 §7.3 全量语义由真实 ChapterRepositoryImpl 承担
                markedMissing = markedMissing,
                matchedRatio = if (total == 0) 1.0 else unchanged.toDouble() / total,
                detectedReversal = catalog.isLikelyReversed,
                requiresReview = false,
            )
            return AppResult.Success(report)
        }

        override suspend fun saveContent(chapterId: Long, content: ParsedChapterContent): AppResult<Unit> {
            log += "saveContent"
            val chapter = chapters[chapterId]
                ?: return AppResult.Failure(ReaderError.State(StateFailure.CHAPTER_NOT_FOUND))
            contents[chapterId] = content
            chapters[chapterId] = chapter.copy(isCached = true, wordCount = content.wordCount, fetchedAt = FAKE_NOW)
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


    // ------------------------------------------------------------------
    // 脚本构造辅助
    // ------------------------------------------------------------------

    private fun classification(
        kind: PageKind,
        url: String = detailUrl,
        confidence: Confidence = Confidence.HIGH,
    ): UrlClassification = UrlClassification(
        normalizedUrl = url,
        kind = kind,
        confidence = confidence,
        matchedRuleId = null,
        notes = listOf("参考实现分类结果"),
    )

    private fun detailOf(
        confidence: Confidence = Confidence.HIGH,
        catalogUrl: String? = null,
        title: String? = "测试书名",
        warnings: List<String> = emptyList(),
    ): ParseOutcome.Success<ParsedBookDetail> = ParseOutcome.Success(
        data = ParsedBookDetail(
            sourceUrl = detailUrl,
            title = title,
            author = "测试作者",
            coverUrl = null,
            description = "测试简介",
            catalogUrl = catalogUrl,
            fieldSources = if (title != null) mapOf(DetailField.TITLE to ParseLayer.STRUCTURED) else emptyMap(),
            fieldConfidence = if (title != null) mapOf(DetailField.TITLE to confidence) else emptyMap(),
        ),
        overallConfidence = confidence,
        layerTrace = emptyList(),
        warnings = warnings,
    )

    private fun catalogOf(
        count: Int = 5,
        confidence: Confidence = Confidence.MEDIUM,
        sourceUrl: String = catalogUrl,
        warnings: List<String> = emptyList(),
    ): ParseOutcome.Success<ParsedCatalog> = ParseOutcome.Success(
        data = ParsedCatalog(
            sourceUrl = sourceUrl,
            chapters = (0 until count).map { i ->
                ChapterRef(order = i, title = "第${i + 1}章 试炼", url = "https://www.example-novel.com/book/1/c${i + 1}.html")
            },
            isLikelyReversed = false,
            paginationTraversed = 1,
            truncatedByLimit = false,
        ),
        overallConfidence = confidence,
        layerTrace = emptyList(),
        warnings = warnings,
    )

    private fun contentOf(
        sourceUrl: String,
        confidence: Confidence = Confidence.HIGH,
        blocks: List<ContentBlock> = listOf(
            ContentBlock.Paragraph("第一段。"),
            ContentBlock.Paragraph("第二段。"),
        ),
    ): ParseOutcome.Success<ParsedChapterContent> = ParseOutcome.Success(
        data = ParsedChapterContent(
            sourceUrl = sourceUrl,
            title = "第三章",
            blocks = blocks,
            nextPageUrl = null,
            nextChapterUrl = null,
            previousChapterUrl = null,
            isTruncated = false,
            wordCount = blocks.sumOf { (it as? ContentBlock.Paragraph)?.text?.length ?: 0 },
        ),
        overallConfidence = confidence,
        layerTrace = emptyList(),
        warnings = emptyList(),
    )

    private companion object {
        const val FAKE_NOW = 1_700_000_000_000L

        fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/')

        fun failureDetail(failure: ParseFailure): ParseOutcome.Failure =
            ParseOutcome.Failure(ReaderError.Parse(failure), emptyList())

        fun failureCatalog(failure: ParseFailure): ParseOutcome.Failure =
            ParseOutcome.Failure(ReaderError.Parse(failure), emptyList())

        fun failureContent(failure: ParseFailure): ParseOutcome.Failure =
            ParseOutcome.Failure(ReaderError.Parse(failure), emptyList())
    }
}
