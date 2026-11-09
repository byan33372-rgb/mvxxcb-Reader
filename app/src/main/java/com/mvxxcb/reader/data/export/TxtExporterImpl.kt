package com.mvxxcb.reader.data.export

import com.mvxxcb.reader.common.coroutine.DispatcherProvider
import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.common.time.TimeProvider
import com.mvxxcb.reader.domain.error.ParseFailure
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StateFailure
import com.mvxxcb.reader.domain.error.StorageFailure
import com.mvxxcb.reader.domain.export.TxtExportReport
import com.mvxxcb.reader.domain.export.TxtExporter
import com.mvxxcb.reader.domain.model.Book
import com.mvxxcb.reader.domain.model.ChapterMeta
import com.mvxxcb.reader.domain.model.ContentBlock
import com.mvxxcb.reader.domain.repository.BookRepository
import com.mvxxcb.reader.domain.repository.ChapterRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TXT 导出器实现（契约 v1.1 K11 / 任务书 WP-C 用户格式）。
 *
 * - 数据源：ChapterRepository DB 真值（observeChapterMetas 按 chapterIndex 升序 + loadContent 逐章）；
 *   流式 BufferedWriter 分段写 UTF-8，万章不整本进内存（07 §4.1-6）。
 * - 格式（任务书冻结）：文件头 5 行元信息（书名/作者/来源/采集时间/章节数量）→ 空行 →
 *   每章：`=====` 分隔行 + 章节标题行 + 空行 + 段落逐行（段落换行保留）→ 章间空行。
 *   ContentBlock.Paragraph 按序逐行；Image 块跳过并计数（日志，不进报告字段）。
 * - 失败章节（未缓存 / isMissing / 正文损坏）如实记入 report.failedChapterIds 并在文件尾
 *   附「未采集章节清单」，绝不静默略过并标记整本成功（isComplete=false 由调用方按
 *   failedChapterIds.isEmpty() 派生——契约 TxtExportReport 无 filePath/isComplete 字段，不私改契约）。
 * - 完整性校验（任务书）：写毕重读文件——字节数与写入侧逐字节对照 + `=====` 分隔行数
 *   与已完成章数对照（正文段落与分隔符同文时歧义校正）+ 首行前缀检查；
 *   校验失败删除残件并返回 TXT_WRITE_FAILED（K14）。
 * - 幂等：同路径重复调用整文件覆盖（07 Q3 重新生成语义）。
 *
 * 不依赖 LocalBookRepository（WP-L 地盘）：LocalBook 登记由 K10 exportBookTxt / 主 Agent 整合位执行。
 */
class TxtExporterImpl(
    private val bookRepository: BookRepository,
    private val chapterRepository: ChapterRepository,
    private val dispatchers: DispatcherProvider,
    private val timeProvider: TimeProvider,
    private val logger: AppLogger,
) : TxtExporter {

    companion object {
        private const val TAG = "TxtExporter"

        /** 章节分隔符（任务书用户格式冻结） */
        internal const val SEPARATOR = "====="
        private const val HEADER_PREFIX = "书名："
        private const val MISSING_LIST_TITLE = "未采集章节清单"
    }

    override suspend fun export(bookId: Long, outPath: String): AppResult<TxtExportReport> =
        withContext(dispatchers.io) {
            try {
                exportInternal(bookId, outPath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "TXT 导出失败 bookId=$bookId outPath=$outPath", e)
                AppResult.Failure(
                    ReaderError.Storage(StorageFailure.TXT_WRITE_FAILED, "TXT 导出失败 bookId=$bookId", e),
                )
            }
        }

    private suspend fun exportInternal(bookId: Long, outPath: String): AppResult<TxtExportReport> {
        val book: Book = bookRepository.getBook(bookId)
            ?: return AppResult.Failure(
                ReaderError.State(StateFailure.BOOK_NOT_FOUND, "export: bookId=$bookId"),
            )
        // DB 真值目录（DAO 按 chapterIndex ASC）；空书拒绝（无目录无法整本导出）
        val metas: List<ChapterMeta> = chapterRepository.observeChapterMetas(bookId).first()
        if (metas.isEmpty()) {
            return AppResult.Failure(
                ReaderError.Parse(ParseFailure.CATALOG_EMPTY, debugDetail = "export: 空书无目录章节 bookId=$bookId"),
            )
        }

        val file = File(outPath)
        file.parentFile?.let { parent -> if (!parent.exists()) parent.mkdirs() }

        val timeText = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
            .format(Date(timeProvider.nowMillis()))
        var expectedBytes = 0L
        var completed = 0
        var imagesSkipped = 0
        // 正文段落恰与分隔符同文的歧义计数（完整性校验时校正，避免误判损坏）
        var ambiguousSeparators = 0
        val failedIds = mutableListOf<Long>()

        file.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
            fun writeLine(line: String) {
                writer.write(line)
                writer.write("\n")
                expectedBytes += line.toByteArray(Charsets.UTF_8).size + 1L
            }

            fun writeBlank() {
                writer.write("\n")
                expectedBytes += 1L
            }

            // 文件头 5 行元信息
            writeLine("$HEADER_PREFIX${book.title.ifBlank { "未知书名" }}")
            writeLine("作者：${book.author?.takeIf { it.isNotBlank() } ?: "佚名"}")
            writeLine("来源：${book.sourceUrl}")
            writeLine("采集时间：$timeText")
            writeLine("章节数量：${metas.size}")
            writeBlank()

            for (meta in metas) {
                if (!meta.isCached) {
                    // 未缓存（含 isMissing）→ 失败章如实记录（K11：失败清单=未缓存章节）
                    failedIds += meta.id
                    continue
                }
                val content = chapterRepository.loadContent(meta.id)
                if (content == null) {
                    // isCached=1 但正文缺失/损坏 → 导出真相计为失败，不静默
                    logger.w(TAG, "正文缺失/损坏，计为失败章 chapterId=${meta.id}")
                    failedIds += meta.id
                    continue
                }
                writeLine(SEPARATOR)
                writeLine(meta.title)
                writeBlank()
                for (block in content.blocks) {
                    when (block) {
                        is ContentBlock.Paragraph -> {
                            if (block.text == SEPARATOR) ambiguousSeparators++
                            writeLine(block.text)
                        }
                        // Image 块跳过并计数（文本 TXT 无法承载；不中断段落顺序）
                        is ContentBlock.Image -> imagesSkipped++
                    }
                }
                writeBlank()
                completed++
            }

            if (failedIds.isNotEmpty()) {
                writeLine("$MISSING_LIST_TITLE（${failedIds.size} 章）：")
                for (id in failedIds) {
                    val meta = metas.first { it.id == id }
                    writeLine("- ${meta.title}")
                }
            }
            writer.flush()
        }

        // ———— 完整性校验：重读文件字节数 / 分隔符行数 / 首行对照 ————
        var actualBytes = file.length()
        var separatorCount = 0
        var firstLine: String? = null
        file.inputStream().use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).useLines { lines ->
                var first = true
                lines.forEach { line ->
                    if (first) {
                        firstLine = line
                        first = false
                    }
                    if (line == SEPARATOR) separatorCount++
                }
            }
        }
        val expectedSeparatorCount = completed + ambiguousSeparators
        val headerOk = firstLine != null && firstLine.startsWith(HEADER_PREFIX)
        if (actualBytes != expectedBytes || separatorCount != expectedSeparatorCount || !headerOk) {
            val detail = "TXT 完整性校验失败 bytes=$actualBytes/$expectedBytes " +
                "separators=$separatorCount/$expectedSeparatorCount headerOk=$headerOk"
            logger.e(TAG, detail)
            file.delete()
            return AppResult.Failure(
                ReaderError.Storage(StorageFailure.TXT_WRITE_FAILED, detail),
            )
        }
        if (imagesSkipped > 0) {
            logger.d(TAG, "TXT 导出跳过图片块 $imagesSkipped 个 bookId=$bookId")
        }
        return AppResult.Success(
            TxtExportReport(
                chapterCount = metas.size,
                completedChapterCount = completed,
                failedChapterIds = failedIds.toList(),
                fileSize = actualBytes,
            ),
        )
    }
}

/**
 * TXT 受控文件路径与文件名清洗（任务书：`filesDir/txt/<bookId>/<书名>.txt`）。
 * 非法字符（路径分隔符/Windows 保留字符/控制字符）→ `_`；空白折叠；长度上限 80；
 * 清洗后为空回退 `book-<id>`（调用方 TxtExporterImpl / ChapterDownloadWorker 共用）。
 */
internal object TxtFilePaths {
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\u0000-\u001F]")
    private val WHITESPACE_RUN = Regex("\\s+")
    private const val MAX_NAME_LENGTH = 80

    fun sanitizeFileName(raw: String): String {
        val cleaned = ILLEGAL.replace(raw, "_")
            .replace(WHITESPACE_RUN, " ")
            .trim()
            .trim('.', ' ', '_')
        return if (cleaned.length > MAX_NAME_LENGTH) cleaned.take(MAX_NAME_LENGTH).trim('.', ' ', '_') else cleaned
    }

    fun txtFileFor(filesDir: File, bookId: Long, bookTitle: String): File {
        val safe = sanitizeFileName(bookTitle).ifEmpty { "book-$bookId" }
        return File(File(filesDir, "txt").resolve(bookId.toString()), "$safe.txt")
    }
}
