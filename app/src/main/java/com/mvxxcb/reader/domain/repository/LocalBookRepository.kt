package com.mvxxcb.reader.domain.repository

import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.domain.model.LocalBook
import kotlinx.coroutines.flow.Flow

/**
 * 本地整本文件仓储（契约 v1.1 K11；接口骨架 WP-M0，实现 WP-L）。
 *
 * 文件生命周期（生成/覆盖/删除选项）与书架分组查询；实现层：
 * data/repository/LocalBookRepositoryImpl.kt（bookId UNIQUE，一书至多一个登记文件）。
 */
interface LocalBookRepository {

    fun observeForBook(bookId: Long): Flow<LocalBook?>

    suspend fun getByBookId(bookId: Long): LocalBook?

    /** 幂等覆盖登记（重新生成 TXT 后 upsert 同一书） */
    suspend fun upsert(book: LocalBook): AppResult<Long>

    /** 删除登记（deleteFile=true 时同时删除受控文件） */
    suspend fun deleteFile(bookId: Long, deleteFile: Boolean): AppResult<Unit>
}
