package com.mvxxcb.reader.parser.util

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 编码探测（04 §2 / 01 §4 R7）：BOM → Content-Type → meta charset → 字节探测 → GBK 兜底。
 *
 * 探测链（主 Agent 裁定顺序 + 站点规则编码插入位）：
 *   1. BOM（字节级事实，最高优先）
 *   2. 规则 encoding（用户对站点显式配置，见 03 §5；仅当合法且可严格解码）
 *   3. Content-Type 声明
 *   4. meta charset 声明（扫描前 2KB）
 *   5. 字节探测（严格 UTF-8 → UTF-16 空字节模式 → 高字节占比启发）
 *   6. GBK 兜底（GBK → GB18030）
 *
 * 每级候选解码失败（严格模式）自动落入下一级；全部失败返回 null（调用方映射
 * ParseFailure.ENCODING_FAILED）。空字节数组返回 null（调用方映射 EMPTY_RESPONSE）。
 */
object EncodingDetector {

    enum class Source { BOM, RULE, CONTENT_TYPE, META, BYTE_PROBE, GBK_FALLBACK }

    data class Result(
        val charset: Charset,
        val html: String,
        val source: Source,
    )

    private val UTF8 = Charsets.UTF_8
    private val UTF16LE = Charsets.UTF_16LE
    private val UTF16BE = Charsets.UTF_16BE
    private val GBK: Charset? = charsetOrNull("GBK")
    private val GB18030: Charset? = charsetOrNull("GB18030")

    fun detectAndDecode(
        bytes: ByteArray,
        contentTypeCharset: String?,
        ruleEncoding: String? = null,
    ): Result? {
        if (bytes.isEmpty()) return null

        // 1. BOM（解码后剥掉 BOM 字符本身，避免其进入正文/标题）
        bomCharset(bytes)?.let { cs ->
            decodeStrict(bytes, cs)?.let { return Result(cs, it.removePrefix("\uFEFF"), Source.BOM) }
        }

        // 2. 规则编码（用户显式配置优先于站点自声明，修正声明错误的站点）
        ruleEncoding?.let { name ->
            charsetOrNull(name)?.let { cs ->
                decodeStrict(bytes, cs)?.let { return Result(cs, it, Source.RULE) }
            }
        }

        // 3. Content-Type
        contentTypeCharset?.let { name ->
            charsetOrNull(name)?.let { cs ->
                decodeStrict(bytes, cs)?.let { return Result(cs, it, Source.CONTENT_TYPE) }
            }
        }

        // 4. meta charset（HTTP 头未声明时扫描字节前 2KB）
        metaCharset(bytes)?.let { name ->
            charsetOrNull(name)?.let { cs ->
                decodeStrict(bytes, cs)?.let { return Result(cs, it, Source.META) }
            }
        }

        // 5. 字节探测
        byteProbeCharset(bytes)?.let { cs ->
            decodeStrict(bytes, cs)?.let { return Result(cs, it, Source.BYTE_PROBE) }
        }

        // 6. GBK 兜底（GBK → GB18030；均为严格解码，失败则整体失败）
        GBK?.let { cs ->
            decodeStrict(bytes, cs)?.let { return Result(cs, it, Source.GBK_FALLBACK) }
        }
        GB18030?.let { cs ->
            decodeStrict(bytes, cs)?.let { return Result(cs, it, Source.GBK_FALLBACK) }
        }
        return null
    }

    // ———— 各级探测 ————

    private fun bomCharset(bytes: ByteArray): Charset? = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> UTF8
        bytes.size >= 4 && bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
            bytes[2] == 0xFE.toByte() && bytes[3] == 0xFF.toByte() -> charsetOrNull("UTF-32BE")
        bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() &&
            bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte() -> charsetOrNull("UTF-32LE")
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> UTF16BE
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> UTF16LE
        else -> null
    }

    /** Content-Type: text/html; charset=GBK（也兼容直接传入 meta 值） */
    fun charsetFromContentType(contentType: String?): String? {
        if (contentType.isNullOrBlank()) return null
        val m = Regex("(?i)charset\\s*=\\s*\"?([A-Za-z0-9_\\-]+)").find(contentType) ?: return null
        return m.groupValues[1]
    }

    /** meta charset：扫描前 2KB 字节（ISO-8859-1 读ASCII安全） */
    fun metaCharset(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        val m = Regex("(?i)<meta[^>]{0,400}?charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)").find(head) ?: return null
        return m.groupValues[1]
    }

    private fun byteProbeCharset(bytes: ByteArray): Charset? {
        val sample = bytes.size
        var nonAscii = 0
        var nullBytes = 0
        var nullAtEven = 0
        var nullAtOdd = 0
        for (i in 0 until sample) {
            val b = bytes[i].toInt() and 0xFF
            if (b >= 0x80) nonAscii++
            if (b == 0) {
                nullBytes++
                if (i % 2 == 0) nullAtEven++ else nullAtOdd++
            }
        }
        if (nonAscii == 0) return UTF8 // 纯 ASCII 即 UTF-8 子集

        // UTF-16 模式：大量 0x00 空字节且集中在固定奇偶位
        if (nullBytes > sample / 4) {
            if (nullAtEven > nullAtOdd * 3) return UTF16BE
            if (nullAtOdd > nullAtEven * 3) return UTF16LE
        }

        // 严格 UTF-8 尝试（最常见的声明缺失场景）
        if (isValidUtf8(bytes)) return UTF8

        // 高字节占比显著且非 UTF-8 → 交给 GBK 兜底层
        return null
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        val decoder = UTF8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun charsetOrNull(name: String): Charset? = try {
        if (name.isNotBlank()) Charset.forName(name.trim()) else null
    } catch (_: Exception) {
        null
    }
}
