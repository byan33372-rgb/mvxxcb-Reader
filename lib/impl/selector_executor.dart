/// 选择器统一执行器（对齐 Kotlin `parser/rule/SelectorExecutor.kt`）。
///
///  - 支持 **css / regex / jsonpath**；Xpath 一律 Miss（一期未启用，提示用 CSS）；
///  - JSONPath 为自研点号路径解析器（`a.b[0].c`，深度 ≤8）；
///  - 执行期异常兜底返回 Miss（不向调用方抛异常）。
library;

import 'dart:convert';

import 'package:html/dom.dart' as dom;
import 'package:html/parser.dart' as html_parser;

import '../core/site_rule.dart';
import 'url_utils.dart';

class SelectorResult {
  const SelectorResult._(this.values, this.reason);
  const SelectorResult.values(List<String> list) : this._(list, null);
  const SelectorResult.miss(String missReason) : this._(const <String>[], missReason);

  final List<String> values;
  final String? reason;

  bool get isMiss => reason != null;
}

class SelectorExecutor {
  const SelectorExecutor._();

  /// 执行选择器返回全部值（顺序 = 文档顺序）。
  ///
  /// [pageUrl] 用于 css `abs:` 属性与封面绝对化。
  static SelectorResult execute(
    SelectorSpec spec,
    dom.Document document, {
    required String pageUrl,
  }) {
    final SelectorSpec resolved = spec;
    if (resolved is XpathSpec) {
      return SelectorResult.miss(kXpathDisabledReason);
    }
    if (resolved is CssSpec) {
      final (String expr, String attr) = resolved.attr == 'text'
          ? splitCssAttr(resolved.expr)
          : (resolved.expr, resolved.attr);
      final List<String> values = <String>[];
      try {
        for (final dom.Element element in document.querySelectorAll(expr)) {
          final String? value = _extractAttr(element, attr, pageUrl);
          if (value != null) values.add(value);
        }
      } catch (e) {
        return SelectorResult.miss('CSS 选择器无效: ${_brief(e.toString())}');
      }
      if (values.isEmpty) return const SelectorResult.miss('CSS 无匹配');
      return SelectorResult.values(values);
    }
    if (resolved is RegexSpec) {
      if (resolved.expr.length > kMaxRegexLength) {
        return SelectorResult.miss('regex 长度超过上限 $kMaxRegexLength');
      }
      RegExp regex;
      try {
        regex = RegExp(resolved.expr);
      } catch (e) {
        return SelectorResult.miss('regex 语法错误: ${_brief(e.toString())}');
      }
      final String source = document.outerHtml;
      final RegExpMatch? match = regex.firstMatch(source);
      if (match == null) return const SelectorResult.miss('regex 无匹配');
      final int group = resolved.group;
      if (group < 0 || group > match.groupCount) {
        return const SelectorResult.miss('regex 分组越界');
      }
      final String? captured = match.group(group);
      if (captured == null || captured.trim().isEmpty) {
        return const SelectorResult.miss('regex 分组无内容');
      }
      return SelectorResult.values(<String>[captured.trim()]);
    }
    if (resolved is JsonPathSpec) {
      if (jsonPathDepth(resolved.expr) > kMaxJsonPathDepth) {
        return SelectorResult.miss('jsonpath 深度超过上限 $kMaxJsonPathDepth');
      }
      final List<dynamic> candidates = pageJsonCandidates(document);
      if (candidates.isEmpty) return const SelectorResult.miss('页面无内嵌 JSON');
      final dynamic root = candidates.first;
      if (root == null) return const SelectorResult.miss('页面无内嵌 JSON');
      final dynamic hit = resolveJsonPath(root, resolved.expr);
      if (hit == null) return const SelectorResult.miss('jsonpath 无匹配');
      return SelectorResult.values(<String>[jsonToString(hit)]);
    }
    return const SelectorResult.miss('不支持的选择器类型');
  }

  /// 只取第一个值。
  static SelectorResult executeFirst(
    SelectorSpec spec,
    dom.Document document, {
    required String pageUrl,
  }) {
    final SelectorResult result = execute(spec, document, pageUrl: pageUrl);
    if (result.isMiss) return result;
    return SelectorResult.values(<String>[result.values.first]);
  }

  static String? _extractAttr(dom.Element element, String attr, String pageUrl) {
    String value;
    if (attr == 'text') {
      value = element.text;
    } else if (attr == 'html') {
      value = element.innerHtml;
    } else if (attr.startsWith('abs:')) {
      final String name = attr.substring(4);
      final String raw = element.attributes[name] ?? '';
      value = resolveUrl(pageUrl, raw) ?? raw;
    } else {
      value = (element.attributes[attr] ?? '').trim();
      if (value.isEmpty && (attr == 'href' || attr == 'src')) {
        // 相对链接兜底绝对化（对齐 Jsoup absUrl 行为）
        final String raw = element.attributes[attr] ?? '';
        value = resolveUrl(pageUrl, raw) ?? raw;
      }
    }
    final String trimmed = value.trim();
    return trimmed.isEmpty ? null : trimmed;
  }

  // ---------------- 自研 JSONPath（点号路径，无第三方库） ----------------

  /// 解析 `a.b[0].c` 形态路径；中途缺键/越界返回 null。
  static Object? resolveJsonPath(dynamic root, String expr) {
    final List<String>? segments = _parseJsonPath(expr);
    if (segments == null) return null;
    dynamic current = root;
    for (final String seg in segments) {
      if (current is Map) {
        final dynamic next = current[seg];
        if (next == null) return null;
        current = next;
      } else if (current is List) {
        final int? index = int.tryParse(seg);
        if (index == null || index < 0 || index >= current.length) return null;
        current = current[index];
      } else {
        return null;
      }
      if (current == null) return null;
    }
    return current;
  }

  static List<String>? _parseJsonPath(String expr) {
    String s = expr.trim();
    if (s.startsWith(r'$.')) {
      s = s.substring(2);
    } else if (s.startsWith(r'$')) {
      s = s.substring(1);
    }
    if (s.isEmpty) return const <String>[];
    final List<String> segments = <String>[];
    final StringBuffer name = StringBuffer();
    bool lastWasBracket = false;
    for (int i = 0; i < s.length; i++) {
      final String c = s[i];
      if (c == '.') {
        if (name.isNotEmpty) {
          segments.add(name.toString());
          name.clear();
        } else if (!lastWasBracket) {
          return null; // ".." 或以 '.' 开头等非法形态
        }
        lastWasBracket = false;
      } else if (c == '[') {
        if (name.isNotEmpty) {
          segments.add(name.toString());
          name.clear();
        }
        final int close = s.indexOf(']', i);
        if (close < 0) return null;
        String inner = s.substring(i + 1, close).trim();
        if (inner.length >= 2 &&
            ((inner.startsWith('"') && inner.endsWith('"')) ||
                (inner.startsWith("'") && inner.endsWith("'")))) {
          inner = inner.substring(1, inner.length - 1);
        }
        if (inner.isEmpty) return null;
        segments.add(inner);
        lastWasBracket = true;
        i = close;
      } else {
        name.write(c);
        lastWasBracket = false;
      }
    }
    if (name.isNotEmpty) segments.add(name.toString());
    return segments;
  }

  static String jsonToString(dynamic el) {
    if (el is String) return el.trim();
    if (el is num || el is bool) return el.toString();
    try {
      return jsonEncode(el);
    } catch (_) {
      return '$el';
    }
  }

  // ---------------- 页面内嵌 JSON 候选收集（JSON-LD 优先，其次 application/json） ----------------

  static List<dynamic> pageJsonCandidates(dom.Document document) {
    final List<dynamic> candidates = <dynamic>[];
    for (final dom.Element script in document.querySelectorAll('script')) {
      final String type = (script.attributes['type'] ?? '').toLowerCase().trim();
      if (type != 'application/ld+json' && type != 'application/json') continue;
      final dynamic parsed = parseJsonLenient(script.text);
      if (parsed != null) candidates.add(parsed);
    }
    return candidates;
  }

  static Object? parseJsonLenient(String text) {
    final String trimmed = text.trim();
    if (trimmed.isEmpty) return null;
    try {
      return jsonDecode(trimmed);
    } catch (_) {
      // 宽松兜底：剥掉常见尾随逗号/注释干扰后重试一次
      final String relaxed = trimmed
          .replaceAll(RegExp(r'//.*?\n'), '')
          .replaceAll(RegExp(r'/\*.*?\*/', dotAll: true), '')
          .replaceAll(RegExp(r',(\s*[}\]])'), r'$1');
      try {
        return jsonDecode(relaxed);
      } catch (_) {
        return null;
      }
    }
  }
}

/// 便捷解析入口（文档 + URL → dom.Document）。
dom.Document parseHtmlDocument(String html) => html_parser.parse(html);

String _brief(String message) {
  final String t = message.trim();
  return t.length > 60 ? t.substring(0, 60) : t;
}
