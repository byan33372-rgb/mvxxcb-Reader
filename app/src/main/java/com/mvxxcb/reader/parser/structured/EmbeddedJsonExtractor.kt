package com.mvxxcb.reader.parser.structured

import com.mvxxcb.reader.domain.model.Confidence
import com.mvxxcb.reader.domain.model.DetailField
import com.mvxxcb.reader.parser.rule.SelectorExecutor
import org.jsoup.nodes.Document

/**
 * 层①-4 内嵌 JSON 提取：扫描 `<script>` 中的 `var x = {…}` / `window.X = {…}` 形态
 * JSON 对象，命中含书籍键（name/title/bookName + author 等）的对象后映射字段。
 * 置信度 MEDIUM（启发式键名，弱于 JSON-LD/OG）。
 */
class EmbeddedJsonExtractor {

    private val JSON_ASSIGN_RE = Regex("(?:var|let|const|window\\.|\\b)\\s*[A-Za-z_$][\\w$.]{0,40}\\s*=\\s*")

    fun extract(document: Document): Map<DetailField, FieldCandidate> {
        val result = linkedMapOf<DetailField, FieldCandidate>()
        for (script in document.select("script")) {
            val type = script.attr("type").lowercase()
            if (type == "application/ld+json") continue // 已由 JSON-LD 提取器处理
            val data = script.data()
            if (data.length !in 10..200_000) continue
            for (obj in scanJsonObjects(data, maxCandidates = 12)) {
                if (!looksLikeBookObject(obj)) continue
                put(result, DetailField.TITLE, obj.anyString("name", "bookName", "book_name", "bookname", "title"), "内嵌JSON name")
                put(result, DetailField.AUTHOR, obj.anyString("author", "book_author", "writer"), "内嵌JSON author")
                put(result, DetailField.COVER, obj.anyString("cover", "coverUrl", "cover_url", "img", "pic", "image"), "内嵌JSON cover")
                put(result, DetailField.DESCRIPTION, obj.anyString("description", "desc", "intro", "introduction"), "内嵌JSON desc")
                if (result.keys.containsAll(DetailField.entries)) return result
            }
        }
        return result
    }

    private fun put(map: MutableMap<DetailField, FieldCandidate>, field: DetailField, value: String?, note: String) {
        if (value.isNullOrBlank()) return
        if (!map.containsKey(field)) map[field] = FieldCandidate(field, value.trim().take(2000), Confidence.MEDIUM, note)
    }

    private fun looksLikeBookObject(obj: kotlinx.serialization.json.JsonObject): Boolean {
        val hasTitle = listOf("name", "bookName", "book_name", "bookname", "title").any { obj.containsKey(it) }
        val hasAuthor = listOf("author", "book_author", "writer").any { obj.containsKey(it) }
        return hasTitle && hasAuthor
    }

    private fun kotlinx.serialization.json.JsonObject.anyString(vararg keys: String): String? {
        for (k in keys) {
            val v = this[k] ?: continue
            when (v) {
                is kotlinx.serialization.json.JsonPrimitive -> if (v.content.isNotBlank()) return v.content
                is kotlinx.serialization.json.JsonObject -> (v["name"] as? kotlinx.serialization.json.JsonPrimitive)?.let { if (it.content.isNotBlank()) return it.content }
                else -> Unit
            }
        }
        return null
    }

    /** 从脚本源中扫描平衡的 JSON 对象（尊重字符串转义），逐候选尝试解析 */
    private fun scanJsonObjects(source: String, maxCandidates: Int): List<kotlinx.serialization.json.JsonObject> {
        val found = mutableListOf<kotlinx.serialization.json.JsonObject>()
        var index = 0
        while (index < source.length && found.size < maxCandidates) {
            val brace = source.indexOf('{', index)
            if (brace < 0) break
            if (brace > 0) {
                // 只接受赋值/冒号/逗号/括号引导的对象，跳过 `{` 出现在普通字符串内的情形
                val lead = source.getOrNull(brace - 1)
                if (lead != null && !lead.isWhitespace() && lead !in "=:,([") {
                    index = brace + 1
                    continue
                }
            }
            val end = balancedEnd(source, brace)
            if (end == null) {
                index = brace + 1
                continue
            }
            val candidate = source.substring(brace, end + 1)
            val parsed = SelectorExecutor.parseJsonLenient(candidate)
            if (parsed is kotlinx.serialization.json.JsonObject) found += parsed
            index = end + 1
        }
        return found
    }

    /** 返回与 [start] 的 '{' 平衡的 '}' 下标；字符串内不匹配则返回 null */
    private fun balancedEnd(source: String, start: Int): Int? {
        var depth = 0
        var i = start
        var inString = false
        while (i < source.length) {
            val c = source[i]
            if (inString) {
                when (c) {
                    '\\' -> i++
                    '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i
                        if (depth < 0) return null
                    }
                }
            }
            i++
        }
        return null
    }
}
