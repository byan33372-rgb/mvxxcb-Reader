/// 整本采集服务与阅读设置。
///
/// 对齐 Kotlin 版 `data/work/ChapterDownloadWorker.kt` 与 `TxtExporterImpl.kt`：
///  - downloadWholeBook：同书非终态任务幂等复用 → 建任务 → 入队；
///  - 执行内核抽至 `crawl_task_runner.dart`（前台队列与后台 WorkManager 隔离
///    共用；双入口以 DB 租约互斥，同一任务同时只有一个执行方）；
///  - [downloadWholeBook]/[resume]/[retryFailed]/[restoreOnStartup] 在 Android
///    上追加调度后台 Work（App 退到后台/被杀后采集不中断，WP-B）；
///  - [pause]/[cancel] 撤销后台 Work 并释放租约；
///  - 章节间节奏由 SecureHttpClient 的每主机自适应限速保证；
///  - 完成/失败后自动 exportTxt（原子写 tmp+rename + SHA-256）；
///  - 失败章清单写入 TXT 文件尾；存在失败章时任务 FAILED，不记录 TXT 成功路径；
///  - SettingsService：shared_preferences 持久化字号/行高/主题/边距。
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../contracts.dart';
import '../core/app_log.dart';
import '../core/app_result.dart';
import '../core/reader_error.dart';
import 'crawl_task_runner.dart';
import 'data_impl.dart';
import 'txt_format.dart';
import 'url_utils.dart';

class CrawlServiceImpl implements CrawlService {
  CrawlServiceImpl({
    required BookRepository books,
    required ChapterRepository chapters,
    required CrawlTaskStore tasks,
    required ParserEngine parser,
    BackgroundCrawlScheduler? background,
    Future<Directory> Function()? documentsDirectory,
  })  : _books = books,
        _chapters = chapters,
        _tasks = tasks,
        _parser = parser,
        _background = background,
        _documentsDirectory =
            documentsDirectory ?? getApplicationDocumentsDirectory {
    // 前台队列与后台隔离共用执行内核；TXT 导出复用本实例实现。
    _runner = CrawlTaskRunner(
      books: _books,
      chapters: _chapters,
      tasks: _tasks,
      parser: _parser,
      exportTxt: (bookId) => exportTxt(bookId),
    );
  }

  final BookRepository _books;
  final ChapterRepository _chapters;
  final CrawlTaskStore _tasks;
  final ParserEngine _parser;
  final BackgroundCrawlScheduler? _background;
  final Future<Directory> Function() _documentsDirectory;

  late final CrawlTaskRunner _runner;

  Future<void> _queue = Future<void>.value();
  final Set<int> _enqueued = <int>{};
  final Set<int> _rerunRequested = <int>{};

  // ============================================================
  // 流与查询
  // ============================================================

  @override
  Stream<List<CrawlTask>> observeTasks() => _tasks.observeTasks();

  @override
  Stream<CrawlTask?> observeTaskForBook(int bookId) =>
      _tasks.observeTaskForBook(bookId);

  @override
  Future<CrawlTask?> getActiveTaskForBook(int bookId) =>
      _tasks.activeForBook(bookId);

  // ============================================================
  // 任务生命周期
  // ============================================================

  @override
  Future<AppResult<CrawlTask>> downloadWholeBook(int bookId) async {
    try {
      final book = await _books.getBook(bookId);
      if (book == null) {
        return Failure(StateError('BOOK_NOT_FOUND', 'download: bookId=$bookId'));
      }

      final existing = await _tasks.activeForBook(bookId);
      if (existing != null) {
        if (existing.state == CrawlState.waiting) _enqueue(existing.id);
        return Success(existing);
      }

      final metas = await _chapters.observeChapterMetas(bookId).first;
      if (metas.isEmpty) {
        return Failure(ParseError('CATALOG_EMPTY', 'download: bookId=$bookId 无目录'));
      }

      final task = await _tasks.createTask(bookId, metas.length);
      _enqueue(task.id);
      await _scheduleBackground(task.id);
      return Success(task);
    } catch (e) {
      return Failure(StorageError('DB_ERROR', '创建采集任务失败: $e'));
    }
  }

  @override
  Future<AppResult<void>> pause(int taskId) async {
    final task = await _tasks.getTask(taskId);
    if (task == null) {
      return Failure(StateError('TASK_NOT_FOUND', 'pause: id=$taskId'));
    }
    if (task.state == CrawlState.running || task.state == CrawlState.waiting) {
      await _tasks.updateState(taskId, CrawlState.paused);
      await _cancelBackground(taskId);
      AppLog.i('Crawl', 'paused task=$taskId');
    }
    return const Success<void>(null);
  }

  @override
  Future<AppResult<void>> resume(int taskId) async {
    final task = await _tasks.getTask(taskId);
    if (task == null) {
      return Failure(StateError('TASK_NOT_FOUND', 'resume: id=$taskId'));
    }
    if (task.state == CrawlState.paused ||
        task.state == CrawlState.failed ||
        task.state == CrawlState.waiting) {
      await _tasks.updateState(taskId, CrawlState.running);
      _enqueue(taskId);
      await _scheduleBackground(taskId);
      AppLog.i('Crawl', 'resumed task=$taskId');
    }
    return const Success<void>(null);
  }

  @override
  Future<AppResult<void>> cancel(int taskId) async {
    final task = await _tasks.getTask(taskId);
    if (task == null) {
      return Failure(StateError('TASK_NOT_FOUND', 'cancel: id=$taskId'));
    }
    if (task.state != CrawlState.completed &&
        task.state != CrawlState.cancelled) {
      await _tasks.updateState(taskId, CrawlState.cancelled);
      await _cancelBackground(taskId);
      AppLog.i('Crawl', 'cancelled task=$taskId');
    }
    return const Success<void>(null);
  }

  @override
  Future<AppResult<void>> retryFailed(int taskId) async {
    final task = await _tasks.getTask(taskId);
    if (task == null) {
      return Failure(StateError('TASK_NOT_FOUND', 'retryFailed: id=$taskId'));
    }
    final metas = await _chapters.observeChapterMetas(task.bookId).first;
    final cached = metas.where((m) => m.isCached).length;
    await _tasks.resetForRetry(taskId, cached);
    _enqueue(taskId);
    await _scheduleBackground(taskId);
    return const Success<void>(null);
  }

  @override
  Future<AppResult<void>> retryChapter(int bookId, int chapterIndex) async {
    final meta = await _chapters.getChapterByIndex(bookId, chapterIndex);
    if (meta == null) {
      return Failure(StateError(
        'CHAPTER_NOT_FOUND',
        'retryChapter: bookId=$bookId index=$chapterIndex',
      ));
    }
    final parsed = await _parser.parseChapterContent(meta.url);
    final error = parsed.errorOrNull;
    if (error != null) return Failure(error);
    return _chapters.saveContent(meta.id, parsed.valueOrNull!);
  }

  @override
  Future<void> restoreOnStartup() async {
    try {
      final tasks = await _tasks.listTasks();
      for (final task in tasks) {
        if (task.state == CrawlState.waiting || task.state == CrawlState.running) {
          if (task.state == CrawlState.running) {
            await _tasks.updateState(task.id, CrawlState.waiting);
          }
          _enqueue(task.id);
          // WP-B：应用重启后重新挂后台 Work（keep 幂等，进行中的 Work 不受影响）。
          await _scheduleBackground(task.id);
        }
      }
    } catch (_) {
      // 恢复失败不阻断应用启动
    }
  }

  // ============================================================
  // 后台调度（WP-B）
  // ============================================================

  /// Android 上追加挂一次后台 Work；其他平台/未初始化为 no-op。
  /// 前台队列仍会立即执行——DB 租约保证双入口不双跑。
  Future<void> _scheduleBackground(int taskId) async {
    try {
      await _background?.schedule(taskId);
    } catch (_) {
      // 调度失败不影响前台采集
    }
  }

  Future<void> _cancelBackground(int taskId) async {
    try {
      await _background?.cancel(taskId);
    } catch (_) {
      // ignore
    }
  }

  // ============================================================
  // 串行采集循环（执行内核见 crawl_task_runner.dart）
  // ============================================================

  void _enqueue(int taskId) {
    if (!_enqueued.add(taskId)) {
      // 任务正在队列/运行中（如 pause 后立即 resume、retryFailed）：
      // 当前循环退出后再补一轮，避免任务停在 WAITING 无人执行。
      _rerunRequested.add(taskId);
      return;
    }
    _queue = _queue.then((_) async {
      try {
        await _runTask(taskId);
      } catch (_) {
        // 单任务异常不影响队列后续任务
        try {
          await _tasks.updateState(taskId, CrawlState.failed);
        } catch (_) {
          // ignore
        }
      } finally {
        _enqueued.remove(taskId);
        if (_rerunRequested.remove(taskId)) {
          _enqueue(taskId);
        }
      }
    });
  }

  Future<void> _runTask(int taskId) async {
    // 执行内核（租约抢占/逐章循环/TXT 导出/终态落库）与后台隔离共用；
    // 暂停/取消由状态表驱动（循环每章前检查）。
    await _runner.runTask(taskId);
  }

  // ============================================================
  // TXT 导出
  // ============================================================

  @override
  Future<AppResult<TxtExportReport>> exportTxt(int bookId) async {
    try {
      final book = await _books.getBook(bookId);
      if (book == null) {
        return Failure(StateError('BOOK_NOT_FOUND', 'export: bookId=$bookId'));
      }
      final metas = await _chapters.observeChapterMetas(bookId).first;
      if (metas.isEmpty) {
        return Failure(ParseError('CATALOG_EMPTY', 'export: bookId=$bookId'));
      }

      final slices = <TxtChapterSlice>[];
      for (final meta in metas) {
        ReaderContent? content;
        if (meta.isCached && !meta.isMissing) {
          content = await _chapters.loadContent(meta.id);
        }
        slices.add(TxtChapterSlice(
          title: meta.title,
          blocks: content?.blocks ?? const [],
          available: content != null,
        ));
      }

      final build = buildTxtDocument(
        bookTitle: book.title,
        author: book.author,
        sourceUrl: book.sourceUrl,
        collectedAt: _formatTimestamp(DateTime.now().millisecondsSinceEpoch),
        chapters: slices,
      );

      final documents = await _documentsDirectory();
      final safeTitle = sanitizeFileName(book.title);
      final fileName = safeTitle.isEmpty ? 'book-$bookId' : safeTitle;
      final target = File(p.join(documents.path, 'txt', '$bookId', '$fileName.txt'));
      await target.parent.create(recursive: true);

      final bytes = utf8.encode(build.content);
      final tmp = File('${target.path}.tmp');
      await tmp.writeAsBytes(bytes, flush: true);
      try {
        if (await target.exists()) await target.delete();
        await tmp.rename(target.path);
      } catch (_) {
        // 某些平台（Windows/跨卷）rename 失败 → 复制后删除临时文件
        await tmp.copy(target.path);
        await tmp.delete();
      }

      return Success(TxtExportReport(
        filePath: target.path,
        fileSize: bytes.length,
        sha256: sha256.convert(bytes).toString(),
        chapterCount: build.chapterCount,
        completedCount: build.completedCount,
        isComplete: build.isComplete,
      ));
    } catch (e) {
      return Failure(StorageError('TXT_WRITE_FAILED', 'TXT 导出失败: $e'));
    }
  }
}

String _formatTimestamp(int millis) {
  final date = DateTime.fromMillisecondsSinceEpoch(millis);
  String two(int value) => value.toString().padLeft(2, '0');
  return '${date.year}-${two(date.month)}-${two(date.day)} '
      '${two(date.hour)}:${two(date.minute)}';
}

// ============================================================
// 阅读设置
// ============================================================

class SettingsServiceImpl implements SettingsService {
  static const String _fontSizeKey = 'reader.fontSize';
  static const String _lineHeightKey = 'reader.lineHeight';
  static const String _themeKey = 'reader.theme';
  static const String _paddingKey = 'reader.padding';

  @override
  Future<ReaderSettings> load() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      double readDouble(String key, double fallback) {
        final value = prefs.get(key);
        return value is num ? value.toDouble() : fallback;
      }

      int readInt(String key, int fallback) {
        final value = prefs.get(key);
        return value is num ? value.toInt() : fallback;
      }

      final fontSize = readDouble(_fontSizeKey, 18).clamp(12, 40).toDouble();
      final lineHeight =
          readDouble(_lineHeightKey, 1.6).clamp(1.2, 2.5).toDouble();
      final theme = readInt(_themeKey, 1).clamp(0, 4);
      final padding = readDouble(_paddingKey, 20).clamp(0, 60).toDouble();
      return ReaderSettings(
        fontSize: fontSize,
        lineHeightMultiplier: lineHeight,
        backgroundTheme: theme,
        horizontalPaddingDp: padding,
      );
    } catch (_) {
      return const ReaderSettings();
    }
  }

  @override
  Future<void> save(ReaderSettings settings) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setDouble(_fontSizeKey, settings.fontSize);
      await prefs.setDouble(_lineHeightKey, settings.lineHeightMultiplier);
      await prefs.setInt(_themeKey, settings.backgroundTheme);
      await prefs.setDouble(_paddingKey, settings.horizontalPaddingDp);
    } catch (_) {
      // 设置持久化失败不影响阅读
    }
  }
}