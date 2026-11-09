package com.mvxxcb.reader.data.util

import java.net.URI

/**
 * data 层内部 URL 规范化（数据模型 03 §7.1 的防御性二次实现）。
 *
 * 上游 parser 的 UrlNormalizer 是权威实现（ChapterRef.url 契约已注明"已规范化绝对 URL"）；
 * data 层因依赖红线（契约 02 §0.5）不得 import parser.*，故对入库 URL 再做一次同规则的
 * 轻量规范化，保证 merge 匹配键与去重键的确定性。相对链接由 parser 侧解析为绝对 URL，
 * 本实现仅处理绝对 URL；无法解析（无 scheme/host）返回 null，由调用方决定回退。
 *
 * 规则（03 §7.1）：
 *  1. scheme/host 统一小写
 *  2. 去默认端口（http:80 / https:443）；空 path 补 "/"
 *  4. 去 fragment；去跟踪参数（utm_*、from、spm、fr、share_*）
 *  5. 百分号编码归一（unreserved 与多字节字符解码为字面量；其余十六进制统一大写）
 *  6. 尾部 "/" 归一（非根 path 去掉一个尾 "/"）
 *  7. 保留 query（排除跟踪参数后按原顺序）
 */
object SourceUrlNormalizer {

    private const val TRACKING_PREFIX_1 = "utm_"
    private const val TRACKING_PREFIX_2 = "share_"
    private val TRACKING_KEYS = setOf("from", "spm", "fr")

    fun normalize(rawUrl: String): String? {
        val raw = rawUrl.trim()
        if (raw.isEmpty()) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null

        val port = uri.port
        val defaultPort = (scheme == "http" && port == 80) || (scheme == "https" && port == 443)
        val hostPart = if (port > 0 && !defaultPort) "$host:$port" else host

        var path = uri.rawPath ?: ""
        if (path.isEmpty()) path = "/"
        if (path.length > 1 && path.endsWith("/")) path = path.trimEnd('/')
        path = normalizePercentEncoding(path)

        val query = uri.rawQuery?.let { filterAndNormalizeQuery(it) }

        return buildString {
            append(scheme).append("://").append(hostPart).append(path)
            if (!query.isNullOrEmpty()) append('?').append(query)
            // fragment 按规则 4 丢弃
        }
    }

    /** 提取小写 host；解析失败返回空串（供 sourceDomain 兜底） */
    fun hostOf(rawUrl: String): String {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull()
        return uri?.host?.lowercase() ?: ""
    }

    private fun isTrackingKey(key: String): Boolean {
        val k = key.lowercase()
        return k in TRACKING_KEYS ||
            k.startsWith(TRACKING_PREFIX_1) || k.startsWith(TRACKING_PREFIX_2)
    }

    private fun filterAndNormalizeQuery(rawQuery: String): String =
        rawQuery.split('&')
            .filter { it.isNotEmpty() }
            .filter { pair -> !isTrackingKey(decodeComponent(pair.substringBefore('='))) }
            .joinToString("&") { pair ->
                val key = pair.substringBefore('=')
                val value = pair.substringAfter('=', missingDelimiterValue = "")
                normalizePercentEncoding(key) + "=" + normalizePercentEncoding(value)
            }

    private fun decodeComponent(text: String): String = decodePercentBytes(text) ?: text

    /**
     * 百分号编码归一（03 §7.1 第 5 步）：
     *  - 连续 %XX 字节序列按 UTF-8 解码；结果全部为 unreserved（ALPHA/DIGIT/-._~）
     *    或非 ASCII 字符（中文等）→ 写为字面量；
     *  - 其余（ASCII 保留字符/无法解码）→ 十六进制统一大写保留；
     *  - 非法序列原样保留。
     */
    private fun normalizePercentEncoding(component: String): String {
        if (!component.contains('%')) return component
        val sb = StringBuilder(component.length)
        var i = 0
        while (i < component.length) {
            if (component[i] == '%' && hexValue(component.getOrNull(i + 1)) != null &&
                hexValue(component.getOrNull(i + 2)) != null
            ) {
                val bytes = collectPercentBytes(component, i)
                val decoded = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
                if (decoded != null && isSafeLiteral(decoded)) {
                    sb.append(decoded)
                } else {
                    for (b in bytes) sb.append("%02X".format(b))
                }
                i += bytes.size * 3
            } else {
                sb.append(component[i])
                i++
            }
        }
        return sb.toString()
    }

    /** 从 from 开始收集连续的 %XX 三元组字节；调用方保证前两个字节合法 */
    private fun collectPercentBytes(text: String, from: Int): ByteArray {
        val out = ArrayList<Byte>(4)
        var i = from
        while (i + 2 < text.length + 1 && i + 2 <= text.length - 1) {
            val hi = hexValue(text[i + 1])
            val lo = hexValue(text[i + 2])
            if (hi == null || lo == null) break
            out.add(((hi shl 4) or lo).toByte())
            i += 3
            if (i >= text.length || text[i] != '%') break
        }
        return out.toByteArray()
    }

    private fun decodePercentBytes(text: String): String? {
        if (!text.contains('%')) return text
        return runCatching { String(collectPercentBytes(text, text.indexOf('%')), Charsets.UTF_8) }
            .getOrNull()
    }

    private fun hexValue(c: Char?): Int? = when (c) {
        in '0'..'9' -> c?.code?.minus(48)
        in 'a'..'f' -> c?.code?.minus(87)
        in 'A'..'F' -> c?.code?.minus(55)
        else -> null
    }

    /** unreserved + 非 ASCII 字符可作字面量；ASCII 保留字符保持编码 */
    private fun isSafeLiteral(decoded: String): Boolean {
        if (decoded.isEmpty()) return false
        return decoded.all { ch ->
            ch.code > 127 || (ch.isLetterOrDigit() && ch.code < 128) || ch in "-._~"
        }
    }
}
