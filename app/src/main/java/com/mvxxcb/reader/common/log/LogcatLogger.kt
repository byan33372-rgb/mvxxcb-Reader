package com.mvxxcb.reader.common.log

import android.util.Log
import com.mvxxcb.reader.BuildConfig
import com.mvxxcb.reader.common.time.SystemTimeProvider
import com.mvxxcb.reader.common.time.TimeProvider
import com.mvxxcb.reader.domain.error.ReaderError
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logcat 实现（契约 02 §1）：
 *  - debug 构建输出全部级别；release 仅 WARN+（01 §8）。
 *  - 隐私最小化（01 §8）：禁止输出用户粘贴的完整 URL 查询串与正文内容，
 *    调用方须先截断/脱敏，本实现不做二次裁剪。
 *  - 可注入替换（契约 02 §11.1）。
 *
 * 2.0 诊断反馈（WP-DIAG）：新增内存环形缓冲（[BUFFER_CAPACITY] 行上限，超限淘汰最旧），
 * d/i/w/e（含 logError）四路在 logcat 输出之外**始终**追加格式化行（时间戳+级别+tag+消息），
 * 供 [com.mvxxcb.reader.service.DiagnosticsService] 生成反馈文件时取最近日志快照；
 * 缓冲追加不改变 logcat 既有输出口径（debug 门控不变），消息脱敏责任仍在调用方。
 */
class LogcatLogger(
    private val timeProvider: TimeProvider = SystemTimeProvider(),
) : AppLogger {

    companion object {
        /** 环形缓冲容量上限（行） */
        const val BUFFER_CAPACITY = 600

        /** 默认快照行数（取最近 N 行，按时间升序返回） */
        const val SNAPSHOT_DEFAULT_LINES = 400
    }

    private val buffer = ArrayDeque<String>(BUFFER_CAPACITY)
    private val bufferLock = Any()
    private val stampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    override fun d(tag: String, message: String) {
        append('D', tag, message, null)
        if (BuildConfig.DEBUG) Log.d(tag, message)
    }

    override fun i(tag: String, message: String) {
        append('I', tag, message, null)
        if (BuildConfig.DEBUG) Log.i(tag, message)
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        append('W', tag, message, throwable)
        if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        append('E', tag, message, throwable)
        if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
    }

    override fun logError(error: ReaderError, context: String) {
        // 错误日志属 WARN+，release 也输出；仅记录错误分类与脱敏后的 debugDetail
        val summary = when (error) {
            is ReaderError.Network -> "Network(${error.failure.name}, status=${error.statusCode})"
            is ReaderError.Parse -> "Parse(${error.failure.name}, layer=${error.layer?.name})"
            is ReaderError.Security -> "Security(${error.reason.name})"
            is ReaderError.Storage -> "Storage(${error.failure.name})"
            is ReaderError.State -> "State(${error.failure.name})"
            is ReaderError.Unknown -> "Unknown"
        }
        val detail = error.debugDetail?.let { " :: $it" } ?: ""
        append('E', "ReaderError", "[$context] $summary$detail", null)
        Log.e("ReaderError", "[$context] $summary$detail")
    }

    /** 最近日志快照（WP-DIAG）：取最近 [maxLines] 行，按时间升序返回；超量自动裁剪 */
    fun snapshotRecent(maxLines: Int = SNAPSHOT_DEFAULT_LINES): List<String> =
        synchronized(bufferLock) {
            if (maxLines <= 0 || buffer.isEmpty()) {
                emptyList()
            } else {
                val from = (buffer.size - maxLines).coerceAtLeast(0)
                buffer.toList().subList(from, buffer.size)
            }
        }

    override fun snapshotRecent(): List<String> = snapshotRecent(SNAPSHOT_DEFAULT_LINES)

    /** 追加环形缓冲：logcat 输出口径之外的独立诊断通道（01 §8 脱敏责任在调用方） */
    private fun append(level: Char, tag: String, message: String, throwable: Throwable?) {
        synchronized(bufferLock) {
            val ts = stampFormat.format(Date(timeProvider.nowMillis()))
            val line = buildString {
                append(ts).append(' ').append(level).append('/').append(tag).append(": ").append(message)
                if (throwable != null) {
                    append(" :: ").append(throwable.javaClass.name)
                    throwable.message?.let { append(": ").append(it) }
                }
            }
            buffer.addLast(line)
            while (buffer.size > BUFFER_CAPACITY) buffer.removeFirst()
        }
    }
}
