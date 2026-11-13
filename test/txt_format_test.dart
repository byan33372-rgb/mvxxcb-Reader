import 'package:flutter_test/flutter_test.dart';
import 'package:jianyue_flutter/contracts.dart';
import 'package:jianyue_flutter/impl/txt_format.dart';

void main() {
  group('TXT 格式', () {
    test('文件头、章节分隔、段落顺序与图片跳过', () {
      final build = buildTxtDocument(
        bookTitle: '测试之书',
        author: null,
        sourceUrl: 'http://example.com/book/1',
        collectedAt: '2026-11-13 10:00',
        chapters: [
          const TxtChapterSlice(
            title: '第1章 开端',
            blocks: [
              Paragraph('正文第一段。'),
              ImageBlock('http://example.com/1.jpg', '插图'),
              Paragraph('正文第二段。'),
            ],
          ),
          const TxtChapterSlice(
            title: '第2章 冲突',
            blocks: [Paragraph('正文第三段。')],
          ),
        ],
      );

      final lines = build.content.split('\n');
      expect(lines[0], '书名：测试之书');
      expect(lines[1], '作者：佚名');
      expect(lines[2], '来源：http://example.com/book/1');
      expect(lines[3], '采集时间：2026-11-13 10:00');
      expect(lines[4], '章节数量：2');
      expect(lines[5], '');
      expect(lines[6], txtSeparator);
      expect(lines[7], '第1章 开端');
      expect(lines[8], '');
      expect(lines[9], '正文第一段。');
      expect(lines[10], '正文第二段。');
      expect(build.content.contains('http://example.com/1.jpg'), isFalse);
      expect(build.content.contains('插图'), isFalse);

      expect(build.chapterCount, 2);
      expect(build.completedCount, 2);
      expect(build.failedTitles, isEmpty);
      expect(build.isComplete, isTrue);
      expect(txtSeparator, '=====');
      expect(build.content.split(txtSeparator).length - 1, 2);
    });

    test('未采集章节如实计入失败清单，isComplete=false', () {
      final build = buildTxtDocument(
        bookTitle: '测试之书',
        author: '  张三  ',
        sourceUrl: 'http://example.com/book/1',
        collectedAt: '2026-11-13 10:00',
        chapters: const [
          TxtChapterSlice(title: '第1章 开端', blocks: [Paragraph('正文。')]),
          TxtChapterSlice(title: '第2章 冲突', available: false),
          TxtChapterSlice(title: '第3章 尾声', available: false),
        ],
      );

      expect(build.chapterCount, 3);
      expect(build.completedCount, 1);
      expect(build.failedTitles, ['第2章 冲突', '第3章 尾声']);
      expect(build.isComplete, isFalse);
      expect(build.content.contains('作者：张三'), isTrue);
      expect(
        build.content.endsWith('未采集章节清单（2 章）：\n- 第2章 冲突\n- 第3章 尾声\n'),
        isTrue,
      );
      expect(build.content.split(txtSeparator).length - 1, 1);
    });

    test('空书名/空作者兜底', () {
      final build = buildTxtDocument(
        bookTitle: '   ',
        author: '  ',
        sourceUrl: 'http://example.com/book/1',
        collectedAt: '2026-11-13 10:00',
        chapters: const [],
      );
      expect(build.content.startsWith('书名：未知书名\n作者：佚名\n'), isTrue);
      expect(build.chapterCount, 0);
      expect(build.isComplete, isTrue);
    });
  });
}