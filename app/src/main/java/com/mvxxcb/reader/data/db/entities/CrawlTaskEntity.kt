package com.mvxxcb.reader.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * crawl_tasks 表（DB v2，设计 07 §3.1-b / 契约 K9，逐字段对齐）。
 *
 * - FK→books.id ON DELETE CASCADE（删书即清任务记录）。
 * - INDEX(bookId,state)：同书非终态唯一性查询（WAITING/RUNNING/PAUSED）。
 * - INDEX(updatedAt DESC)：采集中心清单排序键。
 * - state 存 TEXT（CrawlState 名）；章节级状态以 chapters.isCached 为 DB 真值派生，不在本表。
 * - id PK 自增：终态再入队=新建任务（允许多条历史记录，同一时刻同书至多一个非终态）。
 */
@Entity(
    tableName = "crawl_tasks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["bookId", "state"]),
        Index(value = ["updatedAt"], orders = [Index.Order.DESC]),
    ],
)
data class CrawlTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val bookId: Long,
    // CrawlState 名（WAITING/RUNNING/PAUSED/FAILED/COMPLETED/CANCELLED）
    @ColumnInfo(defaultValue = "'WAITING'") val state: String = "WAITING",
    // 规范化后目录总章数
    @ColumnInfo(defaultValue = "0") val totalChapters: Int = 0,
    @ColumnInfo(defaultValue = "0") val succeededCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val failedCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val currentChapterIndex: Int = 0,
    val currentChapterTitle: String? = null,
    // TXT 生成后受控文件绝对路径
    val txtPath: String? = null,
    // TXT 生成态（COMPLETED 自动生成）
    @ColumnInfo(defaultValue = "0") val txtGenerated: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
)
