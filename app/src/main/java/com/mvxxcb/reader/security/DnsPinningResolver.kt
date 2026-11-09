package com.mvxxcb.reader.security

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns

/**
 * DNS 钉扎解析器（可选防护，契约 04 §5：防 DNS rebinding）。
 *
 * 作为全应用唯一 OkHttpClient 的 [Dns] 实现：
 *  - 解析结果逐地址过 [AddressGuard] 同一私网/保留段黑名单（与
 *    [UrlSecurityValidatorImpl] 校验时使用的规则完全一致）；
 *  - 任一地址命中 → 剔除；全部命中 → 抛 [UnknownHostException]，OkHttp 以
 *    DNS_FAILURE 语义失败；
 *  - 由此把"校验时看到的 IP"与"建连时实际使用的 IP"收敛到同一黑名单约束上，
 *    封堵经典的 DNS rebinding（域名在校验时返回公网 IP、建连时返回私网 IP）。
 *
 * 残余风险（写入《安全基线》）：两次解析之间攻击者仍可把一个公网 IP 换成另一个
 * 公网 IP（不构成 SSRF）；Teredo (2001::/32) 内嵌 IPv4 未还原，依赖逐跳复查兜底。
 *
 * [allowListForTest] 与 [UrlSecurityValidatorImpl] 语义一致：仅测试放行
 * MockWebServer 所在回环地址，默认空集，生产装配不传。
 */
class DnsPinningResolver(
    private val hostResolver: HostResolver = DefaultHostResolver(),
    private val allowListForTest: Set<String> = emptySet(),
) : Dns {

    @Throws(UnknownHostException::class)
    override fun lookup(hostname: String): List<InetAddress> {
        val key = hostname.trim().trimEnd('.').lowercase()
        val addresses = try {
            hostResolver.resolve(key)
        } catch (e: UnknownHostException) {
            throw UnknownHostException("DNS 解析失败: ${UrlSecurityValidatorImpl.redactQuery(key)}").initCause(e)
        }
        if (allowListForTest.contains(key)) return addresses.toList()

        val safe = addresses.filter { AddressGuard.classify(it) == null }
        if (safe.isEmpty()) {
            throw UnknownHostException(
                "主机 ${UrlSecurityValidatorImpl.redactQuery(key)} 的 DNS 解析结果全部命中私网/保留段黑名单（DNS rebinding 防护）",
            )
        }
        return safe
    }
}
