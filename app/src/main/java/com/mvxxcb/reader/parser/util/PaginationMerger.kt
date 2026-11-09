package com.mvxxcb.reader.parser.util

import com.mvxxcb.reader.domain.model.ChapterRef
import com.mvxxcb.reader.domain.model.ContentBlock

/**
 * 分页合并（01 §6 / 04 §2）：目录分页与正文分页**两套独立策略**，
 * 防漏页/重复/倒序/误入下一章：
 *
 *  - 页 URL 前缀一致性（正文：同 host + 同目录前缀；目录：同 host）
 *  - 页标题一致性（正文：剥离页码装饰后必须一致；不一致视为误入下一章）
 *  - 已见 URL 集合（候选命中已访问集合 → 死循环保护 PAGINATION_LOOP_DETECTED）
 *  - 新页重复率 > 60% → 终止合并（正文）/ 停止翻页（目录）
 *  - 目录防倒序 = 检测标记 [ParsedCatalog.isLikelyReversed]（不中断，倒序目录是合法站点形态）
 *
 * 全部上限由 ParseOptions 执行并置 isTruncated / truncatedByLimit（不静默截断）。
 */
class PaginationMerger {

    sealed interface Verdict {
        /** 继续（合并/抓取下一页） */
        data object Proceed : Verdict

        /** 非错误终止：候选判定为另一章/误链/预算耗尽等，停止合并 */
        data class Stop(val reason: String) : Verdict

        /** 候选判定实为下一章：终止合并，并把候选当作下一章链接 */
        data class StopAsNextChapter(val reason: String) : Verdict

        /** 死循环保护触发（候选已在已访问集合）→ 调用方返回 PAGINATION_LOOP_DETECTED */
        data object Loop : Verdict
    }

    // ————————————————————————————————————————————————
    // 策略一：目录分页（沿 catalogNextPage / 智能发现合并章节列表）
    // ————————————————————————————————————————————————

    inner class CatalogStrategy {
        private val visitedPageUrls = mutableSetOf<String>()
        private val seenChapterUrls = LinkedHashSet<String>()
        private val accumulated = mutableListOf<ChapterRef>()
        private val _warnings = mutableListOf<String>()
        val warnings: List<String> get() = _warnings

        /** 因重复被跳过的章节数（诊断用） */
        var duplicateSkipped = 0
            private set

        /** 页间章节编号是否出现倒退（跨页倒序线索，供 isLikelyReversed 判定） */
        var pageOrderBacktracked = false
            private set
        private var lastPageNumbers: List<Int> = emptyList()

        /** 登记刚抓取的页（首参：finalUrl，重定向终点） */
        fun markVisited(url: String) {
            visitedPageUrls += url
        }

        /** 抓取下一页前的守卫（candidate 已在已访问集合 → Loop） */
        fun precheckNext(candidate: String): Verdict =
            if (candidate in visitedPageUrls) Verdict.Loop else Verdict.Proceed

        /**
         * 合并一页章节（按站点 DOM 顺序；URL 去重保留首次出现）。
         * 返回本页重复率（本页 URL 已在集合中的比例）。
         */
        fun mergePage(pageChapters: List<ChapterRef>): Double {
            if (pageChapters.isEmpty()) return 1.0
            val dup = pageChapters.count { it.url in seenChapterUrls }
            val rate = dup.toDouble() / pageChapters.size
            pageChapters.forEach { ref ->
                if (ref.url !in seenChapterUrls) {
                    seenChapterUrls += ref.url
                    accumulated += ref
                } else {
                    duplicateSkipped++
                }
            }
            // 跨页倒退检测（防倒序线索）：可提取编号时，本页最大编号 < 上一页最小编号
            val numbers = pageChapters.mapNotNull { extractChapterNumber(it.title) }
            if (numbers.isNotEmpty() && lastPageNumbers.isNotEmpty()) {
                if (numbers.max() < lastPageNumbers.min()) pageOrderBacktracked = true
            }
            if (numbers.isNotEmpty()) lastPageNumbers = numbers
            return rate
        }

        /** 页内相邻章节编号递减占比（单页倒序检测用） */
        fun descendingRatio(titles: List<String>): Double {
            val numbers = titles.mapNotNull { extractChapterNumber(it) }
            if (numbers.size < 3) return 0.0
            var desc = 0
            for (i in 0 until numbers.size - 1) if (numbers[i] > numbers[i + 1]) desc++
            return desc.toDouble() / (numbers.size - 1)
        }

        /** 目录页候选跨域守卫（误链他站） */
        fun hostGuard(candidate: String, currentPageUrl: String): Verdict =
            if (!sameHost(candidate, currentPageUrl)) Verdict.Stop("下一页跨域，疑似误链") else Verdict.Proceed

        fun finish(): List<ChapterRef> = accumulated.mapIndexed { index, ref -> ref.copy(order = index) }
    }

    // ————————————————————————————————————————————————
    // 策略二：正文分页（沿 nextPageSelector 合并同章多页）
    // ————————————————————————————————————————————————

    inner class ContentStrategy(
        private val firstPageUrl: String,
        private val firstPageTitle: String?,
        private val maxPages: Int,
        private val maxChars: Int,
    ) {
        private val visitedPageUrls = mutableSetOf(firstPageUrl)
        private val paragraphTexts = mutableSetOf<String>()
        val blocks = mutableListOf<ContentBlock>()

        /** 累计纯文字字符数（wordCount） */
        var totalChars = 0
            private set

        val pageCount: Int get() = visitedPageUrls.size

        /** 抓取下一页前的守卫（防死循环/跨域/误入下一章-目录前缀） */
        fun precheckNext(candidate: String): Verdict = when {
            candidate in visitedPageUrls -> Verdict.Loop
            !sameHost(candidate, firstPageUrl) -> Verdict.Stop("下一页跨域，疑似误链")
            !sameDirectoryPrefix(candidate, firstPageUrl) ->
                Verdict.StopAsNextChapter("下一页与本章目录前缀不同，疑似下一章链接")
            else -> Verdict.Proceed
        }

        /** 抓取后的标题一致性守卫（误入下一章守卫之二） */
        fun titleGuard(candidateTitle: String?): Verdict {
            val first = firstPageTitle?.trim().orEmpty()
            val cand = candidateTitle?.trim().orEmpty()
            if (first.isNotEmpty() && cand.isNotEmpty() &&
                normalizePageTitle(first) != normalizePageTitle(cand)
            ) {
                return Verdict.StopAsNextChapter("下一页标题与本章不一致，疑似下一章链接")
            }
            return Verdict.Proceed
        }

        /** 计算新页内容重复率（不合并）：新页段落文本中已在已有集合出现的比例 */
        fun evaluateDuplicateRate(newBlocks: List<ContentBlock>): Double {
            val texts = newBlocks.filterIsInstance<ContentBlock.Paragraph>().map { it.text }
            if (texts.isEmpty()) return 1.0
            val fresh = texts.count { it !in paragraphTexts }
            return 1.0 - fresh.toDouble() / texts.size
        }

        /** 合并一页块（顺序追加，禁止乱序） */
        fun mergePage(url: String, newBlocks: List<ContentBlock>) {
            visitedPageUrls += url
            newBlocks.forEach { block ->
                blocks += block
                if (block is ContentBlock.Paragraph) {
                    paragraphTexts += block.text
                    totalChars += block.text.length
                }
            }
        }

        fun charBudgetExceeded(): Boolean = totalChars >= maxChars
    }

    companion object {
        /** 新页重复率终止阈值（01 §6：> 60% 判重复终止） */
        const val DUPLICATE_STOP_RATE = 0.6

        private val PAGE_NAV_TEXT_RE =
            Regex("^(下一页|上一页|下页|上页|首页|末页|尾页|下一章|上一章|下一节|上一节|next|previous|prev)$", RegexOption.IGNORE_CASE)

        /** 页码/章节导航文本（目录提取时排除，防止「下一页」被当章节链接） */
        fun isPageNavText(text: String): Boolean = PAGE_NAV_TEXT_RE.matches(text.trim())

        /**
         * 页标题归一（正文分页标题一致性）：剥离页码装饰。
         * 例："第2章 起点 (2/3)" "第2章 起点_2" "第2章 起点（第2页）" → "第2章 起点"
         */
        fun normalizePageTitle(title: String): String {
            var t = title.trim()
            val patterns = listOf(
                Regex("\\s*[（(【\\[]\\s*(第\\s*\\d+\\s*页|\\d+\\s*/\\s*\\d+)\\s*[）)】\\]]\\s*$"),
                Regex("[\\s_\\-]{1,3}\\d+\\s*$"),
                Regex("\\s*\\(\\d+\\)\\s*$"),
            )
            for (p in patterns) t = p.replace(t, "")
            return t.replace(Regex("\\s+"), " ").trim()
        }

        /** 从标题提取章节数字（第12章/第 12 节/第N回；支持简体中文数字 ≤ 9999） */
        fun extractChapterNumber(title: String): Int? {
            val m = Regex("第\\s*([0-9０-９]+|[零〇一二两三四五六七八九十百千]+)\\s*[章节回]").find(title) ?: return null
            val raw = m.groupValues[1]
            val ascii = raw.replace(Regex("[０-９]")) { ch ->
                (ch.value[0].code - '０'.code + '0'.code).toChar().toString()
            }
            return if (ascii.all { it.isDigit() }) {
                ascii.toIntOrNull()
            } else {
                chineseNumberToInt(ascii)
            }
        }

        private val CN_DIGITS = mapOf(
            '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
        )

        private fun chineseNumberToInt(text: String): Int? {
            if (text.isEmpty()) return null
            var total = 0
            var current = 0
            for (ch in text) {
                when {
                    CN_DIGITS.containsKey(ch) -> current = current * 10 + CN_DIGITS[ch]!!
                    ch == '十' -> { total += (if (current == 0) 1 else current) * 10; current = 0 }
                    ch == '百' -> { total += (if (current == 0) 1 else current) * 100; current = 0 }
                    ch == '千' -> { total += (if (current == 0) 1 else current) * 1000; current = 0 }
                    else -> return null
                }
            }
            return total + current
        }

        fun sameHost(a: String, b: String): Boolean {
            val ha = UrlNormalizer.hostOf(a) ?: return false
            val hb = UrlNormalizer.hostOf(b) ?: return false
            return ha == hb
        }

        /** 目录前缀：scheme://host[:port] + path 至最后一个 '/'（含） */
        fun directoryPrefix(url: String): String? {
            val u = try {
                java.net.URI(url.trim())
            } catch (_: Exception) {
                return null
            }
            val scheme = u.scheme?.lowercase() ?: return null
            val host = u.host?.lowercase() ?: return null
            val path = u.rawPath ?: "/"
            val dir = path.substringBeforeLast('/', "") + "/"
            val sb = StringBuilder(scheme).append("://")
            if (host.contains(':')) sb.append('[').append(host).append(']') else sb.append(host)
            if (u.port != -1) sb.append(':').append(u.port)
            sb.append(dir)
            return sb.toString()
        }

        fun sameDirectoryPrefix(a: String, b: String): Boolean {
            val pa = directoryPrefix(a) ?: return false
            val pb = directoryPrefix(b) ?: return false
            return pa == pb
        }
    }
}
