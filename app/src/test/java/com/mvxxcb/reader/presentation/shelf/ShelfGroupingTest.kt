package com.mvxxcb.reader.presentation.shelf

// WP-L（07 §6.2 / B3）：书架分组判定测试（纯 JVM）。五种书形态→分组正确 + 优先级消解。
// 判定语义见 ShelfGrouping（ShelfViewModel.kt）注释：下载中 > 本地导入 > 已完整下载 > 部分下载 > 在线。

import com.mvxxcb.reader.domain.model.Book
import com.mvxxcb.reader.domain.model.CrawlState
import com.mvxxcb.reader.domain.model.CrawlTask
import com.mvxxcb.reader.domain.model.LocalBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShelfGroupingTest {

    // ———— 样本 ————

    private fun book(id: Long): Book = Book(
        id = id, title = "书$id", author = null, coverUrl = null, description = null,
        sourceUrl = "https://www.example.com/book/$id", sourceDomain = "www.example.com",
        catalogUrl = null, latestChapterTitle = null, totalChapters = 10, currentChapterId = null,
        lastReadAt = null, createdAt = 1L, updatedAt = 1L, isTemporary = false, sourceEncoding = null,
    )

    private fun task(
        id: Long, bookId: Long, state: CrawlState, updatedAt: Long,
        ok: Int = 0, fail: Int = 0, txt: Boolean = false,
    ): CrawlTask = CrawlTask(
        id = id, bookId = bookId, state = state, totalChapters = 10,
        succeededCount = ok, failedCount = fail, currentChapterIndex = 0,
        currentChapterTitle = null, txtPath = if (txt) "/tmp/t.txt" else null,
        txtGenerated = txt, createdAt = 1L, updatedAt = updatedAt,
        startedAt = null, finishedAt = null,
    )

    private fun local(bookId: Long, isComplete: Boolean = true): LocalBook = LocalBook(
        id = bookId, bookId = bookId, filePath = "/files/txt/$bookId/x.txt", format = "TXT",
        fileSize = 1024L, chapterCount = 10,
        completedChapterCount = if (isComplete) 10 else 5, isComplete = isComplete,
        createdAt = 1L, updatedAt = 1L,
    )

    // ———— 五种书形态 → 分组正确 ————

    @Test
    fun `形态1_在线书_无任务无登记_无角标_归在线`() {
        val badge = ShelfGrouping.badgeOf(tasks = emptyList(), local = null)
        assertNull(badge)
        assertEquals(ShelfGroup.ONLINE, ShelfGrouping.groupOf(badge))
    }

    @Test
    fun `形态2_下载中_非终态任务三态均判下载中`() {
        listOf(CrawlState.WAITING, CrawlState.RUNNING, CrawlState.PAUSED).forEach { state ->
            val badge = ShelfGrouping.badgeOf(tasks = listOf(task(1, 1, state, 1)), local = null)
            assertEquals(ShelfDownloadBadge.DOWNLOADING, badge)
            assertEquals(ShelfGroup.DOWNLOADING, ShelfGrouping.groupOf(badge))
        }
    }

    @Test
    fun `形态3_已完整下载_COMPLETED任务或登记完整_归已下载`() {
        // 任务 COMPLETED（TXT 已生成）
        val byTask = ShelfGrouping.badgeOf(
            tasks = listOf(task(1, 1, CrawlState.COMPLETED, 2, ok = 10, txt = true)),
            local = null,
        )
        assertEquals(ShelfDownloadBadge.DOWNLOADED, byTask)
        assertEquals(ShelfGroup.DOWNLOADED, ShelfGrouping.groupOf(byTask))

        // 无 COMPLETED 任务但登记文件完整（历史下载已清除任务记录的场景）
        val byLocal = ShelfGrouping.badgeOf(tasks = emptyList(), local = local(1, isComplete = true))
        // 本地导入优先：无任务记录 + 有登记 → 本地导入（不是已下载）
        assertEquals(ShelfDownloadBadge.LOCAL, byLocal)

        // 有任务记录且登记完整 → 已完整下载
        val taskAndLocal = ShelfGrouping.badgeOf(
            tasks = listOf(task(1, 1, CrawlState.FAILED, 2, ok = 6, fail = 4)),
            local = local(1, isComplete = true),
        )
        assertEquals(ShelfDownloadBadge.DOWNLOADED, taskAndLocal)
    }

    @Test
    fun `形态4_部分下载_终态任务未完成_归已下载chip角标部分`() {
        listOf(CrawlState.FAILED, CrawlState.CANCELLED).forEach { state ->
            val badge = ShelfGrouping.badgeOf(
                tasks = listOf(task(1, 1, state, 2, ok = 6, fail = state.let { if (it == CrawlState.FAILED) 4 else 0 })),
                local = null,
            )
            assertEquals(ShelfDownloadBadge.PARTIAL, badge)
            // 分组映射冻结：部分下载归入「已下载」chip，卡片角标区分完整/部分
            assertEquals(ShelfGroup.DOWNLOADED, ShelfGrouping.groupOf(badge))
        }
    }

    @Test
    fun `形态5_本地导入_仅登记无任务_归本地导入`() {
        assertEquals(
            ShelfDownloadBadge.LOCAL,
            ShelfGrouping.badgeOf(tasks = emptyList(), local = local(1, isComplete = true)),
        )
        // isComplete=false 的本地登记（导入文件不完整）仍归本地导入（无采集任务语义优先）
        assertEquals(
            ShelfDownloadBadge.LOCAL,
            ShelfGrouping.badgeOf(tasks = emptyList(), local = local(1, isComplete = false)),
        )
        assertEquals(ShelfGroup.LOCAL, ShelfGrouping.groupOf(ShelfDownloadBadge.LOCAL))
    }

    // ———— 优先级消解 ————

    @Test
    fun `优先级_非终态任务压过本地完整登记_判下载中`() {
        val badge = ShelfGrouping.badgeOf(
            tasks = listOf(task(1, 1, CrawlState.RUNNING, 3, ok = 2)),
            local = local(1, isComplete = true),
        )
        assertEquals(ShelfDownloadBadge.DOWNLOADING, badge)
    }

    @Test
    fun `优先级_同书多任务_任一非终态即下载中_终态取updatedAt最新`() {
        // 任一非终态任务存在 → 下载中（K9 同书非终态唯一，优先级第一）
        val withRunning = ShelfGrouping.badgeOf(
            tasks = listOf(
                task(1, 1, CrawlState.RUNNING, updatedAt = 2),
                task(2, 1, CrawlState.CANCELLED, updatedAt = 5),
            ),
            local = null,
        )
        assertEquals(ShelfDownloadBadge.DOWNLOADING, withRunning)

        // 全为终态 → 按 updatedAt 最新判定：COMPLETED 压过旧 FAILED
        val terminalOnly = ShelfGrouping.badgeOf(
            tasks = listOf(
                task(1, 1, CrawlState.FAILED, updatedAt = 2, ok = 6, fail = 4),
                task(2, 1, CrawlState.COMPLETED, updatedAt = 5, ok = 10, txt = true),
            ),
            local = null,
        )
        assertEquals(ShelfDownloadBadge.DOWNLOADED, terminalOnly)
    }

    @Test
    fun `优先级_仅失败任务无登记_判部分下载`() {
        val badge = ShelfGrouping.badgeOf(
            tasks = listOf(task(1, 1, CrawlState.FAILED, 2, ok = 6, fail = 4)),
            local = null,
        )
        assertEquals(ShelfDownloadBadge.PARTIAL, badge)
    }

    // ———— 全书架聚合 ————

    @Test
    fun `badgesOf_按书聚合任务与登记`() {
        val books = listOf(book(1), book(2), book(3), book(4))
        val tasks = listOf(
            task(11, 1, CrawlState.RUNNING, 3),
            task(12, 2, CrawlState.COMPLETED, 2, ok = 10, txt = true),
        )
        val localByBook = mapOf(3L to local(3, isComplete = true), 4L to null)
        val badges = ShelfGrouping.badgesOf(books, tasks, localByBook)

        assertEquals(4, badges.size)
        assertEquals(ShelfDownloadBadge.DOWNLOADING, badges[1L])
        assertEquals(ShelfDownloadBadge.DOWNLOADED, badges[2L])
        assertEquals(ShelfDownloadBadge.LOCAL, badges[3L])
        assertNull(badges[4L])
    }
}
