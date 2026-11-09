package com.mvxxcb.reader.data.mapper

import com.mvxxcb.reader.data.db.dao.ChapterMetaRow
import com.mvxxcb.reader.data.db.entities.BookEntity
import com.mvxxcb.reader.data.db.entities.BookmarkEntity
import com.mvxxcb.reader.data.db.entities.ChapterEntity
import com.mvxxcb.reader.data.db.entities.ReadingProgressEntity
import com.mvxxcb.reader.data.db.entities.SourceRuleEntity
import com.mvxxcb.reader.data.util.Sha256
import com.mvxxcb.reader.domain.model.Book
import com.mvxxcb.reader.domain.model.Bookmark
import com.mvxxcb.reader.domain.model.Chapter
import com.mvxxcb.reader.domain.model.ChapterMeta
import com.mvxxcb.reader.domain.model.ContentBlock
import com.mvxxcb.reader.domain.model.ReadingProgress
import com.mvxxcb.reader.domain.model.SourceRule
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Entity↔domain 映射（数据模型 03 §1：Entity 不出 data 层）。
 * 正文 JSON 编解码 / contentHash / wordCount 见 [ContentBlockCodec]（03 §8）。
 */

// ———— Book ————

fun BookEntity.toDomain(): Book = Book(
    id = id,
    title = title,
    author = author,
    coverUrl = coverUrl,
    description = description,
    sourceUrl = sourceUrl,
    sourceDomain = sourceDomain,
    catalogUrl = catalogUrl,
    latestChapterTitle = latestChapterTitle,
    totalChapters = totalChapters,
    currentChapterId = currentChapterId,
    lastReadAt = lastReadAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isTemporary = isTemporary,
    sourceEncoding = sourceEncoding,
)

// ———— Chapter / ChapterMeta ————

fun ChapterEntity.toDomain(): Chapter = Chapter(
    id = id,
    bookId = bookId,
    chapterIndex = chapterIndex,
    title = title,
    url = url,
    content = content,
    contentHash = contentHash,
    isCached = isCached,
    isMissing = isMissing,
    fetchedAt = fetchedAt,
    wordCount = wordCount,
)

fun ChapterMetaRow.toDomain(): ChapterMeta = ChapterMeta(
    id = id,
    bookId = bookId,
    chapterIndex = chapterIndex,
    title = title,
    url = url,
    isCached = isCached,
    isMissing = isMissing,
    wordCount = wordCount,
    fetchedAt = fetchedAt,
)

// ———— ReadingProgress ————

fun ReadingProgressEntity.toDomain(): ReadingProgress = ReadingProgress(
    bookId = bookId,
    chapterId = chapterId,
    chapterIndex = chapterIndex,
    scrollPosition = scrollPosition,
    pageIndex = pageIndex,
    inChapterFraction = inChapterFraction,
    progressPercentage = progressPercentage,
    charOffset = charOffset,
    updatedAt = updatedAt,
)

fun ReadingProgress.toEntity(): ReadingProgressEntity = ReadingProgressEntity(
    bookId = bookId,
    chapterId = chapterId,
    chapterIndex = chapterIndex,
    scrollPosition = scrollPosition,
    pageIndex = pageIndex,
    inChapterFraction = inChapterFraction,
    progressPercentage = progressPercentage,
    // 2.0（07 §5.4）：字符偏移主锚点（连续阅读）；旧数据/翻页模式保持 -1 sentinel
    charOffset = charOffset,
    updatedAt = updatedAt,
)

// ———— Bookmark ————

fun BookmarkEntity.toDomain(): Bookmark = Bookmark(
    id = id,
    bookId = bookId,
    chapterId = chapterId,
    chapterIndex = chapterIndex,
    scrollPosition = scrollPosition,
    pageIndex = pageIndex,
    inChapterFraction = inChapterFraction,
    selectedText = selectedText,
    createdAt = createdAt,
)

fun Bookmark.toEntity(): BookmarkEntity = BookmarkEntity(
    id = id,
    bookId = bookId,
    chapterId = chapterId,
    chapterIndex = chapterIndex,
    scrollPosition = scrollPosition,
    pageIndex = pageIndex,
    inChapterFraction = inChapterFraction,
    // 划选摘录 ≤200 字截断（03 §2.4）
    selectedText = selectedText?.take(MAX_SELECTED_TEXT_LENGTH),
    createdAt = createdAt,
)

/** 划选摘录截断长度（03 §2.4） */
const val MAX_SELECTED_TEXT_LENGTH = 200

// ———— SourceRule ————

fun SourceRuleEntity.toDomain(): SourceRule = SourceRule(
    ruleId = ruleId,
    domain = domain,
    version = version,
    ruleJson = ruleJson,
    enabled = enabled,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun SourceRule.toEntity(): SourceRuleEntity = SourceRuleEntity(
    ruleId = ruleId,
    domain = domain,
    version = version,
    ruleJson = ruleJson,
    enabled = enabled,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/**
 * 正文内容块编解码与派生指标（数据模型 03 §8）。
 *
 * ContentBlock 多态 JSON：判别字段 `t`
 *   段落 {"t":"p","v":"段落文本"}
 *   图片 {"t":"img","v":"图片URL","alt":null}
 * contentHash = 全部 Paragraph 文本以 `\n` 连接的 UTF-8 字节 SHA-256；
 * wordCount   = Paragraph 文本字符总数（不含图片）。
 */
object ContentBlockCodec {

    /** 向前兼容：未知 key 忽略、未知判别值读取端跳过（03 §6.4） */
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    @Serializable
    private data class BlockDto(val t: String, val v: String, val alt: String? = null)

    private const val TYPE_PARAGRAPH = "p"
    private const val TYPE_IMAGE = "img"

    fun blocksToJson(blocks: List<ContentBlock>): String {
        val dtos = blocks.map { block ->
            when (block) {
                is ContentBlock.Paragraph -> BlockDto(t = TYPE_PARAGRAPH, v = block.text)
                is ContentBlock.Image -> BlockDto(t = TYPE_IMAGE, v = block.url, alt = block.alt)
            }
        }
        return json.encodeToString(dtos)
    }

    /** 解析失败（损坏 JSON/非法结构）返回 null，由调用方兜底（不抛异常跨层，契约 02 §0.3） */
    fun blocksFromJson(text: String?): List<ContentBlock>? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            json.decodeFromString<List<BlockDto>>(text).mapNotNull { dto ->
                when (dto.t) {
                    TYPE_PARAGRAPH -> ContentBlock.Paragraph(dto.v)
                    TYPE_IMAGE -> ContentBlock.Image(dto.v, dto.alt)
                    // 未知判别值：向前兼容跳过（03 §6.4）
                    else -> null
                }
            }
        }.getOrNull()
    }

    /** 全部 Paragraph 文本以 `\n` 连接（03 §8） */
    fun paragraphText(blocks: List<ContentBlock>): String =
        blocks.filterIsInstance<ContentBlock.Paragraph>().joinToString("\n") { it.text }

    /** contentHash：段落文本连接后的 UTF-8 SHA-256 */
    fun computeContentHash(blocks: List<ContentBlock>): String = Sha256.hexOf(paragraphText(blocks))

    /** wordCount：Paragraph 文本字符总数（不含图片，03 §8） */
    fun computeWordCount(blocks: List<ContentBlock>): Int =
        blocks.filterIsInstance<ContentBlock.Paragraph>().sumOf { it.text.length }
}
