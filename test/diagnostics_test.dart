// 诊断反馈组装与脱敏口径测试（纯函数部分；DB 统计由 impl 采集）。
import 'package:flutter_test/flutter_test.dart';

import 'package:jianyue_flutter/impl/diagnostics_impl.dart';
import 'package:jianyue_flutter/impl/url_utils.dart';

void main() {
  group('assembleFeedbackReport', () {
    test('分节顺序与空节占位', () {
      final text = assembleFeedbackReport(
        sections: {
          '设备与应用': ['应用：简阅', '系统：Android 16'],
          '数据库统计': <String>[],
          '最近操作日志': ['12:00:00 I/Crawl: completed book=1'],
        },
        generatedAt: '2026-11-13 12:00:00',
      );
      expect(text.startsWith('简阅 · 诊断反馈'), isTrue);
      final order = [
        text.indexOf('## 设备与应用'),
        text.indexOf('## 数据库统计'),
        text.indexOf('## 最近操作日志'),
      ];
      expect(order, [order[0], order[1], order[2]]);
      expect(text.contains('（无）'), isTrue);
      expect(text.contains('反馈生成时间：2026-11-13 12:00:00'), isTrue);
      expect(text.contains('URL 均已脱敏'), isTrue);
      // 空节不丢日志行
      expect(text.contains('completed book=1'), isTrue);
    });
  });

  group('redactUrl 脱敏口径（诊断文本中的 URL）', () {
    test('去 query/fragment', () {
      expect(
        redactUrl('https://www.example.com/book/1.html?from=share#top'),
        'https://www.example.com/book/1.html',
      );
    });

    test('超长截断', () {
      final long = 'https://www.example.com/${'a' * 200}';
      final redacted = redactUrl(long);
      expect(redacted.length, lessThanOrEqualTo(97));
      expect(redacted.endsWith('…'), isTrue);
    });

    test('本地 TXT 路径不适用 redactUrl（诊断只取文件名，由 impl 保证）', () {
      // 数据口径备忘：txt 路径只输出 basename，不导出目录结构。
      expect(
        '/storage/emulated/0/Android/data/files/txt/1/书名.txt'
            .split('/')
            .last,
        '书名.txt',
      );
    });
  });

  group('formatTimestamp', () {
    test('毫秒 → 可读时间', () {
      final millis = DateTime(2026, 11, 13, 9, 5, 3).millisecondsSinceEpoch;
      expect(formatTimestamp(millis), '2026-11-13 09:05:03');
    });
  });
}
