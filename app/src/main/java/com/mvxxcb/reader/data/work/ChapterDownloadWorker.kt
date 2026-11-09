package com.mvxxcb.reader.data.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.mvxxcb.reader.R
import com.mvxxcb.reader.ReaderApplication
import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.common.time.TimeProvider
import com.mvxxcb.reader.data.export.TxtFilePaths
import com.mvxxcb.reader.domain.cache.ChapterFetcher
import com.mvxxcb.reader.domain.export.TxtExporter
import com.mvxxcb.reader.domain.model.Book
import com.mvxxcb.reader.domain.model.ChapterMeta
import com.mvxxcb.reader.domain.model.CrawlState
import com.mvxxcb.reader.domain.model.CrawlTask
import com.mvxxcb.reader.domain.repository.BookRepository
import com.mvxxcb.reader.domain.repository.ChapterRepository
import com.mvxxcb.reader.domain.repository.LocalBookRepository
import com.mvxxcb.reader.domain.repository.CrawlTaskRepository
import com.mvxxcb.reader.parser.core.ParseOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 整本采集 Worker（激活改造，设计 07 §第四部分配套 / 契约 K9；WP-C）。
 *
 * 合规红线（机制级，07 第八部分风险#1/#2、K9）：
 * - **全局串行**：进程级 [CrawlGate] Mutex，同进程同一时刻至多一个采集循环（07 Q5）；
 * - **同书串行**：循环内逐章串行 fetch，无并行章节请求；全部网络经 [ChapterFetcher]
 *   （= ParserPipeline → SecureHttpClient，域名间隔 minIntervalPerHostMs 兜底），禁止绕过；
 * - **403/401/验证码页**：fetch 失败 → 该章失败记录（failedCount），**不重试不绕过**；
 * - **断点续传/已下载章不重采**：待抓清单每轮从 DB 派生
 *   （`isCached=0 且非 isMissing`，按 chapterIndex 升序），章节级状态以 chapters.isCached 为真值；
 * - **inputData 只传 taskId**（绕开 WorkManager 10KB 上限；bookId 仅冗余校验），
 *   万章清单不进 inputData；进度写 CrawlTaskRepository → Room Flow 响应式驱动 UI；
 * - **暂停/取消**：任务标志位（state=PAUSED/CANCELLED）+ 每章间检查，命中即安全退出
 *   （resume 由门面置 RUNNING 后重新 enqueue，继续从 DB 派生剩余清单）；取消链见
 *   CrawlTaskScheduler.cancel（cancelUniqueWork）；
 * - **进程重启恢复**：WorkManager 持久化 + DB 状态即真相；重启对账见
 *   CrawlTaskScheduler.reconcileOnAppStart（WAITING/RUNNING 重新入队，KEEP 幂等去重）。
 * - **收尾**：全部章节完成 → TxtExporter.export → markTxtGenerated → COMPLETED（07 Q3：
 *   COMPLETED 自动生成 TXT）；存在失败章或 TXT 生成失败 → FAILED（不生成 TXT，UI 明示可重试）。
 *
 * 通知：前台进度通知（dataSync 类型）+ 完成/失败结果通知；POST_NOTIFICATIONS 未授权或
 * 前台服务启动失败时降级为静默采集（不中断任务）。
 */
class ChapterDownloadWorker internal constructor(
    appContext: Context,
    params: WorkerParameters,
    private val deps: Deps,
) : CoroutineWorker(appContext, params) {

    /**
     * 生产构造（WorkManager 默认反射工厂走此构造）：依赖自 AppContainer 解析
     * （与 MainActivity 同一手动 DI 模式）。AppContainer 的 crawlTaskRepository/txtExporter
     * 由主 Agent 整合位接入真实实现后生效。
     */
    constructor(appContext: Context, params: WorkerParameters) : this(appContext, params, Deps.from(appContext))

    internal class Deps(
        val crawlTaskRepository: CrawlTaskRepository,
        val chapterRepository: ChapterRepository,
        val bookRepository: BookRepository,
        val fetcher: ChapterFetcher,
        val txtExporter: TxtExporter,
        val localBookRepository: LocalBookRepository? = null,
        val timeProvider: TimeProvider,
        val logger: AppLogger,
    ) {
        companion object {
            fun from(appContext: Context): Deps {
                val container = (appContext.applicationContext as ReaderApplication).container
                return Deps(
                    crawlTaskRepository = container.crawlTaskRepository,
                    chapterRepository = container.chapterRepository,
                    bookRepository = container.bookRepository,
                    fetcher = container.chapterFetcher,
                    txtExporter = container.txtExporter,
                    localBookRepository = container.localBookRepository,
                    timeProvider = container.timeProvider,
                    logger = container.logger,
                )
            }
        }
    }

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(KEY_TASK_ID, -1L)
        if (taskId <= 0L) return Result.failure()
        return try {
            // 全局串行门（07 Q5）：同进程同一时刻至多一个采集循环，多书排队
            CrawlGate.mutex.withLock { runCrawl(taskId, deps) }
        } catch (e: CancellationException) {
            // WorkManager 取消：不回写状态（取消链由门面先置 CANCELLED），直接传播
            throw e
        } catch (e: Exception) {
            deps.logger.e(TAG, "采集 Worker 异常 taskId=$taskId runAttempt=$runAttemptCount", e)
            if (runAttemptCount < MAX_AUTO_RETRIES) {
                // 瞬时异常（DB/IO 抖动）：重试 = 从 DB 派生剩余清单续采（断点续传语义）
                Result.retry()
            } else {
                markFailedIfNonTerminal(taskId, "Worker 连续异常：${e.message}", deps)
                Result.failure(buildOutput(taskId = taskId, state = CrawlState.FAILED))
            }
        }
    }

    // ———— 主流程 ————

    private suspend fun runCrawl(taskId: Long, d: Deps): Result {
        val repo = d.crawlTaskRepository
        val task: CrawlTask = repo.observeTask(taskId).first()
            ?: return Result.failure(buildOutput(taskId = taskId, state = null, note = OUT_TASK_NOT_FOUND))

        // bookId 冗余校验（07 风险#2）：调度侧误投递即拒绝，不触碰任务状态
        val inputBookId = inputData.getLong(KEY_BOOK_ID, -1L)
        if (inputBookId > 0L && inputBookId != task.bookId) {
            d.logger.w(TAG, "bookId 冗余校验失败 taskId=$taskId input=$inputBookId db=${task.bookId}")
            return Result.failure(buildOutput(taskId = taskId, state = task.state, note = OUT_BOOK_MISMATCH))
        }

        // 状态对齐：WAITING→RUNNING；RUNNING（进程重启重入）幂等跳过；其余（PAUSED/终态）无操作退出
        when (task.state) {
            CrawlState.WAITING -> {
                val updated = repo.updateState(taskId, CrawlState.RUNNING)
                if (updated is AppResult.Failure) {
                    // 竞态：状态已被外部改变（取消/暂停等）；按当前状态安全退出（Worker 幂等）
                    val nowState = repo.observeTask(taskId).first()?.state
                    return Result.success(buildOutput(taskId = taskId, state = nowState, note = OUT_STATE_RACED))
                }
            }
            CrawlState.RUNNING -> Unit
            else -> return Result.success(buildOutput(taskId = taskId, state = task.state, note = OUT_NOT_ACTIVE))
        }

        val book = d.bookRepository.getBook(task.bookId)
        if (book == null) {
            markFailedIfNonTerminal(taskId, "所属书不存在 bookId=${task.bookId}", d)
            return Result.success(buildOutput(taskId = taskId, state = CrawlState.FAILED, note = OUT_BOOK_MISSING))
        }

        val metas: List<ChapterMeta> = d.chapterRepository.observeChapterMetas(task.bookId).first()
        if (metas.isEmpty()) {
            // 空目录书：无法整本采集 → FAILED（不生成 TXT）
            repo.updateState(taskId, CrawlState.FAILED)
            notifyResult(book, success = false, detail = "目录为空，无法采集", d)
            return Result.success(buildOutput(taskId = taskId, state = CrawlState.FAILED, ok = 0, failed = 0))
        }

        tryEnterForeground(book.title, d)

        // DB 真值派生待抓清单：isCached=0 且非 isMissing，按 chapterIndex 升序（已下载章不重采）
        val cachedAtStart = metas.count { it.isCached }
        val pending = metas.filter { !it.isCached && !it.isMissing }
        var ok = 0
        var failed = 0
        var lastNotifyAt = 0L

        for (chapter in pending) {
            currentCoroutineContext().ensureActive() // WorkManager 取消协作
            val state = repo.observeTask(taskId).first()?.state
            if (state == null || state != CrawlState.RUNNING) {
                // PAUSED / CANCELLED / 终态：安全退出，不回写状态（resume 由门面重新入队续采）
                return Result.success(
                    buildOutput(taskId = taskId, state = state, ok = ok, failed = failed, note = OUT_PAUSED_OR_CANCELLED),
                )
            }
            // 抓取前先写「当前章」（计数不变，UI 实时可见）
            repo.updateProgress(taskId, cachedAtStart + ok, failed, chapter.chapterIndex, chapter.title)

            when (val outcome = d.fetcher.fetch(chapter.url)) {
                is ParseOutcome.Success -> {
                    // 成功即时持久化（不等批次完成）
                    val saved = d.chapterRepository.saveContent(chapter.id, outcome.data)
                    if (saved is AppResult.Success) ok++ else failed++
                }
                // 解析失败（含 403/401/验证码页）：失败记录，不重试不绕过（07 合规红线）
                is ParseOutcome.Failure -> failed++
            }
            repo.updateProgress(taskId, cachedAtStart + ok, failed, chapter.chapterIndex, chapter.title)
            lastNotifyAt = notifyProgressThrottled(book.title, ok + failed, pending.size, lastNotifyAt, d)
        }

        // ———— 收尾：全成 → TXT → LocalBook 登记 → COMPLETED；有失败 → FAILED（Q3：不生成 TXT） ————
        return if (failed == 0) {
            val txtFile = TxtFilePaths.txtFileFor(applicationContext.filesDir, task.bookId, book.title)
            when (val exported = d.txtExporter.export(task.bookId, txtFile.absolutePath)) {
                is AppResult.Success -> {
                    repo.markTxtGenerated(taskId, txtFile.absolutePath)
                    // 2.0 需求「TXT 自动进入本地书架」：采集完成即登记 LocalBook（幂等覆盖）
                    d.localBookRepository?.let { localRepo ->
                        val now = System.currentTimeMillis()
                        val existing = localRepo.getByBookId(task.bookId)
                        localRepo.upsert(
                            com.mvxxcb.reader.domain.model.LocalBook(
                                id = existing?.id ?: 0L,
                                bookId = task.bookId,
                                filePath = txtFile.absolutePath,
                                format = "TXT",
                                fileSize = exported.value.fileSize,
                                chapterCount = exported.value.chapterCount,
                                completedChapterCount = exported.value.completedChapterCount,
                                isComplete = exported.value.failedChapterIds.isEmpty(),
                                createdAt = existing?.createdAt ?: now,
                                updatedAt = now,
                            )
                        )
                    }
                    val completedState = repo.updateState(taskId, CrawlState.COMPLETED)
                    notifyResult(
                        book, success = true,
                        detail = "采集完成 ${exported.value.completedChapterCount}/${exported.value.chapterCount} 章，TXT 已生成",
                        d,
                    )
                    Result.success(
                        buildOutput(taskId = taskId, state = CrawlState.COMPLETED, ok = ok, failed = failed),
                    ).also { if (completedState is AppResult.Failure) logStateRace(completedState, taskId, d) }
                }
                is AppResult.Failure -> {
                    repo.updateState(taskId, CrawlState.FAILED)
                    notifyResult(book, success = false, detail = "TXT 生成失败，可重试", d)
                    Result.success(buildOutput(taskId = taskId, state = CrawlState.FAILED, ok = ok, failed = failed))
                }
            }
        } else {
            repo.updateState(taskId, CrawlState.FAILED)
            notifyResult(book, success = false, detail = "$failed 章失败，可重试失败章节", d)
            Result.success(buildOutput(taskId = taskId, state = CrawlState.FAILED, ok = ok, failed = failed))
        }
    }

    private suspend fun markFailedIfNonTerminal(taskId: Long, detail: String, d: Deps) {
        val result = d.crawlTaskRepository.updateState(taskId, CrawlState.FAILED)
        if (result is AppResult.Failure) logStateRace(result, taskId, d) else d.logger.w(TAG, "任务置 FAILED：$detail")
    }

    private fun logStateRace(failure: AppResult.Failure, taskId: Long, d: Deps) {
        d.logger.w(TAG, "状态回写被拒（可能取消竞态）taskId=$taskId error=${failure.error.debugDetail}")
    }

    // ———— 通知（POST_NOTIFICATIONS 未授权 / 前台失败 → 降级静默） ————

    private suspend fun tryEnterForeground(bookTitle: String, d: Deps) {
        try {
            setForeground(createForegroundInfo(buildProgressNotification(bookTitle, 0, 0)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            d.logger.w(TAG, "前台服务启动失败，降级为静默采集：${e.message}")
        }
    }

    private fun createForegroundInfo(notification: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIF_ID_PROGRESS, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            @Suppress("DEPRECATION")
            ForegroundInfo(NOTIF_ID_PROGRESS, notification)
        }

    private fun ensureChannel(d: Deps) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "整本采集", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildProgressNotification(bookTitle: String, done: Int, total: Int): Notification {
        val nm = NotificationManagerCompat.from(applicationContext)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            ensureChannel(deps)
        }
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("正在采集《$bookTitle》")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (total > 0) {
            builder.setContentText("已完成 $done/$total 章").setProgress(total, done, false)
        } else {
            builder.setContentText("准备中…").setProgress(0, 0, true)
        }
        return builder.build()
    }

    /** 进度通知节流（≥1s 一次，万章不刷屏） */
    private fun notifyProgressThrottled(
        bookTitle: String,
        done: Int,
        total: Int,
        lastNotifyAt: Long,
        d: Deps,
    ): Long {
        val now = System.currentTimeMillis()
        if (now - lastNotifyAt < NOTIFY_INTERVAL_MS) return lastNotifyAt
        postNotification(NOTIF_ID_PROGRESS, buildProgressNotification(bookTitle, done, total), d)
        return now
    }

    private fun notifyResult(book: Book, success: Boolean, detail: String, d: Deps) {
        val title = if (success) "《${book.title}》采集完成" else "《${book.title}》采集中断"
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(detail)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        postNotification(NOTIF_ID_RESULT, notification, d)
    }

    /** 未授权降级为静默：通知总开关关闭即不发送；单次发送失败仅记日志 */
    private fun postNotification(id: Int, notification: Notification, d: Deps) {
        val nm = NotificationManagerCompat.from(applicationContext)
        if (!nm.areNotificationsEnabled()) return
        runCatching { nm.notify(id, notification) }
            .onFailure { d.logger.w(TAG, "通知发送失败（降级静默）：${it.message}") }
    }

    // ———— 输入/输出约定 ————

    companion object {
        private const val TAG = "ChapterDownloadWorker"

        /** inputData：只传 taskId（07 风险#2，绕开 10KB 上限）；bookId 仅冗余校验 */
        const val KEY_TASK_ID = "taskId"
        const val KEY_BOOK_ID = "bookId"

        /** outputData 摘要（07：outputData 只写摘要） */
        const val OUT_BOOK_ID = "bookId"
        const val OUT_STATE = "state"
        const val OUT_SUCCEEDED = "succeeded"
        const val OUT_FAILED = "failed"
        const val OUT_NOTE = "note"
        const val OUT_TASK_NOT_FOUND = "TASK_NOT_FOUND"
        const val OUT_BOOK_MISMATCH = "BOOK_ID_MISMATCH"
        const val OUT_BOOK_MISSING = "BOOK_MISSING"
        const val OUT_NOT_ACTIVE = "NOT_ACTIVE"
        const val OUT_STATE_RACED = "STATE_RACED"
        const val OUT_PAUSED_OR_CANCELLED = "PAUSED_OR_CANCELLED"

        private const val CHANNEL_ID = "crawl_progress"
        private const val NOTIF_ID_PROGRESS = 1001
        private const val NOTIF_ID_RESULT = 1002
        private const val NOTIFY_INTERVAL_MS = 1_000L

        /** 自动重试上限（瞬时异常；超过则置 FAILED 由用户重试，避免无界重试） */
        private const val MAX_AUTO_RETRIES = 3

        /** 全局串行门：同进程同一时刻至多一个采集循环（07 Q5 最保守合规） */
        private object CrawlGate {
            val mutex = Mutex()
        }

        fun buildInputData(taskId: Long, bookId: Long): Data =
            Data.Builder()
                .putLong(KEY_TASK_ID, taskId)
                .putLong(KEY_BOOK_ID, bookId)
                .build()

        internal fun buildOutput(
            taskId: Long,
            state: CrawlState?,
            ok: Int = -1,
            failed: Int = -1,
            note: String? = null,
        ): Data {
            val builder = Data.Builder()
                .putLong(KEY_TASK_ID, taskId)
                .putLong(OUT_SUCCEEDED, ok.toLong())
                .putLong(OUT_FAILED, failed.toLong())
            state?.let { builder.putString(OUT_STATE, it.name) }
            note?.let { builder.putString(OUT_NOTE, it) }
            return builder.build()
        }
    }
}
