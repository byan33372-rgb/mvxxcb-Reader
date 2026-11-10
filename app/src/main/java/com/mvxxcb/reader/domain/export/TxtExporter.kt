package com.mvxxcb.reader.domain.export

import com.mvxxcb.reader.common.result.AppResult

/**
 * TXT 导出器（契约 v1.1 K11；接口骨架 WP-M0，实现 WP-C）。
 *
 * 消费 ChapterRepository 数据：observeChapterMetas 顺序 + loadContent 逐章流式写 UTF-8
 * （分段 flush，万章不整本进内存）；失败章节写入缺失清单。
 * TXT 结构（冻结）：文件头 4 行元信息（书名/作者/来源 URL/采集时间+章节数）→
 * 章节分隔符 `──── 第N章 <标题> ────` → 段落逐行；文件尾附「未采集章节清单」。
 */
interface TxtExporter {

    suspend fun export(bookId: Long, outPath: String): AppResult<TxtExportReport>
}

data class TxtExportReport(
    val chapterCount: Int,
    val completedChapterCount: Int,
    val failedChapterIds: List<Long>,
    val fileSize: Long,
    // 2.0 二期总指令 §7：TXT 完整性哈希（SHA-256，原子写完成后计算；v1.1.1 增量字段）
    val fileSha256: String? = null,
)
