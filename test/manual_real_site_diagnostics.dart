import 'package:flutter_test/flutter_test.dart';
import 'package:jianyue_flutter/core/app_result.dart';
import 'package:jianyue_flutter/impl/parser_impl.dart';
import 'package:jianyue_flutter/impl/resolver_impl.dart';
import 'package:jianyue_flutter/impl/secure_http_impl.dart';

/// 临时诊断（不纳入交付）：真实站点解析链路逐环节观察
void main() {
  test('real site diagnostics', () async {
    final http = SecureHttpClientImpl();
    final parser = ParserEngineImpl(http);
    final resolver = BookResolverImpl(http, parser);
    const url = 'https://www.linovelib.com/novel/5544.html';

    final page = await http.fetch(url);
    switch (page) {
      case Success(:final value):
        print('FETCH OK: ${value.statusCode} len=${value.html.length} charset=${value.charset}');
        print('HEAD: ${value.html.substring(0, value.html.length > 200 ? 200 : value.html.length)}');
      case Failure(:final error):
        print('FETCH FAIL: ${error.runtimeType} $error ${error.debugDetail}');
        return;
    }

    final detail = await parser.parseDetail(url);
    switch (detail) {
      case Success(:final value):
        print('DETAIL OK: title=${value.title} author=${value.author} catalog=${value.catalogUrl} conf=${value.confidence}');
      case Failure(:final error):
        print('DETAIL FAIL: ${error.runtimeType} $error ${error.debugDetail}');
    }

    final catalog = await parser.parseCatalog('https://www.linovelib.com/novel/5544/catalog');
    switch (catalog) {
      case Success(:final value):
        print('CATALOG OK: ${value.chapters.length} chapters first=${value.chapters.take(3).map((c) => c.title).toList()}');
      case Failure(:final error):
        print('CATALOG FAIL: ${error.runtimeType} $error ${error.debugDetail}');
    }

    final res = await resolver.resolve(url);
    switch (res) {
      case Success(:final value):
        print('RESOLVE OK: kind=${value.kind} requiresHint=${value.requiresCatalogHint} detail=${value.detail?.title} catalog=${value.catalog?.chapters.length} evidence=${value.evidence}');
      case Failure(:final error):
        print('RESOLVE FAIL: ${error.runtimeType} $error ${error.debugDetail}');
    }
  }, timeout: const Timeout(Duration(minutes: 2)));
}