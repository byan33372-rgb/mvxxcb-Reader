package com.mvxxcb.reader.domain.model

/**
 * 本地整本文件（契约 v1.1 K11；设计 07 §K11 逐字冻结）。
 *
 * filePath 为应用受控目录绝对路径（filesDir/txt/）；SAF 导出副本不动此值。
 * isComplete=false 表示有失败章（TXT 内附缺失清单，禁止静默整本成功）。
 */
data class LocalBook(
    val id: Long,
    val bookId: Long,
    val filePath: String,                   // 应用受控目录绝对路径（filesDir/txt/）
    val format: String,                     // "TXT" / "EPUB"
    val fileSize: Long,
    val chapterCount: Int,
    val completedChapterCount: Int,
    val isComplete: Boolean,                // false=有失败章（TXT 内附缺失清单）
    val createdAt: Long,                    // epoch ms
    val updatedAt: Long,                    // epoch ms
)
