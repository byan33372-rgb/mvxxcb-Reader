package com.mvxxcb.reader.parser

import com.mvxxcb.reader.parser.util.UrlNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** UrlNormalizer 七步规范化（03 §7.1） */
class UrlNormalizerTest {

    @Test
    fun `步骤1 scheme与host统一小写`() {
        assertEquals("http://www.example.com/Path", UrlNormalizer.normalize("HTTP://WWW.Example.COM/Path"))
        assertEquals("https://www.example.com/a", UrlNormalizer.normalize("HTTPS://WWW.example.com/a"))
    }

    @Test
    fun `步骤2 去默认端口并补空path`() {
        assertEquals("http://example.com/", UrlNormalizer.normalize("http://example.com:80"))
        assertEquals("https://example.com/", UrlNormalizer.normalize("https://example.com:443"))
        assertEquals("http://example.com:8080/x", UrlNormalizer.normalize("http://example.com:8080/x"))
        assertEquals("http://example.com/", UrlNormalizer.normalize("http://example.com"))
    }

    @Test
    fun `步骤3 相对链接基于当前页解析为绝对URL`() {
        assertEquals(
            "https://www.example.com/book/100/chapter/1.html",
            UrlNormalizer.normalize("chapter/1.html", "https://www.example.com/book/100/index.html"),
        )
        assertEquals(
            "https://www.example.com/root.html",
            UrlNormalizer.normalize("/root.html", "https://www.example.com/book/100/"),
        )
    }

    @Test
    fun `步骤4 去fragment与跟踪参数`() {
        assertEquals(
            "https://www.example.com/book/1.html?id=9",
            UrlNormalizer.normalize("https://www.example.com/book/1.html?utm_source=x&utm_campaign=y&from=z&spm=a&fr=b&id=9#frag"),
        )
        assertEquals(
            "https://www.example.com/book/1.html",
            UrlNormalizer.normalize("https://www.example.com/book/1.html?gclid=abc&fbclid=def&share_token=t#top"),
        )
    }

    @Test
    fun `步骤5 百分号编码统一解码后按UTF8重编码（大写十六进制）`() {
        assertEquals(
            "https://www.example.com/%E4%B8%AD%E6%96%87/a%20b.html",
            UrlNormalizer.normalize("https://www.example.com/%e4%b8%ad%E6%96%87/a%20b.html"),
        )
        // 原始未编码中文 → 编码
        assertEquals(
            "https://www.example.com/book/%E5%89%91.html",
            UrlNormalizer.normalize("https://www.example.com/book/剑.html"),
        )
    }

    @Test
    fun `步骤6 尾部斜杠归一`() {
        assertEquals("https://www.example.com/book", UrlNormalizer.normalize("https://www.example.com/book/"))
        assertEquals("https://www.example.com/", UrlNormalizer.normalize("https://www.example.com/"))
        assertEquals("https://www.example.com/", UrlNormalizer.normalize("https://www.example.com"))
    }

    @Test
    fun `步骤7 保留定位章节的query但排除跟踪参数`() {
        assertEquals(
            "https://www.example.com/chapter?id=42&vol=2",
            UrlNormalizer.normalize("https://www.example.com/chapter?id=42&vol=2"),
        )
        assertEquals(
            "https://www.example.com/chapter?id=42",
            UrlNormalizer.normalize("https://www.example.com/chapter?utm_term=t&id=42"),
        )
    }

    @Test
    fun `规范化幂等`() {
        val raw = "https://www.example.com/book/%e5%89%91%E4%BC%A0/?utm_source=a&id=3#frag"
        val once = UrlNormalizer.normalize(raw)!!
        assertEquals(once, UrlNormalizer.normalize(once))
    }

    @Test
    fun `非法URL返回null`() {
        assertNull(UrlNormalizer.normalize("not a url at all"))
        assertNull(UrlNormalizer.normalize("ftp://files.example.com/a"))
        assertNull(UrlNormalizer.normalize(""))
    }

    @Test
    fun `hostOf提取小写host`() {
        assertEquals("www.example.com", UrlNormalizer.hostOf("https://WWW.Example.COM:8080/a?b=c"))
        assertNull(UrlNormalizer.hostOf("::no-host::"))
        assertFalse(UrlNormalizer.isTrackingParam("id"))
        assertTrue(UrlNormalizer.isTrackingParam("utm_medium"))
        assertTrue(UrlNormalizer.isTrackingParam("from"))
    }
}
