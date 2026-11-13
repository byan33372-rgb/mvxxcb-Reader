import 'package:flutter/material.dart';

import 'app.dart';
import 'contracts.dart';
import 'impl/crawl_impl.dart';
import 'impl/data_impl.dart';
import 'impl/parser_impl.dart';
import 'impl/resolver_impl.dart';
import 'impl/secure_http_impl.dart';

/// 简阅·Flutter 装配入口（主 Agent 维护）：
/// 顺序对齐 Kotlin 版 AppContainer：security/http → parser → resolver → DB → repos → 采集 → 设置。
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  final http = SecureHttpClientImpl();
  final parser = ParserEngineImpl(http);
  final resolver = BookResolverImpl(http, parser);

  final database = await AppDatabase.open();
  final events = DataEvents();
  final books = BookRepositoryImpl(database, events);
  final chapters = ChapterRepositoryImpl(database, events);
  final progress = ProgressRepositoryImpl(database, events);
  final taskStore = CrawlTaskStore(database, events);

  final crawl = CrawlServiceImpl(
    books: books,
    chapters: chapters,
    tasks: taskStore,
    parser: parser,
  );
  final settings = SettingsServiceImpl();

  final services = AppServices(
    http: http,
    parser: parser,
    resolver: resolver,
    books: books,
    chapters: chapters,
    progress: progress,
    crawl: crawl,
    settings: settings,
  );

  // 进程重启后恢复未完成采集任务（对齐 Kotlin 版 reconcileOnAppStart）
  await crawl.restoreOnStartup();

  runApp(JianYueApp(services: services));
}