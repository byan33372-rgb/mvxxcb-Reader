package com.mvxxcb.reader.data.repository

import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.common.time.TimeProvider
import com.mvxxcb.reader.data.db.dao.ReadingProgressDao
import com.mvxxcb.reader.data.mapper.toDomain
import com.mvxxcb.reader.data.mapper.toEntity
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StorageFailure
import com.mvxxcb.reader.domain.model.ReadingProgress
import com.mvxxcb.reader.domain.repository.ReadingProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * ReadingProgressRepository 实现（契约 02 §7）。
 * 进度持久化必含章内位置 inChapterFraction（契约 02 §8.6 阅读位置恢复契约）。
 */
class ReadingProgressRepositoryImpl(
    private val progressDao: ReadingProgressDao,
    private val timeProvider: TimeProvider,
    private val logger: AppLogger,
) : ReadingProgressRepository {

    companion object {
        private const val TAG = "ProgressRepository"
    }

    override fun observeProgress(bookId: Long): Flow<ReadingProgress?> =
        progressDao.observeByBookId(bookId).map { it?.toDomain() }

    override suspend fun getProgress(bookId: Long): ReadingProgress? =
        progressDao.getByBookId(bookId)?.toDomain()

    override suspend fun saveProgress(progress: ReadingProgress): AppResult<Unit> = try {
        // updatedAt 一律由注入的 TimeProvider 产生（契约 02 §0.7）
        progressDao.upsert(progress.copy(updatedAt = timeProvider.nowMillis()).toEntity())
        AppResult.Success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.e(TAG, "saveProgress 失败 bookId=${progress.bookId}", e)
        AppResult.Failure(ReaderError.Storage(StorageFailure.DB_ERROR, "saveProgress failed", e))
    }

    override suspend fun clearProgress(bookId: Long): AppResult<Unit> = try {
        progressDao.deleteByBookId(bookId)
        AppResult.Success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.e(TAG, "clearProgress 失败 bookId=$bookId", e)
        AppResult.Failure(ReaderError.Storage(StorageFailure.DB_ERROR, "clearProgress failed", e))
    }
}
