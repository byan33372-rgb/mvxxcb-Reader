package com.mvxxcb.reader.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.common.time.TimeProvider
import com.mvxxcb.reader.data.db.dao.CrawlTaskDao
import com.mvxxcb.reader.data.db.entities.CrawlTaskEntity
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StateFailure
import com.mvxxcb.reader.domain.error.StorageFailure
import com.mvxxcb.reader.domain.model.CrawlState
import com.mvxxcb.reader.domain.model.CrawlTask
import com.mvxxcb.reader.domain.repository.CrawlTaskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * CrawlTaskRepository 实现（契约 v1.1 K9 / 设计 07 §4.1-5）。
 *
 * 状态机单点校验（K9 冻结矩阵；非法转移 → State(INVALID_TASK_STATE)）：
 * `WAITING→RUNNING→{COMPLETED|FAILED|CANCELLED|PAUSED}`；`PAUSED→RUNNING`；
 * `FAILED→WAITING`；`WAITING/RUNNING→CANCELLED`；终态无出边（终态再入队=新建任务）。
 * 同书非终态唯一（WAITING/RUNNING/PAUSED）由 createTask 幂等强制。
 * Entity↔domain 映射保留在本文件（EntityMappers.kt 归 WP-M 所有，不越权改动）。
 */
class CrawlTaskRepositoryImpl(
    private val dao: CrawlTaskDao,
    private val timeProvider: TimeProvider,
    private val logger: AppLogger,
) : CrawlTaskRepository {

    companion object {
        private const val TAG = "CrawlTaskRepository"

        /** 终态集合（finishedAt 落点；deleteTask 仅限终态） */
        private val TERMINAL_STATES: Set<CrawlState> =
            setOf(CrawlState.COMPLETED, CrawlState.FAILED, CrawlState.CANCELLED)

        /** K9 冻结状态机（单点校验表；终态为空集） */
        private val ALLOWED_TRANSITIONS: Map<CrawlState, Set<CrawlState>> = mapOf(
            CrawlState.WAITING to setOf(CrawlState.RUNNING, CrawlState.CANCELLED),
            CrawlState.RUNNING to setOf(
                CrawlState.COMPLETED, CrawlState.FAILED, CrawlState.CANCELLED, CrawlState.PAUSED,
            ),
            CrawlState.PAUSED to setOf(CrawlState.RUNNING),
            CrawlState.FAILED to setOf(CrawlState.WAITING),
            CrawlState.COMPLETED to emptySet(),
            CrawlState.CANCELLED to emptySet(),
        )
    }

    override fun observeTasks(): Flow<List<CrawlTask>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeTask(taskId: Long): Flow<CrawlTask?> =
        dao.observeById(taskId).map { it?.toDomain() }

    override suspend fun getActiveTaskForBook(bookId: Long): CrawlTask? =
        dao.getActiveByBookId(bookId)?.toDomain()

    override suspend fun createTask(bookId: Long, totalChapters: Int): AppResult<CrawlTask> {
        return try {
            val existing = dao.getActiveByBookId(bookId)
            if (existing != null) {
                // 同书非终态唯一（幂等）：复用既有任务，不新建（07 Q2）
                AppResult.Success(existing.toDomain())
            } else {
                val now = timeProvider.nowMillis()
                val id = dao.insert(
                    CrawlTaskEntity(
                        bookId = bookId,
                        state = CrawlState.WAITING.name,
                        totalChapters = totalChapters,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                val created = dao.getById(id)
                    ?: return AppResult.Failure(
                        ReaderError.State(StateFailure.CRAWL_TASK_NOT_FOUND, "createTask: 插入后读取失败 id=$id"),
                    )
                AppResult.Success(created.toDomain())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SQLiteConstraintException) {
            // FK→books 违反：书不存在（级联校验兜底）
            AppResult.Failure(
                ReaderError.State(StateFailure.BOOK_NOT_FOUND, "createTask: bookId=$bookId 不存在"),
            )
        } catch (e: Exception) {
            logger.e(TAG, "createTask 失败 bookId=$bookId", e)
            AppResult.Failure(ReaderError.Storage(StorageFailure.DB_ERROR, "createTask failed", e))
        }
    }

    override suspend fun updateState(taskId: Long, state: CrawlState): AppResult<Unit> {
        return try {
            val current = dao.getById(taskId)
                ?: return AppResult.Failure(
                    ReaderError.State(StateFailure.CRAWL_TASK_NOT_FOUND, "updateState: taskId=$taskId"),
                )
            val from = CrawlState.valueOf(current.state)
            if (state !in (ALLOWED_TRANSITIONS[from] ?: emptySet())) {
                return AppResult.Failure(
                    ReaderError.State(
                        StateFailure.INVALID_TASK_STATE,
                        "非法转移 $from → $state（taskId=$taskId）",
                    ),
                )
            }
            val now = timeProvider.nowMillis()
            // startedAt/finishedAt 一次性落点（DAO COALESCE：传 null 不覆盖既有值）
            val startedAt = if (state == CrawlState.RUNNING) now else null
            val finishedAt = if (state in TERMINAL_STATES) now else null
            dao.updateState(taskId, state.name, startedAt, finishedAt, now)
            AppResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            AppResult.Failure(
                ReaderError.State(
                    StateFailure.INVALID_TASK_STATE,
                    "updateState: DB 存量状态非法 taskId=$taskId",
                ),
            )
        } catch (e: Exception) {
            logger.e(TAG, "updateState 失败 taskId=$taskId state=$state", e)
            AppResult.Failure(ReaderError.Storage(StorageFailure.DB_ERROR, "updateState failed", e))
        }
    }

    override suspend fun updateProgress(
        taskId: Long,
        succeeded: Int,
        failed: Int,
        currentIndex: Int,
        currentTitle: String?,
    ): AppResult<Unit> {
        return try {
            val current = dao.getById(taskId)
                ?: return AppResult.Failure(
                    ReaderError.State(StateFailure.CRAWL_TASK_NOT_FOUND, "updateProgress: taskId=$taskId"),
                )
            if (CrawlState.valueOf(current.state) in TERMINAL_STATES) {
                // 终态不接受进度回写（Worker 取消竞态下容忍该 Failure，仅日志）
                return AppResult.Failure(
                    ReaderError.State(
                        StateFailure.INVALID_TASK_STATE,
                        "updateProgress: 终态任务 taskId=$taskId state=${current.state}",
                    ),
                )
            }
            dao.updateProgress(taskId, succeeded, failed, currentIndex, currentTitle, timeProvider.nowMillis())
            AppResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            AppResult.Failure(
                ReaderError.State(StateFailure.INVALID_TASK_STATE, "updateProgress: DB 存量状态非法 taskId=$taskId"),
            )
        } catch (e: Exception) {
            logger.e(TAG, "updateProgress 失败 taskId=$taskId", e)
            AppResult.Failure(ReaderError.Storage(StorageFailure.DB_ERROR, "updateProgress failed", e))
        }
    }

    override suspend fun markTxtGenerated(taskId: Long, path: String): AppResult<Unit> {
        return try {
            dao.getById(taskId)
                ?: return AppResult.Failure(
                    ReaderError.State(StateFailure.CRAWL_TASK_NOT_FOUND, "markTxtGenerated: taskId=$taskId"),
                )
            // 任意状态可登记（幂等）：COMPLETED 自动生成 / 用户手动重新生成（07 Q3）均覆盖
            dao.markTxtGenerated(taskId, path, timeProvider.nowMillis())
            AppResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "markTxtGenerated 失败 taskId=$taskId", e)
            AppResult.Failure(ReaderError.Storage(StorageFailure.DB_ERROR, "markTxtGenerated failed", e))
        }
    }

    override suspend fun deleteTask(taskId: Long): AppResult<Unit> {
        return try {
            val current = dao.getById(taskId)
                ?: return AppResult.Failure(
                    ReaderError.State(StateFailure.CRAWL_TASK_NOT_FOUND, "deleteTask: taskId=$taskId"),
                )
            if (CrawlState.valueOf(current.state) !in TERMINAL_STATES) {
                return AppResult.Failure(
                    ReaderError.State(
                        StateFailure.INVALID_TASK_STATE,
                        "deleteTask: 仅终态可清除 taskId=$taskId state=${current.state}",
                    ),
                )
            }
            dao.deleteById(taskId)
            AppResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            AppResult.Failure(
                ReaderError.State(StateFailure.INVALID_TASK_STATE, "deleteTask: DB 存量状态非法 taskId=$taskId"),
            )
        } catch (e: Exception) {
            logger.e(TAG, "deleteTask 失败 taskId=$taskId", e)
            AppResult.Failure(ReaderError.Storage(StorageFailure.DB_ERROR, "deleteTask failed", e))
        }
    }

    // ———— Entity ↔ domain（Entity 不出 data 层，03 §1） ————

    private fun CrawlTaskEntity.toDomain(): CrawlTask = CrawlTask(
        id = id,
        bookId = bookId,
        state = CrawlState.valueOf(state),
        totalChapters = totalChapters,
        succeededCount = succeededCount,
        failedCount = failedCount,
        currentChapterIndex = currentChapterIndex,
        currentChapterTitle = currentChapterTitle,
        txtPath = txtPath,
        txtGenerated = txtGenerated,
        createdAt = createdAt,
        updatedAt = updatedAt,
        startedAt = startedAt,
        finishedAt = finishedAt,
    )
}
