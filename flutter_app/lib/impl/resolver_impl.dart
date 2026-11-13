/// 整本识别器实现（章节 URL 反向定位详情/目录）。
///
/// 对齐 Kotlin 版 `parser/resolver/BookResolverImpl.kt` 的静态回溯链：
///  1. 详情页直达：parseDetail → catalogUrl → parseCatalog；
///  2. 目录页直达：parseCatalog；
///  3. 章节页/未知页：解析当前页（标题/上下章/面包屑）→ 面包屑候选 ≤6 逐个 classify
///     → 命中详情/目录即锁定；无果沿上一章有界回溯 ≤15 跳（预算 20 次请求）；
///  4. catalogHint 验证（classify → parse → 书名校验）；
///  5. 全部失败 requiresCatalogHint=true，不猜测/拼接 URL。
library;

import 'package:html/dom.dart' as dom;
import 'package:html/parser.dart' as html_parser;

import '../contracts.dart';
import '../core/app_result.dart';
import '../core/reader_error.dart';
import 'parser_impl.dart';
import 'url_utils.dart';

class BookResolverImpl implements BookResolver {
  BookResolverImpl(this._http, this._parser);

  final SecureHttpClient _http;
  final ParserEngineImpl _parser;

  static const int _maxBacktraceSteps = 15;
  static const int _maxBreadcrumbCandidates = 6;
  static const int _requestBudget = 20;

  @override
  Future<AppResult<BookResolution>> resolve(
    String url, {
    String? catalogHint,
  }) async {
    final normalized = normalizeUrl(url);
    if (normalized == null) {
      return Failure(NetworkError('INVALID_URL', url: redactUrl(url)));
    }

    final evidence = <String>[];
    final classification = await _parser.classify(normalized);
    final classError = classification.errorOrNull;
    if (classError != null) {
      evidence.add('[分类] $normalized → 失败：${_describe(classError)}');
      return Failure(classError);
    }
    final classified = classification.valueOrNull!;
    final kind = classified.kind;
    evidence.add(
      '[分类] $normalized → kind=${kind.name}（置信度 ${classified.confidence.name}）',
    );
    for (final note in classified.notes) {
      evidence.add('[分类依据] $note');
    }

    switch (kind) {
      case PageKind.bookDetail:
        return Success(await _resolveDirectDetail(
          normalized,
          catalogHint,
          evidence,
        ));
      case PageKind.catalog:
        return Success(await _resolveDirectCatalog(
          normalized,
          catalogHint,
          evidence,
        ));
      case PageKind.chapter:
      case PageKind.unknown:
        return Success(await _resolveViaChapterChain(
          normalized,
          kind,
          catalogHint,
          evidence,
        ));
    }
  }

  // ============================================================
  // 直达分支
  // ============================================================

  Future<BookResolution> _resolveDirectDetail(
    String normalized,
    String? catalogHint,
    List<String> evidence,
  ) async {
    final detailResult = await _parser.parseDetail(normalized);
    final detailError = detailResult.errorOrNull;
    if (detailError != null) {
      evidence.add('[详情解析] $normalized → 失败：${_describe(detailError)}');
    } else {
      final detail = detailResult.valueOrNull!;
      evidence.add(
        '[详情解析] ${detail.sourceUrl} → title=${detail.title ?? '缺失'}'
        '（置信度 ${detail.confidence.name}）',
      );
      final catalogUrl = detail.catalogUrl;
      if (catalogUrl != null) {
        final catalogResult = await _parser.parseCatalogWithTitle(catalogUrl);
        final catalogError = catalogResult.errorOrNull;
        if (catalogError != null) {
          evidence.add('[目录解析] $catalogUrl → 失败：${_describe(catalogError)}');
        } else {
          final result = catalogResult.valueOrNull!;
          final catalog = result.catalog;
          final match = _validateNames(detail.title, result.pageTitle);
          evidence.add(
            '[书名校验] detail="${detail.title}" catalog="${result.pageTitle ?? ''}" → ${match.name}',
          );
          var confidence = _minConfidence(detail.confidence, catalog.confidence);
          if (match == _NameMatch.mismatched) confidence = Confidence.low;
          evidence.add('[结论] 详情页 + 目录页定位成功');
          return BookResolution(
            normalizedUrl: normalized,
            kind: PageKind.bookDetail,
            detailUrl: detail.sourceUrl,
            catalogUrl: catalog.sourceUrl,
            detail: detail,
            catalog: catalog,
            confidence: confidence,
            requiresCatalogHint: false,
            evidence: evidence,
          );
        }
      } else {
        evidence.add('[详情解析] ${detail.sourceUrl} → 详情页未声明目录链接，禁止猜测');
      }

      final hinted = await _tryHint(
        catalogHint,
        knownNames: [if (detail.title != null) detail.title!],
        inputKind: PageKind.bookDetail,
        evidence: evidence,
      );
      if (hinted != null) return hinted;

      return BookResolution(
        normalizedUrl: normalized,
        kind: PageKind.bookDetail,
        detailUrl: detail.sourceUrl,
        detail: detail,
        confidence: Confidence.low,
        requiresCatalogHint: true,
        evidence: evidence,
      );
    }

    final hinted = await _tryHint(
      catalogHint,
      knownNames: const [],
      inputKind: PageKind.bookDetail,
      evidence: evidence,
    );
    if (hinted != null) return hinted;

    return BookResolution(
      normalizedUrl: normalized,
      kind: PageKind.bookDetail,
      detailUrl: normalized,
      confidence: Confidence.low,
      requiresCatalogHint: true,
      evidence: evidence,
    );
  }

  Future<BookResolution> _resolveDirectCatalog(
    String normalized,
    String? catalogHint,
    List<String> evidence,
  ) async {
    final catalogResult = await _parser.parseCatalogWithTitle(normalized);
    final catalogError = catalogResult.errorOrNull;
    if (catalogError == null) {
      final result = catalogResult.valueOrNull!;
      evidence.add(
        '[目录解析] ${result.catalog.sourceUrl} → ${result.catalog.chapters.length} 章'
        '（置信度 ${result.catalog.confidence.name}）',
      );
      return BookResolution(
        normalizedUrl: normalized,
        kind: PageKind.catalog,
        catalogUrl: result.catalog.sourceUrl,
        catalog: result.catalog,
        confidence: result.catalog.confidence,
        requiresCatalogHint: false,
        evidence: evidence,
      );
    }
    evidence.add('[目录解析] $normalized → 失败：${_describe(catalogError)}');

    final hinted = await _tryHint(
      catalogHint,
      knownNames: const [],
      inputKind: PageKind.catalog,
      evidence: evidence,
    );
    if (hinted != null) return hinted;

    return BookResolution(
      normalizedUrl: normalized,
      kind: PageKind.catalog,
      confidence: Confidence.low,
      requiresCatalogHint: true,
      evidence: evidence,
    );
  }

  // ============================================================
  // 章节页回溯链
  // ============================================================

  Future<BookResolution> _resolveViaChapterChain(
    String normalized,
    PageKind inputKind,
    String? catalogHint,
    List<String> evidence,
  ) async {
    final visited = <String>{normalized};
    String? current = normalized;
    var steps = 0;
    var budget = _requestBudget;
    var knownNames = <String>[];

    while (current != null) {
      if (steps >= _maxBacktraceSteps) {
        evidence.add('[回溯上限] 已达 $_maxBacktraceSteps 跳，停止回溯');
        break;
      }
      if (budget <= 0) {
        evidence.add('[预算] 请求预算耗尽，停止回溯');
        break;
      }

      budget--;
      final pageResult = await _http.fetch(current);
      final pageError = pageResult.errorOrNull;
      if (pageError != null) {
        evidence.add('[章节页] ${redactUrl(current)} → 抓取失败：${_describe(pageError)}');
        break;
      }
      final fetched = pageResult.valueOrNull!;
      final document = html_parser.parse(fetched.html);
      steps++;

      final pageTitle = _pageTitle(document);
      if (knownNames.isEmpty) {
        knownNames = _bookNameCandidates(pageTitle);
      }
      final previous = _detectPreviousChapterLink(document, fetched.finalUrl);
      evidence.add(
        '[章节页] ${redactUrl(fetched.finalUrl)} 第 $steps/$_maxBacktraceSteps 跳'
        ' title=${pageTitle ?? '缺失'} 上一章=${previous ?? '无'}',
      );

      final breadcrumbs = _breadcrumbCandidates(
        document,
        fetched.finalUrl,
        max: _maxBreadcrumbCandidates,
      );
      evidence.add('[面包屑] 候选 ${breadcrumbs.length} 个');

      for (final crumb in breadcrumbs) {
        if (budget <= 0) {
          evidence.add('[预算] 面包屑验证中预算耗尽');
          break;
        }
        budget--;
        final classification = await _parser.classify(crumb.url);
        final classified = classification.valueOrNull;
        if (classified == null) continue;

        if (classified.kind == PageKind.catalog) {
          final catalogResult = await _parser.parseCatalogWithTitle(crumb.url);
          final catalogError = catalogResult.errorOrNull;
          if (catalogError != null) {
            evidence.add('[面包屑] ${crumb.url} → 目录解析失败');
            continue;
          }
          final result = catalogResult.valueOrNull!;
          final match = _validateAnyName(knownNames, result.pageTitle);
          evidence.add(
            '[面包屑] ${crumb.url} → 命中目录（${result.catalog.chapters.length} 章，'
            '书名校验 ${match.name}）',
          );
          var confidence = Confidence.medium;
          if (match == _NameMatch.mismatched) confidence = Confidence.low;
          return BookResolution(
            normalizedUrl: normalized,
            kind: inputKind,
            catalogUrl: result.catalog.sourceUrl,
            catalog: result.catalog,
            confidence: confidence,
            requiresCatalogHint: false,
            evidence: evidence,
          );
        }

        if (classified.kind == PageKind.bookDetail) {
          final detailResult = await _parser.parseDetail(crumb.url);
          final detailError = detailResult.errorOrNull;
          if (detailError != null) {
            evidence.add('[面包屑] ${crumb.url} → 详情解析失败');
            continue;
          }
          final detail = detailResult.valueOrNull!;
          final catalogUrl = detail.catalogUrl;
          if (catalogUrl == null) {
            evidence.add('[面包屑] ${crumb.url} → 详情页未声明目录链接');
            continue;
          }
          final catalogResult = await _parser.parseCatalogWithTitle(catalogUrl);
          final catalogError = catalogResult.errorOrNull;
          if (catalogError != null) {
            evidence.add('[面包屑] $catalogUrl → 目录解析失败');
            continue;
          }
          final result = catalogResult.valueOrNull!;
          final match = _validateNames(detail.title, result.pageTitle);
          evidence.add(
            '[面包屑] ${crumb.url} → 命中详情 + 目录（${result.catalog.chapters.length} 章，'
            '书名校验 ${match.name}）',
          );
          var confidence = Confidence.medium;
          if (match == _NameMatch.mismatched) confidence = Confidence.low;
          return BookResolution(
            normalizedUrl: normalized,
            kind: inputKind,
            detailUrl: detail.sourceUrl,
            catalogUrl: result.catalog.sourceUrl,
            detail: detail,
            catalog: result.catalog,
            confidence: confidence,
            requiresCatalogHint: false,
            evidence: evidence,
          );
        }
      }

      if (previous == null || visited.contains(previous)) {
        evidence.add('[上一章回溯] 无可用上一章链接，回溯链终止');
        break;
      }
      visited.add(previous);
      current = previous;
    }

    final hinted = await _tryHint(
      catalogHint,
      knownNames: knownNames,
      inputKind: inputKind,
      evidence: evidence,
    );
    if (hinted != null) return hinted;

    evidence.add('[结论] 面包屑与上一章回溯均未定位目录入口，需要用户补充目录页 URL');
    return BookResolution(
      normalizedUrl: normalized,
      kind: inputKind,
      confidence: Confidence.low,
      requiresCatalogHint: true,
      evidence: evidence,
    );
  }

  // ============================================================
  // catalogHint
  // ============================================================

  Future<BookResolution?> _tryHint(
    String? catalogHint, {
    required List<String> knownNames,
    required PageKind inputKind,
    required List<String> evidence,
  }) async {
    if (catalogHint == null || catalogHint.trim().isEmpty) return null;
    final normalizedHint = normalizeUrl(catalogHint);
    if (normalizedHint == null) {
      evidence.add('[提示验证] ${redactUrl(catalogHint)} → URL 非法，忽略');
      return null;
    }

    final classification = await _parser.classify(normalizedHint);
    final classified = classification.valueOrNull;
    if (classified == null) {
      evidence.add('[提示验证] $normalizedHint → classify 失败');
      return null;
    }
    evidence.add(
      '[提示验证] $normalizedHint → kind=${classified.kind.name}'
      '（置信度 ${classified.confidence.name}）',
    );

    if (classified.kind == PageKind.catalog) {
      final catalogResult = await _parser.parseCatalogWithTitle(normalizedHint);
      final catalogError = catalogResult.errorOrNull;
      if (catalogError != null) {
        evidence.add('[提示验证] $normalizedHint → 目录解析失败');
        return null;
      }
      final result = catalogResult.valueOrNull!;
      final match = _validateAnyName(knownNames, result.pageTitle);
      final confidence = match == _NameMatch.matched
          ? Confidence.medium
          : Confidence.low;
      evidence.add(
        '[提示验证] $normalizedHint → 目录 ${result.catalog.chapters.length} 章'
        '（书名校验 ${match.name}）',
      );
      return BookResolution(
        normalizedUrl: normalizedHint,
        kind: inputKind,
        catalogUrl: result.catalog.sourceUrl,
        catalog: result.catalog,
        confidence: confidence,
        requiresCatalogHint: false,
        evidence: evidence,
      );
    }

    if (classified.kind == PageKind.bookDetail) {
      final detailResult = await _parser.parseDetail(normalizedHint);
      final detail = detailResult.valueOrNull;
      if (detail == null) {
        evidence.add('[提示验证] $normalizedHint → 详情解析失败');
        return null;
      }
      final catalogUrl = detail.catalogUrl;
      if (catalogUrl == null) {
        evidence.add('[提示验证] $normalizedHint → 详情页未声明目录链接');
        return null;
      }
      final catalogResult = await _parser.parseCatalogWithTitle(catalogUrl);
      final result = catalogResult.valueOrNull;
      if (result == null) {
        evidence.add('[提示验证] $catalogUrl → 目录解析失败');
        return null;
      }
      final match = _validateNames(detail.title, result.pageTitle);
      final confidence = match == _NameMatch.matched
          ? Confidence.medium
          : Confidence.low;
      evidence.add(
        '[提示验证] $catalogUrl → 详情 + 目录 ${result.catalog.chapters.length} 章'
        '（书名校验 ${match.name}）',
      );
      return BookResolution(
        normalizedUrl: normalizedHint,
        kind: inputKind,
        detailUrl: detail.sourceUrl,
        catalogUrl: result.catalog.sourceUrl,
        detail: detail,
        catalog: result.catalog,
        confidence: confidence,
        requiresCatalogHint: false,
        evidence: evidence,
      );
    }

    evidence.add('[提示验证] $normalizedHint → 页面类型不是详情/目录，无法作为入口');
    return null;
  }

  // ============================================================
  // 导航证据采集
  // ============================================================

  String? _pageTitle(dom.Document document) {
    final h1 = document.querySelector('h1')?.text.trim();
    if (h1 != null && h1.isNotEmpty) return h1;
    final title = document.querySelector('title')?.text.trim();
    return (title == null || title.isEmpty) ? null : title;
  }

  List<String> _bookNameCandidates(String? title) {
    if (title == null || title.trim().isEmpty) return const [];
    final result = <String>[];
    final parts = title.split(RegExp(r'[_|\-—–·,，:：\s]+'));
    for (final part in parts) {
      var candidate = part.trim().replaceAll(RegExp('[《》「」『』【】]'), '');
      if (candidate.length < 2) continue;
      if (extractChapterNumber(candidate) != null) continue;
      if (candidate == '首页' || candidate == '目录' || candidate == '最新章节') {
        continue;
      }
      if (!result.contains(candidate)) result.add(candidate);
    }
    return result;
  }

  List<_Crumb> _breadcrumbCandidates(
    dom.Document document,
    String pageUrl, {
    required int max,
  }) {
    final pageHost = hostOf(pageUrl);
    final anchors = <dom.Element>[];
    const selectors = [
      '[class*=breadcrumb] a',
      '[class*=crumb] a',
      '[id*=breadcrumb] a',
      '[class*=position] a',
      '[class*=path] a',
      '[class*=nav] a',
      'nav a',
    ];
    for (final selector in selectors) {
      anchors.addAll(document.querySelectorAll(selector));
    }
    for (final anchor in document.querySelectorAll('a')) {
      final text = anchor.text.trim();
      if (text.isEmpty || text.length > 20) continue;
      if (text.contains('目录') ||
          text.contains('章节列表') ||
          text.contains('全部章节') ||
          text.contains('作品目录') ||
          text.contains('返回书页')) {
        anchors.add(anchor);
      }
    }

    final result = <_Crumb>[];
    final seen = <String>{};
    for (final anchor in anchors) {
      final href = (anchor.attributes['href'] ?? '').trim();
      if (href.isEmpty || href == '#' || href.toLowerCase().startsWith('javascript')) {
        continue;
      }
      final absolute = resolveUrl(pageUrl, href);
      if (absolute == null) continue;
      if (absolute == normalizeUrl(pageUrl)) continue;
      if (pageHost != null && hostOf(absolute) != pageHost) continue;
      if (!seen.add(absolute)) continue;
      result.add(_Crumb(text: anchor.text.trim(), url: absolute));
      if (result.length >= max) break;
    }
    return result;
  }

  String? _detectPreviousChapterLink(dom.Document document, String pageUrl) {
    const includes = ['上一章', '上一节', 'previous chapter'];
    const excludes = ['上一页', '上一頁'];
    for (final anchor in document.querySelectorAll('a')) {
      final text = anchor.text.trim().toLowerCase();
      if (text.isEmpty || text.length > 12) continue;
      if (excludes.any(text.contains)) continue;
      if (!includes.any(text.contains)) continue;
      final href = (anchor.attributes['href'] ?? '').trim();
      if (href.isEmpty || href == '#' || href.toLowerCase().startsWith('javascript')) {
        continue;
      }
      final absolute = resolveUrl(pageUrl, href);
      if (absolute != null) return absolute;
    }
    return null;
  }

  // ============================================================
  // 辅助
  // ============================================================

  _NameMatch _validateNames(String? detailTitle, String? catalogTitle) {
    final a = _normalizeName(detailTitle);
    final b = _normalizeName(catalogTitle);
    if (a.isEmpty || b.isEmpty) return _NameMatch.unverified;
    return (a.contains(b) || b.contains(a))
        ? _NameMatch.matched
        : _NameMatch.mismatched;
  }

  _NameMatch _validateAnyName(List<String> knownNames, String? catalogTitle) {
    final b = _normalizeName(catalogTitle);
    if (b.isEmpty || knownNames.isEmpty) return _NameMatch.unverified;
    for (final name in knownNames) {
      final a = _normalizeName(name);
      if (a.isEmpty) continue;
      if (a.contains(b) || b.contains(a)) return _NameMatch.matched;
    }
    return _NameMatch.mismatched;
  }

  String _normalizeName(String? value) {
    if (value == null) return '';
    var text = value.replaceAll(RegExp(r'\s+'), '').toLowerCase();
    text = text.replaceAll(RegExp('[《》「」『』【】]'), '');
    return text;
  }

  Confidence _minConfidence(Confidence a, Confidence b) {
    int rank(Confidence c) {
      switch (c) {
        case Confidence.high:
          return 3;
        case Confidence.medium:
          return 2;
        case Confidence.low:
          return 1;
      }
    }

    return rank(a) <= rank(b) ? a : b;
  }

  String _describe(ReaderError error) {
    if (error is NetworkError) return 'NetworkError(${error.failure})';
    if (error is ParseError) return 'ParseError(${error.failure})';
    if (error is SecurityError) return 'SecurityError(${error.reason})';
    if (error is StorageError) return 'StorageError(${error.failure})';
    if (error is StateError) return 'StateError(${error.failure})';
    return 'UnknownError';
  }
}

enum _NameMatch { matched, mismatched, unverified }

class _Crumb {
  const _Crumb({required this.text, required this.url});

  final String text;
  final String url;
}