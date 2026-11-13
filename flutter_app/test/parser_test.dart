import 'package:flutter_test/flutter_test.dart';
import 'package:jianyue_flutter/contracts.dart';
import 'package:jianyue_flutter/core/app_result.dart';
import 'package:jianyue_flutter/core/reader_error.dart';
import 'package:jianyue_flutter/impl/parser_impl.dart';

class _FakeHttp implements SecureHttpClient {
  _FakeHttp(this.pages);

  final Map<String, FetchedPage> pages;
  final Map<String, ReaderError> errors = {};
  int calls = 0;

  @override
  Future<AppResult<FetchedPage>> fetch(
    String url, {
    Map<String, String>? headers,
    String? preferredCharset,
  }) async {
    calls++;
    final error = errors[url];
    if (error != null) return Failure(error);
    final page = pages[url];
    if (page == null) {
      return Failure(NetworkError(
        'HTTP_ERROR_STATUS',
        url: url,
        statusCode: 404,
        debugDetail: 'fixture 未提供该 URL',
      ));
    }
    return Success(page);
  }
}

FetchedPage _page(String url, String html) {
  return FetchedPage(
    finalUrl: url,
    html: html,
    charset: 'utf-8',
    statusCode: 200,
  );
}

const String _para1 =
    '这是第一段测试正文，用于验证正文容器评分与段落抽取逻辑。'
    '主角推开门，看到庭院里落满了树叶，远处传来钟声，空气里有雨后泥土的味道。'
    '他握紧手中的信，知道今天之后一切都将不同。';
const String _para2 =
    '第二段继续描写场景与人物心理，字数需要足够长，才能让正文容器的评分超过导航栏。'
    '风从窗缝里钻进来，吹动桌上的纸页，他想起多年前的约定，也想起那些没有说出口的话。';
const String _para3 =
    '第三段是正文的收束，包含对白与动作描写。他抬起头，看见门外站着一个人，那人没有说话。'
    '两个人在昏暗的走廊里对视，时间仿佛停顿了一瞬，随后脚步声慢慢远去。';
const String _para4 =
    '分页后的第四段正文，用来验证正文分页合并逻辑。页码不同但标题一致时应当继续合并，'
    '重复率超过阈值时应当停止，避免把同一页内容重复写入。';
const String _para5 =
    '分页后的第五段正文，继续补充情节。窗外天色渐亮，远处传来第一声鸟鸣，'
    '他终于合上那本旧书，把信折好放进衣袋，起身走向门口。';

const String _detailHtml = '''
<!DOCTYPE html>
<html>
<head>
  <title>测试之书 - 示例站</title>
  <meta property="og:title" content="OG 测试之书">
  <script type="application/ld+json">
  {"@context":"https://schema.org","@type":"Book","name":"测试之书",
   "author":{"@type":"Person","name":"张三"},
   "image":"/images/cover.jpg",
   "description":"这是一本用于测试解析器的书。"}
  </script>
</head>
<body>
  <h1>测试之书</h1>
  <a href="/book/1/list">目录</a>
</body>
</html>
''';

const String _catalogHtml = '''
<!DOCTYPE html>
<html>
<head><title>测试之书 目录 - 示例站</title></head>
<body>
  <div class="filter-bar">
    <a href="/wenku/dengekibunko/1.html">电击文库</a>
    <a href="/class/1.html">分类</a>
    <a href="/rank/1.html">排行</a>
  </div>
  <div class="breadcrumb">
    <a href="/book/1/">书籍详情</a>
    <a href="/">首页</a>
  </div>
  <div class="chapter-list">
    <a href="/book/1/1001.html">第1章 开端</a>
    <a href="/book/1/1002.html">第2章 冲突</a>
    <a href="/book/1/1003.html">第3章 尾声</a>
    <a href="1004.html">第4章 新章</a>
    <a href="/book/1/1001.html">第1章 开端</a>
    <a href="javascript:void(0)">第5章 无效</a>
  </div>
</body>
</html>
''';

const String _chapterPage1Html = '''
<!DOCTYPE html>
<html>
<head><title>第1章 开端_测试之书_示例站</title></head>
<body>
  <h1>第1章 开端</h1>
  <div class="content" id="content">
    <p>$_para1</p>
    <p>$_para2</p>
    <img src="/img/1.jpg" alt="插图">
    <div class="ad-banner">这里是广告，请勿阅读。</div>
    <p>$_para3</p>
  </div>
  <div class="nav">
    <a href="/read/1/0.html">上一章</a>
    <a href="/read/1/2.html">下一章</a>
    <a href="/read/1/1_2.html">下一页</a>
  </div>
</body>
</html>
''';

const String _chapterPage2Html = '''
<!DOCTYPE html>
<html>
<head><title>第1章 开端（第2页）_测试之书_示例站</title></head>
<body>
  <h1>第1章 开端（第2页）</h1>
  <div id="content">
    <p>$_para4</p>
    <p>$_para5</p>
  </div>
</body>
</html>
''';

String _dynamicHtml() {
  final script = 'console.log("shell");' * 400;
  return '''
<!DOCTYPE html>
<html>
<head><title>第1章 动态</title></head>
<body>
  <div id="app"></div>
  <script>$script</script>
</body>
</html>
''';
}

void main() {
  group('classify', () {
    final parser = ParserEngineImpl(_FakeHttp({}));

    test('章节页/目录页/详情页 URL 启发式', () async {
      final chapter = await parser.classify('http://example.com/book/1/1001.html');
      expect(chapter.valueOrNull!.kind, PageKind.chapter);

      final catalog = await parser.classify('http://example.com/book/1/list');
      expect(catalog.valueOrNull!.kind, PageKind.catalog);

      final detail = await parser.classify('http://example.com/book/1/');
      expect(detail.valueOrNull!.kind, PageKind.bookDetail);

      final unknown = await parser.classify('http://example.com/about');
      expect(unknown.valueOrNull!.kind, PageKind.unknown);
      expect(unknown.valueOrNull!.confidence, Confidence.low);
    });

    test('非法 URL 返回 INVALID_URL', () async {
      final result = await parser.classify('javascript:void(0)');
      expect(result.errorOrNull, isA<NetworkError>());
      expect((result.errorOrNull! as NetworkError).failure, 'INVALID_URL');
    });
  });

  group('parseDetail', () {
    test('JSON-LD 优先，封面绝对化，识别目录链接', () async {
      final http = _FakeHttp({
        'http://example.com/book/1': _page(
          'http://example.com/book/1/',
          _detailHtml,
        ),
      });
      final parser = ParserEngineImpl(http);

      final result = await parser.parseDetail('http://example.com/book/1/');
      final detail = result.valueOrNull!;
      expect(detail.title, '测试之书');
      expect(detail.author, '张三');
      expect(detail.coverUrl, 'http://example.com/images/cover.jpg');
      expect(detail.description, '这是一本用于测试解析器的书。');
      expect(detail.catalogUrl, 'http://example.com/book/1/list');
      expect(detail.confidence, Confidence.high);
      expect(http.calls, 1);
    });

    test('无书名结构返回 DETAIL_INCOMPLETE', () async {
      final http = _FakeHttp({
        'http://example.com/book/9': _page(
          'http://example.com/book/9/',
          '<html><head><title></title></head><body><div>无内容</div></body></html>',
        ),
      });
      final parser = ParserEngineImpl(http);
      final result = await parser.parseDetail('http://example.com/book/9/');
      expect(result.errorOrNull, isA<ParseError>());
      expect((result.errorOrNull! as ParseError).failure, 'DETAIL_INCOMPLETE');
    });
  });

  group('parseCatalog', () {
    test('排除筛选栏/面包屑污染，保留章节并处理相对链接与去重', () async {
      final http = _FakeHttp({
        'http://example.com/book/1/list': _page(
          'http://example.com/book/1/list',
          _catalogHtml,
        ),
      });
      final parser = ParserEngineImpl(http);

      final result = await parser.parseCatalog('http://example.com/book/1/list');
      final catalog = result.valueOrNull!;
      expect(catalog.chapters.length, 4);
      expect(catalog.chapters.map((c) => c.title).toList(), [
        '第1章 开端',
        '第2章 冲突',
        '第3章 尾声',
        '第4章 新章',
      ]);
      expect(catalog.chapters[3].url, 'http://example.com/book/1/1004.html');
      expect(
        catalog.chapters.any((c) => c.url.contains('/wenku/')),
        isFalse,
      );
      expect(catalog.chapters.any((c) => c.title == '书籍详情'), isFalse);
      expect(catalog.isLikelyReversed, isFalse);
      expect(catalog.chapters.map((c) => c.order).toList(), [0, 1, 2, 3]);
    });
  });

  group('parseChapterContent', () {
    test('正文容器评分、广告清除、图片绝对化与分页合并', () async {
      final http = _FakeHttp({
        'http://example.com/read/1/1.html': _page(
          'http://example.com/read/1/1.html',
          _chapterPage1Html,
        ),
        'http://example.com/read/1/1_2.html': _page(
          'http://example.com/read/1/1_2.html',
          _chapterPage2Html,
        ),
      });
      final parser = ParserEngineImpl(http);

      final result =
          await parser.parseChapterContent('http://example.com/read/1/1.html');
      final content = result.valueOrNull!;
      expect(content.title, '第1章 开端');
      expect(content.nextChapterUrl, 'http://example.com/read/1/2.html');
      expect(content.previousChapterUrl, 'http://example.com/read/1/0.html');
      expect(content.nextPageUrl, isNull);
      expect(content.isTruncated, isFalse);

      final paragraphs = content.blocks.whereType<Paragraph>().toList();
      expect(paragraphs.length, 5);
      expect(paragraphs.map((p) => p.text).toList(), [
        _para1,
        _para2,
        _para3,
        _para4,
        _para5,
      ]);
      expect(content.blocks.whereType<ImageBlock>().length, 1);
      expect(
        content.blocks.whereType<ImageBlock>().first.url,
        'http://example.com/img/1.jpg',
      );
      expect(
        content.blocks.whereType<Paragraph>().any(
              (p) => p.text.contains('这里是广告'),
            ),
        isFalse,
      );
      expect(content.wordCount, greaterThan(300));
      expect(
        content.wordCount,
        paragraphs.fold<int>(0, (sum, p) => sum + p.text.length),
      );
      expect(http.calls, 2);
    });

    test('动态壳页面 → DYNAMIC_PAGE_REQUIRED', () async {
      final http = _FakeHttp({
        'http://example.com/read/2/1.html': _page(
          'http://example.com/read/2/1.html',
          _dynamicHtml(),
        ),
      });
      final parser = ParserEngineImpl(http);
      final result =
          await parser.parseChapterContent('http://example.com/read/2/1.html');
      expect(result.errorOrNull, isA<ParseError>());
      expect((result.errorOrNull! as ParseError).failure, 'DYNAMIC_PAGE_REQUIRED');
    });
  });
}