package com.mvxxcb.reader.domain.repository

interface ReadingProgressRepository {
    fun observeProgress(bookId: Long): kotlinx.coroutines.flow.Flow<com.mvxxcb.reader.domain.model.ReadingProgress?>
    suspend fun getProgress(bookId: Long): com.mvxxcb.reader.domain.model.ReadingProgress?
    suspend fun saveProgress(progress: com.mvxxcb.reader.domain.model.ReadingProgress): com.mvxxcb.reader.common.result.AppResult<Unit>
    suspend fun clearProgress(bookId: Long): com.mvxxcb.reader.common.result.AppResult<Unit>
}
