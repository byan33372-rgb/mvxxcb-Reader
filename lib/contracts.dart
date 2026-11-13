import 'core/app_result.dart';
import 'core/site_rule.dart';

// ============================================================
// 共享数据模型与服务契约（Flutter 版冻结接口，对齐 Kotlin 版架构）
// 实现方：lib/impl/**（引擎层） ｜ 消费方：lib/ui/**（界面层）
// 修改本文件需主 Agent 批准（对应 Kotlin 版契约冻结纪律）
// ============================================================

// ---------------- 基础枚举 ----------------

enum PageKind { bookDetail, catalog, chapter, unknown }

enum Confidence { low, medium, high }

enum CrawlState { waiting, running, paused, failed, completed, cancelled }

/// 目录排序
enum CatalogSort { asc, desc }

// ---------------- 领域模型 ----------------

class Book {
  final int id; // 0 = 未入库
  final String title;
  final String? author;
  final String? coverUrl;
  final String? description;
  final String sourceUrl; // 规范化来源 URL（详情页）
  final String sourceDomain;
  final String? catalogUrl;
  final String? latestChapterTitle;
  final int totalChapters;
  final int? currentChapterId;
  final int? lastReadAt;
  final int createdAt;
  final int updatedAt;
  final bool isTemporary;

  const Book({
    required this.id,
    required this.title,
    this.author,
    this.coverUrl,
    this.description,
    required this.sourceUrl,
    required this.sourceDomain,
    this.catalogUrl,
    this.latestChapterTitle,
    this.totalChapters = 0,
    this.currentChapterId,
    this.lastReadAt,
    required this.createdAt,
    required this.updatedAt,
    this.isTemporary = false,
  });

  Book copyWith({
    int? id,
    String? title,
    String? author,
    String? coverUrl,
    String? description,
    String? sourceUrl,
    String? sourceDomain,
    String? catalogUrl,
    String? latestChapterTitle,
    int? totalChapters,
    int? currentChapterId,
    int? lastReadAt,
    int? createdAt,
    int? updatedAt,
    bool? isTemporary,
  }) {
    return Book(
      id: id ?? this.id,
      title: title ?? this.title,
      author: author ?? this.author,
      coverUrl: coverUrl ?? this.coverUrl,
      description: description ?? this.description,
      sourceUrl: sourceUrl ?? this.sourceUrl,
      sourceDomain: sourceDomain ?? this.sourceDomain,
      catalogUrl: catalogUrl ?? this.catalogUrl,
      latestChapterTitle: latestChapterTitle ?? this.latestChapterTitle,
      totalChapters: totalChapters ?? this.totalChapters,
      currentChapterId: currentChapterId ?? this.currentChapterId,
      lastReadAt: lastReadAt ?? this.lastReadAt,
      createdAt: createdAt ?? this.createdAt,
      updatedAt: updatedAt ?? this.updatedAt,
      isTemporary: isTemporary ?? this.isTemporary,
    );
  }
}

/// 目录用轻量章节（不含正文）
class ChapterMeta {
  final int id;
  final int bookId;
  final int chapterIndex; // 0-based
  final String title;
  final String url;
  final bool isCached;
  final bool isMissing;
  final int wordCount;
  final int? fetchedAt;

  const ChapterMeta({
    required this.id,
    required this.bookId,
    required this.chapterIndex,
    required this.title,
    required this.url,
    this.isCached = false,
    this.isMissing = false,
    this.wordCount = 0,
    this.fetchedAt,
  });
}

/// 含正文的完整章节
class Chapter {
  final int id;
  final int bookId;
  final int chapterIndex;
  final String title;
  final String url;
  final String? content; // JSON 序列化内容块
  final String? contentHash;
  final bool isCached;
  final bool isMissing;
  final int? fetchedAt;
  final int wordCount;

  const Chapter({
    required this.id,
    required this.bookId,
    required this.chapterIndex,
    required this.title,
    required this.url,
    this.content,
    this.contentHash,
    this.isCached = false,
    this.isMissing = false,
    this.fetchedAt,
    this.wordCount = 0,
  });
}

class ChapterRef {
  final int order;
  final String title;
  final String url;
  final String? volumeTitle;

  const ChapterRef({required this.order, required this.title, required this.url, this.volumeTitle});
}

class ParsedBookDetail {
  final String sourceUrl;
  final String? title;
  final String? author;
  final String? coverUrl;
  final String? description;
  final String? catalogUrl;
  final Confidence confidence;

  const ParsedBookDetail({
    required this.sourceUrl,
    this.title,
    this.author,
    this.coverUrl,
    this.description,
    this.catalogUrl,
    this.confidence = Confidence.medium,
  });
}

class ParsedCatalog {
  final String sourceUrl;
  final List<ChapterRef> chapters;
  final bool isLikelyReversed;
  final bool truncatedByLimit;
  final Confidence confidence;

  const ParsedCatalog({
    required this.sourceUrl,
    required this.chapters,
    this.isLikelyReversed = false,
    this.truncatedByLimit = false,
    this.confidence = Confidence.medium,
  });
}

sealed class ContentBlock {
  const ContentBlock();
}

class Paragraph extends ContentBlock {
  final String text;
  const Paragraph(this.text);
}

class ImageBlock extends ContentBlock {
  final String url;
  final String? alt;
  const ImageBlock(this.url, [this.alt]);
}

class ParsedChapterContent {
  final String sourceUrl;
  final String? title;
  final List<ContentBlock> blocks;
  final String? nextPageUrl;
  final String? nextChapterUrl;
  final String? previousChapterUrl;
  final bool isTruncated;
  final int wordCount;

  const ParsedChapterContent({
    required this.sourceUrl,
    this.title,
    required this.blocks,
    this.nextPageUrl,
    this.nextChapterUrl,
    this.previousChapterUrl,
    this.isTruncated = false,
    this.wordCount = 0,
  });
}

/// 阅读器最终消费的内容
class ReaderContent {
  final int chapterId;
  final int chapterIndex;
  final int bookId;
  final String title;
  final List<ContentBlock> blocks;

  const ReaderContent({
    required this.chapterId,
    required this.chapterIndex,
    required this.bookId,
    required this.title,
    required this.blocks,
  });
}

class UrlClassification {
  final String normalizedUrl;
  final PageKind kind;
  final Confidence confidence;
  final List<String> notes;

  const UrlClassification({
    required this.normalizedUrl,
    required this.kind,
    this.confidence = Confidence.medium,
    this.notes = const [],
  });
}

/// BookResolver 输出：从任意入口定位到的整本信息
class BookResolution {
  final String normalizedUrl;
  final PageKind kind;
  final String? detailUrl;
  final String? catalogUrl;
  final ParsedBookDetail? detail;
  final ParsedCatalog? catalog;
  final Confidence confidence;
  final bool requiresCatalogHint;
  final List<String> evidence;

  const BookResolution({
    required this.normalizedUrl,
    required this.kind,
    this.detailUrl,
    this.catalogUrl,
    this.detail,
    this.catalog,
    this.confidence = Confidence.low,
    this.requiresCatalogHint = false,
    this.evidence = const [],
  });
}

class ReadingProgress {
  final int bookId;
  final int chapterId;
  final int chapterIndex;
  final double inChapterFraction; // 0..1（兜底锚点）
  final int charOffset; // 章内字符偏移（主锚点，-1 = 未记录）
  final double progressPercentage; // 0..100
  final int updatedAt;

  const ReadingProgress({
    required this.bookId,
    required this.chapterId,
    required this.chapterIndex,
    this.inChapterFraction = 0,
    this.charOffset = -1,
    this.progressPercentage = 0,
    required this.updatedAt,
  });
}

class CrawlTask {
  final int id;
  final int bookId;
  final CrawlState state;
  final int totalChapters;
  final int succeededCount;
  final int failedCount;
  final int currentChapterIndex;
  final String? currentChapterTitle;
  final String? txtPath;
  final int createdAt;
  final int updatedAt;

  const CrawlTask({
    required this.id,
    required this.bookId,
    required this.state,
    required this.totalChapters,
    this.succeededCount = 0,
    this.failedCount = 0,
    this.currentChapterIndex = 0,
    this.currentChapterTitle,
    this.txtPath,
    required this.createdAt,
    required this.updatedAt,
  });

  CrawlTask copyWith({
    CrawlState? state,
    int? succeededCount,
    int? failedCount,
    int? currentChapterIndex,
    String? currentChapterTitle,
    String? txtPath,
    int? updatedAt,
  }) {
    return CrawlTask(
      id: id,
      bookId: bookId,
      state: state ?? this.state,
      totalChapters: totalChapters,
      succeededCount: succeededCount ?? this.succeededCount,
      failedCount: failedCount ?? this.failedCount,
      currentChapterIndex: currentChapterIndex ?? this.currentChapterIndex,
      currentChapterTitle: currentChapterTitle ?? this.currentChapterTitle,
      txtPath: txtPath ?? this.txtPath,
      createdAt: createdAt,
      updatedAt: updatedAt ?? this.updatedAt,
    );
  }
}

class TxtExportReport {
  final String filePath;
  final int fileSize;
  final String sha256;
  final int chapterCount;
  final int completedCount;
  final bool isComplete;

  const TxtExportReport({
    required this.filePath,
    required this.fileSize,
    required this.sha256,
    required this.chapterCount,
    required this.completedCount,
    required this.isComplete,
  });
}

class ReaderSettings {
  final double fontSize;
  final double lineHeightMultiplier;
  final int backgroundTheme; // 0 白 1 米 2 绿 3 灰 4 黑
  final double horizontalPaddingDp;

  const ReaderSettings({
    this.fontSize = 18,
    this.lineHeightMultiplier = 1.6,
    this.backgroundTheme = 1,
    this.horizontalPaddingDp = 20,
  });

  ReaderSettings copyWith({double? fontSize, double? lineHeightMultiplier, int? backgroundTheme, double? horizontalPaddingDp}) {
    return ReaderSettings(
      fontSize: fontSize ?? this.fontSize,
      lineHeightMultiplier: lineHeightMultiplier ?? this.lineHeightMultiplier,
      backgroundTheme: backgroundTheme ?? this.backgroundTheme,
      horizontalPaddingDp: horizontalPaddingDp ?? this.horizontalPaddingDp,
    );
  }
}

// ---------------- 服务契约（引擎层实现，UI 层消费） ----------------

/// 安全 HTTP 出口（唯一网络通道）：SSRF 校验 + 重定向逐跳复查 + 超时/大小上限 + 每主机自适应限速
abstract class SecureHttpClient {
  /// 返回解码后的 HTML 文本与最终 URL；失败为 AppResult.Failure
  ///
  /// [preferredCharset]：站点规则 encoding 提示（如 GBK），优先于传输声明解码；
  /// 非白名单编码被实现方忽略。可缺省（对齐 Kotlin 版无此参数时的行为）。
  Future<AppResult<FetchedPage>> fetch(
    String url, {
    Map<String, String>? headers,
    String? preferredCharset,
  });
}

class FetchedPage {
  final String finalUrl;
  final String html;
  final String? charset;
  final int statusCode;

  const FetchedPage({required this.finalUrl, required this.html, this.charset, required this.statusCode});
}

/// 解析引擎（四层策略：规则(层0/2) → 结构化 → 通用智能 → 置信度）
abstract class ParserEngine {
  Future<AppResult<UrlClassification>> classify(String url);
  Future<AppResult<ParsedBookDetail>> parseDetail(String url);
  Future<AppResult<ParsedCatalog>> parseCatalog(String url);
  Future<AppResult<ParsedChapterContent>> parseChapterContent(String url);
}

/// 整本识别器（章节 URL 反向定位详情/目录）
abstract class BookResolver {
  Future<AppResult<BookResolution>> resolve(String url, {String? catalogHint});
}

abstract class BookRepository {
  Stream<List<Book>> observeShelf();
  Future<Book?> getBook(int bookId);
  Future<Book?> findBySourceUrl(String normalizedUrl);
  Future<AppResult<int>> upsertFromImport({
    ParsedBookDetail? detail,
    required String sourceUrl,
    ParsedCatalog? catalog,
  });
  Future<AppResult<void>> updateBook(Book book);
  Future<AppResult<void>> deleteBook(int bookId);
}

abstract class ChapterRepository {
  Stream<List<ChapterMeta>> observeChapterMetas(int bookId);
  Future<ChapterMeta?> getChapterMeta(int chapterId);
  Future<Chapter?> getChapterByIndex(int bookId, int chapterIndex);
  Future<Chapter?> getChapterByUrl(int bookId, String url);
  /// 目录写入：URL 规范化 + 标题指纹合并（保护进度与缓存），返回合并统计
  Future<AppResult<int>> replaceCatalog(int bookId, ParsedCatalog catalog);
  Future<AppResult<void>> saveContent(int chapterId, ParsedChapterContent content);
  Future<ReaderContent?> loadContent(int chapterId);
}

abstract class ProgressRepository {
  Future<ReadingProgress?> getProgress(int bookId);
  Stream<ReadingProgress?> observeProgress(int bookId);
  Future<AppResult<void>> saveProgress(ReadingProgress progress);
}

abstract class CrawlService {
  Stream<List<CrawlTask>> observeTasks();
  Stream<CrawlTask?> observeTaskForBook(int bookId);
  Future<CrawlTask?> getActiveTaskForBook(int bookId);
  /// 建任务并入队（同书幂等复用非终态任务）
  Future<AppResult<CrawlTask>> downloadWholeBook(int bookId);
  Future<AppResult<void>> pause(int taskId);
  Future<AppResult<void>> resume(int taskId);
  Future<AppResult<void>> cancel(int taskId);
  Future<AppResult<void>> retryFailed(int taskId);
  /// 单章重采
  Future<AppResult<void>> retryChapter(int bookId, int chapterIndex);
  /// 采集完成后 TXT 导出（原子写 + SHA-256）
  Future<AppResult<TxtExportReport>> exportTxt(int bookId);
  /// 应用启动恢复（WAITING/RUNNING 任务重新入队）
  Future<void> restoreOnStartup();
}

abstract class SettingsService {
  Future<ReaderSettings> load();
  Future<void> save(ReaderSettings settings);
}

/// 站点规则服务（source_rules 表读写；规则编辑器消费）。
///
/// 对齐 Kotlin 版 SourceRuleRepository + presentation/rules 视图模型：
///  - 编辑器保存统一走 [saveRuleFromDraft]（草稿 → ruleJson → Schema 校验，
///    fieldErrors 非空即失败，不落库）；
///  - ruleId 约定：编辑器创建的规则 = `manual.<domain>`。
abstract class SiteRuleService {
  Future<List<StoredSiteRule>> listRules();
  Future<StoredSiteRule?> getRule(String ruleId);
  Future<AppResult<StoredSiteRule>> saveRuleFromDraft(
    RuleDraft draft, {
    /// 编辑已有规则时传入原 ruleId/版本/创建时间（保留身份与 created_at）
    String? existingRuleId,
    int? existingVersion,
    int? existingCreatedAt,
  });
  Future<AppResult<void>> deleteRule(String ruleId);
  Future<AppResult<void>> setEnabled(String ruleId, bool enabled);
}

/// 诊断反馈（对齐 Kotlin `service/DiagnosticsService.kt`）。
///
/// 文本口径：设备与应用 / 数据库统计 / 采集任务 / 阅读设置 / 最近操作日志 /
/// 反馈生成时间；所有 URL 一律 [redactUrl] 脱敏（去 query/fragment + 截断）。
abstract class DiagnosticsService {
  Future<AppResult<FeedbackReport>> buildFeedback();
}

/// 诊断反馈产出（UI 层经分享面板导出）。
class FeedbackReport {
  final String text;
  final String suggestedFileName;

  const FeedbackReport({required this.text, required this.suggestedFileName});
}

/// 后台采集调度（WP-B）：Android 上映射 WorkManager one-off Work；
/// 其他平台由空实现兜底（前台队列仍是主执行方）。
///
/// 双入口防双跑由 DB 租约（crawl_tasks.lease_until）保证——调度器只负责
/// "尽快触发一次后台执行"，同一 taskId 重复 schedule 必须幂等（keep 语义）。
abstract class BackgroundCrawlScheduler {
  /// 请求在后台尽快执行一次任务循环（同一 taskId 幂等）。
  Future<void> schedule(int taskId);

  /// 撤销后台任务（暂停/取消时调用；正在运行的 worker 由系统停止，
  /// 租约经 TTL 过期自然释放）。
  Future<void> cancel(int taskId);
}

/// 应用服务容器（bootstrap.dart 装配）
class AppServices {
  final SecureHttpClient http;
  final ParserEngine parser;
  final BookResolver resolver;
  final BookRepository books;
  final ChapterRepository chapters;
  final ProgressRepository progress;
  final CrawlService crawl;
  final SettingsService settings;
  final SiteRuleService rules;
  final DiagnosticsService diagnostics;

  /// 释放底层资源（后台隔离任务结束时调用；前台进程可不提供）。
  final Future<void> Function()? dispose;

  const AppServices({
    required this.http,
    required this.parser,
    required this.resolver,
    required this.books,
    required this.chapters,
    required this.progress,
    required this.crawl,
    required this.settings,
    required this.rules,
    required this.diagnostics,
    this.dispose,
  });
}