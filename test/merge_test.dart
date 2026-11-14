import 'package:flutter_test/flutter_test.dart';
import 'package:jianyue_flutter/contracts.dart';
import 'package:jianyue_flutter/impl/catalog_merge.dart';
import 'package:jianyue_flutter/impl/url_utils.dart';

OldChapterRow _old(int id, int index, String title, String url) {
  final normalized = normalizeUrl(url)!;
  return OldChapterRow(
    id: id,
    chapterIndex: index,
    title: title,
    url: normalized,
    normalizedUrl: normalized,
    titleFingerprint: titleFingerprint(title),
  );
}

ChapterRef _ref(int order, String title, String url) {
  return ChapterRef(order: order, title: title, url: url);
}

void main() {
  group('目录合并计划', () {
    test('URL 命中保 id、指纹换址保 id、新章插入、缺失不删除', () {
      final old = [
        _old(10, 0, '第1章 开端', 'http://example.com/book/1/a.html'),
        _old(20, 1, '第2章 冲突', 'http://example.com/book/1/b.html'),
        _old(30, 2, '第3章 尾声', 'http://example.com/book/1/c.html'),
      ];
      final plan = planCatalogMerge(
        oldChapters: old,
        newChapters: [
          _ref(0, '第1章 开端', 'http://example.com/book/1/a.html'),
          _ref(1, '第2章 冲突', 'http://example.com/book/1/b2.html'),
          _ref(2, '第4章 新增', 'http://example.com/book/1/d.html'),
        ],
      );

      expect(plan.entries.length, 3);
      expect(plan.entries[0].oldId, 10);
      expect(plan.entries[0].addressChanged, isFalse);
      expect(plan.entries[0].titleChanged, isFalse);

      expect(plan.entries[1].oldId, 20);
      expect(plan.entries[1].addressChanged, isTrue);
      expect(plan.entries[1].url, 'http://example.com/book/1/b2.html');

      expect(plan.entries[2].isNew, isTrue);
      expect(plan.entries[2].oldId, -1);

      expect(plan.addedCount, 1);
      expect(plan.unchangedCount, 1);
      expect(plan.updatedUrlCount, 1);
      expect(plan.markedMissingCount, 1);
      expect(plan.missingOldIds, [30]);
      expect(plan.matchedRatio, closeTo(2 / 3, 0.0001));
      expect(plan.requiresReview, isFalse);
      expect(plan.detectedReversal, isFalse);
    });

    test('新目录整体倒序 → 自动反转后按阅读顺序匹配', () {
      final old = [
        _old(10, 0, '第1章 开端', 'http://example.com/book/1/a.html'),
        _old(20, 1, '第2章 冲突', 'http://example.com/book/1/b.html'),
        _old(30, 2, '第3章 尾声', 'http://example.com/book/1/c.html'),
      ];
      final plan = planCatalogMerge(
        oldChapters: old,
        newChapters: [
          _ref(0, '第3章 尾声', 'http://example.com/book/1/c.html'),
          _ref(1, '第2章 冲突', 'http://example.com/book/1/b.html'),
          _ref(2, '第1章 开端', 'http://example.com/book/1/a.html'),
        ],
      );

      expect(plan.detectedReversal, isTrue);
      expect(plan.entries.map((e) => e.title).toList(), [
        '第1章 开端',
        '第2章 冲突',
        '第3章 尾声',
      ]);
      expect(plan.entries.map((e) => e.oldId).toList(), [10, 20, 30]);
      expect(plan.addedCount, 0);
      expect(plan.missingOldIds, isEmpty);
      expect(plan.matchedRatio, 1.0);
    });

    test('parser 标记倒序同样采信；重复 URL 去重', () {
      final old = [
        _old(10, 0, '第1章 开端', 'http://example.com/book/1/a.html'),
        _old(20, 1, '第2章 冲突', 'http://example.com/book/1/b.html'),
        _old(30, 2, '第3章 尾声', 'http://example.com/book/1/c.html'),
      ];
      final plan = planCatalogMerge(
        oldChapters: old,
        parserSaysReversed: true,
        newChapters: [
          _ref(0, '第1章 开端', 'http://example.com/book/1/a.html'),
          _ref(1, '第2章 冲突', 'http://example.com/book/1/b.html'),
          _ref(2, '第2章 冲突', 'http://example.com/book/1/b.html'),
          _ref(3, '第3章 尾声', 'http://example.com/book/1/c.html'),
        ],
      );

      expect(plan.detectedReversal, isTrue);
      expect(plan.entries.length, 3);
      expect(plan.entries.map((e) => e.title).toList(), [
        '第3章 尾声',
        '第2章 冲突',
        '第1章 开端',
      ]);
    });

    test('旧章节大量缺失 → requiresReview', () {
      final old = [
        _old(10, 0, '第1章 开端', 'http://example.com/book/1/a.html'),
        _old(20, 1, '第2章 冲突', 'http://example.com/book/1/b.html'),
        _old(30, 2, '第3章 尾声', 'http://example.com/book/1/c.html'),
      ];
      final plan = planCatalogMerge(
        oldChapters: old,
        newChapters: [
          _ref(0, '第9章 全新', 'http://example.com/book/1/z.html'),
        ],
      );
      expect(plan.addedCount, 1);
      expect(plan.markedMissingCount, 3);
      expect(plan.matchedRatio, 0);
      expect(plan.requiresReview, isTrue);
    });
  });
}
