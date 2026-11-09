package com.mvxxcb.reader.security

import java.net.IDN
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException

/**
 * 主机 → IP 解析抽象（WP-S）。
 *
 * 产品默认实现走 [InetAddress.getAllByName]；单测/QA 注入假解析器即可覆盖
 * DNS 失败、多 A 记录（rebinding 形态）等场景，无需真实网络。
 * 该注入点只影响"解析"，不影响黑名单判定本身——产品路径不存在任何绕过开关。
 */
fun interface HostResolver {
    @Throws(UnknownHostException::class)
    fun resolve(host: String): Array<InetAddress>
}

/** 默认解析器：系统 DNS。 */
class DefaultHostResolver : HostResolver {
    override fun resolve(host: String): Array<InetAddress> = InetAddress.getAllByName(host)
}

/**
 * URL 安全校验实现（契约 02 §4.1 / 04 §5 DoD1）。
 *
 * 校验必须基于**解析后的 IP**，不能只看字符串：
 *  1. scheme 白名单：仅 http/https（一期 Manifest usesCleartextTraffic=true，http 放行；
 *     其余 scheme 一律 NON_HTTP_SCHEME）。
 *  2. 主机提取不依赖 [URI.getHost]——十进制/十六进制/八进制/缩写 IP 字面量与
 *     非 ASCII（IDN）主机在 JDK 中 getHost() 返回 null 或被误判，故由本类自行从
 *     rawAuthority 提取并解析。
 *  3. IP 字面量变体（"2130706433"、"0x7f000001"、"0x7f.0.0.1"、"0177.0.0.1"、"127.1"）
 *     在本地按 inet_aton 语义解析为 [InetAddress] 后进黑名单；注意桌面 JDK 会把
 *     "0177.0.0.1" 解析为 177.0.0.1（非八进制）、不认 0x 前缀，因此变体解析不委托 JDK。
 *  4. 域名主机经 [HostResolver] 解析出**全部地址**，任一命中黑名单即拒绝
 *     （多 A 记录下"一公一私"的 rebinding 形态同样拒绝）。
 *  5. IPv6：::1、fc00::/7、fe80::/10、fec0::/10、::、组播 ff00::/8、2001:db8::/32，
 *     以及 ::ffff: 内嵌 IPv4（映射/兼容形）、64:ff9b::/96（NAT64）、2002::/16（6to4）
 *     内嵌 IPv4 一律还原成 IPv4 再判定。
 *  6. 重定向逐跳复查：[validateRedirect] 对目标 URL 走同一校验管线。
 *
 * 可测试性（主 Agent 指令）：构造参数 [allowListForTest] 允许单测放行
 * MockWebServer 所在的 127.0.0.1/localhost。默认**空集**，生产装配不传即无后门；
 * 白名单只豁免"地址黑名单"，scheme/格式/凭据检查仍然生效。
 */
class UrlSecurityValidatorImpl(
    /** 主机解析器，默认系统 DNS；单测注入假解析器。 */
    private val hostResolver: HostResolver = DefaultHostResolver(),
    /** 仅测试用：豁免地址黑名单的主机/IP（小写比较）。默认空，生产禁止传值。 */
    private val allowListForTest: Set<String> = emptySet(),
) : UrlSecurityValidator {

    override fun validate(rawUrl: String): UrlCheckResult = check(rawUrl, fromUrl = null)

    override fun validateRedirect(fromUrl: String, toUrl: String): UrlCheckResult =
        check(toUrl, fromUrl = fromUrl)

    // ———————————————————— 主校验管线 ————————————————————

    private fun check(rawUrl: String, fromUrl: String?): UrlCheckResult {
        val from = if (fromUrl != null) "重定向自 ${redactQuery(fromUrl)}: " else ""

        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return blocked(BlockedReason.MALFORMED_URL, "${from}空 URL")
        }

        val uri = parseUri(trimmed)
            ?: return blocked(BlockedReason.MALFORMED_URL, "${from}URL 无法解析")

        // 1) scheme 白名单（在一切主机检查之前：javascript:/data:/file: 等先行拒绝）
        val scheme = uri.scheme?.lowercase()
            ?: return blocked(BlockedReason.MALFORMED_URL, "${from}缺少 scheme")
        if (scheme != "http" && scheme != "https") {
            return blocked(BlockedReason.NON_HTTP_SCHEME, "${from}scheme=$scheme 非 http/https")
        }
        if (uri.isOpaque || uri.rawAuthority == null) {
            return blocked(BlockedReason.MALFORMED_URL, "${from}URL 缺少主机（opaque URI）")
        }

        // 2) 主机提取与格式约束
        if (uri.userInfo != null) {
            // 防钓鱼混淆：http://example.com@evil.com/ 这类 URL 一律拒绝
            return blocked(BlockedReason.INVALID_HOST, "${from}URL 携带 userinfo，禁止")
        }

        val hostRaw = extractHost(uri.rawAuthority!!)
            ?: return blocked(BlockedReason.INVALID_HOST, "${from}无法从 authority 提取主机")
        var hostKey = hostRaw.trimEnd('.').lowercase()
        if (hostKey.isEmpty() || hostKey == "[]") {
            return blocked(BlockedReason.INVALID_HOST, "${from}主机为空")
        }

        // 严格解析成功也可能拿到非 ASCII 主机（URI 不校验主机字符集），统一转 punycode
        if (hostKey.any { it.code > 0x7e }) {
            hostKey = try {
                IDN.toASCII(hostKey, IDN.USE_STD3_ASCII_RULES).lowercase()
            } catch (_: Exception) {
                return blocked(BlockedReason.INVALID_HOST, "${from}主机无法转换为 ASCII（IDN）: ${redactQuery(hostKey)}")
            }
        }

        val port = uri.port
        if (port == 0) {
            return blocked(BlockedReason.INVALID_HOST, "${from}端口非法（0）")
        }

        // 3) 地址黑名单（allowListForTest 只豁免本步）
        if (!allowListForTest.contains(hostKey)) {
            val addresses = resolveHost(hostKey)
                ?: return blocked(BlockedReason.INVALID_HOST, "${from}主机解析失败或非法: ${redactQuery(hostKey)}")
            val firstBlocked = addresses.firstNotNullOfOrNull { AddressGuard.classify(it) }
            if (firstBlocked != null) {
                return blocked(firstBlocked, "${from}主机 ${redactQuery(hostKey)} 解析地址命中黑名单")
            }
        }

        // 4) 规范化 URL：小写 scheme/host、去默认端口、去 fragment、IPv6 保留方括号
        val normalized = normalize(uri, scheme, hostKey, port)
            ?: return blocked(BlockedReason.MALFORMED_URL, "${from}URL 规范化失败")
        return UrlCheckResult.Allowed(normalized)
    }

    /** 解析主机：IP 字面量变体本地解析；域名走注入解析器。失败或空结果返回 null。 */
    private fun resolveHost(hostKey: String): Array<out InetAddress>? {
        // IPv6 字面量（含方括号形态）
        if (hostKey.startsWith("[")) {
            val inner = hostKey.removeSurrounding("[", "]")
            return try {
                arrayOf(InetAddress.getByName(inner))   // 含 ':' 的字面量，不触发 DNS
            } catch (_: Exception) {
                null
            }
        }
        // IPv4 十进制/十六进制/八进制/缩写变体（不委托 JDK：JDK 不认 0x、把 0177 当十进制）
        parseIpv4Variant(hostKey)?.let { return arrayOf(it) }
        // 普通域名 → 注入解析器（空结果同样视为解析失败）
        return try {
            hostResolver.resolve(hostKey).takeIf { it.isNotEmpty() }
        } catch (_: UnknownHostException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun blocked(reason: BlockedReason, detail: String): UrlCheckResult =
        UrlCheckResult.Blocked(reason, detail)

    // ———————————————————— 解析辅助 ————————————————————

    /** 严格解析失败时按序尝试：IDN 主机转 punycode、非法字符百分号编码。 */
    private fun parseUri(raw: String): URI? {
        tryStrict(raw)?.let { return it }

        // IDN：主机含非 ASCII 时 URI.getHost()==null，转 punycode 后重试
        //（idnRepair 只转主机，路径/query 的非法字符再交 encodeIllegal 二次处理）
        idnRepair(raw)?.let { idn ->
            tryStrict(idn)?.let { return it }
            encodeIllegal(idn)?.let { repaired ->
                tryStrict(repaired)?.let { return it }
                tryStrict(encodeBrackets(repaired))?.let { return it }
            }
        }

        // 非法字符（空格/中文路径等）百分号编码后重试；仍失败再编码 query 中的 [] 后重试
        encodeIllegal(raw)?.let { repaired ->
            tryStrict(repaired)?.let { return it }
            tryStrict(encodeBrackets(repaired))?.let { return it }
        }
        return null
    }

    private fun tryStrict(s: String): URI? = try {
        URI(s)
    } catch (_: URISyntaxException) {
        null
    }

    private val SCHEME_RE = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val AUTHORITY_RE = Regex("^//[^/?#]*")

    /** 将非 ASCII IDN 主机转为 punycode 后重建 URL（携带端口时保留端口）。 */
    private fun idnRepair(raw: String): String? {
        val schemeEnd = SCHEME_RE.find(raw)?.range?.last ?: return null
        val scheme = raw.substring(0, schemeEnd + 1)
        val rest = raw.substring(schemeEnd + 1)
        if (!rest.startsWith("//")) return null
        val authorityMatch = AUTHORITY_RE.find(rest) ?: return null
        val authority = authorityMatch.value.substring(2)
        val afterAuthority = rest.substring(authorityMatch.range.last + 1)
        if (authority.contains('@')) return null   // userinfo 由主流程拒绝，修复路径不代劳
        val hostPart = if (authority.count { it == ':' } == 1) authority.substringBeforeLast(':') else authority
        if (hostPart.isEmpty() || hostPart.none { it.code > 0x7e }) return null
        return try {
            val ascii = IDN.toASCII(hostPart.trimEnd('.'), IDN.USE_STD3_ASCII_RULES).lowercase()
            val portSuffix = if (authority != hostPart) authority.substringAfterLast(':') else ""
            "$scheme//$ascii${if (portSuffix.isEmpty()) "" else ":$portSuffix"}$afterAuthority"
        } catch (_: Exception) {
            null
        }
    }

    /** 百分号编码 URI 中的非法字符（控制字符视为不可修复）。 */
    private fun encodeIllegal(raw: String): String? {
        val safe = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,;=:@/?#[]%"
        val sb = StringBuilder(raw.length + 16)
        for (ch in raw) {
            when {
                ch == ' ' -> sb.append("%20")
                ch.code < 0x20 || ch.code == 0x7f -> return null
                safe.indexOf(ch) >= 0 -> sb.append(ch)
                else -> {
                    for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                        sb.append('%').append(((b.toInt() and 0xff) or 0x100).toString(16).substring(1))
                    }
                }
            }
        }
        return sb.toString()
    }

    /** query/path 中残余的 [ ]（RFC 3986 仅允许出现在 host）二次编码。 */
    private fun encodeBrackets(s: String): String = s.replace("[", "%5B").replace("]", "%5D")

    /**
     * 从 rawAuthority 提取 host：去 userinfo、去端口；IPv6 保留方括号。
     * 覆盖 getHost()==null 的场景（0x7f.0.0.1、127.1、IDN 等）。
     */
    private fun extractHost(authority: String): String? {
        val noUser = authority.substringAfterLast('@')
        if (noUser.startsWith("[")) {
            val end = noUser.indexOf(']')
            if (end < 0) return null
            return noUser.substring(0, end + 1)
        }
        // 无方括号：端口以最后一个 ':' 分隔（裸 IPv6 未加方括号 → 非法）
        val colon = noUser.lastIndexOf(':')
        val host = if (colon >= 0) noUser.substring(0, colon) else noUser
        if (host.isEmpty() || host.contains(':')) return null
        return host
    }

    /**
     * inet_aton 语义的 IPv4 变体解析：
     *  - 每段可为十进制 / 0x 十六进制；前导 0（长度>1）按八进制（0177 → 127）；
     *  - 支持 1~4 段缩写："127.1" → 127.0.0.1，"2130706433"（32 位整数）→ 127.0.0.1；
     *  - 不匹配数字/点/0x 语法时返回 null（交由 DNS 路径）。
     */
    private fun parseIpv4Variant(host: String): InetAddress? {
        if (!IPV4_VARIANT_RE.matches(host)) return null
        if (host.startsWith(".") || host.endsWith(".") || host.contains("..")) return null
        val parts = host.split('.')
        if (parts.size !in 1..4) return null
        val values = LongArray(parts.size)
        for (i in parts.indices) {
            val p = parts[i]
            values[i] = when {
                p.length > 2 && (p.startsWith("0x") || p.startsWith("0X")) ->
                    p.substring(2).toLongOrNull(16) ?: return null
                p.length > 1 && p[0] == '0' -> p.toLongOrNull(8) ?: return null
                else -> p.toLongOrNull() ?: return null
            }
            if (values[i] < 0) return null
        }
        val out = ByteArray(4)
        var filled = 0
        for (i in 0 until parts.size - 1) {
            if (values[i] > 255) return null
            out[filled++] = values[i].toByte()
        }
        val last = values[parts.size - 1]
        val remaining = 4 - filled
        if (remaining == 0) {
            if (last > 255) return null
            out[3] = last.toByte()
        } else {
            if (last >= (1L shl (8 * remaining))) return null
            for (b in 0 until remaining) {
                out[filled + b] = ((last shr (8 * (remaining - 1 - b))) and 0xff).toByte()
            }
        }
        return InetAddress.getByAddress(out)
    }

    // ———————————————————— 规范化 ————————————————————

    private fun normalize(uri: URI, scheme: String, hostKey: String, port: Int): String? {
        // hostKey 已在 check() 中统一为 ASCII（punycode）小写；IPv6 保留方括号
        val defaultPort = if (scheme == "https") 443 else 80
        val sb = StringBuilder(scheme).append("://").append(hostKey)
        if (port > 0 && port != defaultPort) sb.append(':').append(port)
        val path = uri.rawPath
        sb.append(if (path.isNullOrEmpty()) "/" else path)
        uri.rawQuery?.let { sb.append('?').append(it) }
        return sb.toString()
    }

    companion object {
        private val IPV4_VARIANT_RE =
            Regex("^(?:0[xX][0-9a-fA-F]+|[0-9]+)(?:\\.(?:0[xX][0-9a-fA-F]+|[0-9]+))*$")

        /**
         * 日志脱敏：截断并去掉查询串与 fragment（01 §8 隐私最小化——日志禁止输出
         * 完整 URL 查询串）。security 包内实现类统一使用。
         */
        internal fun redactQuery(url: String): String {
            val trimmed = url.trim()
            val q = trimmed.indexOf('?')
            val base = if (q >= 0) trimmed.substring(0, q) else trimmed
            val frag = base.indexOf('#')
            val noFrag = if (frag >= 0) base.substring(0, frag) else base
            return if (noFrag.length > 96) noFrag.substring(0, 96) + "…" else noFrag
        }
    }
}

/**
 * 地址黑名单判定（WP-S 内部共享，[DnsPinningResolver] 复用同一套规则）。
 * 返回 null 表示公网地址放行；返回 reason 表示拒绝。
 */
internal object AddressGuard {

    fun classify(addr: InetAddress): BlockedReason? = when (addr) {
        is Inet4Address -> classifyV4(addr)
        else -> classifyV6(addr)
    }

    private fun classifyV4(a: Inet4Address): BlockedReason? {
        val b = a.address
        // 云元数据端点（先于 link-local 判定）
        if (b[0] == 169.toByte() && b[1] == 254.toByte() &&
            b[2] == 169.toByte() && b[3] == 254.toByte()
        ) return BlockedReason.METADATA_ENDPOINT
        if (a.isLoopbackAddress) return BlockedReason.LOOPBACK                 // 127/8
        if (a.isLinkLocalAddress) return BlockedReason.LINK_LOCAL              // 169.254/16
        if (a.isSiteLocalAddress) return BlockedReason.PRIVATE_ADDRESS         // 10/8、172.16/12、192.168/16
        if (a.isAnyLocalAddress) return BlockedReason.RESERVED_ADDRESS         // 0.0.0.0
        if (a.isMulticastAddress) return BlockedReason.RESERVED_ADDRESS        // 224/4
        val u0 = b[0].toInt() and 0xff
        val u1 = b[1].toInt() and 0xff
        return when {
            u0 == 0 -> BlockedReason.RESERVED_ADDRESS                          // 0.0.0.0/8 "this network"
            u0 == 100 && (u1 and 0xC0) == 64 -> BlockedReason.RESERVED_ADDRESS // 100.64/10 CGNAT
            u0 == 192 && u1 == 0 -> BlockedReason.RESERVED_ADDRESS             // 192.0.0/24 与 192.0.2/24 TEST-NET-1
            u0 == 198 && u1 == 51 -> BlockedReason.RESERVED_ADDRESS            // 198.51.100/24 TEST-NET-2
            u0 == 203 && u1 == 0 -> BlockedReason.RESERVED_ADDRESS             // 203.0.113/24 TEST-NET-3
            u0 == 198 && u1 in 18..19 -> BlockedReason.RESERVED_ADDRESS        // 198.18/15 基准测试
            u0 >= 240 -> BlockedReason.RESERVED_ADDRESS                        // 240/4 保留（含广播）
            else -> null
        }
    }

    private fun classifyV6(a: InetAddress): BlockedReason? {
        val b = a.address
        if (a.isLoopbackAddress) return BlockedReason.LOOPBACK                 // ::1
        if (a.isAnyLocalAddress) return BlockedReason.RESERVED_ADDRESS         // ::
        if (a.isLinkLocalAddress) return BlockedReason.LINK_LOCAL              // fe80::/10
        if (a.isSiteLocalAddress) return BlockedReason.PRIVATE_ADDRESS         // fec0::/10（废弃站点本地）
        if (a.isMulticastAddress) return BlockedReason.RESERVED_ADDRESS        // ff00::/8
        val u0 = b[0].toInt() and 0xff
        // fc00::/7 ULA
        if (u0 in 0xfc..0xfd) return BlockedReason.PRIVATE_ADDRESS
        // 2001:db8::/32 文档段
        if (u0 == 0x20 && (b[1].toInt() and 0xff) == 0x01 &&
            (b[2].toInt() and 0xff) == 0x0d && (b[3].toInt() and 0xff) == 0xb8
        ) return BlockedReason.RESERVED_ADDRESS

        // 内嵌 IPv4 还原：::ffff:x.y.z.w（映射形）、::x.y.z.w（兼容形）、
        // 2002::x.y.z.w:…（6to4）、64:ff9b::x.y.z.w（NAT64 well-known）
        val embedded: ByteArray? = when {
            b[0].toInt() == 0 && b[1].toInt() == 0 && b[2].toInt() == 0 && b[3].toInt() == 0 &&
                b[4].toInt() == 0 && b[5].toInt() == 0 && b[6].toInt() == 0 && b[7].toInt() == 0 &&
                b[8].toInt() == 0 && b[9].toInt() == 0 &&
                (b[10].toInt() and 0xff) == 0xff && (b[11].toInt() and 0xff) == 0xff ->
                b.copyOfRange(12, 16)
            b[0].toInt() == 0 && b[1].toInt() == 0 && b[2].toInt() == 0 && b[3].toInt() == 0 &&
                b[4].toInt() == 0 && b[5].toInt() == 0 && b[6].toInt() == 0 && b[7].toInt() == 0 &&
                b[8].toInt() == 0 && b[9].toInt() == 0 && b[10].toInt() == 0 && b[11].toInt() == 0 &&
                !(b[12].toInt() == 0 && b[13].toInt() == 0 && b[14].toInt() == 0 && b[15].toInt() == 0) ->
                b.copyOfRange(12, 16)
            u0 == 0x20 && (b[1].toInt() and 0xff) == 0x02 ->
                b.copyOfRange(2, 6)
            u0 == 0x00 && (b[1].toInt() and 0xff) == 0x64 &&
                (b[2].toInt() and 0xff) == 0xff && (b[3].toInt() and 0xff) == 0x9b &&
                b[4].toInt() == 0 && b[5].toInt() == 0 && b[6].toInt() == 0 && b[7].toInt() == 0 &&
                b[8].toInt() == 0 && b[9].toInt() == 0 && b[10].toInt() == 0 && b[11].toInt() == 0 ->
                b.copyOfRange(12, 16)
            else -> null
        }
        if (embedded != null) {
            return classifyV4(InetAddress.getByAddress(embedded) as Inet4Address)
        }
        return null
    }
}
