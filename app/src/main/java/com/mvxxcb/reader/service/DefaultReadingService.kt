package com.mvxxcb.reader.service

import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.common.time.TimeProvider
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StateFailure
import com.mvxxcb.reader.domain.model.CatalogMergeReport
import com.mvxxcb.reader.domain.model.ParsedBookDetail
import com.mvxxcb.reader.domain.model.ReaderContent
import com.mvxxcb.reader.domain.repository.BookRepository
import com.mvxxcb.reader.domain.repository.ChapterRepository
import com.mvxxcb.reader.domain.service.ReadingService
import com.mvxxcb.reader.parser.core.ParseRequest
import com.mvxxcb.reader.parser.pipeline.ParserPipeline

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

    private companion object {
        const val TAG = "DefaultReadingSvc"
    }
}
