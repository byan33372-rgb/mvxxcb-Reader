package com.mvxxcb.reader.domain.error

sealed interface ReaderError {
    val debugDetail: String?

    data class Network(
        val failure: NetworkFailure,
        val url: String?,
        val statusCode: Int?,
        override val debugDetail: String? = null,
        val cause: Throwable? = null,
    ) : ReaderError

    data class Parse(
        val failure: ParseFailure,
        val layer: com.mvxxcb.reader.domain.model.ParseLayer? = null,
        val fieldErrors: Map<String, String> = emptyMap(),   // 字段名 → 失败原因
        override val debugDetail: String? = null,
        val cause: Throwable? = null,
    ) : ReaderError

    data class Security(
        val reason: com.mvxxcb.reader.security.BlockedReason,
        val url: String?,
        override val debugDetail: String? = null,
    ) : ReaderError

    data class Storage(
        val failure: StorageFailure,
        override val debugDetail: String? = null,
        val cause: Throwable? = null,
    ) : ReaderError

    data class State(
        val failure: StateFailure,
        override val debugDetail: String? = null,
    ) : ReaderError

    data class Unknown(
        override val debugDetail: String? = null,
        val cause: Throwable? = null,
    ) : ReaderError
}

enum class NetworkFailure {
    INVALID_URL, DNS_FAILURE, CONNECT_TIMEOUT, READ_TIMEOUT, CONNECTION_REFUSED,
    TLS_ERROR, HTTP_ERROR_STATUS, TOO_MANY_REDIRECTS, REDIRECT_LOOP,
    RESPONSE_TOO_LARGE, RATE_LIMITED, EMPTY_RESPONSE, CANCELLED, OFFLINE,
}

enum class ParseFailure {
    UNSUPPORTED_STRUCTURE,          // 四层均无法识别结构
    DETAIL_INCOMPLETE,              // 详情关键字段（书名）缺失
    CATALOG_EMPTY,                  // 目录零章节
    CATALOG_PAGINATION_FAILED,      // 目录分页中断/失败
    CONTENT_EMPTY,                  // 正文为空
    CONTENT_PAGINATION_FAILED,      // 正文分页中断/失败
    PAGINATION_LOOP_DETECTED,       // 分页死循环保护触发
    ENCODING_FAILED,                // 编码无法识别/解码失败
    DYNAMIC_PAGE_REQUIRED,          // 疑似动态渲染页（一期明确提示，不做 WebView）
    LOW_CONFIDENCE,                 // 整体置信度低于阈值
    DUPLICATE_CHAPTER_CONFLICT,     // 目录合并冲突超阈值
    TIMEOUT_BUDGET_EXCEEDED,        // 解析任务超时预算耗尽
    CATALOG_UNRESOLVED,             // BookResolver：无法从章节页回溯详情/目录入口（需用户补充目录 URL）
}

enum class StorageFailure {
    DB_ERROR, DISK_IO, DISK_FULL, CACHE_CORRUPT,
    TXT_WRITE_FAILED,               // 2.0：TXT 导出写盘失败（07 §契约 v1.1 K14）
}

enum class StateFailure {
    BOOK_NOT_FOUND, CHAPTER_NOT_FOUND, CHAPTER_NOT_CACHED, INVALID_ARGUMENT,
    CRAWL_TASK_NOT_FOUND,           // 2.0：采集任务不存在（07 K14）
    INVALID_TASK_STATE,             // 2.0：采集任务状态机非法转移（07 K14）
    LOCAL_BOOK_NOT_FOUND,           // 2.0：本地书条目不存在（07 K14）
}
