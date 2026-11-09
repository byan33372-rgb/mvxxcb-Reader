package com.mvxxcb.reader.data.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters

/**
 * 章节批量下载 Worker（04 §3：二期启用的持久化批量任务骨架，一期仅保证编译通过）。
 *
 * 一期批量缓存走进程内 [com.mvxxcb.reader.domain.cache.ContentCacheManager.cacheChapters]；
 * 二期将把批量缓存迁移到 WorkManager 持久执行（进程死亡可续传），本骨架预置：
 *  - inputData 约定（bookId + chapterIds）
 *  - 由 [CacheWorkScheduler] 按 bookId 唯一任务名入队（KEEP 策略防重复）
 */
class ChapterDownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // 二期启用：inputData 读 bookId/chapterIds → ContentCacheManager 批量缓存 →
        // 进度经 setProgress 上报 → 失败按 Result.retry() 退避重试。一期不调度本 Worker。
        return Result.success()
    }

    companion object {
        const val KEY_BOOK_ID = "bookId"
        const val KEY_CHAPTER_IDS = "chapterIds"

        fun buildInputData(bookId: Long, chapterIds: LongArray): Data =
            Data.Builder()
                .putLong(KEY_BOOK_ID, bookId)
                .putLongArray(KEY_CHAPTER_IDS, chapterIds)
                .build()
    }
}
