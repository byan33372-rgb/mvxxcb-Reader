package com.mvxxcb.reader.security

data class NetworkLimits(
    val connectTimeoutMs: Long = 10_000,
    val readTimeoutMs: Long = 15_000,
    val maxRedirects: Int = 5,
    val maxResponseBytes: Long = 8L * 1024 * 1024,
    val maxRequestsPerTask: Int = 30,
    // 2.0 采集实测（linovelib）：500ms 突发请求会在 ~4-5 次后触发站点频率限制；
    // 合规姿态收紧为 2s（契约 v1.1 追加，主 Agent 裁定 2026-11-10）
    val minIntervalPerHostMs: Long = 2_000,
) {
    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 8L * 1024 * 1024
        val DEFAULT = NetworkLimits()
    }
}
