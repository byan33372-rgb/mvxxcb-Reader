/// 共享采集任务循环（WP-B：前台 CrawlServiceImpl 与后台 WorkManager 隔离体
/// 共用同一执行内核，对齐 Kotlin 版 ChapterDownloadWorker 的执行语义）。
///
/// 双入口防双跑：进入循环前必须通过 DB 租约（`crawl_tasks.lease_until`）
/// 抢占成功；执行中周期续租；退出（终态/暂停/取消/异常）时释放租约。
/// 章节间节奏由 SecureHttpClient 的每主机自适应限速保证（起始 2s）。
library;

import '../contracts.dart';
import '../core/app_log.dart';
import '../core/app_result.dart';
import '../core/reader_error.dart';
import 'data_impl.dart';

/// 采集任务执行内核。
class CrawlTaskRunner {
  CrawlTaskRunner({
    required BookRepository books,
    required ChapterRepository chapters,
    required CrawlTaskStore tasks,
    required ParserEngine parser,
    Future<AppResult<TxtExportReport>> Function(int bookId)? exportTxt,
    this.leaseTtl = const Duration(minutes: 2),
  })  : _books = books,
        _chapters = chapters,
        _tasks = tasks,
        _parser = parser,
        _exportTxt = exportTxt;

  final BookRepository _books;
  final ChapterRepository _chapters;
  final CrawlTaskStore _tasks;
  final ParserEngine _parser;

  /// TXT 导出实现：与前台 CrawlServiceImpl 同源（装配方注入）。
  final Future<AppResult<TxtExportReport>> Function(int bookId)? _exportTxt;

  /// 租约时长：覆盖"若干章网络请求"的执行窗口；续租由循环周期触发。
  final Duration leaseTtl;

  /// 执行任务循环。调用方需保证：
  ///  - 任务存在且处于 waiting/running/paused 非终态；
  ///  - 前台入口由 CrawlServiceImpl 串行队列互斥，后台入口由 WorkManager
  ///    uniqueName + 本方法内的租约抢占互斥。
  ///
  /// 返回值仅表示循环是否执行（租约抢占成功即 true），终态由任务表状态表达。
  Future<bool> runTask(int taskId) async {
    var task = await _tasks.getTask(taskId);
    if (task == null) return false;
    if (task.state == CrawlState.completed ||
        task.state == CrawlState.failed ||
        task.state == CrawlState.cancelled) {
      return false;
    }
    if (task.state == CrawlState.waiting) {
      await _tasks.updateState(taskId, CrawlState.running);
      task = task.copyWith(state: CrawlState.running);
    }
    if (task.state != CrawlState.running) {
      await _tasks.clearLease(taskId);
      return false;
    }

    final nowMs = DateTime.now().millisecondsSinceEpoch;
    final acquired = await _tasks.tryAcquireLease(taskId, nowMs, ttl: leaseTtl);
    if (!acquired) {
      // 其他执行方（前台/后台）正在处理该任务：本轮退出，不重复跑。
      AppLog.i('CrawlRunner', 'lease busy task=$taskId');
      return false;
    }

    try {
      final book = await _books.getBook(task.bookId);
      if (book == null) {
        await _tasks.updateState(taskId, CrawlState.failed);
        return true;
      }

      final metas = await _chapters.observeChapterMetas(book.id).first;
      if (metas.isEmpty) {
        await _tasks.updateState(taskId, CrawlState.failed);
        return true;
      }

      var succeeded = metas.where((m) => m.isCached).length;
      var failed = 0;
      final pending = metas
          .where((m) => !m.isCached && !m.isMissing)
          .toList(growable: false);

      for (final meta in pending) {
        final latest = await _tasks.getTask(taskId);
        if (latest == null || latest.state != CrawlState.running) {
          // 暂停/取消/后台改派：退出循环并释放租约。
          await _tasks.clearLease(taskId);
          return true;
        }

        await _tasks.updateProgress(
          taskId,
          succeeded: succeeded,
          failed: failed,
          currentIndex: meta.chapterIndex,
          currentTitle: meta.title,
        );

        final parsed = await _parser.parseChapterContent(meta.url);
        if (parsed is Success<ParsedChapterContent>) {
          final saved = await _chapters.saveContent(meta.id, parsed.value);
          if (saved.isSuccess) {
            succeeded++;
          } else {
            failed++;
          }
        } else {
          failed++;
        }

        await _tasks.updateProgress(
          taskId,
          succeeded: succeeded,
          failed: failed,
          currentIndex: meta.chapterIndex,
          currentTitle: meta.title,
        );
        // 每章续租：网络请求以 2s 级间隔计，逐章续租保证长任务不失约。
        await _tasks.renewLease(
          taskId,
          DateTime.now().millisecondsSinceEpoch,
          ttl: leaseTtl,
        );
      }

      final latest = await _tasks.getTask(taskId);
      if (latest == null || latest.state != CrawlState.running) {
        await _tasks.clearLease(taskId);
        return true;
      }

      final exported = _exportTxt?.call(book.id) ??
          Future.value(
            Failure<TxtExportReport>(
              StateError('UNSUPPORTED', 'CrawlTaskRunner: exportTxt 未装配'),
            ),
          );
      final report = (await exported).valueOrNull;
      if (report != null && report.isComplete) {
        await _tasks.markTxtGenerated(taskId, report.filePath);
        await _tasks.updateState(taskId, CrawlState.completed);
        AppLog.i(
          'CrawlRunner',
          'completed book=${book.id} chapters=${report.completedCount}',
        );
      } else {
        // 有失败章：TXT 文件尾含失败清单，但任务不标记 TXT 成功。
        await _tasks.updateState(taskId, CrawlState.failed);
        AppLog.i(
          'CrawlRunner',
          'failed book=${book.id} pendingFailed=$failed',
        );
      }
      return true;
    } catch (e) {
      AppLog.e('CrawlRunner', 'task=$taskId crashed: $e');
      try {
        await _tasks.updateState(taskId, CrawlState.failed);
      } catch (_) {
        // ignore
      }
      return true;
    } finally {
      await _tasks.clearLease(taskId);
    }
  }
}
