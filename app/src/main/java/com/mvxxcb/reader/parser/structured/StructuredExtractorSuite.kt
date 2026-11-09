package com.mvxxcb.reader.parser.structured

import com.mvxxcb.reader.parser.core.rank
import com.mvxxcb.reader.parser.util.UrlNormalizer
import org.jsoup.nodes.Document

/**
 * 层① 结构化提取套件（01 §6）：JSON-LD → OG/meta → meta → 内嵌 JSON 依序合并，
 * 同字段先到先得（层内不再互相覆盖），并产出各子提取器的命中摘要（供 LayerTrace）。
 */
class StructuredExtractorSuite(
    private val jsonLd: JsonLdExtractor = JsonLdExtractor(),
    private val og: OgMetaExtractor = OgMetaExtractor(),
    private val meta: MetaExtractor = MetaExtractor(),
    private val embedded: EmbeddedJsonExtractor = EmbeddedJsonExtractor(),
) {

    data class Result(
        val fields: Map<com.mvxxcb.reader.domain.model.DetailField, FieldCandidate>,
        val summary: String,
    )

    fun extractDetailFields(document: Document, pageUrl: String): Result {
        val merged = linkedMapOf<com.mvxxcb.reader.domain.model.DetailField, FieldCandidate>()

        // 层①内部子提取器合并（01 §6）：同字段冲突取置信度更高者，同级先到先得。
        // 注意 Confidence ordinal 与高低相反，必须用 rank() 显式映射（主 Agent 裁定）。
        fun merge(candidates: Map<com.mvxxcb.reader.domain.model.DetailField, FieldCandidate>) {
            candidates.forEach { (field, candidate) ->
                val existing = merged[field]
                if (existing == null || candidate.confidence.rank() > existing.confidence.rank()) {
                    merged[field] = candidate
                }
            }
        }

        var s1 = 0
        var s2 = 0
        var s3 = 0
        var s4 = 0
        try {
            val r = jsonLd.extract(document); s1 = r.size; merge(absolutize(r, document, pageUrl))
        } catch (_: Exception) { }
        try {
            val r = og.extract(document); s2 = r.size; merge(absolutize(r, document, pageUrl))
        } catch (_: Exception) { }
        try {
            val r = meta.extract(document); s3 = r.size; merge(r) // title 标签不需绝对化
        } catch (_: Exception) { }
        try {
            val r = embedded.extract(document); s4 = r.size; merge(absolutize(r, document, pageUrl))
        } catch (_: Exception) { }

        val summary = "JSON-LD:$s1 OG:$s2 meta:$s3 内嵌JSON:$s4（合并 ${merged.size} 字段）"
        return Result(merged, summary)
    }

    /** 封面等 URL 字段在结构化层即完成绝对化 + 规范化 */
    private fun absolutize(
        candidates: Map<com.mvxxcb.reader.domain.model.DetailField, FieldCandidate>,
        document: Document,
        pageUrl: String,
    ): Map<com.mvxxcb.reader.domain.model.DetailField, FieldCandidate> = candidates.mapValues { (field, candidate) ->
        if (field == com.mvxxcb.reader.domain.model.DetailField.COVER) {
            val base = document.baseUri().ifEmpty { pageUrl }
            val abs = UrlNormalizer.resolveAbsolute(base, candidate.value) ?: candidate.value
            candidate.copy(value = abs)
        } else {
            candidate
        }
    }
}
