/// 诊断反馈（WP-D，对齐 Kotlin `service/DiagnosticsService.kt` 文本口径）。
///
/// 分节：设备与应用 / 数据库统计 / 采集任务 / 阅读设置 / 最近操作日志 /
/// 反馈生成时间。隐私口径：
///  - URL 一律 [redactUrl] 脱敏（去 query/fragment + 截断）；
///  - 本地 TXT 路径只展示文件名，不导出完整目录结构；
///  - 不包含书名/正文内容（Kotlin 同口径：仅统计与状态）。
library;

import 'dart:io' show Platform;

import 'package:path/path.dart' as p;

import '../contracts.dart';
import '../core/app_log.dart';
import '../core/app_result.dart';
import '../core/reader_error.dart';
import 'data_impl.dart';
import 'url_utils.dart';

/// 诊断反馈服务实现。
class DiagnosticsServiceImpl implements DiagnosticsService {
  DiagnosticsServiceImpl({
    required AppDatabase database,
    required CrawlTaskStore tasks,
    required SettingsService settings,
    this.appVersion = '1.1.0（Flutter）',
  })  : _database = database,
        _tasks = tasks,
        _settings = settings;

  final AppDatabase _database;
  final CrawlTaskStore _tasks;
  final SettingsService _settings;
  final String appVersion;

  @override
  Future<AppResult<FeedbackReport>> buildFeedback() async {
    try {
      final sections = <String, List<String>>{};
      sections['设备与应用'] = _deviceLines();
      sections['数据库统计'] = await _databaseStatLines();
      sections['采集任务'] = await _taskLines();
      sections['阅读设置'] = await _settingLines();
      sections['最近操作日志'] = AppLog.snapshotRecent(120);

      final now = DateTime.now();
      return Success(FeedbackReport(
        text: assembleFeedbackReport(
          sections: sections,
          generatedAt: formatTimestamp(now.millisecondsSinceEpoch),
        ),
        suggestedFileName:
            'jianyue-feedback-${_stampForFile(now)}.txt',
      ));
    } catch (e) {
      return Failure(StorageError('DB_ERROR', '生成诊断反馈失败: $e'));
    }
  }

  // ============================================================
  // 分节采集
  // ============================================================

  List<String> _deviceLines() {
    return <String>[
      '应用：简阅（Flutter 版）$appVersion',
      '系统：${Platform.operatingSystem} ${Platform.operatingSystemVersion}',
      '区域：${Platform.localeName}',
    ];
  }

  Future<List<String>> _databaseStatLines() async {
    final db = _database.db;
    Future<int> count(String sql, [List<Object?> args = const []]) async {
      final rows = await db.rawQuery('SELECT COUNT(*) AS c FROM ($sql)', args);
      return (rows.first['c'] as int?) ?? 0;
    }

    final books = await count('SELECT * FROM books WHERE is_temporary = 0');
    final chapters = await count('SELECT * FROM chapters');
    final cached =
        await count('SELECT * FROM chapters WHERE is_cached = 1');
    final missing =
        await count('SELECT * FROM chapters WHERE is_missing = 1');
    final tasks = await count('SELECT * FROM crawl_tasks');
    return <String>[
      '书架书籍：$books',
      '章节总数：$chapters（已缓存 $cached，缺失 $missing）',
      '采集任务总数：$tasks',
    ];
  }

  Future<List<String>> _taskLines() async {
    final tasks = await _tasks.listTasks();
    final lines = <String>[];
    for (final task in tasks.take(10)) {
      final txt = task.txtPath == null
          ? '未生成'
          : 'TXT=${p.basename(task.txtPath!)}';
      lines.add(
        '任务 #${task.id}：书 ${task.bookId}，状态 ${task.state.name}，'
        '进度 ${task.succeededCount}/${task.totalChapters}'
        '（失败 ${task.failedCount}），当前第 ${task.currentChapterIndex} 章，$txt',
      );
    }
    if (tasks.length > 10) {
      lines.add('… 其余 ${tasks.length - 10} 条任务从略');
    }
    return lines;
  }

  Future<List<String>> _settingLines() async {
    final settings = await _settings.load();
    return <String>[
      '字号：${settings.fontSize.toStringAsFixed(0)}',
      '行距：${settings.lineHeightMultiplier.toStringAsFixed(1)}x',
      '背景主题：${settings.backgroundTheme}',
      '水平边距：${settings.horizontalPaddingDp.toStringAsFixed(0)}dp',
    ];
  }
}

// ============================================================
// 纯函数组装（可单测）
// ============================================================

/// 分节数据 → 反馈文本（sections 有序展示，空节显示"（无）"）。
String assembleFeedbackReport({
  required Map<String, List<String>> sections,
  required String generatedAt,
}) {
  final buffer = StringBuffer();
  buffer.writeln('简阅 · 诊断反馈');
  buffer.writeln('================================');
  for (final entry in sections.entries) {
    buffer.writeln();
    buffer.writeln('## ${entry.key}');
    final lines = entry.value;
    if (lines.isEmpty) {
      buffer.writeln('（无）');
    } else {
      for (final line in lines) {
        buffer.writeln(line);
      }
    }
  }
  buffer.writeln();
  buffer.writeln('--------------------------------');
  buffer.writeln('反馈生成时间：$generatedAt');
  buffer.writeln('URL 均已脱敏（去 query/fragment）。');
  return buffer.toString();
}

String formatTimestamp(int millis) {
  final date = DateTime.fromMillisecondsSinceEpoch(millis);
  String two(int value) => value.toString().padLeft(2, '0');
  return '${date.year}-${two(date.month)}-${two(date.day)} '
      '${two(date.hour)}:${two(date.minute)}:${two(date.second)}';
}

String _stampForFile(DateTime now) {
  String two(int value) => value.toString().padLeft(2, '0');
  return '${now.year}${two(now.month)}${two(now.day)}-'
      '${two(now.hour)}${two(now.minute)}';
}
