package com.mvxxcb.reader.domain.model

/**
 * 采集任务模型（契约 v1.1 K9；设计 07 §K9 逐字冻结）。
 *
 * 状态机（非法转移 → `ReaderError.State(INVALID_TASK_STATE)`，校验在
 * CrawlTaskRepository 单点执行）：
 * `WAITING→RUNNING→{COMPLETED|FAILED|CANCELLED|PAUSED}`；
 * `PAUSED→RUNNING`（继续）；`FAILED→WAITING`（重试失败=未缓存章重入队）；
 * `WAITING/RUNNING→CANCELLED`；终态再入队=新建任务。
 * 约束：同一时刻同书至多一个非终态任务（Repository 强制）。
 * 章节级状态以 `chapters.isCached` 为 DB 真值派生（失败清单=未缓存且非 isMissing，
 * 天然支持「已下载章不重采」）。
 */

/** 采集任务状态（六态）；DB 存 TEXT（crawl_tasks.state，v2 默认 'WAITING'） */
enum class CrawlState { WAITING, RUNNING, PAUSED, FAILED, COMPLETED, CANCELLED }

data class CrawlTask(
    val id: Long,
    val bookId: Long,
    val state: CrawlState,
    val totalChapters: Int,                 // 规范化后目录总章数
    val succeededCount: Int,
    val failedCount: Int,
    val currentChapterIndex: Int,
    val currentChapterTitle: String?,
    val txtPath: String?,
    val txtGenerated: Boolean,              // TXT 生成态（COMPLETED 自动生成）
    val createdAt: Long,                    // epoch ms
    val updatedAt: Long,                    // epoch ms
    val startedAt: Long?,                   // epoch ms；null = 尚未进入 RUNNING
    val finishedAt: Long?,                  // epoch ms；null = 未到终态
)
