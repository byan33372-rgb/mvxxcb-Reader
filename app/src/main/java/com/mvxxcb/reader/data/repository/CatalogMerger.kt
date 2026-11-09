package com.mvxxcb.reader.data.repository

import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.data.db.AppDatabase
import com.mvxxcb.reader.data.db.dao.BookDao
import com.mvxxcb.reader.data.db.dao.ChapterDao
import com.mvxxcb.reader.data.db.entities.ChapterEntity
import com.mvxxcb.reader.data.util.SourceUrlNormalizer
import com.mvxxcb.reader.data.util.TitleFingerprint
import com.mvxxcb.reader.domain.model.CatalogMergeReport
import com.mvxxcb.reader.domain.model.ChapterRef
import com.mvxxcb.reader.domain.model.ParsedCatalog
import androidx.room.withTransaction

/**
 * 目录更新合并算法（数据模型 03 §7.3，七步）—— 全项目数据安全核心，禁止盲目覆盖。
 *
 * 由 [ChapterRepositoryImpl.replaceCatalog] 与 [BookRepositoryImpl.upsertFromImport]/
 * confirmTemporaryBook 共用；整体在 Room 事务内执行，任何步骤失败整批回滚。
 *
 * 保证（03 §7.3）：
 *  - 章节以 chapterId 关联进度/书签/缓存，id 稳定即进度无损；
 *  - URL 命中保 id；指纹命中（唯一或邻位消歧）换址保 id；
 *  - 未命中 → isMissing=1 不删除（保护进度/缓存）；
 *  - chapterIndex 按新目录读取顺序 0..n-1 重排（反转目录先反转为读取顺序）；
 *  - matchedRatio < 0.6 → requiresReview=true（仍写入，id 与进度已保护）；
 *  - 同步更新 books.totalChapters / latestChapterTitle / catalogUrl。
 */
class CatalogMerger(
    private val db: AppDatabase,
    private val chapterDao: ChapterDao,
    private val bookDao: BookDao,
    private val logger: AppLogger,
) {

    companion object {
        /** 重排临时偏移：先整体平移旧索引，避免 UNIQUE(bookId, chapterIndex) 途中冲突 */
        private const val RENUMBER_OFFSET = 1_000_000_000

        /** 反转判定阈值：≥95% 相邻匹配对序相反（03 §7.3 第 1 步） */
        private const val REVERSAL_RATIO = 0.95

        /** requiresReview 阈值（03 §7.3 第 6 步） */
        private const val REVIEW_THRESHOLD = 0.6
    }

    /**
     * 执行合并。抛出的异常（含 FK/约束错误）由调用方转 Storage 错误并依赖事务回滚；
     * CancellationException 原样上抛不吞。
     */
    suspend fun merge(bookId: Long, catalog: ParsedCatalog, nowMillis: Long): CatalogMergeReport {
        // 空目录拒绝写入：整表标记缺失等效于清空，违反"禁止盲目覆盖"（03 §7 目标）；
        // 该场景应由 parser 侧报 CATALOG_EMPTY，data 层兜底拒绝。
        if (catalog.chapters.isEmpty()) {
            throw IllegalArgumentException("empty catalog rejected (03 §7.3: 禁止盲目覆盖)")
        }
        return db.withTransaction {
            val old = chapterDao.getAllForBook(bookId).sortedBy { it.chapterIndex }
            val orderedNew = prepareNewChapters(catalog, old)
            val plan = plan(old, orderedNew, catalog.isLikelyReversed)
            execute(bookId, plan, old, catalog, nowMillis)
            plan.report
        }
    }

    // ———— 第 0 步：新目录预处理（按 order 排序 + 防御性规范化 URL + 去重） ————

    private fun prepareNewChapters(catalog: ParsedCatalog, old: List<ChapterEntity>): List<ChapterRef> {
        return catalog.chapters
            .sortedBy { it.order }
            .mapNotNull { ref ->
                val normalized = SourceUrlNormalizer.normalize(ref.url) ?: ref.url
                ref.copy(url = normalized)
            }
            .distinctBy { it.url } // 站点重复章节链接：保留首个（防同 URL 双写）
            .also { newRefs ->
                if (newRefs.size != catalog.chapters.size) {
                    logger.w("CatalogMerger", "新目录含重复 URL，去重 ${catalog.chapters.size}→${newRefs.size}")
                }
                if (old.isEmpty()) {
                    logger.d("CatalogMerger", "首建目录 ${newRefs.size} 章 bookUrl=${catalog.sourceUrl}")
                }
            }
    }

    // ———— 纯规划（无副作用，可单测） ————

    internal data class Plan(
        val matched: List<MatchedChapter>,      // 旧章（保 id），按新目录顺序
        val added: List<ChapterRef>,            // 新章节（待分配 id），按新目录顺序
        val missingOldIds: List<Long>,          // 旧章未出现在新目录 → isMissing
        val report: CatalogMergeReport,
    )

    internal data class MatchedChapter(
        val oldId: Long,
        val chapterIndex: Int,                  // 新序号
        val title: String,
        val url: String,
        val normalizedUrl: String,
        val fingerprint: String,
        val volumeTitle: String?,
        val titleChanged: Boolean,
        val addressChanged: Boolean,
    )

    internal fun plan(
        old: List<ChapterEntity>,
        newRefs: List<ChapterRef>,
        parserSaysReversed: Boolean,
    ): Plan {
        // —— 第 1 步：反转检测 ——
        val oldPositionByUrl = old.withIndex().associate { (i, e) -> e.normalizedUrl to i }
        val oldPositionSeq = newRefs.mapNotNull { oldPositionByUrl[it.url] }
        val detectedReversal = detectReversal(oldPositionSeq, parserSaysReversed)
        val ordered = if (detectedReversal) newRefs.asReversed() else newRefs

        // —— 第 2 步：mapA = url→旧章；mapB = fingerprint→[旧章…] ——
        val mapA = HashMap<String, ChapterEntity>(old.size * 2)
        val mapB = HashMap<String, MutableList<ChapterEntity>>(old.size * 2)
        for (e in old) {
            mapA.putIfAbsent(e.normalizedUrl, e)
            mapB.getOrPut(e.titleFingerprint) { ArrayList() }.add(e)
        }
        val consumed = HashSet<Long>(old.size)

        // —— 第 3 步：逐个处理 N[i] ——
        val matched = ArrayList<MatchedChapter>(ordered.size)
        val added = ArrayList<ChapterRef>(ordered.size)
        var unchanged = 0
        var updatedUrl = 0
        var lastMatchedOldIndex: Int? = null   // 邻位消歧锚点

        for ((i, ref) in ordered.withIndex()) {
            val fp = TitleFingerprint.of(ref.title)
            val urlHit = mapA[ref.url]

            when {
                // 3.a URL 命中 → 同章：保 id；标题变更则更新 title/fingerprint；unchanged++
                urlHit != null -> {
                    consumed += urlHit.id
                    val titleChanged = urlHit.title != ref.title
                    matched += MatchedChapter(
                        oldId = urlHit.id,
                        chapterIndex = i,
                        title = ref.title,
                        url = ref.url,
                        normalizedUrl = ref.url,
                        fingerprint = fp,
                        volumeTitle = ref.volumeTitle,
                        titleChanged = titleChanged,
                        addressChanged = false,
                    )
                    unchanged++
                    lastMatchedOldIndex = urlHit.chapterIndex
                }

                // 3.b 未命中 URL 但指纹命中（候选唯一，或邻位/未占用消歧）→ 同章换址
                else -> {
                    val candidates = (mapB[fp] ?: emptyList()).filter { it.id !in consumed }
                    val chosen = when {
                        candidates.isEmpty() -> null
                        candidates.size == 1 -> candidates[0]
                        else -> {
                            // 多候选：优先邻位（旧章邻居与新邻居匹配一致），否则取首个未占用候选
                            val anchor = lastMatchedOldIndex
                            candidates.firstOrNull { anchor != null && it.chapterIndex == anchor + 1 }
                                ?: candidates.firstOrNull { anchor != null && it.chapterIndex == anchor - 1 }
                                ?: candidates[0]
                        }
                    }
                    if (chosen != null) {
                        consumed += chosen.id
                        matched += MatchedChapter(
                            oldId = chosen.id,
                            chapterIndex = i,
                            title = ref.title,
                            url = ref.url,
                            normalizedUrl = ref.url,
                            fingerprint = fp,
                            volumeTitle = ref.volumeTitle,
                            titleChanged = chosen.title != ref.title,
                            addressChanged = true,
                        )
                        updatedUrl++
                        lastMatchedOldIndex = chosen.chapterIndex
                    } else {
                        // 3.c 均未命中 → 新章节（占位进 matched 保持新目录顺序，execute 时走 insert；
                        // added 与 matched 中 oldId=-1 的占位按迭代顺序一一对应）
                        added += ref
                        matched += MatchedChapter(
                            oldId = -1L,
                            chapterIndex = i,
                            title = ref.title,
                            url = ref.url,
                            normalizedUrl = ref.url,
                            fingerprint = fp,
                            volumeTitle = ref.volumeTitle,
                            titleChanged = false,
                            addressChanged = false,
                        )
                    }
                }
            }
        }

        // —— 第 4 步：旧章未出现在 N → isMissing（不删除） ——
        val missingOldIds = old.filter { it.id !in consumed }.map { it.id }

        // —— 第 6 步：匹配率（|O|=0 视为无保护对象，比率 1.0，见类注释） ——
        val denominator = maxOf(old.size, ordered.size)
        val matchedRatio = if (denominator == 0 || old.isEmpty()) 1.0
        else (unchanged + updatedUrl).toDouble() / denominator

        val report = CatalogMergeReport(
            added = added.size,
            unchanged = unchanged,
            updatedUrl = updatedUrl,
            markedMissing = missingOldIds.size,
            matchedRatio = matchedRatio,
            detectedReversal = detectedReversal,
            requiresReview = old.isNotEmpty() && matchedRatio < REVIEW_THRESHOLD,
        )
        return Plan(matched, added, missingOldIds, report)
    }

    /** 旧位置序列相邻对反向占比 ≥95%（或序列完全互为倒序）→ 反转；parser 标记同样采信 */
    private fun detectReversal(oldPositionSeq: List<Int>, parserSaysReversed: Boolean): Boolean {
        if (parserSaysReversed) return true
        if (oldPositionSeq.size < 2) return false
        var decreasing = 0
        for (i in 0 until oldPositionSeq.size - 1) {
            if (oldPositionSeq[i] > oldPositionSeq[i + 1]) decreasing++
        }
        return decreasing.toDouble() / (oldPositionSeq.size - 1) >= REVERSAL_RATIO
    }

    // ———— 执行（事务内；第 5 步重排 + 第 4 步标记缺失 + 第 7 步书籍同步） ————

    private suspend fun execute(
        bookId: Long,
        plan: Plan,
        old: List<ChapterEntity>,
        catalog: ParsedCatalog,
        nowMillis: Long,
    ) {
        val oldById = old.associateBy { it.id }
        val total = plan.matched.size

        // 旧索引整体平移进临时区（重排安全），见 RENUMBER_OFFSET
        if (oldById.isNotEmpty()) {
            chapterDao.shiftChapterIndexes(bookId, RENUMBER_OFFSET)
        }

        // 第 5 步：chapterIndex 按新目录顺序 0..n-1 重排（已匹配章节 id 不变）
        for (item in plan.matched) {
            if (item.oldId > 0) {
                chapterDao.updateCatalogEntry(
                    id = item.oldId,
                    title = item.title,
                    fingerprint = item.fingerprint,
                    url = item.url,
                    normalizedUrl = item.normalizedUrl,
                    volumeTitle = item.volumeTitle,
                )
                chapterDao.setChapterIndex(item.oldId, item.chapterIndex)
            }
        }

        // 新章节插入（自增 id；content 为空，isMissing=0）。
        // 先以临时高位索引落库（避开旧章临时区与新目录区间的 UNIQUE 冲突，兼容重复 order 值），
        // 随后按 plan 顺序回填正式序号。
        if (plan.added.isNotEmpty()) {
            var tempIndex = RENUMBER_OFFSET + old.size
            val newEntities = plan.added.map { ref ->
                ChapterEntity(
                    bookId = bookId,
                    chapterIndex = tempIndex++,
                    title = ref.title,
                    url = ref.url,
                    normalizedUrl = ref.url,
                    titleFingerprint = TitleFingerprint.of(ref.title),
                    volumeTitle = ref.volumeTitle,
                )
            }
            val insertedIds = chapterDao.insertAll(newEntities)
            // 插入行按 plan.added 顺序与 matched 中 oldId=-1 的占位一一对应，回填正式序号
            var insertedCursor = 0
            for (item in plan.matched) {
                if (item.oldId <= 0) {
                    val id = insertedIds[insertedCursor++]
                    chapterDao.setChapterIndex(id, item.chapterIndex)
                }
            }
        }

        // 第 4 步：缺失章节标记 isMissing=1（不删除，保护进度/书签/缓存）。
        // 索引挪至目录尾部区段（n..n+k），与新目录 0..n-1 区间无冲突；
        // 展示顺序交给 UI（缺失章通常灰显/隐藏），真值关联键仍是 chapterId。
        val missingSorted = plan.missingOldIds
            .mapNotNull { oldById[it] }
            .sortedBy { it.chapterIndex }
        for ((j, row) in missingSorted.withIndex()) {
            chapterDao.setMissingWithIndex(row.id, missing = true, chapterIndex = total + j)
        }

        // 第 7 步：同步更新 books.totalChapters / latestChapterTitle / catalogUrl
        val latestTitle = plan.matched.lastOrNull()?.title
        val normalizedCatalogUrl = SourceUrlNormalizer.normalize(catalog.sourceUrl) ?: catalog.sourceUrl
        bookDao.updateCatalogSummary(
            bookId = bookId,
            totalChapters = total,
            latestChapterTitle = latestTitle,
            catalogUrl = normalizedCatalogUrl,
            updatedAt = nowMillis,
        )
    }
}
