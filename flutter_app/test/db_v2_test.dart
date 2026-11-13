// DB v2 测试：source_rules 存取 + 采集任务租约（双入口防双跑核心语义）。
// 使用 sqflite_common_ffi 内存库（dev 依赖，已在 pubspec dev_dependencies）。
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:jianyue_flutter/contracts.dart';
import 'package:jianyue_flutter/core/app_result.dart';
import 'package:jianyue_flutter/core/site_rule.dart';
import 'package:jianyue_flutter/impl/data_impl.dart';

Future<int> _insertBook(AppDatabase database) async {
  return database.db.insert('books', {
    'title': '测试书',
    'source_url': 'https://www.example.com/book/1.html',
    'source_domain': 'www.example.com',
    'total_chapters': 0,
    'created_at': 0,
    'updated_at': 0,
  });
}

/// v1 表结构（与 1.0.0 发布版一致：无 source_rules、crawl_tasks 无 lease_until）。
Future<void> _createV1Schema(Database db, int version) async {
  await db.execute('''
    CREATE TABLE books (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      title TEXT NOT NULL,
      author TEXT,
      cover_url TEXT,
      description TEXT,
      source_url TEXT NOT NULL,
      source_domain TEXT NOT NULL,
      catalog_url TEXT,
      latest_chapter_title TEXT,
      total_chapters INTEGER NOT NULL DEFAULT 0,
      current_chapter_id INTEGER,
      last_read_at INTEGER,
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL
    )
  ''');
  await db.execute('''
    CREATE TABLE crawl_tasks (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      book_id INTEGER NOT NULL,
      state TEXT NOT NULL,
      total_chapters INTEGER NOT NULL DEFAULT 0,
      succeeded INTEGER NOT NULL DEFAULT 0,
      failed INTEGER NOT NULL DEFAULT 0,
      current_index INTEGER NOT NULL DEFAULT 0,
      current_title TEXT,
      txt_path TEXT,
      created_at INTEGER NOT NULL,
      updated_at INTEGER NOT NULL
    )
  ''');
  await db.execute(
    'CREATE INDEX idx_crawl_tasks_book ON crawl_tasks(book_id, state)',
  );
}

void main() {
  setUpAll(() {
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
  });

  group('SourceRuleStore（DB v2）', () {
    late AppDatabase database;
    late SourceRuleStore store;

    setUp(() async {
      database = await AppDatabase.open(path: inMemoryDatabasePath);
      store = SourceRuleStore(database, DataEvents());
    });

    tearDown(() async {
      await database.close();
    });

    test('保存/查询/按域名启用过滤', () async {
      const ruleJson = '''
        {"ruleId": "manual.www.example.com", "domain": "www.example.com", "version": 1}
      ''';
      final saved = await store.saveRule(
        ruleId: 'manual.www.example.com',
        domain: 'www.example.com',
        version: 1,
        ruleJson: ruleJson,
        enabled: true,
      );
      expect(saved.isSuccess, isTrue);
      expect(saved.valueOrNull!.ruleId, 'manual.www.example.com');

      final byDomain = await store.findEnabledByDomain('www.example.com');
      expect(byDomain, isNotNull);

      // 禁用后按域名查不到
      await store.setEnabled('manual.www.example.com', false);
      expect(await store.findEnabledByDomain('www.example.com'), isNull);
      // 列表仍在（含禁用）
      expect(await store.listRules(), hasLength(1));

      // 同域名第二条被拒绝（UNIQUE domain 语义）
      final conflict = await store.saveRule(
        ruleId: 'demo.v1',
        domain: 'www.example.com',
        version: 1,
        ruleJson: '{"ruleId": "demo.v1"}',
        enabled: true,
      );
      expect(conflict.isSuccess, isFalse);

      // 域名查询大小写归一
      await store.setEnabled('manual.www.example.com', true);
      expect(
        await store.findEnabledByDomain('WWW.Example.COM'),
        isNotNull,
      );
    });

    test('删除与 RULE_NOT_FOUND', () async {
      final missing = await store.deleteRule('nope.v1');
      expect(missing.isSuccess, isFalse);

      await store.saveRule(
        ruleId: 'manual.a.com',
        domain: 'a.com',
        version: 1,
        ruleJson: '{"ruleId": "manual.a.com", "domain": "a.com"}',
        enabled: true,
      );
      final deleted = await store.deleteRule('manual.a.com');
      expect(deleted.isSuccess, isTrue);
      expect(await store.listRules(), isEmpty);
    });

    test('存储规则可被 Schema 解析（引擎入口闭环）', () async {
      const draft = RuleDraft(
        domain: 'www.example.com',
        bookNameSelector: 'h1',
        chapterLinkSelector: 'dd a',
      );
      await store.saveRule(
        ruleId: 'manual.www.example.com',
        domain: draft.domain,
        version: 1,
        ruleJson: encodeRuleDraft(draft),
        enabled: true,
      );
      final stored = await store.findEnabledByDomain('www.example.com');
      final parsed = parseRuleSchema(stored!.ruleJson);
      expect(parsed.isValid, isTrue);
      expect(parsed.rule!.ruleId, 'manual.www.example.com');
    });
  });

  group('CrawlTaskStore 租约（防双跑）', () {
    late AppDatabase database;
    late CrawlTaskStore store;

    setUp(() async {
      database = await AppDatabase.open(path: inMemoryDatabasePath);
      store = CrawlTaskStore(database, DataEvents());
    });

    tearDown(() async {
      await database.close();
    });

    test('抢占→未过期拒绝→过期重占→释放', () async {
      final bookId = await _insertBook(database);
      final task = await store.createTask(bookId, 10);
      const ttl = Duration(seconds: 1);

      // 终态任务不允许拿租约
      await store.updateState(task.id, CrawlState.completed);
      expect(
        await store.tryAcquireLease(task.id, 1000, ttl: ttl),
        isFalse,
      );
      await store.updateState(task.id, CrawlState.waiting);

      // 抢占成功
      expect(await store.tryAcquireLease(task.id, 1000, ttl: ttl), isTrue);
      // 未过期：其他执行方拿不到
      expect(await store.tryAcquireLease(task.id, 1500, ttl: ttl), isFalse);
      // 过期（>1s 后）：可以重占
      expect(await store.tryAcquireLease(task.id, 3000, ttl: ttl), isTrue);
      // 释放后立即可再抢
      await store.clearLease(task.id);
      expect(await store.tryAcquireLease(task.id, 3100, ttl: ttl), isTrue);
    });

    test('续租不抛异常且延长窗口', () async {
      final bookId = await _insertBook(database);
      final task = await store.createTask(bookId, 10);
      const ttl = Duration(seconds: 1);
      expect(await store.tryAcquireLease(task.id, 1000, ttl: ttl), isTrue);
      await store.renewLease(task.id, 1500, ttl: ttl);
      // 续租后 1500+1000=2500 前不可抢占
      expect(await store.tryAcquireLease(task.id, 2000, ttl: ttl), isFalse);
      expect(await store.tryAcquireLease(task.id, 2600, ttl: ttl), isTrue);
    });
  });

  group('DB v1 → v2 迁移（1.0.0 → 1.1.0 升级路径）', () {
    late String path;

    setUp(() async {
      path = p.join(
        Directory.systemTemp.path,
        'jianyue_migration_${DateTime.now().microsecondsSinceEpoch}.db',
      );
    });

    tearDown(() async {
      final file = File(path);
      if (await file.exists()) await file.delete();
    });

    test('保留旧数据、新增 source_rules 与 lease_until', () async {
      // 1) 建立 v1 库并写入一条旧数据
      final legacy = await databaseFactory.openDatabase(
        path,
        options: OpenDatabaseOptions(
          version: 1,
          onCreate: _createV1Schema,
        ),
      );
      await legacy.insert('books', {
        'title': '旧书',
        'source_url': 'https://www.example.com/book/9.html',
        'source_domain': 'www.example.com',
        'total_chapters': 0,
        'created_at': 0,
        'updated_at': 0,
      });
      await legacy.close();

      // 2) 用生产入口重开（触发 onUpgrade → v2）
      final upgraded = await AppDatabase.open(path: path);
      final db = upgraded.db;

      // 旧数据仍在
      final books = await db.query('books');
      expect(books, hasLength(1));
      expect(books.first['title'], '旧书');

      // 新表已建
      final rulesTable = await db.rawQuery(
        "SELECT name FROM sqlite_master WHERE type='table' AND name='source_rules'",
      );
      expect(rulesTable, hasLength(1));

      // 新列已补（ALTER TABLE）
      final columns = await db.rawQuery('PRAGMA table_info(crawl_tasks)');
      final names = columns.map((c) => c['name'] as String).toSet();
      expect(names, contains('lease_until'));

      // 3) 迁移后的库可正常读写规则（闭环）
      final store = SourceRuleStore(upgraded, DataEvents());
      final saved = await store.saveRule(
        ruleId: 'manual.www.example.com',
        domain: 'www.example.com',
        version: 1,
        ruleJson:
            '{"ruleId": "manual.www.example.com", "domain": "www.example.com", "version": 1}',
        enabled: true,
      );
      expect(saved.isSuccess, isTrue);
      await upgraded.close();
    });
  });
}
