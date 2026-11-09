package com.mvxxcb.reader.domain.repository

interface BookmarkRepository {
    fun observeBookmarks(bookId: Long): kotlinx.coroutines.flow.Flow<List<com.mvxxcb.reader.domain.model.Bookmark>>
    suspend fun addBookmark(bookmark: com.mvxxcb.reader.domain.model.Bookmark): com.mvxxcb.reader.common.result.AppResult<Long>
    suspend fun removeBookmark(bookmarkId: Long): com.mvxxcb.reader.common.result.AppResult<Unit>
}
