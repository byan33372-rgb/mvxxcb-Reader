package com.mvxxcb.reader.security

data class FetchRequest(
    val url: String,                                // 原始 URL（内部先过校验）
    val headers: Map<String, String> = emptyMap(),  // UA/Referer/Cookie 由 parser 侧组装传入
    val maxBytes: Long = NetworkLimits.DEFAULT_MAX_RESPONSE_BYTES,
    val followRedirects: Boolean = true,
)

data class FetchResult(
    val finalUrl: String,               // 重定向终点（已校验、已规范化）
    val statusCode: Int,
    val headers: Map<String, List<String>>,   // key 全小写
    val bodyBytes: ByteArray,                 // 原始字节（解码由 parser 负责）
    val contentType: String?,
    val declaredCharset: String?,             // Content-Type 或 meta charset
    val elapsedMs: Long,
    val redirectChain: List<String>,          // 途经 URL（有序）
    val fromCache: Boolean = false,
)   // 注意：ByteArray 参与相等性时实现方需自行 override equals/hashCode

interface SecureHttpClient {
    /**
     * 唯一网络出口。强制执行：SSRF 校验（含每次重定向复查）、重定向上限、
     * 响应体大小上限（超限中断并报 RESPONSE_TOO_LARGE）、连接/读超时、
     * 同主机最小请求间隔（限流）、TLS 默认安全策略。
     * 失败一律返回 AppResult.Failure(ReaderError)，不抛异常。
     */
    suspend fun fetch(request: FetchRequest): com.mvxxcb.reader.common.result.AppResult<FetchResult>
}
