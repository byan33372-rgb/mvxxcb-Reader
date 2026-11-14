import 'package:flutter_test/flutter_test.dart';
import 'package:jianyue_flutter/impl/url_utils.dart';

void main() {
  group('URL 规范化', () {
    test('小写 scheme/host、去默认端口/fragment/跟踪参数、去尾斜杠', () {
      final normalized = normalizeUrl(
        '  HTTP://Example.COM:80/a/b/?utm_source=wx&id=1&from=share#frag  ',
      );
      expect(normalized, 'http://example.com/a/b?id=1');
    });

    test('保留业务 query，删除 utm_/share_ 前缀参数', () {
      final normalized = normalizeUrl(
        'https://example.com/read?chapter=12&utm_campaign=x&share_from=y',
      );
      expect(normalized, 'https://example.com/read?chapter=12');
    });

    test('相对链接基于基址解析为绝对 URL', () {
      expect(
        normalizeUrl('100.html', baseUrl: 'http://example.com/book/1/'),
        'http://example.com/book/1/100.html',
      );
      expect(
        normalizeUrl('/read/1/2.html', baseUrl: 'http://example.com/book/1/'),
        'http://example.com/read/1/2.html',
      );
    });

    test('空 path 补 /，非根路径去尾斜杠，规范化幂等', () {
      final once = normalizeUrl('https://example.com');
      expect(once, 'https://example.com/');
      expect(normalizeUrl(once!), once);
      final twice = normalizeUrl('https://example.com/a/');
      expect(twice, 'https://example.com/a');
      expect(normalizeUrl(twice!), twice);
    });

    test('非 http/https 与非法 URL 返回 null', () {
      expect(normalizeUrl('javascript:void(0)'), isNull);
      expect(normalizeUrl('ftp://example.com/a'), isNull);
      expect(normalizeUrl(''), isNull);
    });

    test('hostOf / directoryPrefix', () {
      expect(hostOf('HTTP://Example.com/a'), 'example.com');
      expect(hostOf('not a url'), isNull);
      expect(
        directoryPrefix('http://example.com/book/1/2.html?x=1'),
        'http://example.com/book/1/',
      );
      expect(directoryPrefix('http://example.com'), 'http://example.com/');
    });
  });

  group('标题指纹', () {
    test('章节编号空白差异归一后指纹一致', () {
      expect(
        titleFingerprint('第 12 章 开端'),
        titleFingerprint('第12章 开端'),
      );
      expect(
        titleFingerprint('第12章 开端'),
        isNot(titleFingerprint('第13章 开端')),
      );
    });

    test('全角/装饰/书名号归一', () {
      expect(
        titleFingerprint('《测试之书》 正文'),
        titleFingerprint('测试之书'),
      );
    });

    test('指纹为 SHA-256 前 32 hex，空标题为空串', () {
      final fingerprint = titleFingerprint('第1章 开端');
      expect(fingerprint.length, 32);
      expect(RegExp(r'^[0-9a-f]{32}$').hasMatch(fingerprint), isTrue);
      expect(titleFingerprint('   '), '');
    });
  });

  group('章节锚点辅助', () {
    test('章节编号提取（阿拉伯/中文数字）', () {
      expect(extractChapterNumber('第12章 开端'), 12);
      expect(extractChapterNumber('第一百二十三章 终局'), 123);
      expect(extractChapterNumber('序章'), isNull);
    });

    test('非章节路径/分卷锚/导航文本判定', () {
      expect(isNonChapterPath('http://x.com/wenku/1.html'), isTrue);
      expect(isNonChapterPath('http://x.com/book/1/2.html'), isFalse);
      expect(isVolumeAnchor('http://x.com/book/1/vol_2.html'), isTrue);
      expect(isVolumeAnchor('http://x.com/book/1/2.html'), isFalse);
      expect(isPageNavText('下一页'), isTrue);
      expect(isPageNavText('第1章 开端'), isFalse);
      expect(matchesChapterPattern('第1章 开端', 'http://x.com/a'), isTrue);
      expect(matchesChapterPattern('随机文本', 'http://x.com/a/123.html'), isTrue);
    });

    test('页标题归一与倒序检测', () {
      expect(normalizePageTitle('第1章 开端（第2页）'), '第1章 开端');
      expect(normalizePageTitle('第1章 开端_2'), '第1章 开端');
      expect(
        descendingRatio(['第3章 丙', '第2章 乙', '第1章 甲']),
        greaterThan(0.9),
      );
      expect(
        descendingRatio(['第1章 甲', '第2章 乙', '第3章 丙']),
        0,
      );
    });

    test('文件名清洗', () {
      expect(sanitizeFileName(' 我的/书:名? '), '我的_书_名');
      expect(sanitizeFileName(''), '');
      expect(sanitizeFileName('a' * 100).length, 80);
    });
  });
}
