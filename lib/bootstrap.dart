/// 应用装配（对齐 Kotlin 版 AppContainer 顺序）：
/// security/http → parser(规则引擎) → resolver → DB → repos/stores →
/// 采集 → 设置 → 规则 → 诊断。
///
/// 前台 [main.dart] 与后台隔离 [background_impl.dart] 的 dispatcher 共用本入口；
/// 后台隔离重建整套服务（独立 DB 连接），任务结束经 `AppServices.dispose` 释放。
library;

import 'dart:io' show Directory;

import 'contracts.dart';
import 'impl/crawl_impl.dart';
import 'impl/crawl_task_runner.dart';
import 'impl/data_impl.dart';
import 'impl/diagnostics_impl.dart';
import 'impl/parser_impl.dart';
import 'impl/resolver_impl.dart';
import 'impl/rule_engine_impl.dart';
import 'impl/secure_http_impl.dart';

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
