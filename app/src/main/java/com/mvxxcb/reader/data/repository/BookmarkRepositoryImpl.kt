package com.mvxxcb.reader.data.repository

import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.common.time.TimeProvider
import com.mvxxcb.reader.data.db.dao.BookmarkDao
import com.mvxxcb.reader.data.mapper.toDomain
import com.mvxxcb.reader.data.mapper.toEntity
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StorageFailure
import com.mvxxcb.reader.domain.model.Bookmark
import com.mvxxcb.reader.domain.repository.BookmarkRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** BookmarkRepository 实现（契约 02 §7）；selectedText ≤200 字截断见 [toEntity]。 */
class BookmarkRepositoryImpl(
    private val bookmarkDao: BookmarkDao,
    private val timeProvider: TimeProvider,
    private val logger: AppLogger,
) : BookmarkRepository {

    companion object {
        private const val TAG = "BookmarkRepository"
    }

    override fun observeBookmarks(bookId: Long): Flow<List<Bookmark>> =
        bookmarkDao.observeByBookId(bookId).map { list -> list.map { it.toDomain() } }

    override suspend fun addBookmark(bookmark: Bookmark): AppResult<Long> = try {
        val rowId = bookmarkDao.insert(
            bookmark.copy(createdAt = timeProvider.nowMillis()).toEntity()
        )
        AppResult.Success(rowId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.e(TAG, "addBookmark 失败 bookId=${bookmark.bookId}", e)
        AppResult.Failure(ReaderError.Storage(StorageFailure.DB_ERROR, "addBookmark failed", e))
    }

    override suspend fun removeBookmark(bookmarkId: Long): AppResult<Unit> = try {
        bookmarkDao.deleteById(bookmarkId)
        AppResult.Success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.e(TAG, "removeBookmark 失败 id=$bookmarkId", e)
        AppResult.Failure(ReaderError.Storage(StorageFailure.DB_ERROR, "removeBookmark failed", e))
    }
}
