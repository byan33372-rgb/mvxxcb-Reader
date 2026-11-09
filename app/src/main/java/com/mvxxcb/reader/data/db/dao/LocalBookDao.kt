package com.mvxxcb.reader.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mvxxcb.reader.data.db.entities.LocalBookEntity
import kotlinx.coroutines.flow.Flow

/**
 * local_books 数据访问（DB v2；契约 K11）。
 *
 * bookId UNIQUE（一书至多一个登记文件）；@Upsert 主键冲突走 UPDATE（非 REPLACE，
 * 不触发 FK CASCADE、保 id 稳定），与 BookDao 同约定。
 */
@Dao
interface LocalBookDao {

    @Query("SELECT * FROM local_books WHERE bookId = :bookId LIMIT 1")
    fun observeByBookId(bookId: Long): Flow<LocalBookEntity?>

    @Query("SELECT * FROM local_books WHERE bookId = :bookId LIMIT 1")
    suspend fun getByBookId(bookId: Long): LocalBookEntity?

    /** @Upsert：主键冲突走 UPDATE（幂等覆盖由 Repository 按既有行回填 id 保证） */
    @Upsert
    suspend fun upsert(book: LocalBookEntity): Long

    @Query("DELETE FROM local_books WHERE bookId = :bookId")
    suspend fun deleteByBookId(bookId: Long)
}
