package com.mvxxcb.reader.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * bookmarks 表（数据模型 03 §2.4，逐字段对齐）。
 *
 * 删除书/章节时级联清理；selectedText ≤200 字截断（截断在 Repository 写入前执行）。
 */
@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["id"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["bookId", "createdAt"]),
        Index(value = ["chapterId"]),
    ],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val bookId: Long,
    val chapterId: Long,
    @ColumnInfo(defaultValue = "0") val chapterIndex: Int = 0,
    val scrollPosition: Double? = null,
    val pageIndex: Int? = null,
    @ColumnInfo(defaultValue = "0.0") val inChapterFraction: Double = 0.0,
    // 划选摘录（可空）
    val selectedText: String? = null,
    val createdAt: Long,
)
