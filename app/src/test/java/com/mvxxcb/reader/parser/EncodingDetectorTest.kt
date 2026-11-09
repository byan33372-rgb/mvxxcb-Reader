package com.mvxxcb.reader.parser

import com.mvxxcb.reader.parser.util.EncodingDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/** 编码探测链（BOM → 规则 → Content-Type → meta → 字节探测 → GBK 兜底） */
class EncodingDetectorTest {

    private val gbk: Charset = Charset.forName("GBK")

    @Test
    fun `BOM优先`() {
        val text = "星尘落海，剑指南天。"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)
        val r = EncodingDetector.detectAndDecode(bytes, contentTypeCharset = null)!!
        assertEquals(EncodingDetector.Source.BOM, r.source)
        assertEquals(Charsets.UTF_8, r.charset)
        assertEquals(text, r.html)
    }

    @Test
    fun `Content-Type声明生效`() {
        val text = "灵剑山上的少年剑修。"
        val bytes = text.toByteArray(gbk)
        val r = EncodingDetector.detectAndDecode(bytes, contentTypeCharset = "GBK")!!
        assertEquals(EncodingDetector.Source.CONTENT_TYPE, r.source)
        assertEquals(text, r.html)
    }

    @Test
    fun `meta_charset探测（GBK声明）`() {
        // detail-gbk.html：真实 GBK 字节 + meta charset=gbk，无 Content-Type 声明
        val bytes = sampleBytes("detail-gbk.html")
        assertNull(EncodingDetector.charsetFromContentType(null))
        val r = EncodingDetector.detectAndDecode(bytes, contentTypeCharset = null)!!
        assertEquals(EncodingDetector.Source.META, r.source)
        assertEquals("GBK", r.charset.name().uppercase())
        assertTrue("GBK 解码后应含书名", r.html.contains("灵剑传说"))
        assertTrue(r.html.contains("王小明"))
    }

    @Test
    fun `字节探测识别未声明的UTF8`() {
        val text = "无任何编码声明的中文页面内容，需要字节级探测。"
        val r = EncodingDetector.detectAndDecode(text.toByteArray(Charsets.UTF_8), contentTypeCharset = null)!!
        assertEquals(EncodingDetector.Source.BYTE_PROBE, r.source)
        assertEquals(text, r.html)
    }

    @Test
    fun `GBK兜底识别无声明的GBK字节`() {
        val text = "少年剑修踏月而行，衣袂翻飞。"
        val bytes = text.toByteArray(gbk) // 无 BOM、无声明、非 UTF-8
        val r = EncodingDetector.detectAndDecode(bytes, contentTypeCharset = null)!!
        assertEquals(EncodingDetector.Source.GBK_FALLBACK, r.source)
        assertEquals(text, r.html)
    }

    @Test
    fun `规则编码优先于字节探测`() {
        // 声明错误的场景：用户规则指定 GBK；字节是 GBK → 规则层命中
        val text = "卷入千年之前的恩怨情仇。"
        val bytes = text.toByteArray(gbk)
        val r = EncodingDetector.detectAndDecode(bytes, contentTypeCharset = null, ruleEncoding = "GBK")!!
        assertEquals(EncodingDetector.Source.RULE, r.source)
        assertEquals(text, r.html)
    }

    @Test
    fun `非法声明名回退字节探测`() {
        val text = "声明了不存在的字符集时回退。"
        val r = EncodingDetector.detectAndDecode(text.toByteArray(Charsets.UTF_8), contentTypeCharset = "UTF-99")!!
        assertEquals(text, r.html)
    }

    @Test
    fun `空字节与完全不可解码返回null`() {
        assertNull(EncodingDetector.detectAndDecode(ByteArray(0), contentTypeCharset = null))
    }

    @Test
    fun `charset提取辅助`() {
        assertEquals("GBK", EncodingDetector.charsetFromContentType("text/html; charset=GBK"))
        assertEquals("utf-8", EncodingDetector.charsetFromContentType("text/html;charset=\"utf-8\""))
        val meta = "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=gbk\">".toByteArray(Charsets.ISO_8859_1)
        assertEquals("gbk", EncodingDetector.metaCharset(meta))
    }
}
