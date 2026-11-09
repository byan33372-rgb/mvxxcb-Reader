package com.mvxxcb.reader.domain.service

import com.mvxxcb.reader.common.result.AppResult
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.error.StateFailure

interface ReadingService {
    /** 缓存优先加载章节正文；未缓存且 preferCache=false 或无缓存时经 pipeline 抓取解析并入库 */
    suspend fun loadChapter(bookId: Long, chapterIndex: Int, preferCache: Boolean = true):
        com.mvxxcb.reader.common.result.AppResult<com.mvxxcb.reader.domain.model.ReaderContent>
    /** 章节 URL 直达加载（用于临时阅读/分享链接，绑定 bookId 时同步补目录条目） */
    suspend fun loadChapterByUrl(bookId: Long, url: String):
        com.mvxxcb.reader.common.result.AppResult<com.mvxxcb.reader.domain.model.ReaderContent>
    /** 重新抓取目录并按「规范化 URL + 标题指纹」合并（算法见 03 §8），绝不盲目覆盖 */
    suspend fun refreshCatalog(bookId: Long):
        com.mvxxcb.reader.common.result.AppResult<com.mvxxcb.reader.domain.model.CatalogMergeReport>
    /** 重新解析详情并更新书信息（字段非空才覆盖，不动进度/缓存） */
    suspend fun refreshBookDetail(bookId: Long): com.mvxxcb.reader.common.result.AppResult<com.mvxxcb.reader.domain.model.ParsedBookDetail>

    // ———— 2.0 采集门面（契约 v1.1 K10；实现委托 CrawlTaskRepository/ChapterCrawler/TxtExporter） ————
    // 以下默认体为 WP-M0 契约骨架占位：返回 State(INVALID_ARGUMENT)，保证既有实现
    // （DefaultReadingService / 测试假体）零改动可编译；WP-C 在 B2 批次 override 全部七方法，
    // 既有四方法签名不动（K10 冻结）。

    /** 确认信息→建任务入队；已有非终态任务幂等返回 */
    suspend fun downloadWholeBook(bookId: Long): AppResult<com.mvxxcb.reader.domain.model.CrawlTask> =
        scaffoldNotImplemented()

    suspend fun pauseCrawl(taskId: Long): AppResult<Unit> = scaffoldNotImplemented()

    suspend fun resumeCrawl(taskId: Long): AppResult<Unit> = scaffoldNotImplemented()

    /** 取消采集（含 cancelUniqueWork 取消链） */
    suspend fun cancelCrawl(taskId: Long): AppResult<Unit> = scaffoldNotImplemented()

    /** 失败章重入队（FAILED→WAITING） */
    suspend fun retryCrawlFailures(taskId: Long): AppResult<Unit> = scaffoldNotImplemented()

    /** 单章重采（经 ChapterFetcher） */
    suspend fun retryChapter(bookId: Long, chapterId: Long): AppResult<com.mvxxcb.reader.domain.model.ChapterMeta> =
        scaffoldNotImplemented()

    /** 合并 TXT + LocalBook 登记（幂等覆盖） */
    suspend fun exportBookTxt(bookId: Long): AppResult<com.mvxxcb.reader.domain.model.LocalBook> =
        scaffoldNotImplemented()

    private fun scaffoldNotImplemented(): AppResult<Nothing> =
        AppResult.Failure(
            ReaderError.State(
                StateFailure.INVALID_ARGUMENT,
                debugDetail = "契约 v1.1 K10 骨架占位（WP-M0）：实现由 WP-C 在 B2 批次提供",
            ),
        )
}
