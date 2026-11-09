package com.mvxxcb.reader.data.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager

/**
 * 缓存任务调度器（04 §3：二期启用骨架；一期不调用）。
 * 封装 WorkManager 入队细节，二期将接通 [ChapterDownloadWorker] 实际批量缓存逻辑。
 */
class CacheWorkScheduler(private val context: Context) {

    /** 唯一任务名（按书去重） */
    fun workName(bookId: Long): String = "chapter-download-$bookId"

    /** 入队全书批量下载（二期启用；KEEP 防重复）。一期仅保留调度骨架。 */
    fun enqueueBookDownload(bookId: Long, chapterIds: LongArray) {
        val request = OneTimeWorkRequest.Builder(ChapterDownloadWorker::class.java)
            .setInputData(ChapterDownloadWorker.buildInputData(bookId, chapterIds))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName(bookId), ExistingWorkPolicy.KEEP, request)
    }

    /** 取消某书的下载任务（二期启用） */
    fun cancelBookDownload(bookId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(bookId))
    }
}
