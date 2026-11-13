/// 应用内环形日志（对齐 Kotlin 版 `common/log/LogcatLogger` 的环形缓冲快照语义）。
///
/// 用途：
///  - 引擎层关键操作（解析/采集/规则/反馈）记一行摘要，供「诊断反馈」导出；
///  - release 下不打印到控制台（避免泄漏到 logcat），仅保留内存环形缓冲；
///  - 隐私口径：调用方传入的 message 自行脱敏（URL 一律用 `redactUrl`），本层不二次改写。
library;

import 'dart:io' show Platform;

/// 环形日志缓冲（进程级，最后 [maxLines] 行）。
class AppLog {
  AppLog._();

  static const int maxLines = 300;

  static final List<String> _lines = <String>[];

  static void i(String tag, [String? detail]) => _record('I', tag, detail);

  static void w(String tag, [String? detail]) => _record('W', tag, detail);

  static void e(String tag, [String? detail]) => _record('E', tag, detail);

  /// 最近 [count] 行（旧→新顺序）。
  static List<String> snapshotRecent([int count = 120]) {
    if (_lines.isEmpty) return const <String>[];
    final int start = _lines.length > count ? _lines.length - count : 0;
    return List<String>.unmodifiable(_lines.sublist(start));
  }

  /// 清空（诊断反馈导出后不清理；测试隔离用）。
  static void clear() => _lines.clear();

  static void _record(String level, String tag, String? detail) {
    final DateTime now = DateTime.now();
    String two(int v) => v.toString().padLeft(2, '0');
    final String stamp =
        '${two(now.hour)}:${two(now.minute)}:${two(now.second)}'
        '.${now.millisecond.toString().padLeft(3, '0')}';
    final String message = detail == null || detail.isEmpty
        ? ''
        : ' $detail';
    final String line = '$stamp $level/$tag:$message';
    _lines.add(line);
    if (_lines.length > maxLines) {
      _lines.removeRange(0, _lines.length - maxLines);
    }
    // 仅 debug 构建输出到控制台，避免 release 泄漏。
    assert(() {
      try {
        Platform.isAndroid; // 触发一次平台检查（测试环境兼容）
      } catch (_) {}
      // ignore: avoid_print
      print('AppLog $line');
      return true;
    }());
  }
}
