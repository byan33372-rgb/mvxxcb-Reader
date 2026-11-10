package com.mvxxcb.reader.service

import android.content.Context
import android.database.Cursor
import android.os.Build
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mvxxcb.reader.BuildConfig
import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.common.log.LogcatLogger
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.common.time.TimeProvider
import com.mvxxcb.reader.data.db.AppDatabase
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StorageFailure
import com.mvxxcb.reader.domain.model.ReaderSettings
import com.mvxxcb.reader.domain.repository.CrawlTaskRepository
import com.mvxxcb.reader.domain.repository.LocalBookRepository
import com.mvxxcb.reader.domain.repository.ReaderSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断反馈服务（2.0 WP-DIAG；用户诉求：应用内置「监察所有操作」+ 一键生成反馈文件）。
 *
 * 职责：把设备信息 / 数据库统计（真实 SQL 查询）/ 采集任务 / 阅读设置 /
 * 最近操作日志（[LogcatLogger] 环形缓冲快照）汇总为纯文本反馈文件，
 * 输出到 `filesDir/feedback/反馈数据-yyyyMMdd-HHmmss.txt`（保留历史反馈文件，
 * 「反馈文件夹」语义；分享经 FileProvider，见 AndroidManifest + res/xml/file_paths.xml）。
 *
 * 隐私红线（05 台账口径）：
 *  - 反馈文件**不含任何书籍简介/正文内容**（books 明细仅 id/标题/来源/域名/章数/临时标记）；
 *  - URL 类字段一律脱敏：保留 `scheme://host/path`，query 整体以 `?…` 代替，丢弃 fragment；
 *  - 日志缓冲行的消息文本由调用方（logger.w/e 调用点已按 05 台账脱敏）保证，本层不二次改写。
 *
 * 失败语义：IO/DB 异常 → `AppResult.Failure(ReaderError.Storage(TXT_WRITE_FAILED))`（复用 07 K14）。
 */
class DiagnosticsService(
    private val appContext: Context,
    private val database: AppDatabase,
    private val readerSettingsRepository: ReaderSettingsRepository,
    private val crawlTaskRepository: CrawlTaskRepository,
    private val localBookRepository: LocalBookRepository,
    private val logBuffer: LogcatLogger,
    private val timeProvider: TimeProvider,
    private val logger: AppLogger,
) {

    companion object {
        private const val TAG = "DiagnosticsService"
        private const val FEEDBACK_DIR = "feedback"
        private const val FILE_PREFIX = "反馈数据-"

        /**
         * URL 脱敏（与 security 层 redactQuery 同口径并叠加 `?…` 标记）：
         * 保留 scheme://host/path；有 query 追加 `?…`（原文不落盘）；丢弃 fragment。
         */
        internal fun redactUrl(url: String): String {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return trimmed
            val q = trimmed.indexOf('?')
            val base = if (q >= 0) trimmed.substring(0, q) else trimmed
            val frag = base.indexOf('#')
            val noFrag = if (frag >= 0) base.substring(0, frag) else base
            return if (q >= 0) "$noFrag?…" else noFrag
        }

        /** 行内净化：标题等文本若含换行，压成单行，保持报告行结构 */
        internal fun singleLine(text: String?): String =
            (text ?: "").replace("\r", " ").replace("\n", " ").trim()

        /** 报告纯文本装配（纯函数，可 JVM 单测） */
        internal fun buildFeedbackText(sections: FeedbackSections): String = buildString {
            appendLine("==== 简阅 反馈数据 ====")
            appendSection("设备与应用", sections.device)
            appendSection("数据库统计", sections.dbStats)
            appendSection("采集任务", sections.crawlTasks)
            appendSection("阅读设置", sections.settings)
            appendSection("最近操作日志", sections.recentLogs)
            appendSection("反馈生成时间", listOf(sections.generatedAt))
        }

        private fun StringBuilder.appendSection(title: String, lines: List<String>) {
            appendLine("【$title】")
            if (lines.isEmpty()) appendLine("（空）") else lines.forEach { appendLine(it) }
            appendLine()
        }
    }

    /** 反馈报告各分节内容（每节 = 行列表；buildFeedbackText 纯装配） */
    data class FeedbackSections(
        val device: List<String>,
        val dbStats: List<String>,
        val crawlTasks: List<String>,
        val settings: List<String>,
        val recentLogs: List<String>,
        val generatedAt: String,
    )

    /**
     * 生成反馈文件（filesDir/feedback/反馈数据-yyyyMMdd-HHmmss.txt，UTF-8）。
     * 历史反馈文件保留不删除；成功返回文件引用（供分享面板 FileProvider 取 Uri）。
     */
    suspend fun generateFeedback(): AppResult<File> = try {
        withContext(Dispatchers.IO) {
            val now = timeProvider.nowMillis()
            val dir = File(appContext.filesDir, FEEDBACK_DIR)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "$FILE_PREFIX${fileStamp(now)}.txt")
            val text = buildFeedbackText(collectSections(now))
            file.writeText(text, Charsets.UTF_8)
            logger.i(TAG, "反馈数据已生成: ${file.name}")
            AppResult.Success(file)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w(TAG, "反馈数据生成失败", e)
        AppResult.Failure(ReaderError.Storage(StorageFailure.TXT_WRITE_FAILED, e.message, e))
    }

    // ———— 分节采集（真实查询：raw SQL / DAO / Repository） ————

    private suspend fun collectSections(now: Long): FeedbackSections {
        val db = database.openHelper.readableDatabase
        return FeedbackSections(
            device = deviceLines(),
            dbStats = dbStatsLines(db),
            crawlTasks = crawlTaskLines(),
            settings = settingsLines(),
            recentLogs = logBuffer.snapshotRecent(LogcatLogger.SNAPSHOT_DEFAULT_LINES),
            generatedAt = dateTimeStamp(now),
        )
    }

    private fun deviceLines(): List<String> = listOf(
        "型号: ${Build.MODEL}",
        "系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "应用版本: ${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE})",
        "数据库版本: ${database.openHelper.readableDatabase.version}",
    )

    private fun dbStatsLines(db: SupportSQLiteDatabase): List<String> = buildList {
        val bookCount = rawCount(db, "SELECT COUNT(*) FROM books")
        add("books 总数: $bookCount")
        // 逐本明细（不含简介 description / 正文——隐私红线）；URL 脱敏
        rawRows(
            db,
            "SELECT id, title, sourceUrl, sourceDomain, totalChapters, isTemporary FROM books ORDER BY id",
        ) { c ->
            add(
                "- id=${c.getLong(0)} 标题=${singleLine(c.getString(1))} " +
                    "来源=${redactUrl(c.getString(2) ?: "")} 域名=${c.getString(3) ?: ""} " +
                    "总章数=${c.getInt(4)} 临时=${c.getInt(5) != 0}",
            )
        }
        add("chapters 总数: ${rawCount(db, "SELECT COUNT(*) FROM chapters")}")
        add("chapters 已缓存: ${rawCount(db, "SELECT COUNT(*) FROM chapters WHERE isCached = 1")}")
        add("reading_progress 行数: ${rawCount(db, "SELECT COUNT(*) FROM reading_progress")}")
        add("bookmarks 行数: ${rawCount(db, "SELECT COUNT(*) FROM bookmarks")}")
        add("source_rules 行数: ${rawCount(db, "SELECT COUNT(*) FROM source_rules")}")
        add("local_books 行数: ${rawCount(db, "SELECT COUNT(*) FROM local_books")}")
    }

    private suspend fun crawlTaskLines(): List<String> {
        val tasks = crawlTaskRepository.observeTasks().first()
        if (tasks.isEmpty()) return emptyList()
        return tasks.map { t ->
            val txtState = when {
                t.txtPath == null -> "未生成"
                File(t.txtPath).exists() -> "已生成(文件存在)"
                else -> "已生成(文件缺失)"
            }
            // 登记态交叉核对（local_books：一书至多一个登记文件）
            val registered = localBookRepository.getByBookId(t.bookId) != null
            "- id=${t.id} bookId=${t.bookId} state=${t.state.name} " +
                "成功=${t.succeededCount} 失败=${t.failedCount} 当前章=${t.currentChapterIndex} " +
                "TXT=$txtState 本地登记=${if (registered) "有" else "无"} " +
                "开始=${t.startedAt?.let(::dateTimeStamp) ?: "-"} " +
                "结束=${t.finishedAt?.let(::dateTimeStamp) ?: "-"}"
        }
    }

    private suspend fun settingsLines(): List<String> {
        val s: ReaderSettings = readerSettingsRepository.current()
        return listOf(
            "fontFamily=${s.fontFamily.name}",
            "fontSizeSp=${s.fontSizeSp}",
            "lineHeightMultiplier=${s.lineHeightMultiplier}",
            "paragraphSpacingDp=${s.paragraphSpacingDp}",
            "boldText=${s.boldText}",
            "horizontalPaddingDp=${s.horizontalPaddingDp}",
            "backgroundTheme=${s.backgroundTheme.name}",
            "readingMode=${s.readingMode.name}",
            "brightnessMode=${s.brightnessMode.name}",
            "brightness=${s.brightness}",
            "screenOrientation=${s.screenOrientation.name}",
            "autoSaveProgress=${s.autoSaveProgress}",
            "keepScreenOn=${s.keepScreenOn}",
            "autoPrefetchNext=${s.autoPrefetchNext}",
        )
    }

    // ———— 工具 ————

    private fun rawCount(db: SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { c ->
            check(c.moveToFirst()) { "COUNT 查询无结果行: $sql" }
            c.getLong(0)
        }

    private fun rawRows(
        db: SupportSQLiteDatabase,
        sql: String,
        row: (Cursor) -> Unit,
    ) {
        db.query(sql).use { c -> while (c.moveToNext()) row(c) }
    }

    private fun fileStamp(epochMillis: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(epochMillis))

    private fun dateTimeStamp(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(epochMillis))
}
