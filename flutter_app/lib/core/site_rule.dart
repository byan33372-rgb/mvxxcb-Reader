/// 站点规则模型与规则 JSON Schema 校验（契约 02 §5.3 + docs/03 §5 对齐）。
///
/// 对齐 Kotlin 版：
///  - `parser/rule/RuleEngineImpl.kt` 的 `parseRuleJson`（逐字段校验回显 fieldErrors）；
///  - `parser/rule/SelectorExecutor.kt` 的复杂度上限与 css 简写（@attr）；
///  - `presentation/rules/RuleJsonCodec.kt`（表单草稿 ↔ ruleJson 双向编解码）。
///
/// 本文件为纯 Dart（不依赖 package:html），可在单测中直接调用；
/// 选择器执行（CSS/regex/jsonpath 命中）见 `impl/selector_executor.dart`。
library;

import 'dart:convert';

import 'app_result.dart';
import 'reader_error.dart';

// ---------------- 规则模型 ----------------

/// 12 个选择器键（与 Kotlin 版 RuleSelectorKey 一一对应）。
enum RuleSelectorKey {
  bookName('bookNameSelector'),
  author('authorSelector'),
  cover('coverSelector'),
  description('descriptionSelector'),
  catalog('catalogSelector'),
  catalogNextPage('catalogNextPageSelector'),
  chapterTitle('chapterTitleSelector'),
  chapterLink('chapterLinkSelector'),
  content('contentSelector'),
  contentNextPage('nextPageSelector'),
  nextChapter('nextChapterSelector'),
  previousChapter('previousChapterSelector');

  const RuleSelectorKey(this.jsonField);

  /// 规则 JSON / fieldErrors 中的字段名。
  final String jsonField;
}

/// 选择器规格：css（可带 @attr 后缀的对象形态）/ xpath / regex / jsonpath。
sealed class SelectorSpec {
  const SelectorSpec();
}

class CssSpec extends SelectorSpec {
  const CssSpec(this.expr, this.attr);
  final String expr;
  final String attr; // text / href / src / data-src 等（css 简写后缀）
}

class XpathSpec extends SelectorSpec {
  const XpathSpec(this.expr);
  final String expr;
}

class RegexSpec extends SelectorSpec {
  const RegexSpec(this.expr, this.group);
  final String expr;
  final int group;
}

class JsonPathSpec extends SelectorSpec {
  const JsonPathSpec(this.expr);
  final String expr;
}

enum CleaningType { regex, selector, whitespace }

class CleaningRule {
  const CleaningRule(this.type, this.pattern, [this.replacement = '']);

  final CleaningType type;
  final String pattern;
  final String replacement;
}

/// 解析后的站点规则（内存形态）。
class ParsedSiteRule {
  const ParsedSiteRule({
    required this.ruleId,
    required this.domain,
    required this.isManual,
    this.encoding,
    required this.selectors,
    required this.cleaningRules,
    this.contentMaxPages,
  });

  final String ruleId;
  final String domain;
  final bool isManual;
  final String? encoding;
  final Map<RuleSelectorKey, SelectorSpec> selectors;
  final List<CleaningRule> cleaningRules;
  final int? contentMaxPages;
}

// ---------------- 复杂度上限（对齐 SelectorExecutor 常量） ----------------

const String kXpathDisabledReason = 'XPath 执行器一期未启用，请使用 CSS';
const int kMaxRegexLength = 200;
const int kMaxJsonPathDepth = 8;
const int kMaxXpathDepth = 10;
const int kMaxCleaningRules = 32;
const int kMaxSelectorExprLength = 300;

/// css 简写 "@attr" 后缀支持的属性（03 §5 selectorDef）。
const Set<String> kKnownCssAttrs = {
  'text', 'html', 'href', 'src', 'abs:href', 'content', 'value', 'alt',
  'title', 'data-src', 'data-original', 'rel',
};

/// css 简写拆分："div.cover img@src" → ("div.cover img", "src")。
(String, String) splitCssAttr(String expr) {
  final int at = expr.lastIndexOf('@');
  if (at > 0) {
    final String attr = expr.substring(at + 1).trim();
    if (kKnownCssAttrs.contains(attr)) {
      return (expr.substring(0, at).trim(), attr);
    }
  }
  return (expr.trim(), 'text');
}

/// xpath 深度 ≈ 步数（按 '/' 分段，'//' 计一步）。
int xpathDepth(String expr) =>
    expr.split('/').where((s) => s.trim().isNotEmpty).length;

/// jsonpath 深度 = 执行器遍历步数（与 `_parseJsonPath` 一致：
/// `a.b[0].c` → a/b/0/c 共 4 步；`[0].a` → 2 步）。
int jsonPathDepth(String expr) {
  int depth = 0;
  bool hasName = false;
  final String s = expr.trim();
  for (int i = 0; i < s.length; i++) {
    final String c = s[i];
    if (c == '.') {
      if (hasName) depth += 1;
      hasName = false;
    } else if (c == '[') {
      if (hasName) {
        depth += 1;
        hasName = false;
      }
      final int close = s.indexOf(']', i);
      if (close < 0) return 1 << 30;
      depth += 1;
      i = close; // 循环再 +1
    } else {
      hasName = true;
    }
  }
  if (hasName) depth += 1;
  return depth;
}

// ---------------- 规则 Schema 校验 ----------------

/// 校验结果：rule 非空 = 通过；fieldErrors 供编辑页逐字段回显。
class RuleParseResult {
  const RuleParseResult({this.rule, this.fieldErrors = const <String, String>{}});

  final ParsedSiteRule? rule;
  final Map<String, String> fieldErrors;

  bool get isValid => rule != null && fieldErrors.isEmpty;
}

/// 规则 JSON → 解析结果（对齐 Kotlin `parseRuleJson` 的逐字段校验语义）。
///
/// 失败时按 Kotlin 口径同时产出 `StateError(INVALID_ARGUMENT)`（debugDetail =
/// `fieldErrors={k=v; …}`），供引擎层直接作为 AppResult.Failure 使用。
RuleParseResult parseRuleSchema(String ruleJson) {
  final Map<String, String> errors = <String, String>{};

  final dynamic decoded;
  try {
    decoded = jsonDecode(ruleJson);
  } catch (e) {
    return RuleParseResult(fieldErrors: {
      'ruleJson': 'JSON 解析失败: ${_brief(e.toString())}',
    });
  }
  if (decoded is! Map<String, dynamic>) {
    return const RuleParseResult(fieldErrors: {
      'ruleJson': '规则必须是 JSON 对象',
    });
  }

  final String? ruleId = _stringOf(decoded['ruleId']);
  if (ruleId == null || ruleId.isEmpty) {
    errors['ruleId'] = '缺少必填字段';
  } else if (ruleId.startsWith('manual.')) {
    // manual.* 前缀规则必须使用 manual.<域名> 形态（对齐 Kotlin 命名口径）。
    if (!RegExp(r'^manual\.[a-z0-9.-]{3,80}$').hasMatch(ruleId)) {
      errors['ruleId'] =
          'manual 规则命名必须为 manual.<域名>（如 manual.www.example.com）';
    }
  } else if (!RegExp(r'^[a-z0-9][a-z0-9._-]{1,63}$').hasMatch(ruleId)) {
    errors['ruleId'] = '不符合命名规范（slug.v1 或 manual.<domain>）';
  }

  final String? domain = _stringOf(decoded['domain']);
  if (domain == null || domain.isEmpty) {
    errors['domain'] = '缺少必填字段';
  } else if (!RegExp(r'^[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$').hasMatch(domain)) {
    errors['domain'] = '域名格式不正确';
  }

  final int? version = _intOf(decoded['version']);
  if (version == null) {
    errors['version'] = '缺少必填字段';
  } else if (version < 1 || version > 999999) {
    errors['version'] = '必须在 1..999999';
  }

  String? encoding;
  final String? rawEncoding = _stringOf(decoded['encoding']);
  if (rawEncoding != null && rawEncoding.isNotEmpty) {
    if (!const {'UTF-8', 'GBK', 'GB18030', 'BIG5'}.contains(rawEncoding)) {
      errors['encoding'] = '仅允许 UTF-8/GBK/GB18030/BIG5';
    } else if (rawEncoding == 'UTF-8') {
      encoding = null; // UTF-8 为默认，无需显式指定
    } else {
      encoding = rawEncoding;
    }
  }

  final Map<RuleSelectorKey, SelectorSpec> selectors = <RuleSelectorKey, SelectorSpec>{};
  for (final RuleSelectorKey key in RuleSelectorKey.values) {
    final dynamic el = decoded[key.jsonField];
    if (el == null) continue;
    final spec = _interpretSelector(el, errors, key.jsonField);
    if (spec == null) continue;
    final String? reason = validateSelectorSpec(spec);
    if (reason != null) {
      errors[key.jsonField] = reason;
    } else {
      selectors[key] = spec;
    }
  }

  final List<CleaningRule> cleaningRules = <CleaningRule>[];
  final dynamic rawCleaning = decoded['contentCleaningRules'];
  if (rawCleaning is List) {
    if (rawCleaning.length > kMaxCleaningRules) {
      errors['contentCleaningRules'] = '最多 $kMaxCleaningRules 条';
    } else {
      for (int i = 0; i < rawCleaning.length; i++) {
        final dynamic item = rawCleaning[i];
        final String prefix = 'contentCleaningRules[$i]';
        if (item is! Map) {
          errors['$prefix.type'] = '必须是对象';
          continue;
        }
        final String type = (_stringOf(item['type']) ?? '').trim();
        if (type != 'regex' && type != 'selector' && type != 'whitespace') {
          errors['$prefix.type'] = '仅允许 regex/selector/whitespace';
          continue;
        }
        final String pattern = (_stringOf(item['pattern']) ?? '').trim();
        if (pattern.isEmpty || pattern.length > 300) {
          errors['$prefix.pattern'] = '长度必须为 1..300';
          continue;
        }
        if (type == 'regex') {
          try {
            RegExp(pattern);
          } catch (e) {
            errors['$prefix.pattern'] = '正则语法错误: ${_brief(e.toString())}';
            continue;
          }
        }
        cleaningRules.add(CleaningRule(
          CleaningType.values.firstWhere((t) => t.name == type),
          pattern,
          _stringOf(item['replacement']) ?? '',
        ));
      }
    }
  }

  final int? contentMaxPages = _intOf(decoded['contentMaxPages']);
  if (contentMaxPages != null && (contentMaxPages < 1 || contentMaxPages > 20)) {
    errors['contentMaxPages'] = '必须在 1..20';
  }

  if (errors.isNotEmpty) {
    return RuleParseResult(fieldErrors: Map<String, String>.unmodifiable(errors));
  }

  return RuleParseResult(
    rule: ParsedSiteRule(
      ruleId: ruleId!,
      domain: domain!,
      isManual: ruleId.startsWith('manual.'),
      encoding: encoding,
      selectors: Map<RuleSelectorKey, SelectorSpec>.unmodifiable(selectors),
      cleaningRules: List<CleaningRule>.unmodifiable(cleaningRules),
      contentMaxPages: contentMaxPages,
    ),
  );

  // —— 内部 ——
}

/// 校验失败的统一 AppResult（对齐 Kotlin invalidArgument 装配）。
Failure<T> invalidArgumentFailure<T>(Map<String, String> errors) {
  final String detail =
      'fieldErrors={${errors.entries.map((e) => '${e.key}=${e.value}').join('; ')}}';
  return Failure<T>(StateError('INVALID_ARGUMENT', detail));
}

/// 选择器复杂度校验（返回错误原因；null = 通过）。
String? validateSelectorSpec(SelectorSpec spec) {
  if (spec is XpathSpec) {
    if (xpathDepth(spec.expr) > kMaxXpathDepth) {
      return 'xpath 深度超过上限 $kMaxXpathDepth';
    }
    return null;
  }
  if (spec is RegexSpec) {
    if (spec.expr.length > kMaxRegexLength) {
      return 'regex 长度超过上限 $kMaxRegexLength';
    }
    try {
      RegExp(spec.expr);
      return null;
    } catch (e) {
      return 'regex 语法错误: ${_brief(e.toString())}';
    }
  }
  if (spec is JsonPathSpec) {
    if (jsonPathDepth(spec.expr) > kMaxJsonPathDepth) {
      return 'jsonpath 深度超过上限 $kMaxJsonPathDepth';
    }
    return null;
  }
  return null; // css 无复杂度上限
}

/// 选择器解释：字符串 = CSS 简写（可带 @attr）；对象 = {type, expr, attr/group}。
SelectorSpec? _interpretSelector(
  dynamic el,
  Map<String, String> errors,
  String field,
) {
  if (el is String) {
    final (String expr, String attr) = splitCssAttr(el);
    if (expr.isEmpty) {
      errors[field] = '选择器不能为空';
      return null;
    }
    return CssSpec(expr, attr);
  }
  if (el is Map) {
    final String type = (_stringOf(el['type']) ?? '').trim().toLowerCase();
    final String expr = (_stringOf(el['expr']) ?? '').trim();
    if (expr.isEmpty || expr.length > kMaxSelectorExprLength) {
      errors['$field.expr'] = '长度必须为 1..$kMaxSelectorExprLength';
      return null;
    }
    switch (type) {
      case 'css':
        return CssSpec(expr, _stringOf(el['attr']) ?? 'text');
      case 'xpath':
        return XpathSpec(expr);
      case 'regex':
        return RegexSpec(expr, _intOf(el['group']) ?? 1);
      case 'jsonpath':
        return JsonPathSpec(expr);
      case '':
        errors['$field.type'] = '缺少 type';
        return null;
      default:
        errors['$field.type'] = '未知类型 $type';
        return null;
    }
  }
  errors[field] = '选择器必须是字符串或对象';
  return null;
}

String? _stringOf(dynamic value) {
  if (value is String) return value;
  if (value is num) return value.toString();
  if (value is bool) return value.toString();
  return null;
}

int? _intOf(dynamic value) {
  if (value is int) return value;
  if (value is num) return value.toInt();
  if (value is String) return int.tryParse(value.trim());
  return null;
}

String _brief(String message) {
  final t = message.trim();
  return t.length > 120 ? t.substring(0, 120) : t;
}

// ---------------- 编辑器草稿（表单 ↔ ruleJson） ----------------

/// 编辑页表单草稿（对齐 Kotlin SourceRuleDraft：选择器一行一条 css 简写，
/// 清洗规则一行一条 regex；selector/whitespace 清洗无法用行文本表达，保留 JSON 导入原样）。
class RuleDraft {
  const RuleDraft({
    required this.domain,
    this.enabled = true,
    this.encoding,
    this.bookNameSelector = '',
    this.authorSelector = '',
    this.coverSelector = '',
    this.descriptionSelector = '',
    this.catalogSelector = '',
    this.catalogNextPageSelector = '',
    this.chapterTitleSelector = '',
    this.chapterLinkSelector = '',
    this.contentSelector = '',
    this.nextPageSelector = '',
    this.nextChapterSelector = '',
    this.previousChapterSelector = '',
    this.cleaningRulesText = '',
  });

  final String domain;
  final bool enabled;
  final String? encoding;
  final String bookNameSelector;
  final String authorSelector;
  final String coverSelector;
  final String descriptionSelector;
  final String catalogSelector;
  final String catalogNextPageSelector;
  final String chapterTitleSelector;
  final String chapterLinkSelector;
  final String contentSelector;
  final String nextPageSelector;
  final String nextChapterSelector;
  final String previousChapterSelector;
  final String cleaningRulesText;

  RuleDraft copyWith({
    String? domain,
    bool? enabled,
    String? encoding,
    String? bookNameSelector,
    String? authorSelector,
    String? coverSelector,
    String? descriptionSelector,
    String? catalogSelector,
    String? catalogNextPageSelector,
    String? chapterTitleSelector,
    String? chapterLinkSelector,
    String? contentSelector,
    String? nextPageSelector,
    String? nextChapterSelector,
    String? previousChapterSelector,
    String? cleaningRulesText,
    bool clearEncoding = false,
  }) {
    return RuleDraft(
      domain: domain ?? this.domain,
      enabled: enabled ?? this.enabled,
      encoding: clearEncoding ? null : (encoding ?? this.encoding),
      bookNameSelector: bookNameSelector ?? this.bookNameSelector,
      authorSelector: authorSelector ?? this.authorSelector,
      coverSelector: coverSelector ?? this.coverSelector,
      descriptionSelector: descriptionSelector ?? this.descriptionSelector,
      catalogSelector: catalogSelector ?? this.catalogSelector,
      catalogNextPageSelector: catalogNextPageSelector ?? this.catalogNextPageSelector,
      chapterTitleSelector: chapterTitleSelector ?? this.chapterTitleSelector,
      chapterLinkSelector: chapterLinkSelector ?? this.chapterLinkSelector,
      contentSelector: contentSelector ?? this.contentSelector,
      nextPageSelector: nextPageSelector ?? this.nextPageSelector,
      nextChapterSelector: nextChapterSelector ?? this.nextChapterSelector,
      previousChapterSelector: previousChapterSelector ?? this.previousChapterSelector,
      cleaningRulesText: cleaningRulesText ?? this.cleaningRulesText,
    );
  }
}

/// 存储态规则（source_rules 表行）。
class StoredSiteRule {
  const StoredSiteRule({
    required this.ruleId,
    required this.domain,
    required this.version,
    required this.ruleJson,
    required this.enabled,
    required this.createdAt,
    required this.updatedAt,
  });

  final String ruleId;
  final String domain;
  final int version;
  final String ruleJson;
  final bool enabled;
  final int createdAt;
  final int updatedAt;
}

/// 编辑器草稿 → 规则 JSON（对齐 RuleJsonCodec.encode：ruleId = `manual.<domain>`；
/// 域名统一小写，与 DB 层 domain 归一口径一致）。
String encodeRuleDraft(RuleDraft draft) {
  final String domain = draft.domain.trim().toLowerCase();
  final Map<String, Object?> obj = <String, Object?>{
    'ruleId': 'manual.$domain',
    'domain': domain,
    'version': 1,
    'enabled': draft.enabled,
    if (draft.encoding != null && draft.encoding!.trim().isNotEmpty)
      'encoding': draft.encoding!.trim(),
    if (_clean(draft.bookNameSelector).isNotEmpty)
      'bookNameSelector': _clean(draft.bookNameSelector),
    if (_clean(draft.authorSelector).isNotEmpty)
      'authorSelector': _clean(draft.authorSelector),
    if (_clean(draft.coverSelector).isNotEmpty)
      'coverSelector': _clean(draft.coverSelector),
    if (_clean(draft.descriptionSelector).isNotEmpty)
      'descriptionSelector': _clean(draft.descriptionSelector),
    if (_clean(draft.catalogSelector).isNotEmpty)
      'catalogSelector': _clean(draft.catalogSelector),
    if (_clean(draft.catalogNextPageSelector).isNotEmpty)
      'catalogNextPageSelector': _clean(draft.catalogNextPageSelector),
    if (_clean(draft.chapterTitleSelector).isNotEmpty)
      'chapterTitleSelector': _clean(draft.chapterTitleSelector),
    if (_clean(draft.chapterLinkSelector).isNotEmpty)
      'chapterLinkSelector': _clean(draft.chapterLinkSelector),
    if (_clean(draft.contentSelector).isNotEmpty)
      'contentSelector': _clean(draft.contentSelector),
    if (_clean(draft.nextPageSelector).isNotEmpty)
      'nextPageSelector': _clean(draft.nextPageSelector),
    if (_clean(draft.nextChapterSelector).isNotEmpty)
      'nextChapterSelector': _clean(draft.nextChapterSelector),
    if (_clean(draft.previousChapterSelector).isNotEmpty)
      'previousChapterSelector': _clean(draft.previousChapterSelector),
    'contentCleaningRules': _cleaningRulesTextToJson(draft.cleaningRulesText),
  };
  return jsonEncode(obj);
}

/// 存储规则 JSON → 编辑器草稿（解析失败返回 null；仅还原 regex 清洗行，
/// selector/whitespace 类型无法用行文本表达——一期已知限制，与 Kotlin 版口径一致）。
RuleDraft? decodeRuleDraft(String ruleJson, {String? fallbackDomain, bool? fallbackEnabled}) {
  dynamic decoded;
  try {
    decoded = jsonDecode(ruleJson);
  } catch (_) {
    return null;
  }
  if (decoded is! Map<String, dynamic>) return null;
  String selectorOf(String field) => _clean(_stringOf(decoded[field]) ?? '');
  final dynamic rawCleaning = decoded['contentCleaningRules'];
  final List<String> regexLines = <String>[];
  if (rawCleaning is List) {
    for (final dynamic item in rawCleaning) {
      if (item is! Map) continue;
      if (_stringOf(item['type']) != 'regex') continue;
      final pattern = _stringOf(item['pattern']);
      if (pattern != null && pattern.trim().isNotEmpty) regexLines.add(pattern.trim());
    }
  }
  return RuleDraft(
    domain: (_stringOf(decoded['domain']) ?? fallbackDomain ?? '').trim(),
    enabled: decoded['enabled'] is bool
        ? decoded['enabled'] as bool
        : (fallbackEnabled ?? true),
    encoding: _stringOf(decoded['encoding']),
    bookNameSelector: selectorOf('bookNameSelector'),
    authorSelector: selectorOf('authorSelector'),
    coverSelector: selectorOf('coverSelector'),
    descriptionSelector: selectorOf('descriptionSelector'),
    catalogSelector: selectorOf('catalogSelector'),
    catalogNextPageSelector: selectorOf('catalogNextPageSelector'),
    chapterTitleSelector: selectorOf('chapterTitleSelector'),
    chapterLinkSelector: selectorOf('chapterLinkSelector'),
    contentSelector: selectorOf('contentSelector'),
    nextPageSelector: selectorOf('nextPageSelector'),
    nextChapterSelector: selectorOf('nextChapterSelector'),
    previousChapterSelector: selectorOf('previousChapterSelector'),
    cleaningRulesText: regexLines.join('\n'),
  );
}

/// 清洗规则行文本（一行一条 regex）→ JSON 数组。
List<Map<String, String>> _cleaningRulesTextToJson(String text) {
  return text
      .split('\n')
      .map((line) => line.trim())
      .where((line) => line.isNotEmpty)
      .map((line) => <String, String>{'type': 'regex', 'pattern': line})
      .toList(growable: false);
}

String _clean(String v) => v.trim();
