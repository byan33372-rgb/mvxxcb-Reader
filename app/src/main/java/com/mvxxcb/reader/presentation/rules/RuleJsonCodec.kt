package com.mvxxcb.reader.presentation.rules

// WP-U（04 §4）：SourceRule.ruleJson ↔ SourceRuleDraft 双向编解码。
// Schema 依据 docs/03 §5（SourceRule JSON Schema v1）。
// presentation 依赖红线允许 kotlinx-serialization（仅禁 parser/data/security/jsoup/okhttp/room）。
//
// 编解码范围：schema 中 12 个选择器字段 + encoding + contentCleaningRules（regex 行）。
// cleaningRulesText 契约定义为「一行一条 regex」：编码时每行 → CleaningRule(type=regex, pattern=行)，
// 解码时仅还原 regex 类型（selector/whitespace 类型无法用纯文本行表达，保留原样重新编码会丢失，
// 属一期已知限制，UI 在编辑说明中提示）。

import com.mvxxcb.reader.domain.model.SourceRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

object RuleJsonCodec {

    private val json = Json { ignoreUnknownKeys = true }

    /** SourceRule.ruleJson → 表单草稿（解析失败返回 null） */
    fun decode(rule: SourceRule): SourceRuleDraft? = runCatching {
        val obj = json.parseToJsonElement(rule.ruleJson).jsonObject
        SourceRuleDraft(
            ruleId = obj.stringOrNull("ruleId"),
            domain = obj.stringOrDefault("domain", rule.domain),
            enabled = obj.booleanOrDefault("enabled", rule.enabled),
            encoding = obj.stringOrNull("encoding"),
            bookNameSelector = obj.stringOrDefault("bookNameSelector", ""),
            authorSelector = obj.stringOrDefault("authorSelector", ""),
            coverSelector = obj.stringOrDefault("coverSelector", ""),
            descriptionSelector = obj.stringOrDefault("descriptionSelector", ""),
            catalogSelector = obj.stringOrDefault("catalogSelector", ""),
            catalogNextPageSelector = obj.stringOrDefault("catalogNextPageSelector", ""),
            chapterTitleSelector = obj.stringOrDefault("chapterTitleSelector", ""),
            chapterLinkSelector = obj.stringOrDefault("chapterLinkSelector", ""),
            contentSelector = obj.stringOrDefault("contentSelector", ""),
            nextPageSelector = obj.stringOrDefault("nextPageSelector", ""),
            nextChapterSelector = obj.stringOrDefault("nextChapterSelector", ""),
            previousChapterSelector = obj.stringOrDefault("previousChapterSelector", ""),
            cleaningRulesText = obj.cleaningRulesToText(),
        )
    }.getOrNull()

    /** 表单草稿 → ruleJson 字符串 */
    fun encode(draft: SourceRuleDraft): String {
        val versionObj = buildJsonObject {
            put("ruleId", draft.ruleId ?: "manual.${draft.domain.ifBlank { "unknown" }}")
            put("domain", draft.domain)
            put("version", 1)
            put("enabled", draft.enabled)
            put("encoding", draft.encoding?.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) } ?: JsonNull)
            putIfNotBlank("bookNameSelector", draft.bookNameSelector)
            putIfNotBlank("authorSelector", draft.authorSelector)
            putIfNotBlank("coverSelector", draft.coverSelector)
            putIfNotBlank("descriptionSelector", draft.descriptionSelector)
            putIfNotBlank("catalogSelector", draft.catalogSelector)
            putIfNotBlank("catalogNextPageSelector", draft.catalogNextPageSelector)
            putIfNotBlank("chapterTitleSelector", draft.chapterTitleSelector)
            putIfNotBlank("chapterLinkSelector", draft.chapterLinkSelector)
            putIfNotBlank("contentSelector", draft.contentSelector)
            putIfNotBlank("nextPageSelector", draft.nextPageSelector)
            putIfNotBlank("nextChapterSelector", draft.nextChapterSelector)
            putIfNotBlank("previousChapterSelector", draft.previousChapterSelector)
            put("contentCleaningRules", draft.cleaningRulesTextToJsonArray())
        }
        return json.encodeToString(JsonObject.serializer(), versionObj)
    }

    // ———— 内部 ————

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

    private fun JsonObject.stringOrDefault(key: String, default: String): String =
        stringOrNull(key) ?: default

    private fun JsonObject.booleanOrDefault(key: String, default: Boolean): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

    private fun JsonObject.cleaningRulesToText(): String {
        val arr = (this["contentCleaningRules"] as? JsonArray) ?: return ""
        return arr.mapNotNull { element ->
            val ruleObj = element as? JsonObject ?: return@mapNotNull null
            val type = ruleObj.stringOrNull("type") ?: return@mapNotNull null
            val pattern = ruleObj.stringOrNull("pattern") ?: return@mapNotNull null
            if (type == "regex") pattern else null
        }.joinToString("\n")
    }

    private fun SourceRuleDraft.cleaningRulesTextToJsonArray(): JsonArray {
        val lines = cleaningRulesText.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return JsonArray(lines.map { line ->
            buildJsonObject {
                put("type", "regex")
                put("pattern", line)
            }
        })
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putIfNotBlank(key: String, value: String) {
        if (value.isNotBlank()) put(key, value)
    }
}
