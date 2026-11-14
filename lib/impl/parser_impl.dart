/// 解析引擎实现（URL 分类 / 详情 / 目录 / 正文）。
///
/// 语义对齐 Kotlin 版：
///  - `parser/core/UrlClassifierImpl.kt`（Flutter v1 仅用 URL 形态启发式，不额外抓取）；
///  - `parser/structured/*`（JSON-LD → OG → meta → 通用层逐层合并）；
///  - `parser/rule/*`（站点规则层：manual.* 优先级 4，非人工站点规则 3；
///    与 Kotlin DetailParseStep 层序语义一致，规则未命中自动回退通用管线）；
///  - `parser/generic/GenericSmartExtractor.kt`（目录容器/正文容器评分、导航链接）；
///  - `parser/util/CatalogAnchorFilter.kt`（非章节路径排除、书路径段聚类、分卷锚）；
///  - `parser/util/PaginationMerger.kt`（目录 ≤5 页 / 正文 ≤10 页、重复率终止）；
///  - `parser/util/TextCleaner.kt`（噪声标签/广告容器清理、图片绝对化）。
library;

import 'dart:convert';

import 'package:html/dom.dart' as dom;
import 'package:html/parser.dart' as html_parser;

import '../contracts.dart';
import '../core/app_result.dart';
import '../core/reader_error.dart';
import '../core/site_rule.dart';
import 'rule_engine_impl.dart';
import 'url_utils.dart';

/// 目录解析结果 + 目录页标题（供 BookResolver 做书名校验）。
class CatalogParseResult {
  const CatalogParseResult({required this.catalog, this.pageTitle});

  final ParsedCatalog catalog;
  final String? pageTitle;
}

class ParserEngineImpl implements ParserEngine {
  ParserEngineImpl(this._http, {RuleEngine? ruleEngine})
      : _ruleEngine = ruleEngine;

  final SecureHttpClient _http;
  final RuleEngine? _ruleEngine;

  // 章节页特征：显式 /read /chapter、x_x.html 编号分页、或"数字父段/数字叶段"（如 /novel/5544/346134.html）。
  // 注意：裸 /novel/5544.html（单数字段 + .html）是详情页，不得被章节正则误吞（验收实测缺陷修复）。
  static final RegExp _chapterUrlRe = RegExp(
    r'(?:/read/|/chapter/|chapter-\d+)|/\d+_\d+\.x?html|/\d+/\d+(?:\.[a-z0-9]+)?(?:[/?#]|$)',
    caseSensitive: false,
  );
  static final RegExp _catalogUrlRe = RegExp(
    r'(?:catalog|chapterlist|chapters|/list(?:[/?#]|$)|/all(?:[/?#]|$)|/mulu|/bookcase|目录)',
    caseSensitive: false,
  );
  static final RegExp _detailUrlRe = RegExp(
    r'(?:/book/|/novel/|/xiaoshuo/|/info/|/txt/)',
    caseSensitive: false,
  );

  static final RegExp _noiseClassRe = RegExp(
    r'(?:^|[\s_\-])(ad|ads|advert|advertisement|banner|promote|promotion|sidebar|'
    r'footer|nav|navbar|menu|breadcrumb|login|signin|recommend|recomm|tip|tips)'
    r'(?:$|[\s_\-])',
    caseSensitive: false,
  );

  static const Set<String> _noiseTags = {
    'script', 'style', 'iframe', 'noscript', 'form', 'button', 'input',
    'select', 'aside', 'footer', 'nav',
  };

  static const Set<String> _blockTags = {
    'p', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'li', 'blockquote', 'div',
    'section', 'article', 'td', 'dd', 'dt', 'pre',
  };

  static const Set<String> _volumeTags = {'dt', 'h2', 'h3', 'h4'};

  static const List<String> _adLinePhrases = [
    '请记住本书首发域名',
    '请收藏本站',
    '一秒记住',
    '手机版阅读网址',
    '最新网址：',
    '最新网址:',
    '本章未完，请点击下一页继续阅读',
    '点击下一页继续阅读',
  ];

  // ============================================================
  // 公共契约实现
  // ============================================================

  @override
  Future<AppResult<UrlClassification>> classify(String url) async {
    final normalized = normalizeUrl(url);
    if (normalized == null) {
      return Failure(NetworkError('INVALID_URL', url: redactUrl(url)));
    }
    final notes = <String>[];
    final lower = normalized.toLowerCase();

    PageKind kind;
    Confidence confidence;
    // 顺序：目录 → 章节（具体编号模式）→ 详情（宽泛特征词），保证 /novel/5544.html 判为详情（验收实测缺陷修复）
    if (_catalogUrlRe.hasMatch(lower)) {
      kind = PageKind.catalog;
      confidence = Confidence.medium;
      notes.add('URL 含目录页特征（catalog/list/mulu 等）');
    } else if (_chapterUrlRe.hasMatch(lower)) {
      kind = PageKind.chapter;
      confidence = Confidence.medium;
      notes.add('URL 含章节页特征（/read、编号分页等）');
    } else if (_detailUrlRe.hasMatch(lower)) {
      kind = PageKind.bookDetail;
      confidence = Confidence.medium;
      notes.add('URL 含详情页特征（book/novel/info 等）');
    } else {
      kind = PageKind.unknown;
      confidence = Confidence.low;
      notes.add('URL 无明显页面特征');
    }

    return Success(UrlClassification(
      normalizedUrl: normalized,
      kind: kind,
      confidence: confidence,
      notes: notes,
    ));
  }

  @override
  Future<AppResult<ParsedBookDetail>> parseDetail(String url) async {
    final normalized = normalizeUrl(url);
    if (normalized == null) {
      return Failure(NetworkError('INVALID_URL', url: redactUrl(url)));
    }
    // 规则层先于抓取加载：encoding 提示用于优先解码（GBK 站点实测必要）。
    final rule = await _ruleFor(normalized);
    final fetched = await _fetch(normalized, preferredCharset: rule?.encoding);
    final fetchError = fetched.errorOrNull;
    if (fetchError != null) return Failure(fetchError);
    final page = fetched.valueOrNull!;

    try {
      final document = html_parser.parse(page.html);
      final fields = <String, _Field>{};

      _extractJsonLd(document, fields);
      _extractOpenGraph(document, fields);
      _extractMeta(document, fields);
      // 站点规则层（manual.* 优先级 4；非人工站点规则 3，与 JSON-LD 同级先到先得）
      if (rule != null) {
        _extractRuleDetail(document, page.finalUrl, rule, fields);
      }
      _extractGenericDetail(document, page.finalUrl, fields);

      final title = fields['title']?.value.trim();
      if (title == null || title.isEmpty) {
        return Failure(ParseError(
          'DETAIL_INCOMPLETE',
          '未能识别书名 url=${redactUrl(page.finalUrl)}',
        ));
      }

      final cover = _absolutize(page.finalUrl, fields['cover']?.value);
      final catalogUrl = _pickCatalogLink(document, page.finalUrl);

      final titlePriority = fields['title']?.priority ?? 1;
      final confidence = titlePriority >= 3
          ? Confidence.high
          : (titlePriority >= 2 ? Confidence.medium : Confidence.low);

      return Success(ParsedBookDetail(
        sourceUrl: page.finalUrl,
        title: title,
        author: _nonEmpty(fields['author']?.value),
        coverUrl: cover,
        description: _nonEmpty(fields['description']?.value),
        catalogUrl: catalogUrl,
        confidence: confidence,
      ));
    } catch (e) {
      return Failure(ParseError('UNSUPPORTED_STRUCTURE', '详情解析异常: $e'));
    }
  }

  @override
  Future<AppResult<ParsedCatalog>> parseCatalog(String url) async {
    final result = await parseCatalogWithTitle(url);
    final error = result.errorOrNull;
    if (error != null) return Failure(error);
    return Success(result.valueOrNull!.catalog);
  }

  /// 目录解析 + 目录页 `<title>`（契约外的内部入口，BookResolver 使用）。
  Future<AppResult<CatalogParseResult>> parseCatalogWithTitle(String url) async {
    final normalized = normalizeUrl(url);
    if (normalized == null) {
      return Failure(NetworkError('INVALID_URL', url: redactUrl(url)));
    }
    final rule = await _ruleFor(normalized);
    final firstFetch = await _fetch(normalized, preferredCharset: rule?.encoding);
    final fetchError = firstFetch.errorOrNull;
    if (fetchError != null) return Failure(fetchError);
    final firstPage = firstFetch.valueOrNull!;

    try {
      // —— 站点规则路径：chapterLinkSelector 命中且有效章节 ≥2 时直接采用；
      //    否则回退通用管线（规则未命中不放弃通用解析）——
      if (rule != null &&
          rule.selectors[RuleSelectorKey.chapterLink] != null) {
        final ruled = await _parseCatalogRuled(firstPage, rule);
        if (ruled != null) return Success(ruled);
      }

      final baseHost = hostOf(firstPage.finalUrl);
      if (baseHost == null) {
        return Failure(ParseError('UNSUPPORTED_STRUCTURE', '目录页 URL 无主机'));
      }

      final pageTitle = _documentTitle(html_parser.parse(firstPage.html));
      final gathered = <_ChapterAnchor>[];
      final visitedPages = <String>{firstPage.finalUrl};
      var currentPage = firstPage;
      var currentDocument = html_parser.parse(firstPage.html);
      var pagesFetched = 1;
      var truncatedByLimit = false;

      var anchors = _extractChapterAnchors(currentDocument, currentPage.finalUrl, baseHost);
      gathered.addAll(anchors);
      var seenUrls = anchors.map((a) => a.url).toSet();

      while (true) {
        final nextPage = _detectNextPageLink(currentDocument, currentPage.finalUrl);
        if (nextPage == null) break;
        if (pagesFetched >= 5) {
          truncatedByLimit = true;
          break;
        }
        if (!visitedPages.add(nextPage)) break;
        if (hostOf(nextPage) != baseHost) break;

        final pageResult = await _fetch(nextPage);
        final pageError = pageResult.errorOrNull;
        if (pageError != null) break;
        final fetched = pageResult.valueOrNull!;

        final document = html_parser.parse(fetched.html);
        anchors = _extractChapterAnchors(document, fetched.finalUrl, baseHost);
        if (anchors.isEmpty) break;

        final duplicate = anchors.where((a) => seenUrls.contains(a.url)).length;
        final duplicateRate = duplicate / anchors.length;
        if (duplicateRate > 0.6) break;

        for (final anchor in anchors) {
          if (seenUrls.add(anchor.url)) gathered.add(anchor);
        }
        pagesFetched++;
        currentPage = fetched;
        currentDocument = document;
      }

      final chapters = _buildChapterRefs(gathered);
      if (chapters.length < 2) {
        return Failure(ParseError(
          'CATALOG_EMPTY',
          '目录页有效章节 ${chapters.length} 个 url=${redactUrl(firstPage.finalUrl)}',
        ));
      }

      final titles = chapters.map((c) => c.title).toList();
      final reversed = descendingRatio(titles) >= 0.8;
      final confidence = chapters.length >= 20
          ? Confidence.high
          : (chapters.length >= 5 ? Confidence.medium : Confidence.low);

      return Success(CatalogParseResult(
        pageTitle: pageTitle,
        catalog: ParsedCatalog(
          sourceUrl: firstPage.finalUrl,
          chapters: chapters,
          isLikelyReversed: reversed,
          truncatedByLimit: truncatedByLimit,
          confidence: confidence,
        ),
      ));
    } catch (e) {
      return Failure(ParseError('UNSUPPORTED_STRUCTURE', '目录解析异常: $e'));
    }
  }

  /// 站点规则目录解析：catalogSelector 容器（可选）+ chapterLinkSelector 锚点
  /// + catalogNextPageSelector 翻页（缺省回退通用"下一页"探测）。
  ///
  /// 对齐 Kotlin RuleEngineImpl 目录口径：
  ///  - 锚点**不做章节形态正则要求**（规则即意图，见 [RuleEngine.extractCatalogAnchors]）；
  ///  - 翻页终止：重复率 >0.6 / 跨主机 / 上限 5 页（truncatedByLimit）；
  ///  - 倒序判定阈值 0.6（规则模式显式提供链接，置信度高于通用层的 0.8）。
  ///
  /// 返回 null = 规则未命中有效目录（≥2 章），调用方回退通用管线。
  Future<CatalogParseResult?> _parseCatalogRuled(
    FetchedPage firstPage,
    ParsedSiteRule rule,
  ) async {
    final engine = _ruleEngine;
    if (engine == null) return null;

    final firstDocument = html_parser.parse(firstPage.html);
    final pageTitle = _documentTitle(firstDocument);

    final gathered = <RuleAnchor>[];
    final visitedPages = <String>{firstPage.finalUrl};
    var currentPage = firstPage;
    var currentDocument = firstDocument;
    var pagesFetched = 1;
    var truncatedByLimit = false;

    var anchors =
        engine.extractCatalogAnchors(currentDocument, currentPage.finalUrl, rule);
    if (anchors.isEmpty) return null;
    gathered.addAll(anchors);
    final seenUrls = anchors.map((a) => a.url).toSet();

    while (true) {
      var nextPage = engine.extractCatalogNextPage(
        currentDocument,
        currentPage.finalUrl,
        rule,
      );
      nextPage ??= _detectNextPageLink(currentDocument, currentPage.finalUrl);
      if (nextPage == null) break;
      if (pagesFetched >= 5) {
        truncatedByLimit = true;
        break;
      }
      if (!visitedPages.add(nextPage)) break;
      if (hostOf(nextPage) != hostOf(firstPage.finalUrl)) break;

      final pageResult = await _fetch(nextPage, preferredCharset: rule.encoding);
      final pageError = pageResult.errorOrNull;
      if (pageError != null) break;
      final fetched = pageResult.valueOrNull!;
      final document = html_parser.parse(fetched.html);

      anchors = engine.extractCatalogAnchors(document, fetched.finalUrl, rule);
      if (anchors.isEmpty) break;

      final duplicate = anchors.where((a) => seenUrls.contains(a.url)).length;
      if (anchors.isNotEmpty && duplicate / anchors.length > 0.6) break;

      for (final anchor in anchors) {
        if (seenUrls.add(anchor.url)) gathered.add(anchor);
      }
      pagesFetched++;
      currentPage = fetched;
      currentDocument = document;
    }

    if (gathered.length < 2) return null;

    // 规则模式不做书路径聚类/分卷切分（容器已由规则限定，链接即章节）；
    // 分卷标题支持保留给通用层。
    final chapters = <ChapterRef>[];
    final usedUrls = <String>{};
    for (final anchor in gathered) {
      if (!usedUrls.add(anchor.url)) continue;
      chapters.add(ChapterRef(
        order: chapters.length,
        title: anchor.title,
        url: anchor.url,
      ));
    }
    if (chapters.length < 2) return null;

    final titles = chapters.map((c) => c.title).toList();
    final reversed = descendingRatio(titles) >= 0.6;
    final confidence = chapters.length >= 20
        ? Confidence.high
        : (chapters.length >= 5 ? Confidence.medium : Confidence.low);

    return CatalogParseResult(
      pageTitle: pageTitle,
      catalog: ParsedCatalog(
        sourceUrl: firstPage.finalUrl,
        chapters: chapters,
        isLikelyReversed: reversed,
        truncatedByLimit: truncatedByLimit,
        confidence: confidence,
      ),
    );
  }

  @override
  Future<AppResult<ParsedChapterContent>> parseChapterContent(String url) async {
    final normalized = normalizeUrl(url);
    if (normalized == null) {
      return Failure(NetworkError('INVALID_URL', url: redactUrl(url)));
    }
    final rule = await _ruleFor(normalized);
    final firstFetch = await _fetch(normalized, preferredCharset: rule?.encoding);
    final fetchError = firstFetch.errorOrNull;
    if (fetchError != null) return Failure(fetchError);
    final firstPage = firstFetch.valueOrNull!;

    try {
      // —— 站点规则路径：contentSelector 命中且产出正文时直接采用；
      //    否则回退通用管线 ——
      if (rule != null && rule.selectors[RuleSelectorKey.content] != null) {
        final ruled = await _parseChapterContentRuled(firstPage, rule);
        if (ruled != null) return Success(ruled);
      }

      final firstDocument = html_parser.parse(firstPage.html);
      final container = _findContentContainer(firstDocument);
      if (container == null) {
        if (_pageLooksDynamic(firstDocument)) {
          return Failure(ParseError('DYNAMIC_PAGE_REQUIRED',
              '未找到正文容器且页面疑似动态渲染 url=${redactUrl(firstPage.finalUrl)}'));
        }
        return Failure(ParseError('CONTENT_EMPTY',
            '未找到正文容器 url=${redactUrl(firstPage.finalUrl)}'));
      }

      final containerText = _visibleText(container).trim();
      if (containerText.length < 200 && _pageLooksDynamic(firstDocument)) {
        return Failure(ParseError('DYNAMIC_PAGE_REQUIRED',
            '正文候选文本仅 ${containerText.length} 字，页面疑似动态渲染'));
      }

      final blocks = _collectBlocks(container, firstPage.finalUrl);
      if (blocks.isEmpty) {
        if (_pageLooksDynamic(firstDocument)) {
          return Failure(ParseError('DYNAMIC_PAGE_REQUIRED',
              '正文容器无可读内容，页面疑似动态渲染 url=${redactUrl(firstPage.finalUrl)}'));
        }
        return Failure(ParseError('CONTENT_EMPTY',
            '正文容器无可读内容 url=${redactUrl(firstPage.finalUrl)}'));
      }

      final title = _extractChapterTitle(firstDocument);
      final nextChapterUrl = _detectNextChapterLink(firstDocument, firstPage.finalUrl);
      final previousChapterUrl = _detectPreviousChapterLink(firstDocument, firstPage.finalUrl);

      final paragraphTexts = blocks
          .whereType<Paragraph>()
          .map((p) => p.text)
          .where((t) => t.isNotEmpty)
          .toSet();

      var currentDocument = firstDocument;
      var currentUrl = firstPage.finalUrl;
      var pagesFetched = 1;
      var nextPage = _detectNextPageLink(currentDocument, currentUrl);
      var remainingNextPage = nextPage;
      var truncated = false;
      final visitedPages = <String>{firstPage.finalUrl};

      while (nextPage != null) {
        if (pagesFetched >= 10) {
          truncated = true;
          break;
        }
        if (!visitedPages.add(nextPage)) break;
        if (hostOf(nextPage) != hostOf(firstPage.finalUrl)) break;
        if (directoryPrefix(nextPage) != directoryPrefix(firstPage.finalUrl)) break;

        final pageResult = await _fetch(nextPage);
        final pageError = pageResult.errorOrNull;
        if (pageError != null) break;
        final fetched = pageResult.valueOrNull!;
        final document = html_parser.parse(fetched.html);

        // 标题一致性：不一致视为误入下一章
        final pageTitle = _extractChapterTitle(document);
        if (title != null &&
            title.trim().isNotEmpty &&
            pageTitle != null &&
            pageTitle.trim().isNotEmpty &&
            normalizePageTitle(title) != normalizePageTitle(pageTitle)) {
          break;
        }

        final pageContainer = _findContentContainer(document);
        if (pageContainer == null) break;
        final newBlocks = _collectBlocks(pageContainer, fetched.finalUrl);
        if (newBlocks.isEmpty) break;

        final newParagraphs = newBlocks
            .whereType<Paragraph>()
            .map((p) => p.text)
            .where((t) => t.isNotEmpty)
            .toList();
        if (newParagraphs.isNotEmpty) {
          final duplicate = newParagraphs.where(paragraphTexts.contains).length;
          if (duplicate / newParagraphs.length > 0.6) break;
        }

        blocks.addAll(newBlocks);
        paragraphTexts.addAll(newParagraphs);
        pagesFetched++;
        currentDocument = document;
        currentUrl = fetched.finalUrl;
        nextPage = _detectNextPageLink(currentDocument, currentUrl);
        remainingNextPage = nextPage;
      }

      final wordCount = blocks
          .whereType<Paragraph>()
          .fold<int>(0, (sum, p) => sum + p.text.length);

      return Success(ParsedChapterContent(
        sourceUrl: firstPage.finalUrl,
        title: title,
        blocks: blocks,
        nextPageUrl: truncated ? remainingNextPage : null,
        nextChapterUrl: nextChapterUrl,
        previousChapterUrl: previousChapterUrl,
        isTruncated: truncated,
        wordCount: wordCount,
      ));
    } catch (e) {
      return Failure(ParseError('UNSUPPORTED_STRUCTURE', '正文解析异常: $e'));
    }
  }

  /// 站点规则正文解析：contentSelector 容器 + 清洗规则（DOM 层 selector 删除、
  /// 文本层 regex/whitespace）+ chapterTitle/上下章/下一页选择器。
  ///
  /// 分页对齐通用管线语义：重复率 >0.6 终止、跨主机/跨目录前缀终止、
  /// 页数上限 = contentMaxPages（缺省 10，与 Kotlin PaginationMerger 一致）；
  /// 规则未提供 next 页选择器时回退通用"下一页"探测。
  ///
  /// 返回 null = 规则未产出正文（容器无命中/正文为空），调用方回退通用管线。
  Future<ParsedChapterContent?> _parseChapterContentRuled(
    FetchedPage firstPage,
    ParsedSiteRule rule,
  ) async {
    final engine = _ruleEngine;
    if (engine == null) return null;

    final firstDocument = html_parser.parse(firstPage.html);
    final first = engine.extractContent(firstDocument, firstPage.finalUrl, rule);
    if (first == null) return null;

    final maxPages = rule.contentMaxPages ?? 10;
    final blocks = <ContentBlock>[
      ...applyTextCleaning(
        _collectBlocks(first.container, firstPage.finalUrl),
        rule.cleaningRules,
      ),
    ];
    if (blocks.isEmpty) return null;

    final title = first.title ?? _extractChapterTitle(firstDocument);
    final paragraphTexts = blocks
        .whereType<Paragraph>()
        .map((p) => p.text)
        .where((t) => t.isNotEmpty)
        .toSet();

    var pagesFetched = 1;
    var truncated = false;
    var remainingNextPage = first.nextPageUrl;
    final visitedPages = <String>{firstPage.finalUrl};

    var nextPage = first.nextPageUrl ??
        _detectNextPageLink(firstDocument, firstPage.finalUrl);
    while (nextPage != null) {
      if (pagesFetched >= maxPages) {
        truncated = true;
        break;
      }
      if (!visitedPages.add(nextPage)) break;
      if (hostOf(nextPage) != hostOf(firstPage.finalUrl)) break;
      if (directoryPrefix(nextPage) != directoryPrefix(firstPage.finalUrl)) {
        break;
      }

      final pageResult = await _fetch(nextPage, preferredCharset: rule.encoding);
      final pageError = pageResult.errorOrNull;
      if (pageError != null) break;
      final fetched = pageResult.valueOrNull!;
      final document = html_parser.parse(fetched.html);
      final extracted = engine.extractContent(document, fetched.finalUrl, rule);
      if (extracted == null) break;

      // 标题一致性：规则标题存在且与页标题不一致 → 误入下一章，终止分页。
      if (title != null &&
          title.trim().isNotEmpty &&
          extracted.title != null &&
          extracted.title!.trim().isNotEmpty &&
          normalizePageTitle(title) != normalizePageTitle(extracted.title!)) {
        break;
      }

      final pageBlocks = applyTextCleaning(
        _collectBlocks(extracted.container, fetched.finalUrl),
        rule.cleaningRules,
      );
      if (pageBlocks.isEmpty) break;

      final newParagraphs = pageBlocks
          .whereType<Paragraph>()
          .map((p) => p.text)
          .where((t) => t.isNotEmpty)
          .toList();
      if (newParagraphs.isNotEmpty) {
        final duplicate = newParagraphs.where(paragraphTexts.contains).length;
        if (duplicate / newParagraphs.length > 0.6) break;
      }

      blocks.addAll(pageBlocks);
      paragraphTexts.addAll(newParagraphs);
      pagesFetched++;
      nextPage = extracted.nextPageUrl ??
          _detectNextPageLink(document, fetched.finalUrl);
      remainingNextPage = nextPage;
    }

    final wordCount = blocks
        .whereType<Paragraph>()
        .fold<int>(0, (sum, p) => sum + p.text.length);
    if (wordCount == 0) return null;

    return ParsedChapterContent(
      sourceUrl: firstPage.finalUrl,
      title: title,
      blocks: blocks,
      nextPageUrl: truncated ? remainingNextPage : null,
      nextChapterUrl: first.nextChapterUrl,
      previousChapterUrl: first.previousChapterUrl,
      isTruncated: truncated,
      wordCount: wordCount,
    );
  }

  // ============================================================
  // 抓取（带规则 encoding 提示）
  // ============================================================

  /// 按页面域名加载启用中的站点规则（无引擎/无规则/规则损坏 → null）。
  Future<ParsedSiteRule?> _ruleFor(String pageUrl) async {
    final engine = _ruleEngine;
    if (engine == null) return null;
    return engine.loadRule(hostOf(pageUrl));
  }

  Future<AppResult<FetchedPage>> _fetch(
    String url, {
    String? preferredCharset,
  }) {
    return _http.fetch(url, preferredCharset: preferredCharset);
  }

  // ============================================================
  // 详情字段提取
  // ============================================================

  void _extractJsonLd(dom.Document document, Map<String, _Field> fields) {
    for (final script in document.querySelectorAll('script')) {
      final type = (script.attributes['type'] ?? '').toLowerCase().trim();
      if (type != 'application/ld+json') continue;
      final raw = script.text.trim();
      if (raw.isEmpty) continue;
      dynamic root;
      try {
        root = jsonDecode(raw);
      } catch (_) {
        continue;
      }
      _walkJsonObjects(root, (object) {
        if (!_isBookObject(object)) return;
        _putField(fields, 'title',
            _jsonString(object['name']) ??
                _jsonString(object['bookName']) ??
                _jsonString(object['alternateName']),
            3);
        _putField(fields, 'author', _jsonAuthor(object['author']), 3);
        _putField(fields, 'cover', _jsonImage(object['image']), 3);
        _putField(fields, 'description', _jsonString(object['description']), 3);
      });
    }
  }

  void _extractOpenGraph(dom.Document document, Map<String, _Field> fields) {
    final metas = _metaMap(document);
    _putField(fields, 'title',
        metas['og:novel:book_name'] ?? metas['og:title'], 2);
    _putField(fields, 'author', metas['og:novel:author'], 2);
    _putField(fields, 'cover', metas['og:image'], 2);
    _putField(fields, 'description', metas['og:description'], 2);
  }

  void _extractMeta(dom.Document document, Map<String, _Field> fields) {
    final metas = _metaMap(document);
    _putField(fields, 'title', metas['book_name'] ?? metas['bookname'], 2);
    _putField(fields, 'author', metas['author'], 2);
    _putField(fields, 'description', metas['description'], 2);
  }

  void _extractGenericDetail(
    dom.Document document,
    String pageUrl,
    Map<String, _Field> fields,
  ) {
    // 标题：第一个非空 h1/h2，其次 <title>
    for (final selector in const ['h1', 'h2']) {
      final element = document.querySelector(selector);
      final text = element?.text.trim();
      if (text != null && text.isNotEmpty) {
        _putField(fields, 'title', text, 1);
        break;
      }
    }
    _putField(fields, 'title', _documentTitle(document), 1);

    final bodyText = document.body?.text ?? '';
    final authorMatch = RegExp(r'作者[：:]\s*([\u4e00-\u9fa5A-Za-z0-9_]{1,20})')
        .firstMatch(bodyText);
    if (authorMatch != null) {
      _putField(fields, 'author', authorMatch.group(1), 1);
    }

    for (final img in document.querySelectorAll('img')) {
      final src = (img.attributes['src'] ?? '').trim();
      if (src.isEmpty) continue;
      final marker = '${img.attributes['id'] ?? ''} ${img.attributes['class'] ?? ''}';
      if (RegExp('cover', caseSensitive: false).hasMatch(marker)) {
        _putField(fields, 'cover', resolveUrl(pageUrl, src), 1);
        break;
      }
    }

    final descSelectors = [
      '[id*=intro]', '[class*=intro]', '[id*=description]',
      '[class*=description]', '[class*=desc]',
    ];
    for (final selector in descSelectors) {
      for (final element in document.querySelectorAll(selector)) {
        final text = element.text.trim();
        if (text.length >= 10 && text.length <= 3000) {
          _putField(fields, 'description', text, 1);
          break;
        }
      }
      if (fields['description'] != null) break;
    }
  }

  void _putField(
    Map<String, _Field> fields,
    String key,
    String? value,
    int priority,
  ) {
    if (value == null) return;
    final trimmed = value.trim();
    if (trimmed.isEmpty) return;
    final existing = fields[key];
    if (existing == null || priority > existing.priority) {
      fields[key] = _Field(trimmed, priority);
    }
  }

  /// 站点规则详情字段（对齐 Kotlin DetailParseStep 层序语义的优先级表达）：
  /// manual.* 规则 = 4（用户意图最高）；非人工站点规则 = 3（与 JSON-LD 同级，
  /// 结构化层先写入故同级不覆盖，即“冲突取先到”）。
  void _extractRuleDetail(
    dom.Document document,
    String pageUrl,
    ParsedSiteRule rule,
    Map<String, _Field> fields,
  ) {
    final engine = _ruleEngine;
    if (engine == null) return;
    final extract = engine.extractDetail(document, pageUrl, rule);
    final priority = rule.isManual ? 4 : 3;
    _putField(fields, 'title', extract.title, priority);
    _putField(fields, 'author', extract.author, priority);
    _putField(fields, 'cover', extract.cover, priority);
    _putField(fields, 'description', extract.description, priority);
  }

  String? _pickCatalogLink(dom.Document document, String pageUrl) {
    final pageHost = hostOf(pageUrl);
    String? fallback;
    for (final anchor in document.querySelectorAll('a')) {
      final text = anchor.text.trim();
      if (text.isEmpty || text.length > 30) continue;
      final containsCatalogText = text.contains('目录') ||
          text.contains('章节列表') ||
          text.contains('全部章节') ||
          text.contains('作品目录');
      if (!containsCatalogText) continue;
      final href = (anchor.attributes['href'] ?? '').trim();
      if (href.isEmpty || href.toLowerCase().startsWith('javascript')) continue;
      final absolute = resolveUrl(pageUrl, href);
      if (absolute == null) continue;
      if (pageHost != null && hostOf(absolute) != pageHost) continue;
      final path = Uri.tryParse(absolute)?.path.toLowerCase() ?? '';
      final pathLooksCatalog = path.contains('catalog') ||
          path.contains('list') ||
          path.contains('mulu') ||
          path.contains('chapter');
      if (pathLooksCatalog) return absolute;
      fallback ??= absolute;
    }
    return fallback;
  }

  // ============================================================
  // 目录提取
  // ============================================================

  List<_ChapterAnchor> _extractChapterAnchors(
    dom.Document document,
    String pageUrl,
    String baseHost,
  ) {
    final result = <_ChapterAnchor>[];
    final normalizedPageUrl = normalizeUrl(pageUrl) ?? pageUrl;
    for (final anchor in document.querySelectorAll('a')) {
      final text = anchor.text.trim();
      if (text.isEmpty || text.length > 80) continue;
      final href = (anchor.attributes['href'] ?? '').trim();
      if (href.isEmpty || href == '#' || href.toLowerCase().startsWith('javascript')) {
        continue;
      }
      final absolute = resolveUrl(pageUrl, href);
      if (absolute == null) continue;
      if (hostOf(absolute) != baseHost) continue;
      if (absolute == normalizedPageUrl) continue;
      if (isPageNavText(text)) continue;
      if (isNonChapterPath(absolute)) continue;
      if (!matchesChapterPattern(text, absolute) && !isVolumeAnchor(absolute)) {
        continue;
      }
      result.add(_ChapterAnchor(
        title: text.length > 120 ? text.substring(0, 120) : text,
        url: absolute,
        element: anchor,
      ));
    }
    return result;
  }

  List<ChapterRef> _buildChapterRefs(List<_ChapterAnchor> anchors) {
    if (anchors.length < 2) return const [];

    // 书路径段聚类：占比 ≥60% 的目录前缀簇优先。
    final clusters = <String, List<_ChapterAnchor>>{};
    for (final anchor in anchors) {
      final prefix = directoryPrefix(anchor.url);
      if (prefix == null) continue;
      clusters.putIfAbsent(prefix, () => []).add(anchor);
    }
    var kept = anchors;
    if (clusters.isNotEmpty) {
      final dominant = clusters.entries.reduce(
        (a, b) => a.value.length >= b.value.length ? a : b,
      );
      if (dominant.value.length >= 2 &&
          dominant.value.length * 10 >= anchors.length * 6) {
        kept = dominant.value;
      }
    }

    final chapters = <ChapterRef>[];
    final seen = <String>{};
    String? currentVolume;
    for (final anchor in kept) {
      if (isVolumeAnchor(anchor.url)) {
        if (anchor.title.isNotEmpty) currentVolume = anchor.title;
        continue;
      }
      if (!seen.add(anchor.url)) continue;
      final volume = _structuralVolumeTitle(anchor.element) ?? currentVolume;
      chapters.add(ChapterRef(
        order: chapters.length,
        title: anchor.title,
        url: anchor.url,
        volumeTitle: volume,
      ));
    }
    return chapters;
  }

  String? _structuralVolumeTitle(dom.Element link) {
    dom.Element? node = link;
    var depth = 0;
    while (node != null && depth < 4) {
      var previous = node.previousElementSibling;
      while (previous != null) {
        if (_volumeTags.contains((previous.localName ?? '').toLowerCase())) {
          final text = previous.text.trim();
          if (text.isNotEmpty && text.length <= 60 && text != link.text.trim()) {
            return text;
          }
        }
        previous = previous.previousElementSibling;
      }
      final parent = node.parent;
      if (parent is dom.Element &&
          const {'dd', 'li', 'div', 'ul', 'dl'}.contains((parent.localName ?? '').toLowerCase())) {
        node = parent;
      } else {
        break;
      }
      depth++;
    }
    return null;
  }

  String? _detectNextPageLink(dom.Document document, String pageUrl) {
    return _detectNavLink(
      document,
      pageUrl,
      includes: const ['下一页', '下页', '下一頁', 'next', '›', '»'],
      excludes: const ['上一页', '上一頁'],
    );
  }

  String? _detectNextChapterLink(dom.Document document, String pageUrl) {
    return _detectNavLink(
      document,
      pageUrl,
      includes: const ['下一章', '下一节', 'next chapter'],
      excludes: const ['下一页', '下一頁'],
    );
  }

  String? _detectPreviousChapterLink(dom.Document document, String pageUrl) {
    return _detectNavLink(
      document,
      pageUrl,
      includes: const ['上一章', '上一节', 'previous chapter'],
      excludes: const ['上一页', '上一頁'],
    );
  }

  String? _detectNavLink(
    dom.Document document,
    String pageUrl, {
    required List<String> includes,
    required List<String> excludes,
  }) {
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
  // 正文容器与内容块
  // ============================================================

  dom.Element? _findContentContainer(dom.Document document) {
    dom.Element? best;
    var bestScore = double.negativeInfinity;

    final candidates = document.querySelectorAll('div, article, section, td, dd, main');
    for (final element in candidates) {
      final text = _visibleText(element).trim();
      if (text.length < 60) continue;
      final paragraphs = element.querySelectorAll('p').length;
      final breaks = element.querySelectorAll('br').length.clamp(0, 20);
      var linkTextLength = 0;
      for (final link in element.querySelectorAll('a')) {
        linkTextLength += link.text.length;
      }
      final linkDensity = text.isEmpty ? 1.0 : linkTextLength / text.length;
      var score = (paragraphs + breaks) * 2.0 + text.length / 100.0 - linkDensity * 50.0;

      final marker =
          '${element.attributes['class'] ?? ''} ${element.attributes['id'] ?? ''}'
              .toLowerCase();
      if (RegExp(r'(content|article|read|chapter|正文)').hasMatch(marker)) {
        score += 30;
      }
      if (_noiseClassRe.hasMatch(marker)) score -= 60;
      final tag = (element.localName ?? '').toLowerCase();
      if (tag == 'article' || tag == 'main') score += 10;

      if (score > bestScore) {
        bestScore = score;
        best = element;
      }
    }

    return best;
  }

  List<ContentBlock> _collectBlocks(dom.Element container, String pageUrl) {
    final blocks = <ContentBlock>[];
    final buffer = StringBuffer();

    void flush() {
      final text = _cleanParagraph(buffer.toString());
      buffer.clear();
      if (text.isNotEmpty) blocks.add(Paragraph(text));
    }

    void walk(dom.Node node) {
      if (node is dom.Text) {
        buffer.write(node.text);
        return;
      }
      if (node is! dom.Element) return;
      final tag = (node.localName ?? '').toLowerCase();
      if (_noiseTags.contains(tag)) return;
      if (_isNoiseElement(node)) return;

      if (tag == 'br') {
        flush();
        return;
      }
      if (tag == 'img') {
        flush();
        final src = (node.attributes['src'] ?? '').trim();
        if (src.isNotEmpty) {
          final absolute = resolveUrl(pageUrl, src);
          if (absolute != null && !absolute.toLowerCase().startsWith('javascript')) {
            final alt = _nonEmpty(node.attributes['alt']);
            blocks.add(ImageBlock(absolute, alt));
          }
        }
        return;
      }
      if (_blockTags.contains(tag)) {
        flush();
        for (final child in node.nodes) {
          walk(child);
        }
        flush();
        return;
      }
      for (final child in node.nodes) {
        walk(child);
      }
    }

    for (final child in container.nodes) {
      walk(child);
    }
    flush();
    return blocks;
  }

  bool _isNoiseElement(dom.Element element) {
    final marker =
        '${element.attributes['class'] ?? ''} ${element.attributes['id'] ?? ''}'.trim();
    if (marker.isEmpty) return false;
    return _noiseClassRe.hasMatch(marker);
  }

  String _cleanParagraph(String raw) {
    var text = raw.replaceAll('\u00A0', ' ');
    text = text.replaceAll(RegExp(r'\s+'), ' ').trim();
    if (text.isEmpty) return '';
    for (final phrase in _adLinePhrases) {
      if (text.length < 200 && text.contains(phrase)) return '';
    }
    return text;
  }

  String? _extractChapterTitle(dom.Document document) {
    final h1 = document.querySelector('h1');
    final h1Text = h1?.text.trim();
    if (h1Text != null && h1Text.isNotEmpty && h1Text.length <= 120) {
      return h1Text;
    }
    final title = _documentTitle(document);
    if (title == null) return null;
    return normalizePageTitle(title);
  }

  bool _pageLooksDynamic(dom.Document document) {
    var scriptsLength = 0;
    for (final script in document.querySelectorAll('script')) {
      scriptsLength += script.text.length;
    }
    final body = document.body;
    final bodyText = body == null ? '' : _visibleText(body).trim();
    if (scriptsLength > 0 &&
        bodyText.length < 200 &&
        scriptsLength > bodyText.length * 2) {
      return true;
    }
    final shell = document.querySelector('#app, #root, [id*=app], [data-v-app]');
    if (shell != null &&
        _visibleText(shell).trim().isEmpty &&
        scriptsLength > 0) {
      return true;
    }
    return false;
  }

  /// 可见文本（排除 script/style/iframe/noscript/title 等非正文节点）。
  String _visibleText(dom.Node root) {
    final buffer = StringBuffer();

    void walk(dom.Node node) {
      if (node is dom.Text) {
        buffer.write(node.text);
        return;
      }
      if (node is! dom.Element) return;
      final tag = (node.localName ?? '').toLowerCase();
      if (_noiseTags.contains(tag) || tag == 'title') return;
      for (final child in node.nodes) {
        walk(child);
      }
    }

    walk(root);
    return buffer.toString();
  }

  // ============================================================
  // 小工具
  // ============================================================

  Map<String, String> _metaMap(dom.Document document) {
    final result = <String, String>{};
    for (final meta in document.querySelectorAll('meta')) {
      final key = (meta.attributes['property'] ?? meta.attributes['name'] ?? '')
          .trim()
          .toLowerCase();
      final content = meta.attributes['content']?.trim();
      if (key.isEmpty || content == null || content.isEmpty) continue;
      result.putIfAbsent(key, () => content);
    }
    return result;
  }

  String? _documentTitle(dom.Document document) {
    final text = document.querySelector('title')?.text.trim();
    return (text == null || text.isEmpty) ? null : text;
  }

  String? _absolutize(String baseUrl, String? raw) {
    if (raw == null || raw.trim().isEmpty) return null;
    return resolveUrl(baseUrl, raw.trim()) ?? raw.trim();
  }

  String? _nonEmpty(String? value) {
    final trimmed = value?.trim();
    return (trimmed == null || trimmed.isEmpty) ? null : trimmed;
  }

  bool _isBookObject(Map<String, dynamic> object) {
    final type = object['@type'];
    final types = <String>[];
    if (type is String) {
      types.add(type);
    } else if (type is List) {
      for (final item in type) {
        if (item is String) types.add(item);
      }
    }
    if (types.any((t) => t.toLowerCase() == 'book' || t.toLowerCase() == 'novel')) {
      return true;
    }
    // 仅含 name 的对象多为嵌套 author/publisher，不能当作书籍主对象；
// 需要 bookName/author，或 name 同时带 image/description。
    if (object.containsKey('bookName') || object.containsKey('author')) {
      return true;
    }
    return object.containsKey('name') &&
        (object.containsKey('image') || object.containsKey('description'));
  }

  void _walkJsonObjects(dynamic root, void Function(Map<String, dynamic>) visit) {
    if (root is Map) {
      final object = <String, dynamic>{};
      root.forEach((key, value) => object[key.toString()] = value);
      visit(object);
      for (final value in root.values) {
        _walkJsonObjects(value, visit);
      }
    } else if (root is List) {
      for (final item in root) {
        _walkJsonObjects(item, visit);
      }
    }
  }

  String? _jsonString(dynamic value) {
    if (value is String) {
      final trimmed = value.trim();
      return trimmed.isEmpty ? null : trimmed;
    }
    if (value is num) return value.toString();
    return null;
  }

  String? _jsonAuthor(dynamic value) {
    if (value is String) return _jsonString(value);
    if (value is Map) {
      return _jsonString(value['name']);
    }
    if (value is List) {
      for (final item in value) {
        final author = _jsonAuthor(item);
        if (author != null) return author;
      }
    }
    return null;
  }

  String? _jsonImage(dynamic value) {
    if (value is String) return _jsonString(value);
    if (value is List) {
      for (final item in value) {
        final image = _jsonImage(item);
        if (image != null) return image;
      }
    }
    if (value is Map) {
      return _jsonString(value['url']);
    }
    return null;
  }
}

// ---------------- 内部类型 ----------------

class _Field {
  const _Field(this.value, this.priority);

  final String value;
  final int priority;
}

class _ChapterAnchor {
  const _ChapterAnchor({
    required this.title,
    required this.url,
    required this.element,
  });

  final String title;
  final String url;
  final dom.Element element;
}
