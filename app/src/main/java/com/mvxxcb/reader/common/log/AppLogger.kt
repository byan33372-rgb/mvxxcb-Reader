package com.mvxxcb.reader.common.log

interface AppLogger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
    fun logError(error: com.mvxxcb.reader.domain.error.ReaderError, context: String)

    /**
     * 诊断反馈（WP-DIAG）：最近日志快照（含默认体，保证既有测试 Fake 实现零破坏）。
     * 默认返回空列表；[LogcatLogger] 覆写为返回环形缓冲内容。
     * 消息文本由调用方按 05 台账脱敏口径保证，本层不做二次裁剪。
     */
    fun snapshotRecent(): List<String> = emptyList()
}
