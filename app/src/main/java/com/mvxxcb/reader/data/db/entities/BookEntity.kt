package com.mvxxcb.reader.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * books 表（数据模型 03 §2.1，逐字段对齐）。
 *
 * - normalizedSourceUrl UNIQUE：按规范化来源 URL 去重（导入合并键）。
 * - lastReadAt DESC 索引：书架排序键。
 * - 临时书（isTemporary=1）不进书架，历史/继续阅读可见。
 */
@Entity(
    tableName = "books",
    indices = [
        Index(value = ["normalizedSourceUrl"], unique = true),
        Index(value = ["lastReadAt"]),
    ],
)
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    // 未解析出书名时空串占位，禁止伪造（03 §2.1）
    @ColumnInfo(defaultValue = "''") val title: String = "",
    val author: String? = null,
    val coverUrl: String? = null,
    val description: String? = null,
    // 原始（规范化后）来源 URL
    val sourceUrl: String,
    // 去重键
    val normalizedSourceUrl: String,
    // 小写 host
    val sourceDomain: String,
    // 规范化目录页 URL
    val catalogUrl: String? = null,
    val latestChapterTitle: String? = null,
    // 0 = 无目录
    @ColumnInfo(defaultValue = "0") val totalChapters: Int = 0,
    // 便捷字段；进度真值在 reading_progress
    val currentChapterId: Long? = null,
    // epoch ms；书架排序键
    val lastReadAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    // 1 = 临时阅读记录
    @ColumnInfo(defaultValue = "0") val isTemporary: Boolean = false,
    // 如 "GBK"，重取时提示 parser
    val sourceEncoding: String? = null,
    // 2.0（DB v2，07 §3.1-e）：0=WEB / 1=LOCAL_TXT / 2=EPUB；老数据 default 语义正确
    @ColumnInfo(defaultValue = "0") val sourceType: Int = 0,
)
