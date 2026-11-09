package com.mvxxcb.reader.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mvxxcb.reader.data.db.entities.CrawlTaskEntity
import kotlinx.coroutines.flow.Flow

/**
 * crawl_tasks 数据访问（DB v2；契约 K9 / 设计 07 §4.1）。
 *
 * 状态机校验在 CrawlTaskRepository 单点执行，DAO 只做存储；非终态集合
 * = WAITING/RUNNING/PAUSED（同书至多一个，Repository 强制）。
 */
@Dao
interface CrawlTaskDao {

    /** 采集中心清单：updatedAt DESC（索引 index_crawl_tasks_updatedAt DESC 支撑） */
    @Query("SELECT * FROM crawl_tasks ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<CrawlTaskEntity>>

    @Query("SELECT * FROM crawl_tasks WHERE id = :taskId")
    fun observeById(taskId: Long): Flow<CrawlTaskEntity?>

    @Query("SELECT * FROM crawl_tasks WHERE id = :taskId")
    suspend fun getById(taskId: Long): CrawlTaskEntity?

    /** 同书非终态任务（幂等入队判据）；无则 null */
    @Query(
        "SELECT * FROM crawl_tasks WHERE bookId = :bookId " +
            "AND state IN ('WAITING', 'RUNNING', 'PAUSED') LIMIT 1"
    )
    suspend fun getActiveByBookId(bookId: Long): CrawlTaskEntity?

    /** 新建任务（终态再入队=新建，见实体 KDoc）；返回自增 id */
    @Insert
    suspend fun insert(task: CrawlTaskEntity): Long

    /**
     * 状态转移回写：startedAt/finishedAt 用 COALESCE 保持既有值——
     * 传非空即一次性落点（首进 RUNNING 写 startedAt、到终态写 finishedAt），传 null 不覆盖。
     */
    @Query(
        "UPDATE crawl_tasks SET state = :state, " +
            "startedAt = COALESCE(:startedAt, startedAt), " +
            "finishedAt = COALESCE(:finishedAt, finishedAt), " +
            "updatedAt = :now WHERE id = :taskId"
    )
    suspend fun updateState(taskId: Long, state: String, startedAt: Long?, finishedAt: Long?, now: Long)

    /** 进度回写（Worker 逐章推进；currentTitle 可空） */
    @Query(
        "UPDATE crawl_tasks SET succeededCount = :succeeded, failedCount = :failed, " +
            "currentChapterIndex = :currentIndex, currentChapterTitle = :currentTitle, " +
            "updatedAt = :now WHERE id = :taskId"
    )
    suspend fun updateProgress(
        taskId: Long,
        succeeded: Int,
        failed: Int,
        currentIndex: Int,
        currentTitle: String?,
        now: Long,
    )

    /** TXT 生成态登记 */
    @Query(
        "UPDATE crawl_tasks SET txtPath = :txtPath, txtGenerated = 1, " +
            "updatedAt = :now WHERE id = :taskId"
    )
    suspend fun markTxtGenerated(taskId: Long, txtPath: String, now: Long)

    @Query("DELETE FROM crawl_tasks WHERE id = :taskId")
    suspend fun deleteById(taskId: Long)
}
