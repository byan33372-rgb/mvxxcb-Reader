package com.mvxxcb.reader.data.repository

import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.common.time.TimeProvider
import com.mvxxcb.reader.data.db.dao.LocalBookDao
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StorageFailure
import com.mvxxcb.reader.domain.error.StateFailure
import com.mvxxcb.reader.domain.model.LocalBook
import com.mvxxcb.reader.domain.repository.LocalBookRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 本地整本文件仓储实现（契约 v1.1 K11；M0 实体/DAO，主 Agent 整合期实现——WP-L 落地书架 UI 时复核）。
 * deleteFile=true 时同时删除受控文件（filesDir/txt/）；文件缺失按幂等成功处理（登记仍清除）。
 */
class LocalBookRepositoryImpl(
    private val dao: LocalBookDao,
    private val timeProvider: TimeProvider,
    private val logger: AppLogger,
) : LocalBookRepository {

    override fun observeForBook(bookId: Long): Flow<LocalBook?> =
        dao.observeByBookId(bookId).map { it?.toDomain() }

    override suspend fun getByBookId(bookId: Long): LocalBook? = dao.getByBookId(bookId)?.toDomain()

    override suspend fun upsert(book: LocalBook): AppResult<Long> = try {
        AppResult.Success(dao.upsert(book.toEntity()))
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        logger.e(TAG, "LocalBook 登记失败 bookId=${book.bookId}", e)
        AppResult.Failure(ReaderError.Storage(StorageFailure.DB_ERROR, debugDetail = "upsert local_book"))
    }

    override suspend fun deleteFile(bookId: Long, deleteFile: Boolean): AppResult<Unit> {
        val entity = dao.getByBookId(bookId)
            ?: return AppResult.Failure(
                ReaderError.State(StateFailure.LOCAL_BOOK_NOT_FOUND, debugDetail = "bookId=$bookId")
            )
        if (deleteFile) {
            runCatching { java.io.File(entity.filePath).delete() }
                .onFailure { logger.w(TAG, "受控 TXT 文件删除失败（忽略） path=${entity.filePath}") }
        }
        return try {
            dao.deleteByBookId(bookId)
            AppResult.Success(Unit)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            logger.e(TAG, "LocalBook 登记删除失败 bookId=$bookId", e)
            AppResult.Failure(ReaderError.Storage(StorageFailure.DB_ERROR, debugDetail = "delete local_book"))
        }
    }

    private fun LocalBook.toEntity() = com.mvxxcb.reader.data.db.entities.LocalBookEntity(
        id = if (id == 0L) 0L else id,
        bookId = bookId,
        filePath = filePath,
        format = format,
        fileSize = fileSize,
        chapterCount = chapterCount,
        completedChapterCount = completedChapterCount,
        isComplete = isComplete,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun com.mvxxcb.reader.data.db.entities.LocalBookEntity.toDomain() = LocalBook(
        id = id,
        bookId = bookId,
        filePath = filePath,
        format = format,
        fileSize = fileSize,
        chapterCount = chapterCount,
        completedChapterCount = completedChapterCount,
        isComplete = isComplete,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private companion object {
        const val TAG = "LocalBookRepo"
    }
}
