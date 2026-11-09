package com.mvxxcb.reader.data.crawl

import com.mvxxcb.reader.common.coroutine.DefaultDispatcherProvider
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.data.TestSupport
import com.mvxxcb.reader.data.db.AppDatabase
import com.mvxxcb.reader.data.export.TxtExporterImpl
import com.mvxxcb.reader.data.export.TxtFilePaths
import com.mvxxcb.reader.data.repository.BookRepositoryImpl
import com.mvxxcb.reader.data.repository.ChapterRepositoryImpl
import com.mvxxcb.reader.domain.error.ParseFailure
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StateFailure
import com.mvxxcb.reader.domain.export.TxtExportReport
import com.mvxxcb.reader.domain.model.ContentBlock
import com.mvxxcb.reader.domain.model.ParsedChapterContent
import com.mvxxcb.reader.parser.core.ParseOutcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * TxtExporterImpl 单测（契约 K11 / 任务书 WP-C TXT 规格）：
 * 格式（5 行文件头 + `=====` 分隔 + 章节标题 + 段落换行）、顺序（chapterIndex 升序）、
 * UTF-8 中文、图片块跳过、失败章如实记录（不静默整本成功）、重复导出幂等覆盖、
 * 空书/书不存在报错、文件名清洗。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TxtExporterImplTest {

    private lateinit var db: AppDatabase
    private lateinit var chapterRepo: ChapterRepositoryImpl
    private lateinit var bookRepo: BookRepositoryImpl
    private lateinit var exporter: TxtExporterImpl
    private val logger = TestSupport.FakeLogger()
    private val time = TestSupport.FakeTime()

    @Before
    fun setUp() {
        db = TestSupport.buildDb()
        val merger = TestSupport.newMerger(db, logger)
        chapterRepo = ChapterRepositoryImpl(db, db.chapterDao(), merger, time, logger)
        bookRepo = BookRepositoryImpl(db, db.bookDao(), merger, time, logger)
        exporter = TxtExporterImpl(bookRepo, chapterRepo, DefaultDispatcherProvider(), time, logger)
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 建书 + 目录并按 cachedCount 缓存前 N 章（含第 1 章一个图片块） */
    private suspend fun setupBook(
        title: String = "测试之书",
        author: String? = "作者甲",
        chapterCount: Int = 3,
        cachedCount: Int = chapterCount,
    ): Triple<Long, List<Long>, List<String>> {
        val bookId = TestSupport.insertBook(db, title = title)
        db.bookDao().upsert(
            db.bookDao().getById(bookId)!!.copy(author = author),
        )
        chapterRepo.replaceCatalog(
            bookId,
            TestSupport.catalogOf((1..chapterCount).map { i ->
                TestSupport.chapterRef(i - 1, "第${i}章 起点$i", "https://www.example.com/c/$i")
            }),
        )
        val ids = db.chapterDao().getAllForBook(bookId).map { it.id }
        val urls = (1..chapterCount).map { "https://www.example.com/c/$it" }
        for (i in 0 until cachedCount) {
            chapterRepo.saveContent(ids[i], parsedContent(urls[i], listOf("第${i + 1}章段落一", "第${i + 1}章段落二")))
        }
        return Triple(bookId, ids, urls)
    }

    private fun parsedContent(url: String, paragraphs: List<String>, withImage: Boolean = false) =
        ParsedChapterContent(
            sourceUrl = url,
            title = null,
            blocks = buildList {
                paragraphs.forEach { add(ContentBlock.Paragraph(it)) }
                if (withImage) add(ContentBlock.Image("https://www.example.com/pic.jpg", "配图"))
            },
            nextPageUrl = null, nextChapterUrl = null, previousChapterUrl = null,
            isTruncated = false, wordCount = 0,
        )

    private fun outPathFor(bookId: Long, title: String): File =
        TxtFilePaths.txtFileFor(TestSupport.appContext().filesDir, bookId, title)

    private fun readText(file: File): String = file.readText(Charsets.UTF_8)

    // ———— 格式 / 顺序 / 分隔符 / UTF-8 ————

    @Test
    fun `全本导出_文件头分隔符标题段落与UTF8中文`() = runBlocking {
        val (bookId, _, _) = setupBook()
        val file = outPathFor(bookId, "测试之书")
        val result = exporter.export(bookId, file.absolutePath)
        assertTrue("导出应成功：$result", result is AppResult.Success)
        val report = (result as AppResult.Success).value

        assertTrue(file.exists())
        val text = readText(file)
        val lines = text.split("\n")

        // 文件头 5 行元信息（任务书用户格式）
        assertEquals("书名：测试之书", lines[0])
        assertEquals("作者：作者甲", lines[1])
        assertTrue(lines[2].startsWith("来源：https://www.example.com/"))
        assertTrue(lines[3].startsWith("采集时间："))
        assertEquals("章节数量：3", lines[4])

        // 章节分隔符：`=====` × 章节数；标题按 chapterIndex 升序
        assertEquals(3, lines.count { it == "=====" })
        val p1 = text.indexOf("第1章 起点1")
        val p2 = text.indexOf("第2章 起点2")
        val p3 = text.indexOf("第3章 起点3")
        assertTrue(p1 in 5 until p2 && p2 < p3)

        // 段落保留换行（每段一行），UTF-8 中文原样
        assertTrue(text.contains("第1章段落一\n第1章段落二"))
        assertTrue(text.contains("第3章段落一"))

        // report 完整性对照（任务书：重读文件字节数/chapterCount）
        assertEquals(3, report.chapterCount)
        assertEquals(3, report.completedChapterCount)
        assertTrue(report.failedChapterIds.isEmpty())
        assertEquals(file.length(), report.fileSize)
        assertEquals(file.length(), text.toByteArray(Charsets.UTF_8).size.toLong())
    }

    @Test
    fun `图片块跳过不写入文件`() = runBlocking {
        val (bookId, ids, urls) = setupBook(cachedCount = 0)
        // 第 1 章重存为带图片块内容
        chapterRepo.saveContent(
            ids[0],
            ParsedChapterContent(
                sourceUrl = urls[0], title = null,
                blocks = listOf(
                    ContentBlock.Paragraph("纯文字段落"),
                    ContentBlock.Image("https://www.example.com/pic.jpg", "配图"),
                ),
                nextPageUrl = null, nextChapterUrl = null, previousChapterUrl = null,
                isTruncated = false, wordCount = 0,
            ),
        )
        for (i in 1 until ids.size) chapterRepo.saveContent(ids[i], parsedContent(urls[i], listOf("段$i")))
        val file = outPathFor(bookId, "测试之书")
        val result = exporter.export(bookId, file.absolutePath)
        assertTrue(result is AppResult.Success)
        val text = readText(file)
        assertFalse(text.contains("https://www.example.com/pic.jpg"))
        assertTrue(text.contains("纯文字段落"))
    }

    // ———— 失败章如实记录 ————

    @Test
    fun `失败章记录进report且文件尾附缺失清单`() = runBlocking {
        val (bookId, ids, _) = setupBook(cachedCount = 2) // 第 3 章未缓存
        val file = outPathFor(bookId, "测试之书")
        val result = exporter.export(bookId, file.absolutePath)
        assertTrue(result is AppResult.Success)
        val report = (result as AppResult.Success).value

        assertEquals(3, report.chapterCount)
        assertEquals(2, report.completedChapterCount)
        assertEquals(listOf(ids[2]), report.failedChapterIds)

        val text = readText(file)
        assertTrue(text.contains("未采集章节清单"))
        assertTrue(text.contains("- 第3章 起点3"))
        assertFalse(text.contains("=====\n第3章 起点3"))
    }

    @Test
    fun `isCached但正文损坏_计为失败不静默`() = runBlocking {
        val (bookId, ids, urls) = setupBook(cachedCount = 0)
        // 直接以损坏 JSON 落库：isCached=1 但 loadContent 为 null
        db.chapterDao().saveContent(ids[0], "{broken json", "hash", 0, time.nowMillis())
        chapterRepo.saveContent(ids[1], parsedContent(urls[1], listOf("正常段")))
        val file = outPathFor(bookId, "测试之书")
        val result = exporter.export(bookId, file.absolutePath)
        assertTrue(result is AppResult.Success)
        val report = (result as AppResult.Success).value
        assertEquals(listOf(ids[0], ids[2]), report.failedChapterIds) // 损坏章 + 未缓存章均如实记录
        assertEquals(1, report.completedChapterCount)
        assertTrue(readText(file).contains("未采集章节清单"))
    }

    // ———— 幂等 ————

    @Test
    fun `重复导出幂等覆盖同一路径`() = runBlocking {
        val (bookId, _, _) = setupBook()
        val file = outPathFor(bookId, "测试之书")
        val first = exporter.export(bookId, file.absolutePath)
        val firstText = readText(file)
        val firstSize = (first as AppResult.Success).value.fileSize
        val second = exporter.export(bookId, file.absolutePath)
        val secondText = readText(file)
        assertTrue(second is AppResult.Success)
        assertEquals(firstSize, (second as AppResult.Success).value.fileSize)
        assertEquals("重复导出不得追加/复制内容", firstText, secondText)
        assertEquals(firstText.toByteArray(Charsets.UTF_8).size.toLong(), file.length())
        // 同目录不产生第二份文件
        assertEquals(1, file.parentFile!!.listFiles()!!.size)
    }

    // ———— 报错路径 ————

    @Test
    fun `空书无目录章节报CATALOG_EMPTY`() = runBlocking {
        val bookId = TestSupport.insertBook(db, title = "空书", url = "https://www.example.com/empty")
        val file = outPathFor(bookId, "空书")
        val result = exporter.export(bookId, file.absolutePath)
        assertTrue(result is AppResult.Failure)
        val error = (result as AppResult.Failure).error
        assertTrue(error is ReaderError.Parse)
        assertEquals(ParseFailure.CATALOG_EMPTY, (error as ReaderError.Parse).failure)
        assertFalse("失败导出不得留下文件", file.exists())
    }

    @Test
    fun `书不存在报BOOK_NOT_FOUND`() = runBlocking {
        val result = exporter.export(999_999L, TestSupport.appContext().filesDir.resolve("nowhere.txt").absolutePath)
        assertTrue(result is AppResult.Failure)
        val error = (result as AppResult.Failure).error
        assertTrue(error is ReaderError.State)
        assertEquals(StateFailure.BOOK_NOT_FOUND, (error as ReaderError.State).failure)
    }

    // ———— 文件名清洗 ————

    @Test
    fun `文件名非法字符清洗且中文保留`() {
        assertEquals("a_b_c_d_e_f_g_h", TxtFilePaths.sanitizeFileName("a/b:c*d?e\"f<g>h|"))
        assertEquals("斗破苍穹", TxtFilePaths.sanitizeFileName("斗破苍穹"))
        assertEquals("书 名", TxtFilePaths.sanitizeFileName("书  名"))
        assertEquals("", TxtFilePaths.sanitizeFileName("///"))
        assertEquals("", TxtFilePaths.sanitizeFileName("  ..  "))
        // 清洗后为空 → 回退 book-<id>
        val file = TxtFilePaths.txtFileFor(File("/tmp"), 7L, "///")
        assertEquals("book-7.txt", file.name)
        // 路径结构：filesDir/txt/<bookId>/<书名>.txt（分隔符无关断言）
        assertEquals("7", file.parentFile!!.name)
        assertEquals("txt", file.parentFile!!.parentFile!!.name)
        val normal = TxtFilePaths.txtFileFor(File("/data/files"), 3L, "凡人修仙传")
        assertEquals("凡人修仙传.txt", normal.name)
        assertEquals("3", normal.parentFile!!.name)
        assertEquals("txt", normal.parentFile!!.parentFile!!.name)
    }
}
