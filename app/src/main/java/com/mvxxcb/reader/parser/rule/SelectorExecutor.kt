package com.mvxxcb.reader.parser.rule

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.nodes.Document
import org.jsoup.select.Selector

/**
 * 选择器统一执行器（契约 02 §5.3 + 主 Agent 裁定 1/2）：
 *
 * 1. 一期支持 **css / regex / jsonpath**；[SelectorSpec.Xpath] 命中时一律返回
 *    Miss("XPath 执行器一期未启用，请使用 CSS")。
 * 2. JSONPath 为自研点号路径解析器（`a.b[0].c`，深度≤8），不引第三方库。
 * 3. 复杂度上限：xpath 深度≤10、regex 长度≤200、jsonpath 深度≤8；
 *    超限在规则解析（RuleEngineImpl.parseRuleJson）时报 ReaderError.State(INVALID_ARGUMENT)，
 *    执行期兜底返回 Miss（不抛异常跨层）。
 */
object SelectorExecutor {

    const val XPATH_DISABLED_REASON = "XPath 执行器一期未启用，请使用 CSS"
    const val MAX_REGEX_LENGTH = 200
    const val MAX_JSONPATH_DEPTH = 8
    const val MAX_XPATH_DEPTH = 10

    /** css 简写 "@attr" 后缀支持的属性（03 §5 selectorDef） */
    private val KNOWN_ATTRS = setOf(
        "text", "html", "href", "src", "abs:href", "content", "value", "alt", "title",
        "data-src", "data-original", "rel",
    )

    sealed interface Result {
        data class Values(val values: List<String>) : Result
        data class Miss(val reason: String) : Result
    }

    // ———— 复杂度校验（返回错误原因；null = 通过） ————

    fun validate(spec: SelectorSpec): String? = when (spec) {
        is SelectorSpec.Xpath ->
            if (xpathDepth(spec.expr) > MAX_XPATH_DEPTH) "xpath 深度超过上限 $MAX_XPATH_DEPTH" else null
        is SelectorSpec.Regex -> when {
            spec.expr.length > MAX_REGEX_LENGTH -> "regex 长度超过上限 $MAX_REGEX_LENGTH"
            else -> try {
                Regex(spec.expr)
                null
            } catch (e: Exception) {
                "regex 语法错误: ${e.message?.take(80)}"
            }
        }
        is SelectorSpec.JsonPath ->
            if (jsonPathDepth(spec.expr) > MAX_JSONPATH_DEPTH) "jsonpath 深度超过上限 $MAX_JSONPATH_DEPTH" else null
        is SelectorSpec.Css -> null
    }

    /** xpath 深度 ≈ 步数（按 '/' 分段，'//' 计一步） */
    fun xpathDepth(expr: String): Int =
        expr.split('/').count { it.isNotBlank() }

    /** jsonpath 深度 = 点号路径段数（a.b[0].c → 3；前导 [0] 单独计 1） */
    fun jsonPathDepth(expr: String): Int {
        var depth = 0
        var hasName = false
        var i = 0
        val s = expr.trim()
        while (i < s.length) {
            when (s[i]) {
                '.' -> { if (hasName) depth += 1; hasName = false; i += 1 }
                '[' -> {
                    if (hasName) { depth += 1; hasName = false }
                    val close = s.indexOf(']', i)
                    if (close < 0) return Int.MAX_VALUE
                    depth += 1
                    i = close + 1
                }
                else -> { hasName = true; i += 1 }
            }
        }
        if (hasName) depth += 1
        return depth
    }

    /** css 简写拆分："div.cover img@src" → ("div.cover img", "src") */
    fun splitCssAttr(expr: String): Pair<String, String> {
        val at = expr.lastIndexOf('@')
        if (at > 0) {
            val attr = expr.substring(at + 1).trim()
            if (attr in KNOWN_ATTRS) return expr.substring(0, at).trim() to attr
        }
        return expr.trim() to "text"
    }

    // ———— 执行 ————

    /** 执行选择器返回全部值（顺序 = 文档顺序） */
    fun execute(spec: SelectorSpec, document: Document, jsonSource: JsonElement? = null): Result {
        return when (spec) {
            is SelectorSpec.Xpath -> Result.Miss(XPATH_DISABLED_REASON)
            is SelectorSpec.Css -> {
                val (expr, attr) = if (spec.attr == "text") splitCssAttr(spec.expr) else spec.expr to spec.attr
                val values = try {
                    document.select(expr).mapNotNull { el -> extractAttr(el, attr) }
                } catch (e: Exception) {
                    return Result.Miss("CSS 选择器无效: ${e.message?.take(60)}")
                }
                if (values.isEmpty()) Result.Miss("CSS 无匹配") else Result.Values(values)
            }
            is SelectorSpec.Regex -> {
                if (spec.expr.length > MAX_REGEX_LENGTH) return Result.Miss("regex 长度超过上限 $MAX_REGEX_LENGTH")
                val regex = try {
                    Regex(spec.expr)
                } catch (e: Exception) {
                    return Result.Miss("regex 语法错误: ${e.message?.take(60)}")
                }
                val source = document.html()
                val match = regex.find(source) ?: return Result.Miss("regex 无匹配")
                val group = if (spec.group in 0..match.groupValues.lastIndex) match.groupValues[spec.group] else null
                if (group.isNullOrBlank()) Result.Miss("regex 分组无内容") else Result.Values(listOf(group.trim()))
            }
            is SelectorSpec.JsonPath -> {
                if (jsonPathDepth(spec.expr) > MAX_JSONPATH_DEPTH) return Result.Miss("jsonpath 深度超过上限 $MAX_JSONPATH_DEPTH")
                val source = jsonSource ?: pageJsonCandidates(document).firstOrNull()
                    ?: return Result.Miss("页面无内嵌 JSON")
                val hit = resolveJsonPath(source, spec.expr)
                if (hit == null || hit is JsonNull) Result.Miss("jsonpath 无匹配") else Result.Values(listOf(jsonToString(hit)))
            }
        }
    }

    /** 只取第一个值 */
    fun executeFirst(spec: SelectorSpec, document: Document, jsonSource: JsonElement? = null): Result {
        return when (val r = execute(spec, document, jsonSource)) {
            is Result.Values -> if (r.values.isEmpty()) Result.Miss("无匹配") else Result.Values(listOf(r.values.first()))
            is Result.Miss -> r
        }
    }

    private fun extractAttr(el: org.jsoup.nodes.Element, attr: String): String? {
        val v = when {
            attr == "text" -> el.text()
            attr == "html" -> el.html()
            attr.startsWith("abs:") -> el.absUrl(attr.removePrefix("abs:"))
            else -> el.attr(attr).ifEmpty { el.absUrl(attr) }
        }.trim()
        return v.ifEmpty { null }
    }

    // ———— 自研 JSONPath（点号路径，无第三方库） ————

    /**
     * 解析 `a.b[0].c` 形态路径；深度在 [jsonPathDepth] 校验。
     * 返回命中元素；中途缺键/越界返回 null。
     */
    fun resolveJsonPath(root: JsonElement, expr: String): JsonElement? {
        val segments = parseJsonPath(expr) ?: return null
        var current: JsonElement = root
        for (seg in segments) {
            current = when (current) {
                is JsonObject -> (current as JsonObject)[seg] ?: return null
                is JsonArray -> {
                    val idx = seg.toIntOrNull() ?: return null
                    val arr = current as JsonArray
                    if (idx < 0 || idx >= arr.size) return null
                    arr[idx]
                }
                else -> return null
            }
            if (current is JsonNull) return null
        }
        return current
    }

    private fun parseJsonPath(expr: String): List<String>? {
        val s = expr.trim().removePrefix("$").removePrefix("$.")
        if (s.isEmpty()) return emptyList()
        val segments = mutableListOf<String>()
        var name = StringBuilder()
        var lastWasBracket = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '.' -> {
                    if (name.isNotEmpty()) {
                        segments += name.toString(); name = StringBuilder()
                    } else if (!lastWasBracket) {
                        return null // ".." 或以 '.' 开头等非法形态
                    }
                    lastWasBracket = false
                    i += 1
                }
                c == '[' -> {
                    if (name.isNotEmpty()) { segments += name.toString(); name = StringBuilder() }
                    val close = s.indexOf(']', i)
                    if (close < 0) return null
                    val inner = s.substring(i + 1, close).trim().removeSurrounding("\"").removeSurrounding("'")
                    if (inner.isEmpty()) return null
                    segments += inner
                    lastWasBracket = true
                    i = close + 1
                }
                else -> { name.append(c); lastWasBracket = false; i += 1 }
            }
        }
        if (name.isNotEmpty()) segments += name.toString()
        return segments
    }

    private fun jsonToString(el: JsonElement): String = when (el) {
        is JsonPrimitive -> el.content.trim()
        else -> el.toString()
    }

    // ———— 页面内嵌 JSON 候选收集（JSON-LD 优先，其次 application/json） ————

    fun pageJsonCandidates(document: Document): List<JsonElement> {
        val candidates = mutableListOf<JsonElement>()
        val scripts = document.select("script[type=application/ld+json], script[type=application/json]")
        for (script in scripts) {
            parseJsonLenient(script.data())?.let { candidates += it }
        }
        return candidates
    }

    fun parseJsonLenient(text: String): JsonElement? {
        if (text.isBlank()) return null
        val trimmed = text.trim()
        return try {
            kotlinx.serialization.json.Json.parseToJsonElement(trimmed)
        } catch (_: Exception) {
            try {
                kotlinx.serialization.json.Json { isLenient = true }.parseToJsonElement(trimmed)
            } catch (_: Exception) {
                null
            }
        }
    }
}
