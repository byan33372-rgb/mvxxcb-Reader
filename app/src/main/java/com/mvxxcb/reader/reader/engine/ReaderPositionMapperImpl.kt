package com.mvxxcb.reader.reader.engine

// 实现归属 WP-U（04 §4）。契约依据：02 §9（ReaderPositionMapper，纯函数对象）。
//
// 换算口径（页码↔分数往返一致，04 §4 DoD 4）：
//   fractionToPage(f, N) = clamp(floor(f * N), 0, N-1)          // f 落在第 p 页的前半段以内
//   pageToFraction(p, N) = (p + 0.5) / N                        // 页中点分数
//   => fractionToPage(pageToFraction(p, N), N) == p（p ∈ [0, N-1]）
//   => pageToFraction(fractionToPage(f, N), N) 为 f 所在页的中点分数。
// overallProgress = (chapterIndex + inChapterFraction) / totalChapters * 100，clamp 0..100。

class ReaderPositionMapperImpl : ReaderPositionMapper {

    override fun fractionToPage(fraction: Double, pageCount: Int): Int {
        if (pageCount <= 0) return 0
        val f = fraction.coerceIn(0.0, 1.0)
        val page = (f * pageCount).toInt()
        return page.coerceIn(0, pageCount - 1)
    }

    override fun pageToFraction(pageIndex: Int, pageCount: Int): Double {
        if (pageCount <= 0) return 0.0
        val p = pageIndex.coerceIn(0, pageCount - 1)
        return (p + 0.5) / pageCount
    }

    override fun overallProgress(chapterIndex: Int, totalChapters: Int, inChapterFraction: Double): Double {
        if (totalChapters <= 0) return 0.0
        val idx = chapterIndex.coerceIn(0, totalChapters - 1)
        val frac = inChapterFraction.coerceIn(0.0, 1.0)
        val value = ((idx + frac) / totalChapters) * 100.0
        return value.coerceIn(0.0, 100.0)
    }
}
