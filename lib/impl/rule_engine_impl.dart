/// 站点规则引擎（对齐 Kotlin 版 `parser/rule/RuleEngineImpl.kt`）。
///
/// 职责：
///  - 按域名加载启用中的规则（解析失败视为无规则，调用方回退通用解析管线）；
///  - extractDetail：4 个详情字段选择器提取（封面原始值，绝对化由解析器统一做）；
///  - extractCatalog：catalogSelector 容器 + chapterLinkSelector 锚点提取，
///    **不做章节形态正则要求**（用户规则即意图，对齐 Kotlin 版裁定）；
///  - extractContent：contentSelector 容器 + selector 清洗 + 章节标题/上下章/
///    下一页导航选择器（regex/whitespace 文本清洗与分页循环由解析器执行）。
///
/// 与 Kotlin 版四层合并的差异（Flutter 简化，注释备案）：
///  Kotlin DetailParseStep 按层 0 人工规则 → 1 结构化 → 2 站点规则 → 3 通用
///  逐字段合并；Flutter 版直接用 [ParserEngineImpl._putField] 的优先级参数
///  表达同一语义——manual.* 规则 priority 4（最高），非人工站点规则 3，
///  JSON-LD 3 / OG·meta 2（先到先得，同级不覆盖），通用层 1。
library;

import 'package:html/dom.dart' as dom;

import '../contracts.dart';
import '../core/app_result.dart';
import '../core/reader_error.dart';
import '../core/site_rule.dart';
import 'data_impl.dart';
import 'selector_executor.dart';
import 'url_utils.dart';

/// 规则引擎。
class RuleEngine {
  RuleEngine(this._store);

  final SourceRuleStore _store;

  /// 按域名加载启用中的规则；无规则或规则 JSON 损坏返回 null。
  Future<ParsedSiteRule?> loadRule(String? domain) async {
    if (domain == null || domain.isEmpty) return null;
    final stored = await _store.findEnabledByDomain(domain);
    if (stored == null) return null;
    return parseRuleSchema(stored.ruleJson).rule;
  }

  // ============================================================
  // 详情字段
  // ============================================================

  /// 详情字段提取：只返回选择器命中值，合并与绝对化交给解析器。
  RuleDetailExtract extractDetail(
    dom.Document document,
    String pageUrl,
    ParsedSiteRule rule,
  ) {
    String? firstField(RuleSelectorKey key) {
      final spec = rule.selectors[key];
      if (spec == null) return null;
      final result = SelectorExecutor.executeFirst(
        spec,
        document,
        pageUrl: pageUrl,
      );
      if (result.isMiss) return null;
      final value = result.values.first.trim();
      return value.isEmpty ? null : value;
    }

    return RuleDetailExtract(
      title: firstField(RuleSelectorKey.bookName),
      author: firstField(RuleSelectorKey.author),
      cover: firstField(RuleSelectorKey.cover),
      description: firstField(RuleSelectorKey.description),
    );
  }

  // ============================================================
  // 目录锚点
  // ============================================================

  /// 目录锚点提取（chapterLinkSelector 必填；catalogSelector 可选容器限定）。
  ///
  /// 与通用层 [isNonChapterPath]/[matchesChapterPattern] 过滤的差异：
  /// 用户显式给出章节链接选择器时**不做章节形态正则要求**，仅保留
  /// 基础卫生过滤（空文本/空 href/javascript/#/跨主机/翻页导航文案/当前页自指）。
  List<RuleAnchor> extractCatalogAnchors(
    dom.Document document,
    String pageUrl,
    ParsedSiteRule rule,
  ) {
    final linkSpec = rule.selectors[RuleSelectorKey.chapterLink];
    if (linkSpec == null || linkSpec is! CssSpec) return const <RuleAnchor>[];

    dom.Element? container;
    final catalogSpec = rule.selectors[RuleSelectorKey.catalog];
    if (catalogSpec is CssSpec) {
      try {
        container = document.querySelector(catalogSpec.expr);
      } catch (_) {
        container = null;
      }
    }
    final root = container ?? document;

    final List<dom.Element> elements;
    try {
      elements = root.querySelectorAll(linkSpec.expr);
    } catch (_) {
      return const <RuleAnchor>[];
    }
    final normalizedPageUrl = normalizeUrl(pageUrl) ?? pageUrl;
    final pageHost = hostOf(pageUrl);

    final anchors = <RuleAnchor>[];
    final seen = <String>{};
    for (final element in elements) {
      final title = element.text.trim();
      if (title.isEmpty || title.length > 120) continue;
      final href = (element.attributes['href'] ?? '').trim();
      if (href.isEmpty ||
          href == '#' ||
          href.toLowerCase().startsWith('javascript')) {
        continue;
      }
      final absolute = resolveUrl(pageUrl, href);
      if (absolute == null) continue;
      if (pageHost != null && hostOf(absolute) != pageHost) continue;
      if (absolute == normalizedPageUrl) continue;
      if (isPageNavText(title)) continue;
      if (!seen.add(absolute)) continue;
      anchors.add(RuleAnchor(
        title: title.length > 120 ? title.substring(0, 120) : title,
        url: absolute,
      ));
    }
    return anchors;
  }

  /// 目录翻页链接（catalogNextPageSelector；缺省 null → 解析器回退通用探测）。
  String? extractCatalogNextPage(
    dom.Document document,
    String pageUrl,
    ParsedSiteRule rule,
  ) {
    return _navHref(rule.selectors[RuleSelectorKey.catalogNextPage], document,
        pageUrl);
  }

  // ============================================================
  // 正文
  // ============================================================

  /// 正文提取：容器 + selector 清洗（原地删除）+ 标题/导航。
  ///
  /// 返回 null = 规则无法提供正文（contentSelector 缺失/非 css/容器无命中）。
  /// 容器只做 selector 删除清洗；regex/whitespace 文本清洗在解析器侧
  /// 块收集后应用（[applyTextCleaning]），与 Kotlin TextCleaner 分层一致。
  RuleContentExtract? extractContent(
    dom.Document document,
    String pageUrl,
    ParsedSiteRule rule,
  ) {
    final contentSpec = rule.selectors[RuleSelectorKey.content];
    if (contentSpec == null || contentSpec is! CssSpec) return null;

    dom.Element? container;
    try {
      container = document.querySelector(contentSpec.expr);
    } catch (_) {
      container = null;
    }
    if (container == null) return null;

    // selector 清洗：容器内删除噪声节点（含容器自身的克隆无需——原地删除即可）。
    for (final cleaning in rule.cleaningRules) {
      if (cleaning.type != CleaningType.selector) continue;
      try {
        for (final element in container.querySelectorAll(cleaning.pattern)) {
          element.remove();
        }
      } catch (_) {
        // 非法选择器：跳过该条清洗（编辑期已校验，运行期兜底）
      }
    }

    String? title;
    final titleSpec = rule.selectors[RuleSelectorKey.chapterTitle];
    if (titleSpec != null) {
      final result = SelectorExecutor.executeFirst(
        titleSpec,
        document,
        pageUrl: pageUrl,
      );
      if (!result.isMiss) title = result.values.first.trim();
    }

    return RuleContentExtract(
      container: container,
      title: (title == null || title.isEmpty) ? null : title,
      nextPageUrl: _navHref(
        rule.selectors[RuleSelectorKey.contentNextPage],
        document,
        pageUrl,
      ),
      nextChapterUrl: _navHref(
        rule.selectors[RuleSelectorKey.nextChapter],
        document,
        pageUrl,
      ),
      previousChapterUrl: _navHref(
        rule.selectors[RuleSelectorKey.previousChapter],
        document,
        pageUrl,
      ),
    );
  }
}

// ============================================================
// 文本清洗（解析器块收集后应用）
// ============================================================

/// 对正文块应用 regex / whitespace 清洗（selector 清洗已在 DOM 层完成）。
///
///  - regex：`pattern` 正则替换，replacement 为空串即删除命中片段；
///    段落清空后整段删除；
///  - whitespace：折叠段内连续空白为单个空格（与通用采集的
///    `_cleanParagraph` 叠加无冲突，保持幂等）；
///  - 图片块原样保留（Kotlin 口径：清洗只作用于文本）。
List<ContentBlock> applyTextCleaning(
  List<ContentBlock> blocks,
  List<CleaningRule> rules,
) {
  final regexRules = rules.where((r) => r.type == CleaningType.regex).toList();
  final hasWhitespace = rules.any((r) => r.type == CleaningType.whitespace);
  if (regexRules.isEmpty && !hasWhitespace) return blocks;
  final result = <ContentBlock>[];
  for (final block in blocks) {
    if (block is! Paragraph) {
      result.add(block);
      continue;
    }
    var text = block.text;
    for (final rule in regexRules) {
      try {
        text = text.replaceAll(RegExp(rule.pattern), rule.replacement);
      } catch (_) {
        // 非法正则：跳过该条（编辑期已校验，运行期兜底）
      }
    }
    if (hasWhitespace) {
      text = text.replaceAll(RegExp(r'\s+'), ' ');
    }
    text = text.trim();
    if (text.isNotEmpty) result.add(Paragraph(text));
  }
  return result;
}

// ============================================================
// 数据类型
// ============================================================

class RuleDetailExtract {
  const RuleDetailExtract({
    this.title,
    this.author,
    this.cover,
    this.description,
  });

  final String? title;
  final String? author;
  final String? cover;
  final String? description;
}

class RuleAnchor {
  const RuleAnchor({required this.title, required this.url});

  final String title;
  final String url;
}

class RuleContentExtract {
  const RuleContentExtract({
    required this.container,
    this.title,
    this.nextPageUrl,
    this.nextChapterUrl,
    this.previousChapterUrl,
  });

  final dom.Element container;
  final String? title;
  final String? nextPageUrl;
  final String? nextChapterUrl;
  final String? previousChapterUrl;
}

// ============================================================
// 内部
// ============================================================

String? _navHref(SelectorSpec? spec, dom.Document document, String pageUrl) {
  if (spec == null || spec is! CssSpec) return null;
  final result = SelectorExecutor.executeFirst(spec, document, pageUrl: pageUrl);
  if (result.isMiss) return null;
  return resolveUrl(pageUrl, result.values.first);
}

// ============================================================
// 站点规则服务（契约 SiteRuleService 实现；规则编辑器消费）
// ============================================================

class SiteRuleServiceImpl implements SiteRuleService {
  SiteRuleServiceImpl(this._store);

  final SourceRuleStore _store;

  @override
  Future<List<StoredSiteRule>> listRules() => _store.listRules();

  @override
  Future<StoredSiteRule?> getRule(String ruleId) => _store.getRule(ruleId);

  @override
  Future<AppResult<StoredSiteRule>> saveRuleFromDraft(
    RuleDraft draft, {
    String? existingRuleId,
    int? existingVersion,
    int? existingCreatedAt,
  }) async {
    final ruleJson = encodeRuleDraft(draft);
    final parsed = parseRuleSchema(ruleJson);
    if (parsed.rule == null || parsed.fieldErrors.isNotEmpty) {
      return invalidArgumentFailure(parsed.fieldErrors.isEmpty
          ? <String, String>{'ruleJson': '规则校验失败'}
          : parsed.fieldErrors);
    }
    final rule = parsed.rule!;
    final ruleId = existingRuleId ?? rule.ruleId;

    // 域名唯一：同域名不允许存在另一条规则（编辑器显式报错，优于 DB 约束错）。
    final all = await _store.listRules();
    final conflict = all
        .where((r) => r.domain == rule.domain && r.ruleId != ruleId)
        .toList(growable: false);
    if (conflict.isNotEmpty) {
      return Failure(StateError(
        'DOMAIN_CONFLICT',
        '域名 ${rule.domain} 已存在规则 ${conflict.first.ruleId}',
      ));
    }

    // 手工规则 JSON 恒为 schema v1；存储列随编辑递增（升级次数语义）。
    final version = existingVersion == null ? 1 : existingVersion + 1;
    return _store.saveRule(
      ruleId: ruleId,
      domain: rule.domain,
      version: version,
      ruleJson: ruleJson,
      enabled: draft.enabled,
    );
  }

  @override
  Future<AppResult<void>> deleteRule(String ruleId) =>
      _store.deleteRule(ruleId);

  @override
  Future<AppResult<void>> setEnabled(String ruleId, bool enabled) =>
      _store.setEnabled(ruleId, enabled);
}
