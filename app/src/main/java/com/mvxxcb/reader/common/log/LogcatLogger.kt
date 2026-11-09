package com.mvxxcb.reader.common.log

import android.util.Log
import com.mvxxcb.reader.BuildConfig
import com.mvxxcb.reader.domain.error.ReaderError

/**
 * Logcat 实现（契约 02 §1）：
 *  - debug 构建输出全部级别；release 仅 WARN+（01 §8）。
 *  - 隐私最小化（01 §8）：禁止输出用户粘贴的完整 URL 查询串与正文内容，
 *    调用方须先截断/脱敏，本实现不做二次裁剪。
 *  - 可注入替换（契约 02 §11.1）。
 */
class LogcatLogger : AppLogger {

    override fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, message)
    }

    override fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.i(tag, message)
    }

    override fun w(tag: String, message: String, throwable: Throwable?) {
        if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
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
        Log.e("ReaderError", "[$context] $summary$detail")
    }
}
