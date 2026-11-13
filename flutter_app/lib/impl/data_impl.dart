/// 本地数据层（sqflite）：BookRepository / ChapterRepository / ProgressRepository
/// 与采集任务表访问。
///
/// 表结构按任务书冻结（books / chapters / reading_progress / crawl_tasks）；
/// DB v2 追加（对齐 Kotlin 版 03 §2.5）：
///  - `source_rules` 表：站点规则存储（domain UNIQUE，不与书关联）；
///  - `crawl_tasks.lease_until`：任务执行租约（毫秒时间戳），前台/后台双入口
///    防双跑——同一任务同时只允许一个执行方持有租约。
/// 目录合并使用 `catalog_merge.dart` 的纯计划函数，执行层在事务内完成：
///  - URL 命中保 id；
///  - 标题指纹 + 邻位消歧换址保 id；
///  - 新章插入；
///  - 旧章未出现 → is_missing=1（不删除，保护进度/缓存）；
///  - 最后同步 books.total_chapters / latest_chapter_title / catalog_url。
library;

import 'dart:async';
import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite/sqflite.dart';

import '../contracts.dart';
import '../core/app_result.dart';
import '../core/reader_error.dart';
import '../core/site_rule.dart';
import 'catalog_merge.dart';
import 'url_utils.dart';

// ============================================================
// 数据库
// ============================================================

class AppDatabase {
  AppDatabase._(this.db);

  final Database db;

  static Future<AppDatabase> open({String? path}) async {
    final dbPath = path ?? p.join(await getDatabasesPath(), 'jianyue.db');
    final db = await openDatabase(
      dbPath,
      version: 2,
      onConfigure: (db) async {
        await db.execute('PRAGMA foreign_keys = ON');
      },
      onCreate: _createSchema,
      onUpgrade: _upgradeSchema,
    );
    return AppDatabase._(db);
  }

  Future<void> close() => db.close();

  static Future<void> _createSchema(Database db, int version) async {
    await db.execute('''
      CREATE TABLE books (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        title TEXT NOT NULL DEFAULT '',
        author TEXT,
        cover_url TEXT,
        description TEXT,
        source_url TEXT NOT NULL UNIQUE,
        source_domain TEXT NOT NULL DEFAULT '',
        catalog_url TEXT,
        latest_chapter_title TEXT,
        total_chapters INTEGER NOT NULL DEFAULT 0,
        current_chapter_id INTEGER,
        last_read_at INTEGER,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        is_temporary INTEGER NOT NULL DEFAULT 0
      )
    ''');
    await db.execute('''
      CREATE TABLE chapters (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        book_id INTEGER NOT NULL,
        chapter_index INTEGER NOT NULL,
        title TEXT NOT NULL DEFAULT '',
        url TEXT NOT NULL,
        normalized_url TEXT NOT NULL,
        title_fingerprint TEXT NOT NULL DEFAULT '',
        content TEXT,
        content_hash TEXT,
        is_cached INTEGER NOT NULL DEFAULT 0,
        is_missing INTEGER NOT NULL DEFAULT 0,
        fetched_at INTEGER,
        word_count INTEGER NOT NULL DEFAULT 0,
        UNIQUE(book_id, chapter_index),
        FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE
      )
    ''');
    await db.execute('''
      CREATE TABLE reading_progress (
        book_id INTEGER PRIMARY KEY,
        chapter_id INTEGER NOT NULL,
        chapter_index INTEGER NOT NULL DEFAULT 0,
        in_fraction REAL NOT NULL DEFAULT 0,
        char_offset INTEGER NOT NULL DEFAULT -1,
        percentage REAL NOT NULL DEFAULT 0,
        updated_at INTEGER NOT NULL,
        FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE
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
        lease_until INTEGER,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL,
        FOREIGN KEY(book_id) REFERENCES books(id) ON DELETE CASCADE
      )
    ''');
    await db.execute(
      'CREATE INDEX idx_chapters_book_cached ON chapters(book_id, is_cached)',
    );
    await db.execute(
      'CREATE INDEX idx_chapters_book_normalized ON chapters(book_id, normalized_url)',
    );
    await db.execute(
      'CREATE INDEX idx_chapters_book_fingerprint ON chapters(book_id, title_fingerprint)',
    );
    await db.execute('CREATE INDEX idx_books_last_read ON books(last_read_at)');
    await db.execute(
      'CREATE INDEX idx_crawl_tasks_book ON crawl_tasks(book_id, state)',
    );
    await _createV2Objects(db);
  }

  /// v2 对象（source_rules 表）；onCreate 与 onUpgrade 共用，保证幂等。
  static Future<void> _createV2Objects(Database db) async {
    await db.execute('''
      CREATE TABLE IF NOT EXISTS source_rules (
        rule_id TEXT PRIMARY KEY,
        domain TEXT NOT NULL UNIQUE,
        version INTEGER NOT NULL DEFAULT 1,
        rule_json TEXT NOT NULL,
        enabled INTEGER NOT NULL DEFAULT 1,
        created_at INTEGER NOT NULL,
        updated_at INTEGER NOT NULL
      )
    ''');
    await db.execute(
      'CREATE INDEX IF NOT EXISTS idx_source_rules_domain ON source_rules(domain)',
    );
  }

  /// v1 → v2：source_rules 表 + crawl_tasks.lease_until 列（只增不删，保用户数据）。
  static Future<void> _upgradeSchema(
    Database db,
    int oldVersion,
    int newVersion,
  ) async {
    if (oldVersion < 2) {
      await _createV2Objects(db);
      // 已存在的旧表逐列补齐（ALTER TABLE 无 IF NOT EXISTS，先探测列）。
      final columns = await db.rawQuery('PRAGMA table_info(crawl_tasks)');
      final names = columns.map((c) => (c['name'] ?? '') as String).toSet();
      if (!names.contains('lease_until')) {
        await db.execute(
          'ALTER TABLE crawl_tasks ADD COLUMN lease_until INTEGER',
        );
      }
    }
  }
}

/// 数据变更广播（sqflite 无原生响应式流，写后重查 + 广播序号）。
class DataEvents {
  final StreamController<int> _controller = StreamController<int>.broadcast();
  int _sequence = 0;

  Stream<int> get stream => _controller.stream;

  void notify() {
    if (_controller.isClosed) return;
    _controller.add(++_sequence);
  }

  Future<void> dispose() => _controller.close();
}

// ============================================================
// BookRepository
// ============================================================

class BookRepositoryImpl implements BookRepository {
  BookRepositoryImpl(this._database, this._events);

  final AppDatabase _database;
  final DataEvents _events;

  Database get _db => _database.db;

  @override
  Stream<List<Book>> observeShelf() async* {
    yield await _queryShelf();
    await for (final _ in _events.stream) {
      yield await _queryShelf();
    }
  }

  Future<List<Book>> _queryShelf() async {
    final rows = await _db.query(
      'books',
      where: 'is_temporary = 0',
      orderBy: 'COALESCE(last_read_at, updated_at) DESC, id DESC',
    );
    return rows.map(bookFromRow).toList(growable: false);
  }

  @override
  Future<Book?> getBook(int bookId) async {
    final rows = await _db.query('books', where: 'id = ?', whereArgs: [bookId], limit: 1);
    if (rows.isEmpty) return null;
    return bookFromRow(rows.first);
  }

  @override
  Future<Book?> findBySourceUrl(String normalizedUrl) async {
    final normalized = normalizeUrl(normalizedUrl) ?? normalizedUrl.trim();
    final rows = await _db.query(
      'books',
      where: 'source_url = ?',
      whereArgs: [normalized],
      limit: 1,
    );
    if (rows.isEmpty) return null;
    return bookFromRow(rows.first);
  }

  @override
  Future<AppResult<int>> upsertFromImport({
    ParsedBookDetail? detail,
    required String sourceUrl,
    ParsedCatalog? catalog,
  }) async {
    final normalized = normalizeUrl(sourceUrl);
    if (normalized == null) {
      return Failure(NetworkError('INVALID_URL', url: redactUrl(sourceUrl)));
    }
    final now = DateTime.now().millisecondsSinceEpoch;
    final title = _nonEmpty(detail?.title) ?? '';
    final sourceDomain = hostOf(normalized) ?? '';

    try {
      final existing = await findBySourceUrl(normalized);
      final bookId = await _db.transaction<int>((txn) async {
        int id;
        if (existing != null) {
          final values = <String, Object?>{
            'source_domain': sourceDomain,
            'updated_at': now,
          };
          if (title.isNotEmpty) values['title'] = title;
          if (detail?.author != null) values['author'] = detail!.author;
          if (detail?.coverUrl != null) values['cover_url'] = detail!.coverUrl;
          if (detail?.description != null) {
            values['description'] = detail!.description;
          }
          final catalogUrl = catalog?.sourceUrl ?? detail?.catalogUrl;
          if (catalogUrl != null) values['catalog_url'] = catalogUrl;
          if (catalog != null) {
            values['total_chapters'] = catalog.chapters.length;
            if (catalog.chapters.isNotEmpty) {
              values['latest_chapter_title'] = catalog.chapters.last.title;
            }
          }
          await txn.update(
            'books',
            values,
            where: 'id = ?',
            whereArgs: [existing.id],
          );
          id = existing.id;
        } else {
          id = await txn.insert('books', {
            'title': title,
            'author': detail?.author,
            'cover_url': detail?.coverUrl,
            'description': detail?.description,
            'source_url': normalized,
            'source_domain': sourceDomain,
            'catalog_url': catalog?.sourceUrl ?? detail?.catalogUrl,
            'latest_chapter_title':
                catalog != null && catalog.chapters.isNotEmpty
                    ? catalog.chapters.last.title
                    : null,
            'total_chapters': catalog?.chapters.length ?? 0,
            'created_at': now,
            'updated_at': now,
            'is_temporary': 0,
          });
        }
        if (catalog != null && catalog.chapters.isNotEmpty) {
          await executeCatalogMerge(txn, id, catalog, now);
        }
        return id;
      });
      _events.notify();
      return Success(bookId);
    } catch (e) {
      return Failure(StorageError('DB_ERROR', '导入入库失败: $e'));
    }
  }

  @override
  Future<AppResult<void>> updateBook(Book book) async {
    if (book.id <= 0) {
      return Failure(StateError('INVALID_ARGUMENT', 'updateBook: id=${book.id}'));
    }
    try {
      final rows = await _db.update(
        'books',
        {
          'title': book.title,
          'author': book.author,
          'cover_url': book.coverUrl,
          'description': book.description,
          'source_url': book.sourceUrl,
          'source_domain': book.sourceDomain,
          'catalog_url': book.catalogUrl,
          'latest_chapter_title': book.latestChapterTitle,
          'total_chapters': book.totalChapters,
          'current_chapter_id': book.currentChapterId,
          'last_read_at': book.lastReadAt,
          'created_at': book.createdAt,
          'updated_at': book.updatedAt,
          'is_temporary': book.isTemporary ? 1 : 0,
        },
        where: 'id = ?',
        whereArgs: [book.id],
      );
      if (rows == 0) {
        return Failure(StateError('BOOK_NOT_FOUND', 'updateBook: id=${book.id}'));
      }
      _events.notify();
      return const Success<void>(null);
    } catch (e) {
      return Failure(StorageError('DB_ERROR', '更新书籍失败: $e'));
    }
  }

  @override
  Future<AppResult<void>> deleteBook(int bookId) async {
    try {
      final rows = await _db.delete('books', where: 'id = ?', whereArgs: [bookId]);
      if (rows == 0) {
        return Failure(StateError('BOOK_NOT_FOUND', 'deleteBook: id=$bookId'));
      }
      _events.notify();
      return const Success<void>(null);
    } catch (e) {
      return Failure(StorageError('DB_ERROR', '删除书籍失败: $e'));
    }
  }
}

// ============================================================
// ChapterRepository
// ============================================================

class ChapterRepositoryImpl implements ChapterRepository {
  ChapterRepositoryImpl(this._database, this._events);

  final AppDatabase _database;
  final DataEvents _events;

  Database get _db => _database.db;

  @override
  Stream<List<ChapterMeta>> observeChapterMetas(int bookId) async* {
    yield await _queryMetas(bookId);
    await for (final _ in _events.stream) {
      yield await _queryMetas(bookId);
    }
  }

  Future<List<ChapterMeta>> _queryMetas(int bookId) async {
    final rows = await _db.query(
      'chapters',
      where: 'book_id = ?',
      whereArgs: [bookId],
      orderBy: 'chapter_index ASC',
    );
    return rows.map(chapterMetaFromRow).toList(growable: false);
  }

  @override
  Future<ChapterMeta?> getChapterMeta(int chapterId) async {
    final rows = await _db.query(
      'chapters',
      where: 'id = ?',
      whereArgs: [chapterId],
      limit: 1,
    );
    if (rows.isEmpty) return null;
    return chapterMetaFromRow(rows.first);
  }

  @override
  Future<Chapter?> getChapterByIndex(int bookId, int chapterIndex) async {
    final rows = await _db.query(
      'chapters',
      where: 'book_id = ? AND chapter_index = ?',
      whereArgs: [bookId, chapterIndex],
      limit: 1,
    );
    if (rows.isEmpty) return null;
    return chapterFromRow(rows.first);
  }

  @override
  Future<Chapter?> getChapterByUrl(int bookId, String url) async {
    final normalized = normalizeUrl(url) ?? url.trim();
    final rows = await _db.query(
      'chapters',
      where: 'book_id = ? AND normalized_url = ?',
      whereArgs: [bookId, normalized],
      limit: 1,
    );
    if (rows.isEmpty) return null;
    return chapterFromRow(rows.first);
  }

  @override
  Future<AppResult<int>> replaceCatalog(int bookId, ParsedCatalog catalog) async {
    if (catalog.chapters.isEmpty) {
      return Failure(ParseError('CATALOG_EMPTY', 'replaceCatalog: 空目录拒绝写入'));
    }
    final bookRows = await _db.query(
      'books',
      columns: ['id'],
      where: 'id = ?',
      whereArgs: [bookId],
      limit: 1,
    );
    if (bookRows.isEmpty) {
      return Failure(StateError('BOOK_NOT_FOUND', 'replaceCatalog: bookId=$bookId'));
    }
    final now = DateTime.now().millisecondsSinceEpoch;
    try {
      final total = await _db.transaction<int>((txn) async {
        return executeCatalogMerge(txn, bookId, catalog, now);
      });
      _events.notify();
      return Success(total);
    } catch (e) {
      return Failure(StorageError('DB_ERROR', '目录合并失败: $e'));
    }
  }

  @override
  Future<AppResult<void>> saveContent(
    int chapterId,
    ParsedChapterContent content,
  ) async {
    final jsonText = encodeContentBlocks(content.blocks);
    final hash = sha256.convert(utf8.encode(jsonText)).toString();
    final computedWordCount = content.wordCount > 0
        ? content.wordCount
        : content.blocks
            .whereType<Paragraph>()
            .fold<int>(0, (sum, p) => sum + p.text.length);
    try {
      final rows = await _db.update(
        'chapters',
        {
          'content': jsonText,
          'content_hash': hash,
          'is_cached': 1,
          'is_missing': 0,
          'fetched_at': DateTime.now().millisecondsSinceEpoch,
          'word_count': computedWordCount,
        },
        where: 'id = ?',
        whereArgs: [chapterId],
      );
      if (rows == 0) {
        return Failure(StateError('CHAPTER_NOT_FOUND', 'saveContent: id=$chapterId'));
      }
      _events.notify();
      return const Success<void>(null);
    } catch (e) {
      return Failure(StorageError('DB_ERROR', '保存正文失败: $e'));
    }
  }

  @override
  Future<ReaderContent?> loadContent(int chapterId) async {
    final rows = await _db.query(
      'chapters',
      where: 'id = ?',
      whereArgs: [chapterId],
      limit: 1,
    );
    if (rows.isEmpty) return null;
    final row = rows.first;
    final content = row['content'] as String?;
    if (content == null || content.isEmpty) return null;
    try {
      final blocks = decodeContentBlocks(content);
      if (blocks.isEmpty) return null;
      return ReaderContent(
        chapterId: chapterId,
        chapterIndex: (row['chapter_index'] as int?) ?? 0,
        bookId: (row['book_id'] as int?) ?? 0,
        title: (row['title'] as String?) ?? '',
        blocks: blocks,
      );
    } catch (_) {
      return null;
    }
  }
}

// ============================================================
// ProgressRepository
// ============================================================

class ProgressRepositoryImpl implements ProgressRepository {
  ProgressRepositoryImpl(this._database, this._events);

  final AppDatabase _database;
  final DataEvents _events;

  Database get _db => _database.db;

  @override
  Future<ReadingProgress?> getProgress(int bookId) async {
    final rows = await _db.query(
      'reading_progress',
      where: 'book_id = ?',
      whereArgs: [bookId],
      limit: 1,
    );
    if (rows.isEmpty) return null;
    return progressFromRow(rows.first);
  }

  @override
  Stream<ReadingProgress?> observeProgress(int bookId) async* {
    yield await getProgress(bookId);
    await for (final _ in _events.stream) {
      yield await getProgress(bookId);
    }
  }

  @override
  Future<AppResult<void>> saveProgress(ReadingProgress progress) async {
    if (progress.bookId <= 0) {
      return Failure(StateError('INVALID_ARGUMENT', 'saveProgress: bookId=${progress.bookId}'));
    }
    try {
      await _db.transaction((txn) async {
        await txn.insert(
          'reading_progress',
          {
            'book_id': progress.bookId,
            'chapter_id': progress.chapterId,
            'chapter_index': progress.chapterIndex,
            'in_fraction': progress.inChapterFraction,
            'char_offset': progress.charOffset,
            'percentage': progress.progressPercentage,
            'updated_at': progress.updatedAt,
          },
          conflictAlgorithm: ConflictAlgorithm.replace,
        );
        await txn.update(
          'books',
          {
            'current_chapter_id': progress.chapterId,
            'last_read_at': progress.updatedAt,
            'updated_at': progress.updatedAt,
          },
          where: 'id = ?',
          whereArgs: [progress.bookId],
        );
      });
      _events.notify();
      return const Success<void>(null);
    } catch (e) {
      return Failure(StorageError('DB_ERROR', '保存进度失败: $e'));
    }
  }
}

// ============================================================
// 采集任务表访问
// ============================================================

class CrawlTaskStore {
  CrawlTaskStore(this._database, this._events);

  final AppDatabase _database;
  final DataEvents _events;

  Database get _db => _database.db;

  Stream<List<CrawlTask>> observeTasks() async* {
    yield await listTasks();
    await for (final _ in _events.stream) {
      yield await listTasks();
    }
  }

  Stream<CrawlTask?> observeTaskForBook(int bookId) async* {
    yield await activeForBook(bookId);
    await for (final _ in _events.stream) {
      yield await activeForBook(bookId);
    }
  }

  Future<List<CrawlTask>> listTasks() async {
    final rows = await _db.query('crawl_tasks', orderBy: 'updated_at DESC, id DESC');
    return rows.map(crawlTaskFromRow).toList(growable: false);
  }

  Future<CrawlTask?> getTask(int taskId) async {
    final rows = await _db.query(
      'crawl_tasks',
      where: 'id = ?',
      whereArgs: [taskId],
      limit: 1,
    );
    if (rows.isEmpty) return null;
    return crawlTaskFromRow(rows.first);
  }

  /// 同书非终态任务（waiting/running/paused）。
  Future<CrawlTask?> activeForBook(int bookId) async {
    final rows = await _db.query(
      'crawl_tasks',
      where: "book_id = ? AND state IN ('waiting','running','paused')",
      whereArgs: [bookId],
      orderBy: 'updated_at DESC, id DESC',
      limit: 1,
    );
    if (rows.isEmpty) return null;
    return crawlTaskFromRow(rows.first);
  }

  Future<CrawlTask> createTask(int bookId, int totalChapters) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    final id = await _db.insert('crawl_tasks', {
      'book_id': bookId,
      'state': CrawlState.waiting.name,
      'total_chapters': totalChapters,
      'succeeded': 0,
      'failed': 0,
      'current_index': 0,
      'created_at': now,
      'updated_at': now,
    });
    _events.notify();
    return (await getTask(id))!;
  }

  Future<void> updateState(int taskId, CrawlState state) async {
    await _db.update(
      'crawl_tasks',
      {
        'state': state.name,
        'updated_at': DateTime.now().millisecondsSinceEpoch,
      },
      where: 'id = ?',
      whereArgs: [taskId],
    );
    _events.notify();
  }

  Future<void> updateProgress(
    int taskId, {
    required int succeeded,
    required int failed,
    required int currentIndex,
    String? currentTitle,
  }) async {
    await _db.update(
      'crawl_tasks',
      {
        'succeeded': succeeded,
        'failed': failed,
        'current_index': currentIndex,
        'current_title': currentTitle,
        'updated_at': DateTime.now().millisecondsSinceEpoch,
      },
      where: 'id = ?',
      whereArgs: [taskId],
    );
    _events.notify();
  }

  Future<void> markTxtGenerated(int taskId, String txtPath) async {
    await _db.update(
      'crawl_tasks',
      {
        'txt_path': txtPath,
        'updated_at': DateTime.now().millisecondsSinceEpoch,
      },
      where: 'id = ?',
      whereArgs: [taskId],
    );
    _events.notify();
  }

  Future<void> resetForRetry(int taskId, int succeeded) async {
    await _db.update(
      'crawl_tasks',
      {
        'state': CrawlState.waiting.name,
        'succeeded': succeeded,
        'failed': 0,
        'updated_at': DateTime.now().millisecondsSinceEpoch,
      },
      where: 'id = ?',
      whereArgs: [taskId],
    );
    _events.notify();
  }

  // ---------------- 执行租约（前台/后台双入口防双跑） ----------------

  /// 尝试获取租约：仅当无租约或租约已过期时成功（原子 UPDATE 判定）。
  /// 返回 true = 本执行方获得租约，可以启动任务循环。
  Future<bool> tryAcquireLease(
    int taskId,
    int nowMs, {
    Duration ttl = const Duration(minutes: 2),
  }) async {
    final until = nowMs + ttl.inMilliseconds;
    final rows = await _db.update(
      'crawl_tasks',
      {'lease_until': until},
      where:
          'id = ? AND (lease_until IS NULL OR lease_until <= ?) '
          "AND state IN ('waiting','running','paused')",
      whereArgs: [taskId, nowMs],
    );
    if (rows > 0) _events.notify();
    return rows > 0;
  }

  /// 续租（循环执行中周期调用；失败仅忽略，下次重试）。
  Future<void> renewLease(
    int taskId,
    int nowMs, {
    Duration ttl = const Duration(minutes: 2),
  }) async {
    try {
      await _db.update(
        'crawl_tasks',
        {'lease_until': nowMs + ttl.inMilliseconds},
        where: 'id = ?',
        whereArgs: [taskId],
      );
    } catch (_) {
      // 续租失败不影响主循环（租约过期最坏情形：后台与前台短暂双跑，
      // 逐章 DB 状态检查仍保证进度一致）
    }
  }

  /// 释放租约（任务进入终态/暂停/取消时调用）。
  Future<void> clearLease(int taskId) async {
    try {
      await _db.update(
        'crawl_tasks',
        {'lease_until': null},
        where: 'id = ?',
        whereArgs: [taskId],
      );
    } catch (_) {
      // ignore
    }
  }
}

// ============================================================
// 站点规则存储（DB v2，对齐 Kotlin source_rules 表 03 §2.5）
// ============================================================

class SourceRuleStore {
  SourceRuleStore(this._database, this._events);

  final AppDatabase _database;
  final DataEvents _events;

  Database get _db => _database.db;

  Future<List<StoredSiteRule>> listRules() async {
    final rows = await _db.query(
      'source_rules',
      orderBy: 'updated_at DESC, rule_id ASC',
    );
    return rows.map(_ruleFromRow).toList(growable: false);
  }

  Future<StoredSiteRule?> getRule(String ruleId) async {
    final rows = await _db.query(
      'source_rules',
      where: 'rule_id = ?',
      whereArgs: [ruleId],
      limit: 1,
    );
    if (rows.isEmpty) return null;
    return _ruleFromRow(rows.first);
  }

  /// 按域名取启用中的规则（规则引擎入口；同域唯一约束保证至多一条）。
  Future<StoredSiteRule?> findEnabledByDomain(String domain) async {
    final rows = await _db.query(
      'source_rules',
      where: 'domain = ? AND enabled = 1',
      whereArgs: [domain.trim().toLowerCase()],
      limit: 1,
    );
    if (rows.isEmpty) return null;
    return _ruleFromRow(rows.first);
  }

  /// 保存（新建或整体覆盖）。domain 冲突时拒绝——同一域名只允许一条规则，
  /// 由 UNIQUE(domain) 兜底。
  Future<AppResult<StoredSiteRule>> saveRule({
    required String ruleId,
    required String domain,
    required int version,
    required String ruleJson,
    required bool enabled,
  }) async {
    final now = DateTime.now().millisecondsSinceEpoch;
    final normalizedDomain = domain.trim().toLowerCase();
    try {
      final existing = await getRule(ruleId);
      final values = <String, Object?>{
        'domain': normalizedDomain,
        'version': version,
        'rule_json': ruleJson,
        'enabled': enabled ? 1 : 0,
        'updated_at': now,
      };
      if (existing == null) {
        values['created_at'] = now;
        await _db.insert(
          'source_rules',
          values..['rule_id'] = ruleId,
          conflictAlgorithm: ConflictAlgorithm.abort,
        );
      } else {
        // 保留原 created_at；域名被其他规则占用时 INSERT/UPDATE 报约束错。
        await _db.update(
          'source_rules',
          values,
          where: 'rule_id = ?',
          whereArgs: [ruleId],
        );
      }
      _events.notify();
      return Success((await getRule(ruleId))!);
    } catch (e) {
      return Failure(StorageError('DB_ERROR', '保存站点规则失败: $e'));
    }
  }

  Future<AppResult<void>> setEnabled(String ruleId, bool enabled) async {
    try {
      final rows = await _db.update(
        'source_rules',
        {
          'enabled': enabled ? 1 : 0,
          'updated_at': DateTime.now().millisecondsSinceEpoch,
        },
        where: 'rule_id = ?',
        whereArgs: [ruleId],
      );
      if (rows == 0) {
        return Failure(StateError('RULE_NOT_FOUND', 'setEnabled: $ruleId'));
      }
      _events.notify();
      return const Success<void>(null);
    } catch (e) {
      return Failure(StorageError('DB_ERROR', '更新规则开关失败: $e'));
    }
  }

  Future<AppResult<void>> deleteRule(String ruleId) async {
    try {
      final rows = await _db.delete(
        'source_rules',
        where: 'rule_id = ?',
        whereArgs: [ruleId],
      );
      if (rows == 0) {
        return Failure(StateError('RULE_NOT_FOUND', 'deleteRule: $ruleId'));
      }
      _events.notify();
      return const Success<void>(null);
    } catch (e) {
      return Failure(StorageError('DB_ERROR', '删除站点规则失败: $e'));
    }
  }

  StoredSiteRule _ruleFromRow(Map<String, Object?> row) {
    return StoredSiteRule(
      ruleId: (row['rule_id'] as String?) ?? '',
      domain: (row['domain'] as String?) ?? '',
      version: (row['version'] as int?) ?? 1,
      ruleJson: (row['rule_json'] as String?) ?? '',
      enabled: ((row['enabled'] as int?) ?? 0) == 1,
      createdAt: (row['created_at'] as int?) ?? 0,
      updatedAt: (row['updated_at'] as int?) ?? 0,
    );
  }
}

// ============================================================
// 目录合并执行（事务内）
// ============================================================

/// 在事务内执行目录合并，返回新目录章节总数。
Future<int> executeCatalogMerge(
  DatabaseExecutor txn,
  int bookId,
  ParsedCatalog catalog,
  int now,
) async {
  final oldRows = await txn.query(
    'chapters',
    where: 'book_id = ?',
    whereArgs: [bookId],
    orderBy: 'chapter_index ASC',
  );
  final old = oldRows.map(oldChapterFromRow).toList(growable: false);
  final plan = planCatalogMerge(
    oldChapters: old,
    newChapters: catalog.chapters,
    parserSaysReversed: catalog.isLikelyReversed,
  );
  final total = plan.totalChapters;

  // 旧索引整体平移到临时高位区，避免重排途中 UNIQUE 冲突。
  if (old.isNotEmpty) {
    await txn.rawUpdate(
      'UPDATE chapters SET chapter_index = chapter_index + 1000000000 WHERE book_id = ?',
      [bookId],
    );
  }

  // 命中旧章：保 id，更新标题/URL/指纹/序号（正文与缓存不变）。
  for (final entry in plan.entries) {
    if (entry.isNew) continue;
    await txn.update(
      'chapters',
      {
        'chapter_index': entry.chapterIndex,
        'title': entry.title,
        'url': entry.url,
        'normalized_url': entry.normalizedUrl,
        'title_fingerprint': entry.titleFingerprint,
        'is_missing': 0,
      },
      where: 'id = ?',
      whereArgs: [entry.oldId],
    );
  }

  // 新章节插入（0..n-1 中未占用的序号；旧章已平移到高位区）。
  for (final entry in plan.entries) {
    if (!entry.isNew) continue;
    await txn.insert('chapters', {
      'book_id': bookId,
      'chapter_index': entry.chapterIndex,
      'title': entry.title,
      'url': entry.url,
      'normalized_url': entry.normalizedUrl,
      'title_fingerprint': entry.titleFingerprint,
      'content': null,
      'content_hash': null,
      'is_cached': 0,
      'is_missing': 0,
      'fetched_at': null,
      'word_count': 0,
    });
  }

  // 缺失章节：不删除，移到目录尾部区段并置 is_missing=1。
  for (var j = 0; j < plan.missingOldIds.length; j++) {
    await txn.update(
      'chapters',
      {
        'is_missing': 1,
        'chapter_index': total + j,
      },
      where: 'id = ?',
      whereArgs: [plan.missingOldIds[j]],
    );
  }

  final latestTitle = plan.entries.isEmpty ? null : plan.entries.last.title;
  await txn.update(
    'books',
    {
      'total_chapters': total,
      'latest_chapter_title': latestTitle,
      'catalog_url': normalizeUrl(catalog.sourceUrl) ?? catalog.sourceUrl,
      'updated_at': now,
    },
    where: 'id = ?',
    whereArgs: [bookId],
  );
  return total;
}

// ============================================================
// 行映射 / 内容序列化
// ============================================================

Book bookFromRow(Map<String, Object?> row) {
  return Book(
    id: (row['id'] as int?) ?? 0,
    title: (row['title'] as String?) ?? '',
    author: row['author'] as String?,
    coverUrl: row['cover_url'] as String?,
    description: row['description'] as String?,
    sourceUrl: (row['source_url'] as String?) ?? '',
    sourceDomain: (row['source_domain'] as String?) ?? '',
    catalogUrl: row['catalog_url'] as String?,
    latestChapterTitle: row['latest_chapter_title'] as String?,
    totalChapters: (row['total_chapters'] as int?) ?? 0,
    currentChapterId: row['current_chapter_id'] as int?,
    lastReadAt: row['last_read_at'] as int?,
    createdAt: (row['created_at'] as int?) ?? 0,
    updatedAt: (row['updated_at'] as int?) ?? 0,
    isTemporary: ((row['is_temporary'] as int?) ?? 0) == 1,
  );
}

ChapterMeta chapterMetaFromRow(Map<String, Object?> row) {
  return ChapterMeta(
    id: (row['id'] as int?) ?? 0,
    bookId: (row['book_id'] as int?) ?? 0,
    chapterIndex: (row['chapter_index'] as int?) ?? 0,
    title: (row['title'] as String?) ?? '',
    url: (row['url'] as String?) ?? '',
    isCached: ((row['is_cached'] as int?) ?? 0) == 1,
    isMissing: ((row['is_missing'] as int?) ?? 0) == 1,
    wordCount: (row['word_count'] as int?) ?? 0,
    fetchedAt: row['fetched_at'] as int?,
  );
}

Chapter chapterFromRow(Map<String, Object?> row) {
  return Chapter(
    id: (row['id'] as int?) ?? 0,
    bookId: (row['book_id'] as int?) ?? 0,
    chapterIndex: (row['chapter_index'] as int?) ?? 0,
    title: (row['title'] as String?) ?? '',
    url: (row['url'] as String?) ?? '',
    content: row['content'] as String?,
    contentHash: row['content_hash'] as String?,
    isCached: ((row['is_cached'] as int?) ?? 0) == 1,
    isMissing: ((row['is_missing'] as int?) ?? 0) == 1,
    fetchedAt: row['fetched_at'] as int?,
    wordCount: (row['word_count'] as int?) ?? 0,
  );
}

ReadingProgress progressFromRow(Map<String, Object?> row) {
  return ReadingProgress(
    bookId: (row['book_id'] as int?) ?? 0,
    chapterId: (row['chapter_id'] as int?) ?? 0,
    chapterIndex: (row['chapter_index'] as int?) ?? 0,
    inChapterFraction: ((row['in_fraction'] as num?) ?? 0).toDouble(),
    charOffset: (row['char_offset'] as int?) ?? -1,
    progressPercentage: ((row['percentage'] as num?) ?? 0).toDouble(),
    updatedAt: (row['updated_at'] as int?) ?? 0,
  );
}

CrawlTask crawlTaskFromRow(Map<String, Object?> row) {
  return CrawlTask(
    id: (row['id'] as int?) ?? 0,
    bookId: (row['book_id'] as int?) ?? 0,
    state: _crawlStateFromName(row['state'] as String?),
    totalChapters: (row['total_chapters'] as int?) ?? 0,
    succeededCount: (row['succeeded'] as int?) ?? 0,
    failedCount: (row['failed'] as int?) ?? 0,
    currentChapterIndex: (row['current_index'] as int?) ?? 0,
    currentChapterTitle: row['current_title'] as String?,
    txtPath: row['txt_path'] as String?,
    createdAt: (row['created_at'] as int?) ?? 0,
    updatedAt: (row['updated_at'] as int?) ?? 0,
  );
}

OldChapterRow oldChapterFromRow(Map<String, Object?> row) {
  final url = (row['url'] as String?) ?? '';
  return OldChapterRow(
    id: (row['id'] as int?) ?? 0,
    chapterIndex: (row['chapter_index'] as int?) ?? 0,
    title: (row['title'] as String?) ?? '',
    url: url,
    normalizedUrl:
        (row['normalized_url'] as String?) ?? normalizeUrl(url) ?? url,
    titleFingerprint: (row['title_fingerprint'] as String?) ?? '',
  );
}

CrawlState _crawlStateFromName(String? name) {
  for (final state in CrawlState.values) {
    if (state.name == name) return state;
  }
  return CrawlState.waiting;
}

/// 内容块 → JSON 数组文本（`[{"t":"p","v":"…"},{"t":"img","v":"…","alt":…}]`）。
String encodeContentBlocks(List<ContentBlock> blocks) {
  final list = <Map<String, Object?>>[];
  for (final block in blocks) {
    if (block is Paragraph) {
      list.add({'t': 'p', 'v': block.text});
    } else if (block is ImageBlock) {
      list.add({'t': 'img', 'v': block.url, 'alt': block.alt});
    }
  }
  return jsonEncode(list);
}

/// JSON 数组文本 → 内容块；损坏数据返回空列表（调用方按缓存损坏处理）。
List<ContentBlock> decodeContentBlocks(String jsonText) {
  final decoded = jsonDecode(jsonText);
  if (decoded is! List) return const [];
  final blocks = <ContentBlock>[];
  for (final item in decoded) {
    if (item is! Map) continue;
    final type = item['t'];
    final value = item['v'];
    if (value is! String || value.isEmpty) continue;
    if (type == 'p') {
      blocks.add(Paragraph(value));
    } else if (type == 'img') {
      final alt = item['alt'];
      blocks.add(ImageBlock(value, alt is String && alt.isNotEmpty ? alt : null));
    }
  }
  return blocks;
}

String? _nonEmpty(String? value) {
  final trimmed = value?.trim();
  return (trimmed == null || trimmed.isEmpty) ? null : trimmed;
}