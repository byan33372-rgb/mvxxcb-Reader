package com.mvxxcb.reader.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * local_books 表（DB v2，设计 07 §3.1-c / 契约 K11，逐字段对齐）。
 *
 * - FK→books.id ON DELETE CASCADE；bookId UNIQUE（一书至多一个登记的整本文件）。
 * - filePath 为应用受控目录绝对路径（filesDir/txt/）；SAF 导出副本不动此值。
 * - isComplete=0 表示有失败章（TXT 内附缺失清单，禁止静默整本成功）。
 */
@Entity(
    tableName = "local_books",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["bookId"], unique = true),
    ],
)
data class LocalBookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val bookId: Long,
    val filePath: String,
    // "TXT" / "EPUB"
    val format: String,
    @ColumnInfo(defaultValue = "0") val fileSize: Long = 0L,
    @ColumnInfo(defaultValue = "0") val chapterCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val completedChapterCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val isComplete: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)
