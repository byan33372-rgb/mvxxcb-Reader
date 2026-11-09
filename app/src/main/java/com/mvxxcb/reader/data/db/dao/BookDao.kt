package com.mvxxcb.reader.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mvxxcb.reader.data.db.entities.BookEntity
import kotlinx.coroutines.flow.Flow

/** 书架观察：非临时书；lastReadAt 倒序，无记录按 updatedAt 倒序（03 §3） */
@Dao
interface BookDao {

    @Query(
        "SELECT * FROM books WHERE isTemporary = 0 " +
            "ORDER BY (lastReadAt IS NULL), lastReadAt DESC, updatedAt DESC"
    )
    fun observeShelf(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE normalizedSourceUrl = :url LIMIT 1")
    suspend fun findByNormalizedSourceUrl(url: String): BookEntity?

    /** 临时书清单（继续阅读/历史用，03 §3） */
    @Query("SELECT * FROM books WHERE isTemporary = 1 ORDER BY lastReadAt DESC LIMIT :limit")
    suspend fun getTemporaryBooks(limit: Int): List<BookEntity>

    /** @Upsert：主键冲突走 UPDATE（非 REPLACE，不触发 FK CASCADE） */
    @Upsert
    suspend fun upsert(book: BookEntity): Long

    /** 触达更新（阅读打开时，03 §3） */
    @Query(
        "UPDATE books SET currentChapterId = :chapterId, lastReadAt = :now, updatedAt = :now " +
            "WHERE id = :bookId"
    )
    suspend fun touch(bookId: Long, chapterId: Long, now: Long)

    /** 目录合并后的书籍汇总同步（03 §7.3 第 7 步） */
    @Query(
        "UPDATE books SET totalChapters = :totalChapters, latestChapterTitle = :latestChapterTitle, " +
            "catalogUrl = :catalogUrl, updatedAt = :updatedAt WHERE id = :bookId"
    )
    suspend fun updateCatalogSummary(
        bookId: Long,
        totalChapters: Int,
        latestChapterTitle: String?,
        catalogUrl: String?,
        updatedAt: Long,
    )

    /** 删除书：chapters/reading_progress/bookmarks 依赖 FK CASCADE 级联清理 */
    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBook(id: Long)
}
