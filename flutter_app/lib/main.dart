import 'package:flutter/material.dart';

import 'app.dart';
import 'bootstrap.dart';
import 'impl/background_impl.dart';

/// 简阅·Flutter 装配入口（主 Agent 维护）：
/// 装配顺序对齐 Kotlin 版 AppContainer（security/http → parser(规则引擎) →
/// resolver → DB → repos → 采集 → 设置 → 规则 → 诊断），统一在
/// `bootstrap.buildAppServices` 完成；前台与后台隔离共用同一装配入口。
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // WP-B：后台采集隔离（Android WorkManager；初始化失败自动退化为仅前台）。
  final background = WorkmanagerCrawlScheduler();
  await background.ensureInitialized();

  final bundle = await buildAppServices(background: background);

  // 进程重启后恢复未完成采集任务（对齐 Kotlin 版 reconcileOnAppStart，
  // 并重新挂后台 Work）
  await bundle.services.crawl.restoreOnStartup();

  runApp(JianYueApp(services: bundle.services));
}
