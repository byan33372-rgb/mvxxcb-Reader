// 简阅 · 发现·导入页
// 网址输入 + 显式粘贴（Clipboard.getData）；分类→详情→目录→保存四阶段指示；
// 结果预览（空字段如实“未识别”）、低置信度警示、失败横幅（userMessageOf + 重试/补充目录网址）；
// 成功后跳转书籍详情。
// ignore_for_file: deprecated_member_use

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../contracts.dart';
import '../core/app_result.dart';
import '../core/reader_error.dart';
import 'detail_page.dart';
import 'theme.dart';
import 'widgets.dart';

enum _ImportStage { idle, classifying, parsingDetail, parsingCatalog, saving }

class ImportPage extends StatefulWidget {
  const ImportPage({super.key, required this.services});

  final AppServices services;

  @override
  State<ImportPage> createState() => _ImportPageState();
}

class _ImportPageState extends State<ImportPage> {
  final TextEditingController _urlController = TextEditingController();
  final TextEditingController _hintController = TextEditingController();
  final FocusNode _urlFocus = FocusNode();

  _ImportStage _stage = _ImportStage.idle;
  UrlClassification? _classification;
  ParsedBookDetail? _detail;
  int? _catalogCount;
  bool _lowConfidence = false;
  bool _requiresHint = false;
  bool _hasParsedOnce = false;
  int? _existingBookId;
  ReaderError? _error;
  String? _catalogWarning;
  int _runToken = 0;

  bool get _busy => _stage != _ImportStage.idle;

  @override
  void dispose() {
    _urlController.dispose();
    _hintController.dispose();
    _urlFocus.dispose();
    super.dispose();
  }

  Future<void> _pasteFromClipboard() async {
    final ClipboardData? data = await Clipboard.getData(Clipboard.kTextPlain);
    if (!mounted) return;
    final String? text = data?.text?.trim();
    if (text == null || text.isEmpty) {
      showAppSnack(context, '剪贴板没有可用文本');
      return;
    }
    _urlController
      ..text = text
      ..selection = TextSelection.collapsed(offset: text.length);
    _urlFocus.requestFocus();
    setState(() {});
  }

  void _clearAll() {
    _runToken++;
    _urlController.clear();
    _hintController.clear();
    setState(() {
      _stage = _ImportStage.idle;
      _classification = null;
      _detail = null;
      _catalogCount = null;
      _lowConfidence = false;
      _requiresHint = false;
      _hasParsedOnce = false;
      _existingBookId = null;
      _error = null;
      _catalogWarning = null;
    });
  }

  void _cancel() {
    _runToken++;
    setState(() {
      _stage = _ImportStage.idle;
      _error = null;
    });
  }

  void _fail(ReaderError error, {bool requiresHint = false}) {
    if (!mounted) return;
    setState(() {
      _stage = _ImportStage.idle;
      _error = error;
      _requiresHint = requiresHint;
    });
  }

  Future<void> _submit() async {
    if (_busy) return;
    final String rawUrl = _urlController.text.trim();
    final String hint = _hintController.text.trim();
    if (rawUrl.isEmpty) {
      showAppSnack(context, '请先输入小说网址');
      return;
    }
    _urlFocus.unfocus();
    final int token = ++_runToken;
    setState(() {
      _hasParsedOnce = true;
      _stage = _ImportStage.classifying;
      _classification = null;
      _detail = null;
      _catalogCount = null;
      _lowConfidence = false;
      _requiresHint = false;
      _existingBookId = null;
      _error = null;
      _catalogWarning = null;
    });

    UrlClassification? classification;
    try {
      final AppResult<UrlClassification> result = await widget.services.parser
          .classify(rawUrl);
      if (token != _runToken) return;
      if (result is Success<UrlClassification>) {
        classification = result.value;
      }
    } catch (_) {
      // 分类失败不阻断整本识别（由 BookResolver 兜底判断页面类型）。
    }
    if (!mounted || token != _runToken) return;
    setState(() {
      _classification = classification;
      _stage = _ImportStage.parsingDetail;
    });

    BookResolution resolution;
    try {
      final AppResult<BookResolution> result = await widget.services.resolver
          .resolve(rawUrl, catalogHint: hint.isEmpty ? null : hint);
      if (!mounted || token != _runToken) return;
      if (result is Failure<BookResolution>) {
        _fail(result.error, requiresHint: true);
        return;
      }
      resolution = (result as Success<BookResolution>).value;
    } catch (error) {
      if (!mounted || token != _runToken) return;
      _fail(UnknownError('识别失败：$error'));
      return;
    }

    ParsedBookDetail? detail = resolution.detail;
    ParsedCatalog? catalog = resolution.catalog;
    String? catalogWarning;

    if (catalog == null) {
      final String? catalogUrl = detail?.catalogUrl ?? resolution.catalogUrl;
      if (catalogUrl != null && catalogUrl.trim().isNotEmpty) {
        setState(() => _stage = _ImportStage.parsingCatalog);
        try {
          final AppResult<ParsedCatalog> result = await widget.services.parser
              .parseCatalog(catalogUrl.trim());
          if (!mounted || token != _runToken) return;
          if (result is Success<ParsedCatalog>) {
            catalog = result.value;
          } else if (result is Failure<ParsedCatalog>) {
            catalogWarning = userMessageOf(result.error);
          }
        } catch (error) {
          catalogWarning = '目录解析失败：$error';
        }
      }
    }

    if (catalog == null && resolution.requiresCatalogHint) {
      _fail(
        ParseError('CATALOG_UNRESOLVED', 'resolver requires catalog hint'),
        requiresHint: true,
      );
      return;
    }
    if (detail == null && catalog == null) {
      _fail(
        ParseError('DETAIL_INCOMPLETE', 'no detail and no catalog'),
        requiresHint: resolution.requiresCatalogHint,
      );
      return;
    }

    final bool lowConfidence =
        resolution.confidence == Confidence.low ||
        (detail != null && detail.confidence == Confidence.low) ||
        (catalog != null && catalog.confidence == Confidence.low) ||
        (classification != null && classification.confidence == Confidence.low);
    final String sourceUrl = detail?.sourceUrl ?? resolution.normalizedUrl;

    int? existingBookId;
    try {
      final Book? existing = await widget.services.books.findBySourceUrl(
        sourceUrl,
      );
      existingBookId = existing?.id;
    } catch (_) {
      existingBookId = null;
    }

    if (!mounted || token != _runToken) return;
    setState(() {
      _detail = detail;
      _catalogCount = catalog?.chapters.length;
      _lowConfidence = lowConfidence;
      _existingBookId = existingBookId;
      _catalogWarning = catalogWarning;
      _stage = _ImportStage.saving;
    });

    int bookId;
    try {
      final AppResult<int> saved = await widget.services.books.upsertFromImport(
        detail: detail,
        sourceUrl: sourceUrl,
        catalog: catalog,
      );
      if (!mounted || token != _runToken) return;
      if (saved is Failure<int>) {
        _fail(saved.error, requiresHint: false);
        return;
      }
      bookId = (saved as Success<int>).value;
    } catch (error) {
      if (!mounted || token != _runToken) return;
      _fail(UnknownError('保存书籍失败：$error'));
      return;
    }

    setState(() => _stage = _ImportStage.idle);
    if (catalogWarning != null) {
      showAppSnack(context, '书籍已保存，但目录未识别，请在详情页更新目录');
    } else {
      showAppSnack(context, '导入成功');
    }
    if (!mounted) return;
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) =>
            BookDetailPage(services: widget.services, bookId: bookId),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final ColorScheme scheme = theme.colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('发现·导入')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.fromLTRB(
          AppSpacing.screenH,
          AppSpacing.screenV,
          AppSpacing.screenH,
          AppSpacing.xl,
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Text(
              '粘贴书籍详情页 / 目录页 / 章节页链接，自动识别整本信息',
              style: theme.textTheme.bodySmall?.copyWith(
                color: scheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: AppSpacing.sm),
            TextField(
              controller: _urlController,
              focusNode: _urlFocus,
              enabled: !_busy,
              keyboardType: TextInputType.url,
              textInputAction: TextInputAction.go,
              onChanged: (_) => setState(() {}),
              onSubmitted: (_) => _submit(),
              decoration: const InputDecoration(
                labelText: '小说网址',
                hintText: '粘贴书籍详情页 / 目录页 / 章节页链接',
                prefixIcon: Icon(Icons.link, size: 20),
              ),
            ),
            const SizedBox(height: AppSpacing.sm),
            Wrap(
              spacing: AppSpacing.sm,
              runSpacing: AppSpacing.xs,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: <Widget>[
                OutlinedButton.icon(
                  onPressed: _busy ? null : _pasteFromClipboard,
                  icon: const Icon(Icons.content_paste, size: 18),
                  label: const Text('粘贴'),
                ),
                FilledButton(
                  onPressed: _busy ? null : _submit,
                  child: Text(_hasParsedOnce ? '重新解析' : '开始解析'),
                ),
                if (_busy)
                  OutlinedButton(onPressed: _cancel, child: const Text('取消')),
                if (_urlController.text.isNotEmpty && !_busy)
                  TextButton(onPressed: _clearAll, child: const Text('清空')),
              ],
            ),
            if (_busy) ...<Widget>[
              const SizedBox(height: AppSpacing.md),
              _StageStrip(stage: _stage),
              const SizedBox(height: AppSpacing.sm),
              const LinearProgressIndicator(minHeight: 3),
              const SizedBox(height: AppSpacing.xs),
              Text(
                _stageText(_stage),
                style: theme.textTheme.bodySmall?.copyWith(
                  color: scheme.onSurfaceVariant,
                ),
              ),
            ],
            if (_classification != null) ...<Widget>[
              const SizedBox(height: AppSpacing.md),
              SectionCard(
                title: '网址类型',
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    Text(
                      '${_kindLabel(_classification!.kind)}（置信度：${_confidenceLabel(_classification!.confidence)}）',
                      style: theme.textTheme.bodyMedium,
                    ),
                    if (_classification!.notes.isNotEmpty) ...<Widget>[
                      const SizedBox(height: AppSpacing.xs),
                      Text(
                        _classification!.notes.join('\n'),
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: scheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ],
            if (_lowConfidence) ...<Widget>[
              const SizedBox(height: AppSpacing.md),
              _LowConfidenceBanner(),
            ],
            if (_catalogWarning != null) ...<Widget>[
              const SizedBox(height: AppSpacing.md),
              _CatalogWarningBanner(message: _catalogWarning!),
            ],
            if (_detail != null || _catalogCount != null) ...<Widget>[
              const SizedBox(height: AppSpacing.md),
              _ResultPreviewCard(detail: _detail, catalogCount: _catalogCount),
            ],
            if (_existingBookId != null) ...<Widget>[
              const SizedBox(height: AppSpacing.md),
              SectionCard(
                title: '已在书架',
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    Text('书架中已有此书（按网址去重合并）', style: theme.textTheme.bodyMedium),
                    const SizedBox(height: AppSpacing.sm),
                    Align(
                      alignment: Alignment.centerLeft,
                      child: OutlinedButton(
                        onPressed: () {
                          final int? id = _existingBookId;
                          if (id == null) return;
                          Navigator.of(context).push(
                            MaterialPageRoute<void>(
                              builder: (_) => BookDetailPage(
                                services: widget.services,
                                bookId: id,
                              ),
                            ),
                          );
                        },
                        child: const Text('打开'),
                      ),
                    ),
                  ],
                ),
              ),
            ],
            if (_error != null) ...<Widget>[
              const SizedBox(height: AppSpacing.md),
              AppErrorBanner(error: _error),
              const SizedBox(height: AppSpacing.sm),
              TextField(
                controller: _hintController,
                enabled: !_busy,
                keyboardType: TextInputType.url,
                decoration: InputDecoration(
                  labelText: '补充目录网址（可选）',
                  hintText: 'https://example.com/book/123/',
                  helperText: _requiresHint
                      ? '当前章节无法回溯整本小说，请填写目录页网址后重试'
                      : '若目录识别失败，可填写目录页网址后重试',
                  helperMaxLines: 2,
                ),
              ),
              const SizedBox(height: AppSpacing.sm),
              Wrap(
                spacing: AppSpacing.sm,
                children: <Widget>[
                  FilledButton(
                    onPressed: _busy ? null : _submit,
                    child: const Text('重试'),
                  ),
                  OutlinedButton(
                    onPressed: _busy ? null : _clearAll,
                    child: const Text('清空'),
                  ),
                ],
              ),
            ],
            const SizedBox(height: AppSpacing.xl),
          ],
        ),
      ),
    );
  }

  String _stageText(_ImportStage stage) {
    switch (stage) {
      case _ImportStage.classifying:
        return '正在识别网址类型…';
      case _ImportStage.parsingDetail:
        return '正在解析书籍详情…';
      case _ImportStage.parsingCatalog:
        return '正在解析章节目录…';
      case _ImportStage.saving:
        return '正在保存…';
      case _ImportStage.idle:
        return '';
    }
  }
}

class _StageStrip extends StatelessWidget {
  const _StageStrip({required this.stage});

  final _ImportStage stage;

  static const List<String> _labels = <String>['分类', '详情', '目录', '保存'];

  int get _current {
    switch (stage) {
      case _ImportStage.classifying:
        return 0;
      case _ImportStage.parsingDetail:
        return 1;
      case _ImportStage.parsingCatalog:
        return 2;
      case _ImportStage.saving:
        return 3;
      case _ImportStage.idle:
        return 4;
    }
  }

  @override
  Widget build(BuildContext context) {
    final ColorScheme scheme = Theme.of(context).colorScheme;
    return Row(
      children: List<Widget>.generate(_labels.length, (int index) {
        final bool done = index < _current;
        final bool active = index == _current;
        return Expanded(
          child: Row(
            children: <Widget>[
              Icon(
                done
                    ? Icons.check_circle
                    : active
                    ? Icons.radio_button_checked
                    : Icons.radio_button_unchecked,
                size: 15,
                color: done || active ? scheme.primary : scheme.outline,
              ),
              const SizedBox(width: 4),
              Text(
                _labels[index],
                style: Theme.of(context).textTheme.labelSmall?.copyWith(
                  color: done || active
                      ? scheme.primary
                      : scheme.onSurfaceVariant,
                  fontWeight: active ? FontWeight.w600 : FontWeight.w400,
                ),
              ),
            ],
          ),
        );
      }),
    );
  }
}

class _LowConfidenceBanner extends StatelessWidget {
  const _LowConfidenceBanner();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: context.semanticColors.warningContainer,
        borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
      ),
      child: Row(
        children: <Widget>[
          Icon(
            Icons.warning_amber,
            color: context.semanticColors.onWarningContainer,
            size: 20,
          ),
          const SizedBox(width: AppSpacing.sm),
          Expanded(
            child: Text(
              '识别结果可信度较低，字段可能不完整',
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                color: context.semanticColors.onWarningContainer,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _CatalogWarningBanner extends StatelessWidget {
  const _CatalogWarningBanner({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    final ColorScheme scheme = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: scheme.errorContainer,
        borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Icon(Icons.error_outline, color: scheme.onErrorContainer, size: 20),
          const SizedBox(width: AppSpacing.sm),
          Expanded(
            child: Text(
              '目录解析失败：$message\n可先保存书籍，稍后在详情页更新目录。',
              style: Theme.of(
                context,
              ).textTheme.bodySmall?.copyWith(color: scheme.onErrorContainer),
            ),
          ),
        ],
      ),
    );
  }
}

class _ResultPreviewCard extends StatelessWidget {
  const _ResultPreviewCard({required this.detail, required this.catalogCount});

  final ParsedBookDetail? detail;
  final int? catalogCount;

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final ColorScheme scheme = theme.colorScheme;
    return SectionCard(
      title: '解析结果预览',
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          AppCover(url: detail?.coverUrl, width: 72, height: 100, iconSize: 24),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                _PreviewField(label: '书名', value: detail?.title),
                _PreviewField(label: '作者', value: detail?.author),
                _PreviewField(
                  label: '简介',
                  value: detail?.description,
                  maxLines: 3,
                ),
                Text(
                  '目录：${catalogCount != null ? '$catalogCount 章' : '未识别'}',
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: catalogCount != null
                        ? scheme.onSurface
                        : scheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _PreviewField extends StatelessWidget {
  const _PreviewField({
    required this.label,
    required this.value,
    this.maxLines = 1,
  });

  final String label;
  final String? value;
  final int maxLines;

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final ColorScheme scheme = theme.colorScheme;
    final bool empty = value == null || value!.trim().isEmpty;
    return Padding(
      padding: const EdgeInsets.only(bottom: 3),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text(
            '$label：',
            style: theme.textTheme.bodySmall?.copyWith(
              color: scheme.onSurfaceVariant,
            ),
          ),
          Expanded(
            child: Text(
              empty ? '（未识别）' : value!.trim(),
              maxLines: maxLines,
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.bodySmall?.copyWith(
                color: empty ? scheme.onSurfaceVariant : scheme.onSurface,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

String _kindLabel(PageKind kind) {
  switch (kind) {
    case PageKind.bookDetail:
      return '书籍详情页';
    case PageKind.catalog:
      return '目录页';
    case PageKind.chapter:
      return '章节页';
    case PageKind.unknown:
      return '未知页面';
  }
}

String _confidenceLabel(Confidence confidence) {
  switch (confidence) {
    case Confidence.high:
      return '高';
    case Confidence.medium:
      return '中';
    case Confidence.low:
      return '低';
  }
}
