package com.mvxxcb.reader.security

interface UrlSecurityValidator {
    /** 首次请求前校验：scheme 仅 http/https；拒绝 localhost/回环/私网/链路本地/保留段/云元数据地址 */
    fun validate(rawUrl: String): UrlCheckResult
    /** 每次重定向后重新校验目标地址（防 SSRF 重定向绕过） */
    fun validateRedirect(fromUrl: String, toUrl: String): UrlCheckResult
}

sealed interface UrlCheckResult {
    data class Allowed(val normalizedUrl: String) : UrlCheckResult
    data class Blocked(val reason: BlockedReason, val detail: String) : UrlCheckResult
}

enum class BlockedReason {
    NON_HTTP_SCHEME, INVALID_HOST, MALFORMED_URL,
    LOOPBACK, PRIVATE_ADDRESS, LINK_LOCAL, RESERVED_ADDRESS, METADATA_ENDPOINT,
    REDIRECT_LOOP, REDIRECT_LIMIT_EXCEEDED,
}
