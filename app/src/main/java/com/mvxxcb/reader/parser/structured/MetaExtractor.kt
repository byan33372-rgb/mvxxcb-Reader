package com.mvxxcb.reader.parser.structured

import com.mvxxcb.reader.domain.model.Confidence
import com.mvxxcb.reader.domain.model.DetailField
import org.jsoup.nodes.Document

/**
 * 层①-3 meta 提取：meta[name=author]、meta[name=description] 与 `<title>` 兜底。
 * meta 声明置信度 MEDIUM；`<title>` 兜底（需剥站点后缀）置信度 LOW。
 */
class MetaExtractor {

    /** 常见站点后缀分隔符："- 站名" "_ 站名" "｜站名" "|站名" "— 站名" */
    private val TITLE_SUFFIX_RE = Regex("\\s*[-_｜|—·]\\s*[^-_|｜—·]{1,20}$")

    fun extract(document: Document): Map<DetailField, FieldCandidate> {
        val result = linkedMapOf<DetailField, FieldCandidate>()

        fun meta(name: String): String? =
            document.selectFirst("meta[name=$name]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

        meta("author")?.let {
            result[DetailField.AUTHOR] = FieldCandidate(DetailField.AUTHOR, it, Confidence.MEDIUM, "meta[name=author]")
        }
        meta("description")?.let {
            result[DetailField.DESCRIPTION] = FieldCandidate(DetailField.DESCRIPTION, it, Confidence.MEDIUM, "meta[name=description]")
        }

        val docTitle = document.title().trim()
        if (docTitle.isNotEmpty()) {
            val stripped = docTitle.replace(TITLE_SUFFIX_RE, "").trim()
            result.getOrPut(DetailField.TITLE) {
                FieldCandidate(DetailField.TITLE, stripped.ifEmpty { docTitle }, Confidence.LOW, "title 标签兜底")
            }
        }
        return result
    }
}
