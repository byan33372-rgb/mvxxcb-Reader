package com.mvxxcb.reader.security

data class NetworkLimits(
    val connectTimeoutMs: Long = 10_000,
    val readTimeoutMs: Long = 15_000,
    val maxRedirects: Int = 5,
    val maxResponseBytes: Long = 8L * 1024 * 1024,
    val maxRequestsPerTask: Int = 30,
    val minIntervalPerHostMs: Long = 500,
) {
    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 8L * 1024 * 1024
        val DEFAULT = NetworkLimits()
    }
}
