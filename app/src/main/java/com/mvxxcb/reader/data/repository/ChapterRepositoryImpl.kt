package com.mvxxcb.reader.data.repository

import androidx.room.withTransaction
import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.common.time.TimeProvider
import com.mvxxcb.reader.data.db.AppDatabase
import com.mvxxcb.reader.data.db.dao.ChapterDao
import com.mvxxcb.reader.data.db.entities.ChapterEntity
import com.mvxxcb.reader.data.mapper.ContentBlockCodec
import com.mvxxcb.reader.data.mapper.toDomain
import com.mvxxcb.reader.data.util.SourceUrlNormalizer
import com.mvxxcb.reader.data.util.TitleFingerprint
import com.mvxxcb.reader.domain.error.ParseFailure
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StateFailure
import com.mvxxcb.reader.domain.error.StorageFailure
import com.mvxxcb.reader.domain.model.CatalogMergeReport
import com.mvxxcb.reader.domain.model.Chapter
import com.mvxxcb.reader.domain.model.ChapterMeta
import com.mvxxcb.reader.domain.model.ChapterRef
import com.mvxxcb.reader.domain.model.ParsedCatalog
import com.mvxxcb.reader.domain.model.ParsedChapterContent
import com.mvxxcb.reader.domain.model.ReaderContent
import com.mvxxcb.reader.domain.repository.ChapterRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * ChapterRepository 实现（契约 02 §7）。
 *
 * replaceCatalog 严格实现数据模型 03 §7.3 七步合并算法（见 [CatalogMerger]）；
 * 正文存取见 03 §8（ContentBlock JSON / contentHash / wordCount）。
 */
class ChapterRepositoryImpl(
    private val db: AppDatabase,
    private val chapterDao: ChapterDao,
    private val catalogMerger: CatalogMerger,
    private val timeProvider: TimeProvider,
    private val logger: AppLogger,
) : ChapterRepository {

    companion object {
        private const val TAG = "ChapterRepository"
    }

    override fun observeChapterMetas(bookId: Long): Flow<List<ChapterMeta>> =
        chapterDao.observeChapterMetas(bookId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getChapterMeta(chapterId: Long): ChapterMeta? =
        chapterDao.getById(chapterId)?.let { it.toChapterMeta() }

    override suspend fun getChapterByIndex(bookId: Long, chapterIndex: Int): Chapter? =
        chapterDao.getByIndex(bookId, chapterIndex)?.toDomain()

    override suspend fun getChapterByUrl(bookId: Long, normalizedUrl: String): Chapter? =
        chapterDao.getByNormalizedUrl(bookId, normalizedUrl)?.toDomain()

    override suspend fun replaceCatalog(
        bookId: Long,
        catalog: ParsedCatalog,
    ): AppResult<CatalogMergeReport> = try {
        AppResult.Success(catalogMerger.merge(bookId, catalog, timeProvider.nowMillis()))
    } catch (e: CancellationException) {
        throw e
    } catch (e: IllegalArgumentException) {
        // 空目录兜底拒绝（03 §7.3 禁止盲目覆盖）：不落库，按解析错误返回
        AppResult.Failure(
            ReaderError.Parse(
                failure = ParseFailure.CATALOG_EMPTY,
                debugDetail = "replaceCatalog 拒绝空目录 bookId=$bookId",
            )
        )
    } catch (e: Exception) {
        // 事务内任何步骤失败 → 整批回滚（03 §7.3 保证）；转 Storage 错误返回（契约 02 §0.3）
        logger.e(TAG, "replaceCatalog 失败（已回滚）bookId=$bookId", e)
        AppResult.Failure(ReaderError.Storage(StorageFailure.DB_ERROR, "replaceCatalog failed", e))
    }

    override suspend fun saveContent(
        chapterId: Long,
        content: ParsedChapterContent,
    ): AppResult<Unit> {
        return try {
            chapterDao.getById(chapterId)
                ?: return AppResult.Failure(
                    ReaderError.State(StateFailure.CHAPTER_NOT_FOUND, "saveContent: chapterId=$chapterId")
                )
            val blocks = content.blocks
            val json = ContentBlockCodec.blocksToJson(blocks)
            val hash = ContentBlockCodec.computeContentHash(blocks)
            // wordCount 由 data 层按段落字符数重算（03 §8），不信任上游字段
            val words = ContentBlockCodec.computeWordCount(blocks)
            chapterDao.saveContent(chapterId, json, hash, words, timeProvider.nowMillis())
            AppResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "saveContent 失败 chapterId=$chapterId", e)
            AppResult.Failure(ReaderError.Storage(StorageFailure.DB_ERROR, "saveContent failed", e))
        }
    }

    override suspend fun loadContent(chapterId: Long): ReaderContent? {
        val chapter = chapterDao.getById(chapterId) ?: return null
        if (chapter.content == null) return null
        val blocks = ContentBlockCodec.blocksFromJson(chapter.content)
        if (blocks == null) {
            // 内容损坏：返回 null（不崩溃，03 §4 精神）；完整性校验由 ContentCacheManager.verifyBookIntegrity 处理
            logger.w(TAG, "loadContent 内容 JSON 损坏 chapterId=$chapterId")
            return null
        }
        return ReaderContent(
            chapterId = chapter.id,
            chapterIndex = chapter.chapterIndex,
            bookId = chapter.bookId,
            title = chapter.title,
            blocks = blocks,
        )
    }

    override suspend fun deleteContent(chapterId: Long): AppResult<Unit> = try {
        chapterDao.deleteContent(chapterId)
        AppResult.Success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.e(TAG, "deleteContent 失败 chapterId=$chapterId", e)
        AppResult.Failure(ReaderError.Storage(StorageFailure.DB_ERROR, "deleteContent failed", e))
    }

    override suspend fun appendChapter(
        bookId: Long,
        ref: ChapterRef,
    ): AppResult<ChapterMeta> {
        return try {
            val now = timeProvider.nowMillis()
            // 防御性二次规范化（与 CatalogMerger 同规则；ChapterRef.url 契约已规范化）
            val normalized = SourceUrlNormalizer.normalize(ref.url) ?: ref.url
            val result: ChapterMeta = db.withTransaction {
                // 幂等：规范化 URL 命中 → 直接返回既有条目，不写库（保留既有标题/卷标题/进度关联）
                chapterDao.getByNormalizedUrl(bookId, normalized)?.let { existing ->
                    return@withTransaction existing.toChapterMeta()
                }
                val book = db.bookDao().getById(bookId)
                    ?: throw ChapterNotFoundException(bookId)
                // 追加为当前最大 chapterIndex+1 的末章（在缺失章尾部区段之后，天然无冲突）
                val nextIndex = (chapterDao.getMaxChapterIndex(bookId) ?: -1) + 1
                val rowId = chapterDao.insert(
                    ChapterEntity(
                        bookId = bookId,
                        chapterIndex = nextIndex,
                        title = ref.title,
                        url = normalized,
                        normalizedUrl = normalized,
                        titleFingerprint = TitleFingerprint.of(ref.title),
                        volumeTitle = ref.volumeTitle,
                        // content 空 / isMissing=0 / isCached=0（列默认）
                    )
                )
                // books 同步：totalChapters+1、latestChapterTitle=新末章、updatedAt=now；
                // catalogUrl 无来源（补录不带目录页信息），保持原值不动
                db.bookDao().updateCatalogSummary(
                    bookId = bookId,
                    totalChapters = book.totalChapters + 1,
                    latestChapterTitle = ref.title,
                    catalogUrl = book.catalogUrl,
                    updatedAt = now,
                )
                ChapterMeta(
                    id = rowId,
                    bookId = bookId,
                    chapterIndex = nextIndex,
                    title = ref.title,
                    url = normalized,
                    isCached = false,
                    isMissing = false,
                    wordCount = 0,
                    fetchedAt = null,
                )
            }
            AppResult.Success(result)
        } catch (e: ChapterNotFoundException) {
            AppResult.Failure(ReaderError.State(StateFailure.BOOK_NOT_FOUND, "appendChapter: bookId=$bookId"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "appendChapter 失败 bookId=$bookId", e)
            AppResult.Failure(ReaderError.Storage(StorageFailure.DB_ERROR, "appendChapter failed", e))
        }
    }

    // ———— 内部辅助 ————

    private fun ChapterEntity.toChapterMeta(): ChapterMeta = ChapterMeta(
        id = id, bookId = bookId, chapterIndex = chapterIndex, title = title, url = url,
        isCached = isCached, isMissing = isMissing, wordCount = wordCount, fetchedAt = fetchedAt,
    )

    private class ChapterNotFoundException(val bookId: Long) : Exception("book not found: $bookId")
}
