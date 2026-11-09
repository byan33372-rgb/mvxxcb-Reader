package com.mvxxcb.reader.parser.structured

import com.mvxxcb.reader.domain.model.Confidence
import com.mvxxcb.reader.domain.model.DetailField
import com.mvxxcb.reader.parser.rule.SelectorExecutor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.nodes.Document

/**
 * 层①-1 JSON-LD 提取（schema.org）：application/ld+json 中 Book 型对象的
 * name / author / image / description。
 * 结构化数据置信度 HIGH；同字段与后继子提取器先到先得（由套件合并）。
 */
class JsonLdExtractor {

    fun extract(document: Document): Map<DetailField, FieldCandidate> {
        val result = linkedMapOf<DetailField, FieldCandidate>()
        val scripts = document.select("script[type=application/ld+json]")
        for (script in scripts) {
            val root = SelectorExecutor.parseJsonLenient(script.data()) ?: continue
            forEachJsonObject(root) { obj ->
                if (result.keys.containsAll(listOf(DetailField.TITLE, DetailField.AUTHOR, DetailField.COVER, DetailField.DESCRIPTION))) {
                    return@forEachJsonObject
                }
                val types = obj.types()
                val isBook = types.any { it.equals("Book", true) || it.equals("Novel", true) } ||
                    obj.containsKeyBookKeys()
                if (!isBook) return@forEachJsonObject
                putIfAbsent(result, DetailField.TITLE, obj.bookString("name", "bookName", "alternateName"), "JSON-LD name")
                putIfAbsent(result, DetailField.AUTHOR, obj.authorString(), "JSON-LD author")
                putIfAbsent(result, DetailField.COVER, obj.imageString(), "JSON-LD image")
                putIfAbsent(result, DetailField.DESCRIPTION, obj.bookString("description"), "JSON-LD description")
            }
        }
        return result
    }

    private fun putIfAbsent(
        map: MutableMap<DetailField, FieldCandidate>,
        field: DetailField,
        value: String?,
        note: String,
    ) {
        if (value.isNullOrBlank()) return
        if (!map.containsKey(field)) map[field] = FieldCandidate(field, value.trim(), Confidence.HIGH, note)
    }

    private fun JsonObject.containsKeyBookKeys(): Boolean =
        listOf("name", "bookName", "author", "image", "description").any { containsKey(it) }

    private fun JsonObject.bookString(vararg keys: String): String? {
        for (k in keys) {
            val v = this[k] ?: continue
            when (v) {
                is JsonPrimitive -> if (v.isString || v.content.isNotBlank()) return v.content
                is JsonArray -> (v.firstOrNull() as? JsonPrimitive)?.let { return it.content }
                else -> Unit
            }
        }
        return null
    }

    /** author: 字符串 / {"name":…} / 数组，取首个 */
    private fun JsonObject.authorString(): String? {
        val v = this["author"] ?: return null
        return when (v) {
            is JsonPrimitive -> v.content
            is JsonObject -> (v["name"] as? JsonPrimitive)?.content
            is JsonArray -> v.firstNotNullOfOrNull { item ->
                when (item) {
                    is JsonPrimitive -> item.content
                    is JsonObject -> (item["name"] as? JsonPrimitive)?.content
                    else -> null
                }
            }
            else -> null
        }
    }

    /** image: 字符串 / 数组 / {"url":…} */
    private fun JsonObject.imageString(): String? {
        val v = this["image"] ?: return null
        return when (v) {
            is JsonPrimitive -> v.content
            is JsonArray -> v.firstNotNullOfOrNull { (it as? JsonPrimitive)?.content }
            is JsonObject -> (v["url"] as? JsonPrimitive)?.content
            else -> null
        }
    }

    private fun JsonObject.types(): List<String> {
        val t = this["@type"] ?: return emptyList()
        return when (t) {
            is JsonPrimitive -> listOf(t.content)
            is JsonArray -> t.mapNotNull { (it as? JsonPrimitive)?.content }
            else -> emptyList()
        }
    }

    private fun forEachJsonObject(root: JsonElement, block: (JsonObject) -> Unit) {
        when (root) {
            is JsonObject -> {
                block(root)
                root.values.forEach { forEachJsonObject(it, block) }
            }
            is JsonArray -> root.forEach { forEachJsonObject(it, block) }
            else -> Unit
        }
    }
}
