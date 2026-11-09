package com.mvxxcb.reader.domain.cache

interface ContentCacheManager {
    /** 批量缓存（用户主动触发）；进度经 Flow 上报；进程内执行（二期切 WorkManager 持久化） */
    fun cacheChapters(bookId: Long, chapterIds: List<Long>): kotlinx.coroutines.flow.Flow<CacheBatchEvent>
    /** 邻近预取（阅读器自动触发，静默）：缓存 centerIndex±radius 未缓存章节，返回成功数 */
    suspend fun prefetchNeighborhood(bookId: Long, centerChapterIndex: Int, radius: Int = 1):
        com.mvxxcb.reader.common.result.AppResult<Int>
    fun observeCacheStats(bookId: Long): kotlinx.coroutines.flow.Flow<CacheStats>
    suspend fun clearBookCache(bookId: Long): com.mvxxcb.reader.common.result.AppResult<Unit>
    suspend fun clearAllCaches(): com.mvxxcb.reader.common.result.AppResult<Unit>
    /** 完整性校验：contentHash 重算比对 */
    suspend fun verifyBookIntegrity(bookId: Long): com.mvxxcb.reader.common.result.AppResult<IntegrityReport>
    suspend fun cancelActiveBatch(bookId: Long)
}

sealed interface CacheBatchEvent {
    data class Started(val total: Int) : CacheBatchEvent
    data class Progress(val done: Int, val total: Int, val currentTitle: String?) : CacheBatchEvent
    data class ChapterFinished(val chapterId: Long, val success: Boolean,
        val error: com.mvxxcb.reader.domain.error.ReaderError? = null) : CacheBatchEvent
    data class Completed(val report: CacheBatchReport) : CacheBatchEvent
}

data class CacheBatchReport(val succeeded: Int, val failed: Int, val cancelled: Boolean, val failures: List<ChapterFailure>)
data class ChapterFailure(val chapterId: Long, val error: com.mvxxcb.reader.domain.error.ReaderError)
data class CacheStats(val cachedCount: Int, val totalCount: Int, val totalChars: Long)
data class IntegrityReport(val checked: Int, val corruptedChapterIds: List<Long>)
