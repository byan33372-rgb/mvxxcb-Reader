package com.mvxxcb.reader.data.repository

import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.common.time.TimeProvider
import com.mvxxcb.reader.data.db.AppDatabase
import com.mvxxcb.reader.data.db.dao.BookDao
import com.mvxxcb.reader.data.db.entities.BookEntity
import com.mvxxcb.reader.data.mapper.toDomain
import com.mvxxcb.reader.data.util.SourceUrlNormalizer
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StateFailure
import com.mvxxcb.reader.domain.error.StorageFailure
import com.mvxxcb.reader.domain.model.Book
import com.mvxxcb.reader.domain.model.BookUpsertResult
import com.mvxxcb.reader.domain.model.ParsedBookDetail
import com.mvxxcb.reader.domain.model.ParsedCatalog
import com.mvxxcb.reader.domain.repository.BookRepository
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * BookRepository 实现（契约 02 §7）。
 *
 * upsertFromImport：按规范化 URL（normalizedSourceUrl）去重合并——命中已有书则
 * 非空字段合并更新（禁止伪造，空字段不覆盖既有值），并合并目录（03 §7.3）；
 * 未命中则建新书（临时书按 isTemporary）。
 */
class BookRepositoryImpl(
    private val db: AppDatabase,
    private val bookDao: BookDao,
    private val catalogMerger: CatalogMerger,
    private val timeProvider: TimeProvider,
    private val logger: AppLogger,
) : BookRepository {

    companion object {
        private const val TAG = "BookRepository"
    }

    override fun observeShelf(): Flow<List<Book>> =
        bookDao.observeShelf().map { list -> list.map { it.toDomain() } }

    override suspend fun getBook(bookId: Long): Book? =
        bookDao.getById(bookId)?.toDomain()

    override suspend fun findByNormalizedSourceUrl(normalizedUrl: String): Book? {
        val key = SourceUrlNormalizer.normalize(normalizedUrl) ?: normalizedUrl
        return bookDao.findByNormalizedSourceUrl(key)?.toDomain()
    }

    override suspend fun upsertFromImport(
        detail: ParsedBookDetail?,
        sourceUrl: String,
        catalog: ParsedCatalog?,
        isTemporary: Boolean,
    ): AppResult<BookUpsertResult> = try {
        val now = timeProvider.nowMillis()
        val normalized = SourceUrlNormalizer.normalize(sourceUrl)
            ?: return AppResult.Failure(
                ReaderError.State(StateFailure.INVALID_ARGUMENT, "sourceUrl 无法规范化：$sourceUrl")
            )
        val domain = SourceUrlNormalizer.hostOf(sourceUrl)

        val result = db.withTransaction {
            val existing = bookDao.findByNormalizedSourceUrl(normalized)
            if (existing != null) {
                // 命中已有书：非空字段合并（禁止伪造——新值空/缺省不覆盖旧值）
                val merged = existing.copy(
                    title = detail?.title?.takeIf { it.isNotBlank() } ?: existing.title,
                    author = detail?.author ?: existing.author,
                    coverUrl = detail?.coverUrl ?: existing.coverUrl,
                    description = detail?.description ?: existing.description,
                    catalogUrl = detail?.catalogUrl ?: existing.catalogUrl,
                    // 完整导入（isTemporary=false）转正式；临时导入不降级既有状态
                    isTemporary = if (isTemporary) existing.isTemporary else false,
                    updatedAt = now,
                )
                bookDao.upsert(merged)
                catalog?.let { catalogMerger.merge(existing.id, it, now) }
                BookUpsertResult(bookId = existing.id, isNew = false, mergedExisting = true)
            } else {
                val entity = BookEntity(
                    title = detail?.title ?: "", // 未解析出书名空串占位，禁止伪造（03 §2.1）
                    author = detail?.author,
                    coverUrl = detail?.coverUrl,
                    description = detail?.description,
                    sourceUrl = normalized,
                    normalizedSourceUrl = normalized,
                    sourceDomain = domain,
                    catalogUrl = detail?.catalogUrl ?: catalog?.sourceUrl,
                    createdAt = now,
                    updatedAt = now,
                    isTemporary = isTemporary,
                )
                val bookId = bookDao.upsert(entity)
                catalog?.let { catalogMerger.merge(bookId, it, now) }
                BookUpsertResult(bookId = bookId, isNew = true, mergedExisting = false)
            }
        }
        AppResult.Success(result)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.e(TAG, "upsertFromImport 失败 sourceUrl=$sourceUrl", e)
        AppResult.Failure(ReaderError.Storage(StorageFailure.DB_ERROR, "upsertFromImport failed", e))
    }

    override suspend fun confirmTemporaryBook(
        bookId: Long,
        detail: ParsedBookDetail?,
        catalog: ParsedCatalog?,
    ): AppResult<Long> {
        return try {
            val now = timeProvider.nowMillis()
            val confirmed: Long = db.withTransaction {
                val existing = bookDao.getById(bookId)
                    ?: throw BookNotFoundException(bookId)
                val merged = existing.copy(
                    title = detail?.title?.takeIf { it.isNotBlank() } ?: existing.title,
                    author = detail?.author ?: existing.author,
                    coverUrl = detail?.coverUrl ?: existing.coverUrl,
                    description = detail?.description ?: existing.description,
                    catalogUrl = detail?.catalogUrl ?: existing.catalogUrl,
                    isTemporary = false,
                    updatedAt = now,
                )
                bookDao.upsert(merged)
                catalog?.let { catalogMerger.merge(bookId, it, now) }
                bookId
            }
            AppResult.Success(confirmed)
        } catch (e: BookNotFoundException) {
            AppResult.Failure(ReaderError.State(StateFailure.BOOK_NOT_FOUND, "bookId=$bookId"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "confirmTemporaryBook 失败 bookId=$bookId", e)
            AppResult.Failure(ReaderError.Storage(StorageFailure.DB_ERROR, "confirmTemporaryBook failed", e))
        }
    }

    override suspend fun updateBookFields(
        bookId: Long,
        transform: (Book) -> Book,
    ): AppResult<Unit> {
        return try {
            val now = timeProvider.nowMillis()
            db.withTransaction {
                val existing = bookDao.getById(bookId) ?: throw BookNotFoundException(bookId)
                val next = transform(existing.toDomain())
                bookDao.upsert(
                    existing.copy(
                        title = next.title,
                        author = next.author,
                        coverUrl = next.coverUrl,
                        description = next.description,
                        catalogUrl = next.catalogUrl,
                        latestChapterTitle = next.latestChapterTitle,
                        sourceEncoding = next.sourceEncoding,
                        isTemporary = next.isTemporary,
                        updatedAt = now,
                    )
                )
            }
            AppResult.Success(Unit)
        } catch (e: BookNotFoundException) {
            AppResult.Failure(ReaderError.State(StateFailure.BOOK_NOT_FOUND, "bookId=$bookId"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "updateBookFields 失败 bookId=$bookId", e)
            AppResult.Failure(ReaderError.Storage(StorageFailure.DB_ERROR, "updateBookFields failed", e))
        }
    }

    override suspend fun deleteBook(bookId: Long): AppResult<Unit> = try {
        // chapters/reading_progress/bookmarks 依赖 FK CASCADE 级联清理（03 §3）
        bookDao.deleteBook(bookId)
        AppResult.Success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.e(TAG, "deleteBook 失败 bookId=$bookId", e)
        AppResult.Failure(ReaderError.Storage(StorageFailure.DB_ERROR, "deleteBook failed", e))
    }

    private class BookNotFoundException(val bookId: Long) : Exception("book not found: $bookId")
}
