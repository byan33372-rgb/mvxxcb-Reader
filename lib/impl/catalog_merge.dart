/// 目录更新合并的纯逻辑（无 DB 依赖，可单测）。
///
/// 对齐 Kotlin 版 `data/repository/CatalogMerger.kt` 的七步算法：
///  1. 新目录按 order 排序 + URL 规范化 + 去重；
///  2. 反转检测（parser 标记或旧位置序列 ≥95% 递减）；
///  3. URL 命中保 id；
///  4. 未命中 URL 时按标题指纹匹配（唯一 / 邻位消歧）→ 换址保 id；
///  5. 均未命中 → 新章节；
///  6. 旧章未出现在新目录 → 标记缺失（不删除）；
///  7. 匹配率 / requiresReview 统计。
///
/// 真正的 SQL 执行在 `data_impl.dart`，保持本文件纯函数、零副作用。
library;

import '../contracts.dart';
import 'url_utils.dart';

/// 合并前的旧章节行（DB 中已存在的章节）。
class OldChapterRow {
  const OldChapterRow({
    required this.id,
    required this.chapterIndex,
    required this.title,
    required this.url,
    required this.normalizedUrl,
    required this.titleFingerprint,
  });

  final int id;
  final int chapterIndex;
  final String title;
  final String url;
  final String normalizedUrl;
  final String titleFingerprint;
}

/// 合并后按新目录顺序排列的章节项。
class MergedChapterEntry {
  const MergedChapterEntry({
    required this.oldId,
    required this.chapterIndex,
    required this.title,
    required this.url,
    required this.normalizedUrl,
    required this.titleFingerprint,
    required this.titleChanged,
    required this.addressChanged,
  });

  /// 命中旧章时为其 id；新章节为 -1。
  final int oldId;
  final int chapterIndex;
  final String title;
  final String url;
  final String normalizedUrl;
  final String titleFingerprint;
  final bool titleChanged;
  final bool addressChanged;

  bool get isNew => oldId <= 0;
}

/// 合并计划（纯数据）。
class CatalogMergePlan {
  const CatalogMergePlan({
    required this.entries,
    required this.missingOldIds,
    required this.addedCount,
    required this.unchangedCount,
    required this.updatedUrlCount,
    required this.markedMissingCount,
    required this.detectedReversal,
    required this.matchedRatio,
    required this.requiresReview,
  });

  final List<MergedChapterEntry> entries;

  /// 旧章节未出现在新目录 → 执行层置 is_missing=1（不删除）。
  final List<int> missingOldIds;

  final int addedCount;
  final int unchangedCount;
  final int updatedUrlCount;
  final int markedMissingCount;
  final bool detectedReversal;
  final double matchedRatio;
  final bool requiresReview;

  int get totalChapters => entries.length;
}

const double _reviewThreshold = 0.6;
const double _reversalRatio = 0.95;

/// 计算目录合并计划。
CatalogMergePlan planCatalogMerge({
  required List<OldChapterRow> oldChapters,
  required List<ChapterRef> newChapters,
  bool parserSaysReversed = false,
}) {
  final orderedNew = _prepareNewChapters(newChapters);
  final detectedReversal = _detectReversal(
    oldChapters,
    orderedNew,
    parserSaysReversed,
  );
  final ordered = detectedReversal
      ? orderedNew.reversed.toList(growable: false)
      : orderedNew;

  final byUrl = <String, OldChapterRow>{};
  final byFingerprint = <String, List<OldChapterRow>>{};
  for (final row in oldChapters) {
    byUrl.putIfAbsent(row.normalizedUrl, () => row);
    byFingerprint.putIfAbsent(row.titleFingerprint, () => []).add(row);
  }

  final consumed = <int>{};
  final entries = <MergedChapterEntry>[];
  final missingIds = <int>[];
  var unchanged = 0;
  var updatedUrl = 0;
  int? lastMatchedOldIndex;

  for (var i = 0; i < ordered.length; i++) {
    final ref = ordered[i];
    final fingerprint = titleFingerprint(ref.title);
    final urlHit = byUrl[ref.url];

    if (urlHit != null && !consumed.contains(urlHit.id)) {
      consumed.add(urlHit.id);
      final titleChanged = urlHit.title != ref.title;
      entries.add(MergedChapterEntry(
        oldId: urlHit.id,
        chapterIndex: i,
        title: ref.title,
        url: ref.url,
        normalizedUrl: ref.url,
        titleFingerprint: fingerprint,
        titleChanged: titleChanged,
        addressChanged: false,
      ));
      unchanged++;
      lastMatchedOldIndex = urlHit.chapterIndex;
      continue;
    }

    final candidates = (byFingerprint[fingerprint] ?? const <OldChapterRow>[])
        .where((row) => !consumed.contains(row.id))
        .toList(growable: false);
    OldChapterRow? chosen;
    if (candidates.length == 1) {
      chosen = candidates.first;
    } else if (candidates.length > 1) {
      final anchor = lastMatchedOldIndex;
      for (final row in candidates) {
        if (anchor != null && row.chapterIndex == anchor + 1) {
          chosen = row;
          break;
        }
      }
      if (chosen == null) {
        for (final row in candidates) {
          if (anchor != null && row.chapterIndex == anchor - 1) {
            chosen = row;
            break;
          }
        }
      }
      chosen ??= candidates.first;
    }

    if (chosen != null) {
      consumed.add(chosen.id);
      entries.add(MergedChapterEntry(
        oldId: chosen.id,
        chapterIndex: i,
        title: ref.title,
        url: ref.url,
        normalizedUrl: ref.url,
        titleFingerprint: fingerprint,
        titleChanged: chosen.title != ref.title,
        addressChanged: true,
      ));
      updatedUrl++;
      lastMatchedOldIndex = chosen.chapterIndex;
    } else {
      entries.add(MergedChapterEntry(
        oldId: -1,
        chapterIndex: i,
        title: ref.title,
        url: ref.url,
        normalizedUrl: ref.url,
        titleFingerprint: fingerprint,
        titleChanged: false,
        addressChanged: false,
      ));
    }
  }

  final missingRows = oldChapters
      .where((row) => !consumed.contains(row.id))
      .toList()
    ..sort((a, b) => a.chapterIndex.compareTo(b.chapterIndex));
  missingIds.addAll(missingRows.map((row) => row.id));

  final denominator =
      oldChapters.length > ordered.length ? oldChapters.length : ordered.length;
  final matchedRatio = (denominator == 0 || oldChapters.isEmpty)
      ? 1.0
      : (unchanged + updatedUrl) / denominator;

  return CatalogMergePlan(
    entries: List.unmodifiable(entries),
    missingOldIds: List.unmodifiable(missingIds),
    addedCount: entries.where((e) => e.isNew).length,
    unchangedCount: unchanged,
    updatedUrlCount: updatedUrl,
    markedMissingCount: missingIds.length,
    detectedReversal: detectedReversal,
    matchedRatio: matchedRatio,
    requiresReview: oldChapters.isNotEmpty && matchedRatio < _reviewThreshold,
  );
}

List<ChapterRef> _prepareNewChapters(List<ChapterRef> refs) {
  final sorted = List<ChapterRef>.from(refs)
    ..sort((a, b) => a.order.compareTo(b.order));
  final seen = <String>{};
  final result = <ChapterRef>[];
  for (final ref in sorted) {
    final normalized = normalizeUrl(ref.url) ?? ref.url.trim();
    if (normalized.isEmpty) continue;
    if (!seen.add(normalized)) continue;
    result.add(ChapterRef(
      order: result.length,
      title: ref.title,
      url: normalized,
      volumeTitle: ref.volumeTitle,
    ));
  }
  return result;
}

bool _detectReversal(
  List<OldChapterRow> oldChapters,
  List<ChapterRef> newRefs,
  bool parserSaysReversed,
) {
  if (parserSaysReversed) return true;
  if (oldChapters.isEmpty || newRefs.length < 2) return false;

  final oldPositionByUrl = <String, int>{};
  for (var i = 0; i < oldChapters.length; i++) {
    oldPositionByUrl.putIfAbsent(oldChapters[i].normalizedUrl, () => i);
  }
  final sequence = <int>[];
  for (final ref in newRefs) {
    final position = oldPositionByUrl[ref.url];
    if (position != null) sequence.add(position);
  }
  if (sequence.length < 2) return false;
  var decreasing = 0;
  for (var i = 0; i < sequence.length - 1; i++) {
    if (sequence[i] > sequence[i + 1]) decreasing++;
  }
  return decreasing / (sequence.length - 1) >= _reversalRatio;
}