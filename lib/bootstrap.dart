/// 应用装配（对齐 Kotlin 版 AppContainer 顺序）：
/// security/http → parser(规则引擎) → resolver → DB → repos/stores →
/// 采集 → 设置 → 规则 → 诊断。
///
/// 前台 [main.dart] 与后台隔离 [background_impl.dart] 的 dispatcher 共用本入口；
/// 后台隔离重建整套服务（独立 DB 连接），任务结束经 `AppServices.dispose` 释放。
library;

import 'dart:io' show Directory, Platform;

import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
// sqflite_ffi 已重导出 sqflite 的 databaseFactory 等符号，无需重复导入
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'contracts.dart';
import 'core/app_log.dart';
import 'impl/crawl_impl.dart';
import 'impl/crawl_task_runner.dart';
import 'impl/data_impl.dart';
import 'impl/diagnostics_impl.dart';
import 'impl/parser_impl.dart';
import 'impl/resolver_impl.dart';
import 'impl/rule_engine_impl.dart';
import 'impl/secure_http_impl.dart';

/// 桌面端（Windows/Linux）数据库环境装配，须在首次 openDatabase 前调用。
///
/// sqflite 默认工厂只覆盖 Android/iOS/macOS；桌面注入 ffi 工厂并把库目录
/// 指到应用支持目录，否则启动即崩。Android 分支零行为变化；测试进程走
/// flutter_tester（两标志均 false），不会与测试自建 ffi 工厂互踩。
Future<void> setupDesktopDatabase() async {
  if (!Platform.isWindows && !Platform.isLinux) return;
  try {
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
    final support = await getApplicationSupportDirectory();
    final dbDir = Directory(p.join(support.path, 'databases'));
    if (!dbDir.existsSync()) dbDir.createSync(recursive: true);
    await databaseFactory.setDatabasesPath(dbDir.path);
  } catch (e) {
    // 装配失败不中断启动，后续 openDatabase 自带错误上报
    AppLog.w('Bootstrap', 'desktop database setup failed: $e');
  }
}

/// 装配产出：UI 直接消费 [services]；后台隔离复用 [crawlRunner]。
class AppServicesBundle {
  AppServicesBundle({required this.services, required this.crawlRunner});

  final AppServices services;
  final CrawlTaskRunner crawlRunner;
}

/// 构建整套应用服务。
///
/// [dbPath] 供测试注入内存库；[background] 由 Android 主入口传入
/// （WorkmanagerCrawlScheduler），非 Android 平台传 null。
Future<AppServicesBundle> buildAppServices({
  String? dbPath,
  BackgroundCrawlScheduler? background,
  Future<Directory> Function()? documentsDirectory,
}) async {
  final http = SecureHttpClientImpl();
  final database = await AppDatabase.open(path: dbPath);
  final events = DataEvents();
  final books = BookRepositoryImpl(database, events);
  final chapters = ChapterRepositoryImpl(database, events);
  final progress = ProgressRepositoryImpl(database, events);
  final taskStore = CrawlTaskStore(database, events);
  final ruleStore = SourceRuleStore(database, events);
  final ruleEngine = RuleEngine(ruleStore);
  final parser = ParserEngineImpl(http, ruleEngine: ruleEngine);
  final resolver = BookResolverImpl(http, parser);

  final crawl = CrawlServiceImpl(
    books: books,
    chapters: chapters,
    tasks: taskStore,
    parser: parser,
    background: background,
    documentsDirectory: documentsDirectory,
  );
  final settings = SettingsServiceImpl();
  final rules = SiteRuleServiceImpl(ruleStore);
  final diagnostics = DiagnosticsServiceImpl(
    database: database,
    tasks: taskStore,
    settings: settings,
  );

  // 后台隔离共用同一执行内核：租约互斥保证与前台队列不双跑。
  final runner = CrawlTaskRunner(
    books: books,
    chapters: chapters,
    tasks: taskStore,
    parser: parser,
    exportTxt: (bookId) => crawl.exportTxt(bookId),
  );

  final services = AppServices(
    http: http,
    parser: parser,
    resolver: resolver,
    books: books,
    chapters: chapters,
    progress: progress,
    crawl: crawl,
    settings: settings,
    rules: rules,
    diagnostics: diagnostics,
    dispose: () async {
      http.dispose();
      await events.dispose();
      await database.close();
    },
  );

  return AppServicesBundle(services: services, crawlRunner: runner);
}
