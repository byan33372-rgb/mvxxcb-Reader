package com.mvxxcb.reader.security

data class NetworkLimits(
    val connectTimeoutMs: Long = 10_000,
    val readTimeoutMs: Long = 15_000,
    val maxRedirects: Int = 5,
    val maxResponseBytes: Long = 8L * 1024 * 1024,
    val maxRequestsPerTask: Int = 30,
    // 2.0 采集实测（linovelib）：500ms 突发请求会在 ~4-5 次后触发站点频率限制；
    // 合规姿态收紧为 2s（契约 v1.1 追加，主 Agent 裁定 2026-11-10）。
    // 2.0 WP-S 起：本字段语义变为"自适应起始间隔"——不限流站点连续成功后可降档至
    // [adaptiveFloorMs]（保底 1s，不破合规红线）；限流/失败站点自动退避至 [adaptiveCapMs]。
    val minIntervalPerHostMs: Long = 2_000,
    // —— 自适应限速参数（WP-S 追加；原字段全部保留不变） ——
    /** 降档地板（毫秒）：间隔最低值，合规保底 1s，达到后不再降。 */
    val adaptiveFloorMs: Long = 1_000,
    /** 退避天花板（毫秒）：失败翻倍上限；429 无 Retry-After 或 Retry-After 超上限时间隔置为此值。 */
    val adaptiveCapMs: Long = 10_000,
    /** 连续成功多少次后间隔降一档（降档后计数清零，重新累计）。 */
    val speedUpAfterSuccesses: Int = 3,
    /** 每次降档减少的毫秒数。 */
    val stepDownMs: Long = 500,
) {
    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 8L * 1024 * 1024
        val DEFAULT = NetworkLimits()
    }
}
