package com.mvxxcb.reader.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * reading_progress 表（数据模型 03 §2.3，逐字段对齐）。
 *
 * 一书一条（bookId 为主键）；删除书时级联清理。
 * inChapterFraction 必填：重排版恢复锚点（契约 02 §8.6 阅读位置恢复契约）。
 */
@Entity(
    tableName = "reading_progress",
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
        Index(value = ["chapterId"]),
    ],
)
data class ReadingProgressEntity(
    @PrimaryKey val bookId: Long,
    val chapterId: Long,
    // 冗余便于展示
    @ColumnInfo(defaultValue = "0") val chapterIndex: Int = 0,
    // 滚动模式章内 0..1
    val scrollPosition: Double? = null,
    // 翻页模式页码
    val pageIndex: Int? = null,
    // 统一章内位置（必填）
    @ColumnInfo(defaultValue = "0.0") val inChapterFraction: Double = 0.0,
    // 全书 0..100
    @ColumnInfo(defaultValue = "0.0") val progressPercentage: Double = 0.0,
    val updatedAt: Long,
)
