/// TXT 导出格式（纯函数，可单测）。
///
/// 格式对齐 Kotlin 版 `data/export/TxtExporterImpl.kt`：
///  - 文件头 5 行：书名 / 作者 / 来源 / 采集时间 / 章节数量；
///  - 每章：`=====` 分隔行 + 标题 + 空行 + 段落逐行 + 章间空行；
///  - 图片块跳过（TXT 无法承载）；
///  - 未采集章节清单写入文件尾，`isComplete=false`（绝不静默略过）。
library;

import '../contracts.dart';

const String txtSeparator = '=====';

/// 导出输入：单章。
class TxtChapterSlice {
  const TxtChapterSlice({
    required this.title,
    this.blocks = const [],
    this.available = true,
  });

  final String title;
  final List<ContentBlock> blocks;

  /// 是否有可用正文（未缓存/正文损坏时为 false）。
  final bool available;
}

class TxtBuildResult {
  const TxtBuildResult({
    required this.content,
    required this.chapterCount,
    required this.completedCount,
    required this.failedTitles,
  });

  final String content;
  final int chapterCount;
  final int completedCount;
  final List<String> failedTitles;

  bool get isComplete => failedTitles.isEmpty;
}

/// 构建整本 TXT 文本。
TxtBuildResult buildTxtDocument({
  required String bookTitle,
  String? author,
  required String sourceUrl,
  required String collectedAt,
  required List<TxtChapterSlice> chapters,
}) {
  final buffer = StringBuffer();
  final failedTitles = <String>[];
  var completed = 0;

  buffer.writeln('书名：${bookTitle.trim().isEmpty ? '未知书名' : bookTitle.trim()}');
  final authorText = author?.trim() ?? '';
  buffer.writeln('作者：${authorText.isEmpty ? '佚名' : authorText}');
  buffer.writeln('来源：$sourceUrl');
  buffer.writeln('采集时间：$collectedAt');
  buffer.writeln('章节数量：${chapters.length}');
  buffer.writeln();

  for (final chapter in chapters) {
    if (!chapter.available) {
      failedTitles.add(chapter.title);
      continue;
    }
    buffer.writeln(txtSeparator);
    buffer.writeln(chapter.title);
    buffer.writeln();
    for (final block in chapter.blocks) {
      if (block is Paragraph) {
        buffer.writeln(block.text);
      }
      // ImageBlock：TXT 跳过，不中断段落顺序
    }
    buffer.writeln();
    completed++;
  }

  if (failedTitles.isNotEmpty) {
    buffer.writeln('未采集章节清单（${failedTitles.length} 章）：');
    for (final title in failedTitles) {
      buffer.writeln('- $title');
    }
  }

  return TxtBuildResult(
    content: buffer.toString(),
    chapterCount: chapters.length,
    completedCount: completed,
    failedTitles: List.unmodifiable(failedTitles),
  );
}