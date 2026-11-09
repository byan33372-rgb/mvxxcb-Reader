package com.mvxxcb.reader.parser.util

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.charset.CodingErrorAction

/**
 * URL 规范化（03 §7.1 七步，parser/util；入库与解析前必须执行）。
 *
 * 1. 解析为 URI；scheme 统一小写；host 统一小写。
 * 2. 去默认端口（http:80 / https:443）；空 path 补 `/`。
 * 3. 相对链接基于当前页 URL 解析为绝对 URL（[normalize] 的 baseUrl 参数）。
 * 4. 去除 fragment（#…）；去跟踪参数（清单见 [isTrackingParam]，维护在实现内）。
 * 5. 百分号编码统一解码后再按 UTF-8 重编码（大写十六进制）。
 * 6. 尾部 `/` 归一（path 为 `/` 以外时去掉一个尾 `/`）。
 * 7. 保留 query（部分站点用 query 定位章节），但排除跟踪参数。
 *
 * 归一化必须是幂等的：对同一输入重复 normalize 结果不变。
 * 非法 URL 返回 null（调用方映射 Network.INVALID_URL）。
 * 说明：userInfo 一并丢弃（隐私最小化）；IPv6 host 重建时补方括号。
 */
object UrlNormalizer {

    /** 03 §7.1 步骤 4：跟踪参数精确清单（含常见统计参数） */
    private val EXACT_TRACKING_PARAMS = setOf(
        "from", "spm", "fr", "gclid", "fbclid", "refer_flag", "share_token",
        "vd_source", "clickid", "tt_from", "f_from",
    )

    /** 前缀型跟踪参数（utm_campaign / share_source 等） */
    private val TRACKING_PARAM_PREFIXES = listOf("utm_", "share_", "spm_")

    /** RFC 3986 unreserved：归一化重编码时保持原样的字符 */
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    fun normalize(rawUrl: String, baseUrl: String? = null): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null
        val resolved: URI = try {
            val uri = lenientUri(trimmed) ?: return null
            if (uri.scheme != null || baseUrl == null) uri
            else {
                val base = lenientUri(baseUrl.trim()) ?: return null
                base.resolve(uri)
            }
        } catch (_: Exception) {
            return null
        }
        return normalizeResolved(resolved)
    }

    /** 步骤 1：scheme/host 小写 + 步骤 2 默认端口与空 path + 步骤 4/5/6/7 */
    private fun normalizeResolved(uri: URI): String? {
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val rawHost = uri.host ?: return null
        val host = rawHost.lowercase()

        var port = uri.port
        if ((scheme == "http" && port == 80) || (scheme == "https" && port == 443)) port = -1

        var path = uri.rawPath ?: ""
        if (path.isEmpty()) path = "/"                                        // 步骤 2：空 path 补 /
        path = reencodePath(path)                                             // 步骤 5
        if (path.length > 1 && path.endsWith("/")) {                          // 步骤 6：去一个尾 /
            path = path.trimEnd('/').ifEmpty { "/" }
        }

        val query = normalizeQuery(uri.rawQuery)                              // 步骤 4/7
        // 步骤 4：fragment 不重建即被丢弃

        val sb = StringBuilder(scheme).append("://")
        if (!uri.rawUserInfo.isNullOrEmpty()) { /* userInfo 丢弃（隐私最小化） */ }
        if (host.contains(':')) sb.append('[').append(host).append(']') else sb.append(host)
        if (port != -1) sb.append(':').append(port)
        sb.append(path)
        if (query != null) sb.append('?').append(query)
        return sb.toString()
    }

    /** URI 构造容错：空白等非法字符先转 %20 再试一次 */
    private fun lenientUri(text: String): URI? = try {
        URI(text)
    } catch (_: Exception) {
        try {
            URI(text.replace(" ", "%20"))
        } catch (_: Exception) {
            null
        }
    }

    // ———— 步骤 5：百分号编码归一 ————

    private fun reencodePath(path: String): String =
        path.split('/').joinToString("/") { seg -> reencodeComponent(seg, keepPlus = false) }

    private fun normalizeQuery(rawQuery: String?): String? {
        if (rawQuery.isNullOrEmpty()) return null
        val kept = rawQuery.split('&').filter { it.isNotEmpty() }.mapNotNull { pair ->
            val eq = pair.indexOf('=')
            val rawKey = if (eq >= 0) pair.substring(0, eq) else pair
            val rawValue = if (eq >= 0) pair.substring(eq + 1) else null
            val key = decodePercent(rawKey) ?: rawKey
            if (isTrackingParam(key)) {
                null                                                          // 步骤 4：去跟踪参数
            } else if (rawValue == null) {
                reencodeComponent(rawKey, keepPlus = true)
            } else {
                reencodeComponent(rawKey, keepPlus = true) + "=" + reencodeComponent(rawValue, keepPlus = true)
            }
        }
        return if (kept.isEmpty()) null else kept.joinToString("&")
    }

    /**
     * 解码 %XX（严格 UTF-8）后重编码为 UTF-8 大写十六进制。
     * - 解码失败（非法转义/非法 UTF-8 序列）→ 原样返回（幂等：再次运行同样原样）。
     * - [keepPlus]=true 时保留 '+' 原字面（query 语义，避免改变服务端解释）；空格 → %20。
     */
    private fun reencodeComponent(component: String, keepPlus: Boolean): String {
        val decoded = decodePercent(component) ?: return component
        val sb = StringBuilder(decoded.length)
        for (ch in decoded) {
            when {
                ch == '+' && keepPlus -> sb.append('+')
                ch == ' ' -> sb.append("%20")
                UNRESERVED.indexOf(ch) >= 0 -> sb.append(ch)
                else -> {
                    val bytes = ch.toString().toByteArray(Charsets.UTF_8)
                    for (b in bytes) sb.append('%').append(((b.toInt() and 0xFF)).toString(16).uppercase())
                }
            }
        }
        return sb.toString()
    }

    /** 严格 UTF-8 百分号解码；含非法转义或非法 UTF-8 序列返回 null */
    private fun decodePercent(text: String): String? {
        if (!text.contains('%')) return text
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%') {
                if (i + 2 >= text.length) return null
                val hex = text.substring(i + 1, i + 3)
                val v = hex.toIntOrNull(16) ?: return null
                bytes.write(v)
                i += 3
            } else {
                // 非 ASCII 原字面字符按 UTF-8 展开（先经过解码→重编码归一）
                for (b in c.toString().toByteArray(Charsets.UTF_8)) bytes.write(b.toInt())
                i += 1
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(bytes.toByteArray().let { java.nio.ByteBuffer.wrap(it) }).toString()
        } catch (_: Exception) {
            null
        }
    }

    fun isTrackingParam(key: String): Boolean {
        val k = key.trim().lowercase()
        if (k.isEmpty()) return false
        if (k in EXACT_TRACKING_PARAMS) return true
        return TRACKING_PARAM_PREFIXES.any { k.startsWith(it) }
    }

    // ———— 供 pipeline/分类器使用的辅助 ————

    /** 提取小写 host；非法返回 null */
    fun hostOf(url: String): String? = try {
        lenientUri(url.trim())?.host?.lowercase()
    } catch (_: Exception) {
        null
    }

    /** 相对链接 → 绝对规范化 URL（03 §7.1 步骤 3 的入口封装） */
    fun resolveAbsolute(baseUrl: String, relative: String): String? = normalize(relative, baseUrl)
}
