package com.mvxxcb.reader.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mvxxcb.reader.data.db.entities.ReadingProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingProgressDao {

    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    suspend fun getByBookId(bookId: Long): ReadingProgressEntity?

    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    fun observeByBookId(bookId: Long): Flow<ReadingProgressEntity?>

    /** @Upsert：一书一条，重复保存覆盖 */
    @Upsert
    suspend fun upsert(progress: ReadingProgressEntity)

    @Query("DELETE FROM reading_progress WHERE bookId = :bookId")
    suspend fun deleteByBookId(bookId: Long)
}
