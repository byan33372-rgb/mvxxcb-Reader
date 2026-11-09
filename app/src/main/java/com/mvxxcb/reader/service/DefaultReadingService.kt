package com.mvxxcb.reader.service

import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.common.time.TimeProvider
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StateFailure
import com.mvxxcb.reader.domain.model.CatalogMergeReport
import com.mvxxcb.reader.domain.model.CrawlState
import com.mvxxcb.reader.domain.model.LocalBook
import com.mvxxcb.reader.domain.model.ParsedBookDetail
import com.mvxxcb.reader.domain.model.ReaderContent
import com.mvxxcb.reader.domain.repository.BookRepository
import com.mvxxcb.reader.domain.repository.ChapterRepository
import com.mvxxcb.reader.domain.repository.CrawlTaskRepository
import com.mvxxcb.reader.domain.repository.LocalBookRepository
import com.mvxxcb.reader.domain.service.ReadingService
import com.mvxxcb.reader.data.work.CrawlTaskScheduler
import com.mvxxcb.reader.domain.export.TxtExporter
import com.mvxxcb.reader.parser.core.ParseRequest
import com.mvxxcb.reader.parser.pipeline.ParserPipeline
import kotlinx.coroutines.flow.first

/**
 * 阅读取数实现（契约 02 §6.2；装配层组合 parser+data）。
 *
 * 语义以 WP-Q 集成测试（ReaderFlowIntegrationTest 的参考实现）为 C1 验收基线：
 * 缓存优先 → 未缓存经 pipeline 抓取解析并 saveContent 入库 → 失败以错误数据返回；
 * ReaderContent.title 使用目录标题（兜底语义的验收口径）；
 * loadChapterByUrl 未命中目录条目返回 State.CHAPTER_NOT_FOUND（不自动补录）。
 */
class DefaultReadingService(
    private val bookRepository: BookRepository,
    private val chapterRepository: ChapterRepository,
    private val pipeline: ParserPipeline,
    private val timeProvider: TimeProvider,
    private val logger: AppLogger,
    // 2.0 采集门面（契约 v1.1 K10）；可空参数保持一期构造兼容（QA 集成测试假体不受影响）
    private val crawlTaskRepository: CrawlTaskRepository? = null,
    private val crawlScheduler: CrawlTaskScheduler? = null,
    private val txtExporter: TxtExporter? = null,
    private val localBookRepository: LocalBookRepository? = null,
    private val txtPathBuilder: ((bookId: Long, title: String) -> String)? = null,
) : ReadingService {

    override suspend fun loadChapter(
        bookId: Long,
        chapterIndex: Int,
        preferCache: Boolean,
    ): AppResult<ReaderContent> {
        val chapter = chapterRepository.getChapterByIndex(bookId, chapterIndex)
            ?: return AppResult.Failure(
                ReaderError.State(StateFailure.CHAPTER_NOT_FOUND, debugDetail = "index=$chapterIndex bookId=$bookId")
            )

        if (preferCache) {
            chapterRepository.loadContent(chapter.id)?.let { cached -> return AppResult.Success(cached) }
        }

        // 未缓存 / 绕过缓存 → 抓取解析并入库
        return when (val outcome = pipeline.parseChapterContent(ParseRequest(chapter.url))) {
            is com.mvxxcb.reader.parser.core.ParseOutcome.Failure -> {
                logger.w(TAG, "章节解析失败 index=$chapterIndex err=${outcome.error::class.simpleName}")
                AppResult.Failure(outcome.error)
            }
            is com.mvxxcb.reader.parser.core.ParseOutcome.Success -> {
                when (val save = chapterRepository.saveContent(chapter.id, outcome.data)) {
                    is AppResult.Failure -> AppResult.Failure(save.error)
                    is AppResult.Success -> {
                        touchBook(bookId, chapter.id)
                        AppResult.Success(
                            ReaderContent(
                                chapterId = chapter.id,
                                chapterIndex = chapter.chapterIndex,
                                bookId = chapter.bookId,
                                title = chapter.title,
                                blocks = outcome.data.blocks,
                            )
                        )
                    }
                }
            }
        }
    }

    override suspend fun loadChapterByUrl(bookId: Long, url: String): AppResult<ReaderContent> {
        val chapter = chapterRepository.getChapterByUrl(bookId, url)
            ?: return AppResult.Failure(
                // SEC-6-2：日志不落完整 URL（01 §8 隐私最小化），仅保留定位所需的 id 维度
                ReaderError.State(StateFailure.CHAPTER_NOT_FOUND, debugDetail = "bookId=$bookId urlHash=${url.hashCode()}")
            )
        return loadChapter(bookId, chapter.chapterIndex, preferCache = true)
    }

    override suspend fun refreshCatalog(bookId: Long): AppResult<CatalogMergeReport> {
        val book = bookRepository.getBook(bookId)
            ?: return AppResult.Failure(ReaderError.State(StateFailure.BOOK_NOT_FOUND, debugDetail = "bookId=$bookId"))
        val target = book.catalogUrl ?: book.sourceUrl
        return when (val outcome = pipeline.parseCatalog(ParseRequest(target))) {
            is com.mvxxcb.reader.parser.core.ParseOutcome.Failure -> AppResult.Failure(outcome.error)
            is com.mvxxcb.reader.parser.core.ParseOutcome.Success ->
                chapterRepository.replaceCatalog(bookId, outcome.data)
        }
    }

    override suspend fun refreshBookDetail(bookId: Long): AppResult<ParsedBookDetail> {
        val book = bookRepository.getBook(bookId)
            ?: return AppResult.Failure(ReaderError.State(StateFailure.BOOK_NOT_FOUND, debugDetail = "bookId=$bookId"))
        return when (val outcome = pipeline.parseDetail(ParseRequest(book.sourceUrl))) {
            is com.mvxxcb.reader.parser.core.ParseOutcome.Failure -> AppResult.Failure(outcome.error)
            is com.mvxxcb.reader.parser.core.ParseOutcome.Success -> {
                val detail = outcome.data
                // 字段非空才覆盖（02 §6.2：不动进度/缓存；禁止伪造/清空）
                bookRepository.updateBookFields(bookId) { current ->
                    current.copy(
                        title = detail.title?.takeIf { it.isNotBlank() } ?: current.title,
                        author = detail.author ?: current.author,
                        coverUrl = detail.coverUrl ?: current.coverUrl,
                        description = detail.description ?: current.description,
                        catalogUrl = detail.catalogUrl ?: current.catalogUrl,
                    )
                }.onFailure { logger.w(TAG, "详情回写失败 bookId=$bookId") }
                AppResult.Success(detail)
            }
        }
    }

    /** 触达更新（03 BookDao：阅读打开时刷新 currentChapterId/lastReadAt）；尽力而为，失败不打扰阅读 */
    private suspend fun touchBook(bookId: Long, chapterId: Long) {
        val now = timeProvider.nowMillis()
        bookRepository.updateBookFields(bookId) { current ->
            current.copy(currentChapterId = chapterId, lastReadAt = now, updatedAt = now)
        }.onFailure { logger.w(TAG, "触达更新失败（忽略） bookId=$bookId") }
    }

    // ———— 2.0 采集门面（契约 v1.1 K10；主 Agent 整合批次实现，机制由 WP-C 提供） ————

    override suspend fun downloadWholeBook(bookId: Long): AppResult<com.mvxxcb.reader.domain.model.CrawlTask> {
        val repo = requireCrawl() ?: return notAssembled()
        val book = bookRepository.getBook(bookId)
            ?: return AppResult.Failure(ReaderError.State(StateFailure.BOOK_NOT_FOUND, debugDetail = "bookId=$bookId"))
        if (book.totalChapters <= 0) {
            return AppResult.Failure(
                ReaderError.State(StateFailure.INVALID_ARGUMENT, debugDetail = "无目录：请先在详情页更新目录后再下载整本")
            )
        }
        // 同书幂等复用由 CrawlTaskRepositoryImpl.createTask 保证（非终态任务直接返回）
        return when (val created = repo.createTask(bookId, book.totalChapters)) {
            is AppResult.Failure -> created
            is AppResult.Success -> {
                crawlScheduler?.enqueueCrawl(created.value.id, bookId)
                    ?: logger.w(TAG, "CrawlTaskScheduler 未装配，任务已建但未入队 taskId=${created.value.id}")
                AppResult.Success(created.value)
            }
        }
    }

    override suspend fun pauseCrawl(taskId: Long): AppResult<Unit> {
        val repo = requireCrawl() ?: return notAssembled()
        return repo.updateState(taskId, CrawlState.PAUSED)
    }

    override suspend fun resumeCrawl(taskId: Long): AppResult<Unit> {
        val repo = requireCrawl() ?: return notAssembled()
        val task = repo.observeTask(taskId).first()
            ?: return AppResult.Failure(ReaderError.State(StateFailure.CRAWL_TASK_NOT_FOUND, debugDetail = "taskId=$taskId"))
        return when (val r = repo.updateState(taskId, CrawlState.RUNNING)) {
            is AppResult.Failure -> r
            is AppResult.Success -> {
                crawlScheduler?.enqueueCrawl(taskId, task.bookId)
                    ?: logger.w(TAG, "CrawlTaskScheduler 未装配，resume 仅改状态未入队 taskId=$taskId")
                AppResult.Success(Unit)
            }
        }
    }

    override suspend fun cancelCrawl(taskId: Long): AppResult<Unit> {
        val repo = requireCrawl() ?: return notAssembled()
        return when (val r = repo.updateState(taskId, CrawlState.CANCELLED)) {
            is AppResult.Failure -> r
            is AppResult.Success -> {
                crawlScheduler?.cancel(taskId)
                AppResult.Success(Unit)
            }
        }
    }

    override suspend fun retryCrawlFailures(taskId: Long): AppResult<Unit> {
        val repo = requireCrawl() ?: return notAssembled()
        val task = repo.observeTask(taskId).first()
            ?: return AppResult.Failure(ReaderError.State(StateFailure.CRAWL_TASK_NOT_FOUND, debugDetail = "taskId=$taskId"))
        return when (val r = repo.updateState(taskId, CrawlState.WAITING)) {
            is AppResult.Failure -> r
            is AppResult.Success -> {
                crawlScheduler?.enqueueCrawl(taskId, task.bookId)
                    ?: logger.w(TAG, "CrawlTaskScheduler 未装配，重试仅改状态未入队 taskId=$taskId")
                AppResult.Success(Unit)
            }
        }
    }

    /** 单章重采（用户任务书功能15）：强制绕过缓存重新解析入库；任务侧进度由 Worker 后续派生清单自然覆盖 */
    override suspend fun retryChapter(bookId: Long, chapterId: Long): AppResult<com.mvxxcb.reader.domain.model.ChapterMeta> {
        val meta = chapterRepository.getChapterMeta(chapterId)
            ?: return AppResult.Failure(
                ReaderError.State(StateFailure.CHAPTER_NOT_FOUND, debugDetail = "chapterId=$chapterId")
            )
        if (meta.bookId != bookId) {
            return AppResult.Failure(
                ReaderError.State(StateFailure.INVALID_ARGUMENT, debugDetail = "章节不属于该书 chapterId=$chapterId bookId=$bookId")
            )
        }
        return when (val r = loadChapter(bookId, meta.chapterIndex, preferCache = false)) {
            is AppResult.Failure -> r
            is AppResult.Success -> AppResult.Success(meta)
        }
    }

    override suspend fun exportBookTxt(bookId: Long): AppResult<LocalBook> {
        val exporter = txtExporter
            ?: return AppResult.Failure(ReaderError.State(StateFailure.INVALID_ARGUMENT, debugDetail = "TxtExporter 未装配"))
        val repo = requireCrawl() ?: return notAssembled()
        val localRepo = localBookRepository
            ?: return AppResult.Failure(ReaderError.State(StateFailure.INVALID_ARGUMENT, debugDetail = "LocalBookRepository 未装配"))
        val pathBuilder = txtPathBuilder
            ?: return AppResult.Failure(ReaderError.State(StateFailure.INVALID_ARGUMENT, debugDetail = "txtPathBuilder 未装配"))
        val book = bookRepository.getBook(bookId)
            ?: return AppResult.Failure(ReaderError.State(StateFailure.BOOK_NOT_FOUND, debugDetail = "bookId=$bookId"))
        val outPath = pathBuilder(bookId, book.title.ifBlank { "book-$bookId" })
        return when (val exported = exporter.export(bookId, outPath)) {
            is AppResult.Failure -> exported
            is AppResult.Success -> {
                val report = exported.value
                val now = timeProvider.nowMillis()
                val existing = localRepo.getByBookId(bookId)
                val local = LocalBook(
                    id = existing?.id ?: 0L,
                    bookId = bookId,
                    filePath = outPath,
                    format = "TXT",
                    fileSize = report.fileSize,
                    chapterCount = report.chapterCount,
                    completedChapterCount = report.completedChapterCount,
                    isComplete = report.failedChapterIds.isEmpty(),
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )
                when (val registered = localRepo.upsert(local)) {
                    is AppResult.Failure -> registered
                    is AppResult.Success -> {
                        // 任务侧 TXT 生成标记（尽力而为：取该书最近任务）
                        val taskId = repo.observeTasks().first()
                            .filter { it.bookId == bookId }
                            .maxByOrNull { it.updatedAt }?.id
                        taskId?.let { repo.markTxtGenerated(it, outPath) }
                            ?.onFailure { logger.w(TAG, "markTxtGenerated 失败（忽略） taskId=$taskId") }
                        AppResult.Success(local.copy(id = registered.value))
                    }
                }
            }
        }
    }

    private fun requireCrawl(): CrawlTaskRepository? = crawlTaskRepository

    private fun notAssembled(): AppResult<Nothing> = AppResult.Failure(
        ReaderError.State(StateFailure.INVALID_ARGUMENT, debugDetail = "采集服务未装配（crawlTaskRepository 为空）")
    )

    private companion object {
        const val TAG = "DefaultReadingSvc"
    }
}
