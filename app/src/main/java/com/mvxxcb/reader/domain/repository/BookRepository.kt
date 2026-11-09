package com.mvxxcb.reader.domain.repository

interface BookRepository {
    /** 书架：非临时书，按 lastReadAt 倒序、无记录按 updatedAt 倒序 */
    fun observeShelf(): kotlinx.coroutines.flow.Flow<List<com.mvxxcb.reader.domain.model.Book>>
    suspend fun getBook(bookId: Long): com.mvxxcb.reader.domain.model.Book?
    suspend fun findByNormalizedSourceUrl(normalizedUrl: String): com.mvxxcb.reader.domain.model.Book?
    /** 导入入库：按规范化 URL 去重合并；isTemporary=true 建临时书；返回书 id */
    suspend fun upsertFromImport(
        detail: com.mvxxcb.reader.domain.model.ParsedBookDetail?,
        sourceUrl: String,
        catalog: com.mvxxcb.reader.domain.model.ParsedCatalog?,
        isTemporary: Boolean,
    ): com.mvxxcb.reader.common.result.AppResult<com.mvxxcb.reader.domain.model.BookUpsertResult>
    /** 临时书转正式（补全详情/目录） */
    suspend fun confirmTemporaryBook(bookId: Long, detail: com.mvxxcb.reader.domain.model.ParsedBookDetail?,
        catalog: com.mvxxcb.reader.domain.model.ParsedCatalog?): com.mvxxcb.reader.common.result.AppResult<Long>
    suspend fun updateBookFields(bookId: Long, transform: (com.mvxxcb.reader.domain.model.Book) -> com.mvxxcb.reader.domain.model.Book): com.mvxxcb.reader.common.result.AppResult<Unit>
    suspend fun deleteBook(bookId: Long): com.mvxxcb.reader.common.result.AppResult<Unit>   // 级联章节/进度/书签
}
