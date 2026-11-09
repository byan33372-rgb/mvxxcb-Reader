package com.mvxxcb.reader.common.log

interface AppLogger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)
    fun logError(error: com.mvxxcb.reader.domain.error.ReaderError, context: String)
}
