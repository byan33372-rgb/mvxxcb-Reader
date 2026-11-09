package com.mvxxcb.reader.domain.service

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
}
