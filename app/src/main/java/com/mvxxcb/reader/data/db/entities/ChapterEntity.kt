package com.mvxxcb.reader.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * chapters 表（数据模型 03 §2.2，逐字段对齐）。
 *
 * - FK→books.id ON DELETE CASCADE；bookId 复合索引同时充当 FK 索引。
 * - UNIQUE(bookId, chapterIndex)：书内规范顺序 0-based 唯一。
 * - content 为 ContentBlock JSON 数组文本（03 §8）。
 * - 目录更新（03 §7.3 合并）保持 id 稳定，保护进度/书签/缓存。
 */
@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["bookId", "chapterIndex"], unique = true),
        Index(value = ["bookId", "isCached"]),
        Index(value = ["bookId", "normalizedUrl"]),
        Index(value = ["bookId", "titleFingerprint"]),
    ],
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val bookId: Long,
    val chapterIndex: Int,
    @ColumnInfo(defaultValue = "''") val title: String = "",
    // 规范化章节 URL
    val url: String,
    val normalizedUrl: String,
    // 标题指纹（03 §7.2，SHA-256 前 32 hex），目录合并匹配键
    val titleFingerprint: String,
    val volumeTitle: String? = null,
    // JSON 数组：[{"t":"p","v":"…"},{"t":"img","v":"…","alt":null}]
    val content: String? = null,
    // 规范化正文 SHA-256（完整性校验）
    val contentHash: String? = null,
    @ColumnInfo(defaultValue = "0") val isCached: Boolean = false,
    // 目录更新后站点已无此章（保护进度/缓存，不删除）
    @ColumnInfo(defaultValue = "0") val isMissing: Boolean = false,
    val fetchedAt: Long? = null,
    // 纯文字字符数
    @ColumnInfo(defaultValue = "0") val wordCount: Int = 0,
)
