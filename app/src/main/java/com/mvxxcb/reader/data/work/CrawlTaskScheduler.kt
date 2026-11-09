package com.mvxxcb.reader.data.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.domain.model.CrawlState
import com.mvxxcb.reader.domain.repository.CrawlTaskRepository
import kotlinx.coroutines.flow.first

/**
 * 整本采集任务调度器（设计 07 §第四部分配套：unique name `crawl-task-<id>`，KEEP 入队/cancel）。
 *
 * - enqueueCrawl：入队 [ChapterDownloadWorker]（inputData 只传 taskId + bookId 冗余校验）；
 *   KEEP 防重复——resume/重启对账重复入队时，若持久化任务仍在则去重，已结束则重新入队；
 * - cancel：cancelUniqueWork 取消链（门面 K10 cancelCrawl 先置 CANCELLED 再取消）；
 * - reconcileOnAppStart：进程重启对账（07 风险#7）——WAITING/RUNNING 任务重新入队
 *   （WorkManager 持久化任务 KEEP 去重；DB 状态即真相，Worker 幂等续采）；
 *   PAUSED 等待用户显式继续，不入队。由主 Agent 在应用启动时调用（ReaderApplication/AppContainer
 *   整合位）。
 */
class CrawlTaskScheduler(
    private val context: Context,
    private val logger: AppLogger? = null,
) {

    /** 唯一任务名（按任务 id；同一任务重复入队 KEEP 去重） */
    fun workName(taskId: Long): String = "crawl-task-$taskId"

    /** 入队整本采集（KEEP 防重复） */
    fun enqueueCrawl(taskId: Long, bookId: Long) {
        val request = OneTimeWorkRequest.Builder(ChapterDownloadWorker::class.java)
            .setInputData(ChapterDownloadWorker.buildInputData(taskId, bookId))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName(taskId), ExistingWorkPolicy.KEEP, request)
    }

    /** 取消某任务（取消链：先 repository 置 CANCELLED，再 cancelUniqueWork） */
    fun cancel(taskId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(taskId))
    }

    /**
     * 进程重启对账：非终态任务中 WAITING/RUNNING 重新入队（KEEP 幂等去重）；
     * PAUSED 交还用户（不自动续跑）；终态跳过。
     */
    suspend fun reconcileOnAppStart(crawlTaskRepository: CrawlTaskRepository) {
        val tasks = runCatching { crawlTaskRepository.observeTasks().first() }
            .onFailure { logger?.w(TAG, "重启对账读取任务失败：${it.message}") }
            .getOrDefault(emptyList())
        val toResume = tasks.filter { it.state == CrawlState.WAITING || it.state == CrawlState.RUNNING }
        toResume.forEach { task ->
            runCatching { enqueueCrawl(task.id, task.bookId) }
                .onFailure { logger?.w(TAG, "重启对账入队失败 taskId=${task.id}: ${it.message}") }
        }
        logger?.d(TAG, "重启对账完成：待续跑 ${toResume.size} 项")
    }

    companion object {
        private const val TAG = "CrawlTaskScheduler"
    }
}
