/// WP-B 后台隔离采集（workmanager 0.10.x，Android 端零额外 Manifest 配置）。
///
/// 对齐 Kotlin 版 `data/work/CrawlTaskScheduler.kt` + `ChapterDownloadWorker.kt`：
///  - 任务以 one-off Work 入队（uniqueName `jianyue-crawl-<taskId>`），
///    网络连通约束 + dataSync 前台服务（进度常驻通知，Android 14+ 需
///    gradle.properties 的 `workmanager.enableDataSyncForegroundService=true`，
///    已在模板中启用）；
///  - 同一 taskId 的 Work 复用 keep 策略（不重启进行中的采集）；
///  - 暂停/取消时按 uniqueName 撤销 Work；
///  - 双入口防双跑：前台队列与后台隔离共用 DB 租约
///    （`CrawlTaskStore.tryAcquireLease`），同一任务同时只有一个执行方。
///
/// 已知限制（README 同步）：iOS 一期未接入（BGTaskScheduler 配置超出范围）；
/// 非 Android 平台调度器为空实现，前台队列照常工作。
library;

import 'dart:async';
import 'dart:io' show Platform;
import 'dart:ui' show DartPluginRegistrant;

import 'package:flutter/widgets.dart' show WidgetsFlutterBinding;
import 'package:workmanager/workmanager.dart';

import '../contracts.dart';
import '../core/app_log.dart';
import '../bootstrap.dart';

/// 后台任务的 taskName（executeTask 回调第一参数）。
const String kCrawlTaskName = 'jianyue.crawl';

/// WorkManager uniqueName（同一 taskId 唯一）。
String crawlUniqueName(int taskId) => 'jianyue-crawl-$taskId';

/// 后台隔离回调 dispatcher（必须是顶层函数，entry-point pragma 不可删）。
@pragma('vm:entry-point')
void callbackDispatcher() {
  Workmanager().executeTask((taskName, inputData) async {
    // 后台隔离需要重新注册插件（path_provider/sqflite/share 均为插件）。
    DartPluginRegistrant.ensureInitialized();
    WidgetsFlutterBinding.ensureInitialized();

    final raw = inputData?['taskId'];
    final int taskId =
        raw is int ? raw : (int.tryParse('$raw') ?? 0);
    if (taskId <= 0) {
      return Future<bool>.value(true);
    }

    AppLog.i('Background', 'worker start task=$taskId name=$taskName');
    final bundle = await buildAppServices();
    try {
      final acquired = await bundle.crawlRunner.runTask(taskId);
      AppLog.i(
        'Background',
        'worker done task=$taskId ran=$acquired',
      );
      return true;
    } catch (e) {
      // 返回 false → WorkManager 按退避策略重试；任务状态以 DB 为准。
      AppLog.e('Background', 'worker task=$taskId failed: $e');
      return false;
    } finally {
      await bundle.services.dispose?.call();
    }
  });
}

/// 后台采集调度器（Android：WorkManager 封装；其他平台 no-op）。
///
/// 应用前台对 WorkManager 的唯一入口；[ensureInitialized] 未完成前的
/// schedule/cancel 为 no-op（前台队列兜底，不丢任务）。
class WorkmanagerCrawlScheduler implements BackgroundCrawlScheduler {
  WorkmanagerCrawlScheduler();

  bool _ready = false;

  bool get isReady => _ready;

  /// 注册 dispatcher；仅 Android。应用启动时调用一次。
  Future<void> ensureInitialized() async {
    if (_ready || !Platform.isAndroid) return;
    try {
      await Workmanager().initialize(callbackDispatcher);
      _ready = true;
      AppLog.i('Background', 'workmanager initialized');
    } catch (e) {
      // 初始化失败：退化为仅前台采集（不影响应用可用性）。
      AppLog.w('Background', 'workmanager initialize failed: $e');
    }
  }

  @override
  Future<void> schedule(int taskId) async {
    if (!_ready) return;
    try {
      await Workmanager().registerOneOffTask(
        crawlUniqueName(taskId),
        kCrawlTaskName,
        inputData: <String, Object?>{'taskId': taskId},
        constraints: Constraints(networkType: NetworkType.connected),
        existingWorkPolicy: ExistingWorkPolicy.keep,
        backoffPolicy: BackoffPolicy.linear,
        backoffPolicyDelay: const Duration(seconds: 10),
        foregroundServiceConfig: ForegroundServiceConfig(
          notificationTitle: '简阅·后台采集',
          notificationText: '正在下载章节，完成后自动生成 TXT',
          notificationChannelId: 'jianyue_crawl_fg',
          notificationChannelName: '后台采集',
        ),
      );
      AppLog.i('Background', 'scheduled task=$taskId');
    } catch (e) {
      AppLog.w('Background', 'schedule task=$taskId failed: $e');
    }
  }

  @override
  Future<void> cancel(int taskId) async {
    if (!_ready) return;
    try {
      await Workmanager().cancelByUniqueName(crawlUniqueName(taskId));
      AppLog.i('Background', 'cancelled task=$taskId');
    } catch (e) {
      AppLog.w('Background', 'cancel task=$taskId failed: $e');
    }
  }
}
