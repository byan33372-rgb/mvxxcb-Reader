package com.mvxxcb.reader.reader.engine

/** 进度换算（纯函数对象） */
interface ReaderPositionMapper {
    fun fractionToPage(fraction: Double, pageCount: Int): Int
    fun pageToFraction(pageIndex: Int, pageCount: Int): Double
    fun overallProgress(chapterIndex: Int, totalChapters: Int, inChapterFraction: Double): Double
}

object ReaderPositionMappers {
    /**
     * v0 骨架反射兜底（保证 WP-M 骨架可编译）：真正的 ReaderPositionMapperImpl 由 WP-U
     * 提供（reader/engine/ReaderPositionMapperImpl.kt，见 04 §4）。
     * WP-U 集成后须确认首次访问 DEFAULT 时实现类已可加载；也可改为直接构造并删除反射。
     */
    val DEFAULT: ReaderPositionMapper by lazy {
        Class.forName("com.mvxxcb.reader.reader.engine.ReaderPositionMapperImpl")
            .newInstance() as ReaderPositionMapper
    }
}
