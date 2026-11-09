package com.mvxxcb.reader.domain.repository

interface ChapterRepository {
    fun observeChapterMetas(bookId: Long): kotlinx.coroutines.flow.Flow<List<com.mvxxcb.reader.domain.model.ChapterMeta>>
    suspend fun getChapterMeta(chapterId: Long): com.mvxxcb.reader.domain.model.ChapterMeta?
    suspend fun getChapterByIndex(bookId: Long, chapterIndex: Int): com.mvxxcb.reader.domain.model.Chapter?
    suspend fun getChapterByUrl(bookId: Long, normalizedUrl: String): com.mvxxcb.reader.domain.model.Chapter?
    /** 目录写入：内部执行 URL 规范化+指纹合并（03 §8），返回合并报告 */
    suspend fun replaceCatalog(bookId: Long, catalog: com.mvxxcb.reader.domain.model.ParsedCatalog):
        com.mvxxcb.reader.common.result.AppResult<com.mvxxcb.reader.domain.model.CatalogMergeReport>
    /** 保存正文（序列化内容块 JSON + contentHash + wordCount + isCached=true） */
    suspend fun saveContent(chapterId: Long, content: com.mvxxcb.reader.domain.model.ParsedChapterContent):
        com.mvxxcb.reader.common.result.AppResult<Unit>
    /** 读正文（反序列化为 ReaderContent；未缓存返回 null） */
    suspend fun loadContent(chapterId: Long): com.mvxxcb.reader.domain.model.ReaderContent?
    suspend fun deleteContent(chapterId: Long): com.mvxxcb.reader.common.result.AppResult<Unit>

    /**
     * 单章补录（loadChapterByUrl「同步补目录条目」所需；契约 v1.0.3 变更，主 Agent 批准）。
     * 按规范化 URL 查找：命中 → 直接返回既有 ChapterMeta（幂等，不写库）；
     * 未命中 → 追加为当前最大 chapterIndex+1 的末章（title/url/normalizedUrl/titleFingerprint/
     * volumeTitle 按 ChapterRef 落库，isMissing=0，content 空），事务内执行。
     * books.totalChapters(+1)/latestChapterTitle/updatedAt 同步更新；catalogUrl 无来源不改动。
     */
    suspend fun appendChapter(bookId: Long, ref: com.mvxxcb.reader.domain.model.ChapterRef):
        com.mvxxcb.reader.common.result.AppResult<com.mvxxcb.reader.domain.model.ChapterMeta>
}
