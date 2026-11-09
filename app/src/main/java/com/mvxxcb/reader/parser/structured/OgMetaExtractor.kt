package com.mvxxcb.reader.parser.structured

import com.mvxxcb.reader.domain.model.Confidence
import com.mvxxcb.reader.domain.model.DetailField
import org.jsoup.nodes.Document

/**
 * 层①-2 Open Graph 提取：og:title / og:image / og:description，
 * 及中文小说站常用的 og:novel:* 系列（og:novel:book_name / og:novel:author / og:novel:image）。
 * 置信度 HIGH。
 */
class OgMetaExtractor {

    fun extract(document: Document): Map<DetailField, FieldCandidate> {
        val result = linkedMapOf<DetailField, FieldCandidate>()

        fun og(property: String): String? =
            document.selectFirst("meta[property=$property]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

        put(result, DetailField.TITLE, og("og:title") ?: og("og:novel:book_name"), "og:title")
        put(result, DetailField.AUTHOR, og("og:novel:author") ?: og("og:book:author"), "og:novel:author")
        put(result, DetailField.COVER, og("og:image") ?: og("og:novel:image"), "og:image")
        put(result, DetailField.DESCRIPTION, og("og:description") ?: og("og:novel:intro"), "og:description")
        return result
    }

    private fun put(map: MutableMap<DetailField, FieldCandidate>, field: DetailField, value: String?, note: String) {
        if (value.isNullOrBlank()) return
        map[field] = FieldCandidate(field, value, Confidence.HIGH, note)
    }
}
