package com.mvxxcb.reader.domain.cache

fun interface ChapterFetcher {
    /** 为缓存管理抓取并解析单章；实现 = ParserPipeline.parseChapterContent（装配层桥接） */
    suspend fun fetch(chapterUrl: String):
        com.mvxxcb.reader.parser.core.ParseOutcome<com.mvxxcb.reader.domain.model.ParsedChapterContent>
}
