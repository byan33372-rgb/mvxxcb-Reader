package com.mvxxcb.reader.domain.repository

import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.domain.model.CrawlState
import com.mvxxcb.reader.domain.model.CrawlTask
import kotlinx.coroutines.flow.Flow

/**
 * 采集任务仓储（契约 v1.1 K9 / 设计 07 §4.1；接口骨架 WP-M0，实现 WP-C）。
 *
 * 状态机单点校验（K9）：非法转移返回 `AppResult.Failure(ReaderError.State(INVALID_TASK_STATE))`；
 * 同书非终态唯一（createTask 幂等）。实现层：data/repository/CrawlTaskRepositoryImpl.kt。
 */
interface CrawlTaskRepository {

    /** 采集中心任务清单（updatedAt DESC） */
    fun observeTasks(): Flow<List<CrawlTask>>

    fun observeTask(taskId: Long): Flow<CrawlTask?>

    /** 同书当前非终态任务（WAITING/RUNNING/PAUSED）；无则 null */
    suspend fun getActiveTaskForBook(bookId: Long): CrawlTask?

    /** 建任务入队：同书非终态唯一（幂等——已存在则返回既有任务） */
    suspend fun createTask(bookId: Long, totalChapters: Int): AppResult<CrawlTask>

    /** 状态转移（状态机单点校验） */
    suspend fun updateState(taskId: Long, state: CrawlState): AppResult<Unit>

    /** 进度回写（Worker 逐章推进；当前章标题可空） */
    suspend fun updateProgress(
        taskId: Long,
        succeeded: Int,
        failed: Int,
        currentIndex: Int,
        currentTitle: String?,
    ): AppResult<Unit>

    /** TXT 生成态登记（COMPLETED 自动生成后回写） */
    suspend fun markTxtGenerated(taskId: Long, path: String): AppResult<Unit>

    /** 清除记录（仅终态；非终态返回 State(INVALID_TASK_STATE)） */
    suspend fun deleteTask(taskId: Long): AppResult<Unit>
}
