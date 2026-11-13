// 简阅 · 站点规则编辑（WP-U，对齐 Kotlin ui/rules/RuleEditScreen）
// 12 个选择器字段（一行一条 CSS 简写）+ encoding + 清洗规则（一行一条 regex）。
// 逐字段校验回显（fieldErrors）；保存成功 → 事件返回。
// CSS 简写默认取文本；`@attr` 取属性（如 `img.cover@src`）。
// ignore_for_file: deprecated_member_use

import 'package:flutter/material.dart';

import '../contracts.dart';
import '../core/app_result.dart';
import '../core/reader_error.dart';
import '../core/site_rule.dart';
import 'theme.dart';
import 'widgets.dart';

/// 可选编码（规则 encoding 字段；UTF-8 为默认无需显式指定）。
const Map<String, String?> kRuleEncodingOptions = <String, String?>{
  'UTF-8（默认）': null,
  'GBK': 'GBK',
  'GB18030': 'GB18030',
  'BIG5': 'BIG5',
};

class RuleEditPage extends StatefulWidget {
  const RuleEditPage({
    super.key,
    required this.services,
    required this.initialDomain,
    this.existing,
  });

  final AppServices services;

  /// 非空且存在规则 = 编辑，否则按该域名新建（对齐 Kotlin 路由 ?domain=）。
  final String initialDomain;
  final StoredSiteRule? existing;

  @override
  State<RuleEditPage> createState() => _RuleEditPageState();
}

class _RuleEditPageState extends State<RuleEditPage> {
  late RuleDraft _draft;
  final Map<String, String> _fieldErrors = <String, String>{};
  final Map<String, TextEditingController> _controllers =
      <String, TextEditingController>{};
  bool _saving = false;

  /// 12 个选择器字段定义（key = RuleDraft 字段名，label = 中文名）。
  static const List<(String, String, String)> _selectorFields =
      <(String, String, String)>[
    ('bookNameSelector', '书名选择器', '如 .book-info h1 或 #title'),
    ('authorSelector', '作者选择器', '如 .author 或 span:contains(作者)'),
    ('coverSelector', '封面选择器', '如 img.cover@src（取属性）'),
    ('descriptionSelector', '简介选择器', '如 .intro'),
    ('catalogSelector', '目录容器选择器', '可选，限定目录范围；如 #chapterlist'),
    ('catalogNextPageSelector', '目录翻页选择器', '可选；如 a.next'),
    ('chapterTitleSelector', '章节标题选择器', '可选；如 h1'),
    ('chapterLinkSelector', '章节链接选择器', '目录必填；如 dd a'),
    ('contentSelector', '正文容器选择器', '正文必填；如 #content'),
    ('nextPageSelector', '正文翻页选择器', '可选；如 a#next'),
    ('nextChapterSelector', '下一章选择器', '可选；如 a:contains(下一章)'),
    ('previousChapterSelector', '上一章选择器', '可选；如 a:contains(上一章)'),
  ];

  @override
  void initState() {
    super.initState();
    final StoredSiteRule? existing = widget.existing;
    _draft = existing != null
        ? (decodeRuleDraft(existing.ruleJson,
                fallbackDomain: existing.domain, fallbackEnabled: existing.enabled) ??
            RuleDraft(domain: existing.domain, enabled: existing.enabled))
        : RuleDraft(domain: widget.initialDomain, enabled: true);
    _controllers['domain'] =
        TextEditingController(text: _draft.domain);
  }

  @override
  void dispose() {
    for (final controller in _controllers.values) {
      controller.dispose();
    }
    super.dispose();
  }

  TextEditingController _controllerOf(String key) {
    return _controllers.putIfAbsent(key, () {
      final String? existingValue;
      switch (key) {
        case 'bookNameSelector':
          existingValue = _draft.bookNameSelector;
        case 'authorSelector':
          existingValue = _draft.authorSelector;
        case 'coverSelector':
          existingValue = _draft.coverSelector;
        case 'descriptionSelector':
          existingValue = _draft.descriptionSelector;
        case 'catalogSelector':
          existingValue = _draft.catalogSelector;
        case 'catalogNextPageSelector':
          existingValue = _draft.catalogNextPageSelector;
        case 'chapterTitleSelector':
          existingValue = _draft.chapterTitleSelector;
        case 'chapterLinkSelector':
          existingValue = _draft.chapterLinkSelector;
        case 'contentSelector':
          existingValue = _draft.contentSelector;
        case 'nextPageSelector':
          existingValue = _draft.nextPageSelector;
        case 'nextChapterSelector':
          existingValue = _draft.nextChapterSelector;
        case 'previousChapterSelector':
          existingValue = _draft.previousChapterSelector;
        case 'cleaningRulesText':
          existingValue = _draft.cleaningRulesText;
        default:
          existingValue = '';
      }
      return TextEditingController(text: existingValue);
    });
  }

  void _updateDraft(String key, String value) {
    switch (key) {
      case 'bookNameSelector':
        _draft = _draft.copyWith(bookNameSelector: value);
      case 'authorSelector':
        _draft = _draft.copyWith(authorSelector: value);
      case 'coverSelector':
        _draft = _draft.copyWith(coverSelector: value);
      case 'descriptionSelector':
        _draft = _draft.copyWith(descriptionSelector: value);
      case 'catalogSelector':
        _draft = _draft.copyWith(catalogSelector: value);
      case 'catalogNextPageSelector':
        _draft = _draft.copyWith(catalogNextPageSelector: value);
      case 'chapterTitleSelector':
        _draft = _draft.copyWith(chapterTitleSelector: value);
      case 'chapterLinkSelector':
        _draft = _draft.copyWith(chapterLinkSelector: value);
      case 'contentSelector':
        _draft = _draft.copyWith(contentSelector: value);
      case 'nextPageSelector':
        _draft = _draft.copyWith(nextPageSelector: value);
      case 'nextChapterSelector':
        _draft = _draft.copyWith(nextChapterSelector: value);
      case 'previousChapterSelector':
        _draft = _draft.copyWith(previousChapterSelector: value);
      case 'cleaningRulesText':
        _draft = _draft.copyWith(cleaningRulesText: value);
      case 'domain':
        _draft = _draft.copyWith(domain: value);
    }
  }

  /// 清洗规则行的错误回显：整列错误或第 i 条错误。
  String? get _cleaningErrorText {
    final direct = _fieldErrors['contentCleaningRules'];
    if (direct != null) return direct;
    for (final entry in _fieldErrors.entries) {
      if (entry.key.startsWith('contentCleaningRules[')) return entry.value;
    }
    return null;
  }

  Future<void> _save() async {
    if (_saving) return;
    setState(() => _saving = true);
    try {
      // 本地先校验：fieldErrors 逐字段回显（对齐 Kotlin 编辑页语义）。
      final ruleJson = encodeRuleDraft(_draft);
      final parsed = parseRuleSchema(ruleJson);
      if (parsed.rule == null || parsed.fieldErrors.isNotEmpty) {
        if (!mounted) return;
        setState(() {
          _fieldErrors
            ..clear()
            ..addAll(parsed.fieldErrors);
          _saving = false;
        });
        return;
      }

      final result = await widget.services.rules.saveRuleFromDraft(
        _draft,
        existingRuleId: widget.existing?.ruleId,
        existingVersion: widget.existing?.version,
        existingCreatedAt: widget.existing?.createdAt,
      );
      if (!mounted) return;
      final error = result.errorOrNull;
      if (error != null) {
        setState(() {
          _fieldErrors
            ..clear()
            ..addAll(_errorsFromReaderError(error));
          _saving = false;
        });
        return;
      }
      showAppSnack(context, '规则已保存');
      Navigator.of(context).pop(true);
    } catch (e) {
      if (!mounted) return;
      setState(() => _saving = false);
      showAppSnack(context, '保存失败: $e');
    }
  }

  Map<String, String> _errorsFromReaderError(ReaderError error) {
    if (error is StateError) {
      final detail = error.debugDetail ?? '';
      // SiteRuleServiceImpl: DOMAIN_CONFLICT / fieldErrors={k=v; …}
      if (detail.startsWith('fieldErrors=')) {
        final body = detail.substring('fieldErrors='.length)
            .replaceAll(RegExp(r'^\{|\}$'), '');
        final result = <String, String>{};
        for (final pair in body.split('; ')) {
          final index = pair.indexOf('=');
          if (index <= 0) continue;
          result[pair.substring(0, index)] = pair.substring(index + 1);
        }
        return result;
      }
      return <String, String>{'domain': detail};
    }
    return <String, String>{'_': userMessageOf(error)};
  }

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final bool isEditing = widget.existing != null;

    return Scaffold(
      appBar: AppBar(
        title: Text(isEditing ? '编辑站点规则' : '新建站点规则'),
        actions: <Widget>[
          TextButton(
            onPressed: _saving ? null : _save,
            child: _saving
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Text('保存'),
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(
          AppSpacing.screenH,
          AppSpacing.screenV,
          AppSpacing.screenH,
          AppSpacing.xl,
        ),
        children: <Widget>[
          SectionCard(
            title: '站点',
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                TextFormField(
                  controller: _controllers['domain'],
                  enabled: !isEditing,
                  keyboardType: TextInputType.url,
                  decoration: InputDecoration(
                    labelText: '站点域名',
                    hintText: 'www.example.com',
                    errorText: _fieldErrors['domain'] ?? _fieldErrors['ruleId'],
                    helperText:
                        isEditing ? '编辑模式域名不可修改' : '该域名页面的解析将优先使用本规则',
                  ),
                  onChanged: (value) => _updateDraft('domain', value),
                ),
                const SizedBox(height: AppSpacing.md),
                DropdownButtonFormField<String?>(
                  initialValue: _draft.encoding,
                  decoration: const InputDecoration(labelText: '站点编码'),
                  items: kRuleEncodingOptions.entries
                      .map(
                        (entry) => DropdownMenuItem<String?>(
                          value: entry.value,
                          child: Text(entry.key),
                        ),
                      )
                      .toList(growable: false),
                  onChanged: (String? value) =>
                      setState(() => _draft = _draft.copyWith(
                            encoding: value,
                            clearEncoding: value == null,
                          )),
                ),
                const SizedBox(height: AppSpacing.sm),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('启用规则'),
                  subtitle: const Text('关闭后该站点回退通用解析'),
                  value: _draft.enabled,
                  onChanged: (bool value) =>
                      setState(() => _draft = _draft.copyWith(enabled: value)),
                ),
              ],
            ),
          ),
          const SizedBox(height: AppSpacing.cardGap),
          SectionCard(
            title: '选择器（一行一条 CSS 简写）',
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text(
                  'CSS 简写默认取文本；`@attr` 取属性，如 img.cover@src。'
                  '留空的字段继续使用通用解析。',
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
                const SizedBox(height: AppSpacing.md),
                for (final (String, String, String) field in _selectorFields)
                  Padding(
                    padding: const EdgeInsets.only(bottom: AppSpacing.md),
                    child: TextFormField(
                      controller: _controllerOf(field.$1),
                      decoration: InputDecoration(
                        labelText: field.$2,
                        hintText: field.$3,
                        errorText: _fieldErrors[field.$1] ??
                            _fieldErrors['${field.$1}.expr'] ??
                            _fieldErrors['${field.$1}.type'],
                      ),
                      onChanged: (value) => _updateDraft(field.$1, value),
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(height: AppSpacing.cardGap),
          SectionCard(
            title: '正文清洗规则（一行一条正则）',
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text(
                  '命中内容会被删除（替换为空）。常用于去广告行，如 '
                  '请记住本书首发域名.*',
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
                const SizedBox(height: AppSpacing.md),
                TextFormField(
                  controller: _controllerOf('cleaningRulesText'),
                  minLines: 3,
                  maxLines: 8,
                  decoration: InputDecoration(
                    labelText: '正则（每行一条）',
                    hintText: '请记住本书首发域名.*\n请收藏本站.*',
                    errorText: _cleaningErrorText,
                  ),
                  onChanged: (value) =>
                      _updateDraft('cleaningRulesText', value),
                ),
              ],
            ),
          ),
          const SizedBox(height: AppSpacing.lg),
          FilledButton.icon(
            onPressed: _saving ? null : _save,
            icon: const Icon(Icons.save_outlined),
            label: const Text('保存规则'),
          ),
        ],
      ),
    );
  }
}
