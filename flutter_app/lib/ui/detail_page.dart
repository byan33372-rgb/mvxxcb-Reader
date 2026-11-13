// 简阅 · 书籍详情页
// 封面/书名/作者/简介折叠/来源/章节数/最近阅读；开始或继续阅读、目录、
// 整本下载（任务态 + 成功/失败统计）、更新目录（parseCatalog + replaceCatalog）。
// ignore_for_file: deprecated_member_use

import 'dart:async';

import 'package:flutter/material.dart';

import '../contracts.dart';
import '../core/app_result.dart';
import '../core/reader_error.dart';
import 'catalog_page.dart';
import 'crawl_center_page.dart';
import 'reader_page.dart';
import 'theme.dart';
import 'widgets.dart';

class BookDetailPage extends StatefulWidget {
  const BookDetailPage({
    super.key,
    required this.services,
    required this.bookId,
  });

  final AppServices services;
  final int bookId;

  @override
  State<BookDetailPage> createState() => _BookDetailPageState();
}

class _BookDetailPageState extends State<BookDetailPage> {
  Book? _book;
  bool _loading = true;
  ReaderError? _loadError;
  ReaderError? _actionError;
  bool _updatingCatalog = false;
  bool _requestingDownload = false;
  bool _descriptionExpanded = false;

  late final Stream<ReadingProgress?> _progressStream;
  late final Stream<CrawlTask?> _taskStream;
  StreamSubscription<List<Book>>? _shelfSubscription;

  @override
  void initState() {
    super.initState();
    _progressStream = widget.services.progress.observeProgress(widget.bookId);
    _taskStream = widget.services.crawl.observeTaskForBook(widget.bookId);
    _shelfSubscription = widget.services.books.observeShelf().listen(
      (List<Book> books) {
        if (!mounted) return;
        for (final Book book in books) {
          if (book.id == widget.bookId) {
            setState(() => _book = book);
            break;
          }
        }
      },
      onError: (Object error) {
        // 书架流错误不阻塞详情展示，详情自身加载失败会走 _loadError。
      },
    );
    _load();
  }

  @override
  void dispose() {
    _shelfSubscription?.cancel();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _loadError = null;
    });
    try {
      final Book? book = await widget.services.books.getBook(widget.bookId);
      if (!mounted) return;
      setState(() {
        _book = book;
        _loading = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _loadError = UnknownError('加载书籍失败：$error');
      });
    }
  }

  Future<void> _updateCatalog(Book book) async {
    if (_updatingCatalog) return;
    final String? catalogUrl = book.catalogUrl?.trim();
    final String target = (catalogUrl != null && catalogUrl.isNotEmpty)
        ? catalogUrl
        : book.sourceUrl;
    setState(() {
      _updatingCatalog = true;
      _actionError = null;
    });
    try {
      final AppResult<ParsedCatalog> parsed = await widget.services.parser
          .parseCatalog(target);
      if (!mounted) return;
      if (parsed is Failure<ParsedCatalog>) {
        setState(() => _actionError = parsed.error);
        return;
      }
      final AppResult<int> replaced = await widget.services.chapters
          .replaceCatalog(book.id, (parsed as Success<ParsedCatalog>).value);
      if (!mounted) return;
      if (replaced is Failure<int>) {
        setState(() => _actionError = replaced.error);
        return;
      }
      final int count = (replaced as Success<int>).value;
      showAppSnack(context, '目录已更新（$count 章）');
      await _load();
    } catch (error) {
      if (!mounted) return;
      setState(() => _actionError = UnknownError('更新目录失败：$error'));
    } finally {
      if (mounted) setState(() => _updatingCatalog = false);
    }
  }

  Future<void> _confirmDownload(Book book) async {
    if (_requestingDownload) return;
    final bool? confirmed = await showDialog<bool>(
      context: context,
      builder: (BuildContext dialogContext) => AlertDialog(
        title: const Text('下载整本小说'),
        content: Text(
          '书名：${nonEmptyOr(book.title, '（未识别书名）')}\n'
          '章节数：${book.totalChapters} 章\n'
          '来源：${book.sourceDomain}\n\n'
          '将逐章抓取全书并生成本地 TXT，可在采集中心暂停、继续或取消。',
        ),
        actions: <Widget>[
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('开始下载'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    setState(() {
      _requestingDownload = true;
      _actionError = null;
    });
    try {
      final AppResult<CrawlTask> result = await widget.services.crawl
          .downloadWholeBook(book.id);
      if (!mounted) return;
      if (result is Failure<CrawlTask>) {
        setState(() => _actionError = result.error);
      } else {
        showAppSnack(context, '已加入下载队列');
      }
    } catch (error) {
      if (!mounted) return;
      setState(() => _actionError = UnknownError('创建下载任务失败：$error'));
    } finally {
      if (mounted) setState(() => _requestingDownload = false);
    }
  }

  Future<void> _runTaskAction(Future<AppResult<void>> Function() action) async {
    try {
      final AppResult<void> result = await action();
      if (!mounted) return;
      if (result is Failure<void>) {
        setState(() => _actionError = result.error);
      }
    } catch (error) {
      if (!mounted) return;
      setState(() => _actionError = UnknownError('操作失败：$error'));
    }
  }

  void _openReader(Book book, ReadingProgress? progress) {
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => ReaderPage(
          services: widget.services,
          bookId: book.id,
          initialChapterIndex: progress?.chapterIndex ?? 0,
        ),
      ),
    );
  }

  void _openCatalog(Book book) {
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => CatalogPage(services: widget.services, bookId: book.id),
      ),
    );
  }

  void _openCrawlCenter() {
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => CrawlCenterPage(services: widget.services),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final Book? book = _book;
    return Scaffold(
      appBar: AppBar(title: Text(nonEmptyOr(book?.title, '书籍详情'))),
      body: _loading && book == null
          ? const AppLoading(text: '正在加载书籍…')
          : book == null
          ? Padding(
              padding: const EdgeInsets.all(AppSpacing.md),
              child: _loadError != null
                  ? AppErrorBanner(error: _loadError, onRetry: _load)
                  : const AppEmptyState(title: '书籍不存在或已被删除'),
            )
          : StreamBuilder<ReadingProgress?>(
              stream: _progressStream,
              builder:
                  (
                    BuildContext context,
                    AsyncSnapshot<ReadingProgress?> progressSnapshot,
                  ) {
                    final ReadingProgress? progress = progressSnapshot.data;
                    return StreamBuilder<CrawlTask?>(
                      stream: _taskStream,
                      builder:
                          (
                            BuildContext context,
                            AsyncSnapshot<CrawlTask?> taskSnapshot,
                          ) {
                            return _buildBody(
                              context,
                              book,
                              progress,
                              taskSnapshot.data,
                            );
                          },
                    );
                  },
            ),
    );
  }

  Widget _buildBody(
    BuildContext context,
    Book book,
    ReadingProgress? progress,
    CrawlTask? task,
  ) {
    final ThemeData theme = Theme.of(context);
    final ColorScheme scheme = theme.colorScheme;
    final String? description = book.description?.trim();
    final bool hasDescription = description != null && description.isNotEmpty;
    final bool collapsible = hasDescription && description.length > 120;

    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(
        AppSpacing.screenH,
        AppSpacing.screenV,
        AppSpacing.screenH,
        AppSpacing.xl,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          if (book.isTemporary)
            Padding(
              padding: const EdgeInsets.only(bottom: AppSpacing.md),
              child: AppBadge(
                label: '临时书：仅解析到正文，未建立完整目录',
                background: scheme.secondaryContainer,
                foreground: scheme.onSecondaryContainer,
              ),
            ),
          _Header(book: book),
          const SizedBox(height: AppSpacing.md),
          if (_actionError != null) ...<Widget>[
            AppErrorBanner(
              error: _actionError,
              onDismiss: () => setState(() => _actionError = null),
            ),
            const SizedBox(height: AppSpacing.md),
          ],
          Row(
            children: <Widget>[
              Expanded(
                flex: 3,
                child: FilledButton(
                  onPressed: book.totalChapters > 0
                      ? () => _openReader(book, progress)
                      : null,
                  child: Text(
                    progress != null
                        ? '继续阅读 · 第${progress.chapterIndex + 1}章'
                        : '开始阅读',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
              ),
              const SizedBox(width: AppSpacing.sm),
              Expanded(
                flex: 2,
                child: OutlinedButton(
                  onPressed: book.totalChapters > 0
                      ? () => _openCatalog(book)
                      : null,
                  child: const Text('查看目录'),
                ),
              ),
            ],
          ),
          if (book.totalChapters <= 0)
            Padding(
              padding: const EdgeInsets.only(top: AppSpacing.sm),
              child: Text(
                '尚无目录，请先更新目录后再阅读或下载整本',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: scheme.onSurfaceVariant,
                ),
              ),
            ),
          const SizedBox(height: AppSpacing.md),
          _readingInfoCard(book, progress),
          const SizedBox(height: AppSpacing.md),
          _downloadCard(book, task),
          const SizedBox(height: AppSpacing.md),
          SectionCard(
            title: '简介',
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text(
                  hasDescription ? description : '（未识别简介）',
                  maxLines: collapsible && !_descriptionExpanded ? 4 : null,
                  overflow: collapsible && !_descriptionExpanded
                      ? TextOverflow.ellipsis
                      : null,
                  style: theme.textTheme.bodyMedium?.copyWith(
                    color: hasDescription
                        ? scheme.onSurface
                        : scheme.onSurfaceVariant,
                    height: 1.6,
                  ),
                ),
                if (collapsible)
                  Align(
                    alignment: Alignment.centerLeft,
                    child: TextButton(
                      onPressed: () => setState(
                        () => _descriptionExpanded = !_descriptionExpanded,
                      ),
                      child: Text(_descriptionExpanded ? '收起' : '展开全文'),
                    ),
                  ),
              ],
            ),
          ),
          const SizedBox(height: AppSpacing.md),
          Text(
            '来源：${book.sourceDomain}',
            style: theme.textTheme.bodySmall?.copyWith(
              color: scheme.onSurfaceVariant,
            ),
          ),
        ],
      ),
    );
  }

  Widget _readingInfoCard(Book book, ReadingProgress? progress) {
    final ThemeData theme = Theme.of(context);
    final ColorScheme scheme = theme.colorScheme;
    final double fraction = progress == null
        ? 0
        : (progress.progressPercentage / 100).clamp(0.0, 1.0);
    return SectionCard(
      title: '阅读信息',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text(
            '共 ${book.totalChapters} 章'
            '${nonEmptyOr(book.latestChapterTitle, '').isNotEmpty ? ' · 最新：${book.latestChapterTitle!.trim()}' : ''}',
            style: theme.textTheme.bodyMedium,
          ),
          const SizedBox(height: AppSpacing.xs),
          Text(
            progress != null
                ? '最近阅读：${formatDateTime(progress.updatedAt)} · 第 ${progress.chapterIndex + 1} 章 · ${progress.progressPercentage.toStringAsFixed(1)}%'
                : book.lastReadAt != null
                ? '最近阅读：${formatDateTime(book.lastReadAt)}'
                : '最近阅读：尚未开始',
            style: theme.textTheme.bodySmall?.copyWith(
              color: scheme.onSurfaceVariant,
            ),
          ),
          if (progress != null) ...<Widget>[
            const SizedBox(height: AppSpacing.sm),
            ClipRRect(
              borderRadius: BorderRadius.circular(3),
              child: LinearProgressIndicator(
                value: fraction,
                minHeight: 4,
                backgroundColor: scheme.surfaceContainerHighest,
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _downloadCard(Book book, CrawlTask? task) {
    final ThemeData theme = Theme.of(context);
    final ColorScheme scheme = theme.colorScheme;

    final Widget content;
    if (task == null) {
      content = Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          if (book.totalChapters <= 0) ...<Widget>[
            Text(
              '尚无目录，请先更新目录后再下载整本',
              style: theme.textTheme.bodyMedium?.copyWith(
                color: scheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: AppSpacing.sm),
            OutlinedButton(
              onPressed: _updatingCatalog ? null : () => _updateCatalog(book),
              child: _updatingCatalog
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Text('更新目录'),
            ),
          ] else ...<Widget>[
            FilledButton.icon(
              onPressed: _requestingDownload
                  ? null
                  : () => _confirmDownload(book),
              icon: const Icon(Icons.download_outlined, size: 18),
              label: const Text('下载整本小说'),
            ),
            const SizedBox(height: AppSpacing.xs),
            Text(
              '共 ${book.totalChapters} 章，逐章抓取并生成 TXT；期间可暂停/继续',
              style: theme.textTheme.bodySmall?.copyWith(
                color: scheme.onSurfaceVariant,
              ),
            ),
          ],
        ],
      );
    } else {
      content = _taskContent(book, task);
    }

    return SectionCard(
      title: '整本下载',
      trailing: book.totalChapters > 0
          ? TextButton(
              onPressed: _updatingCatalog ? null : () => _updateCatalog(book),
              child: _updatingCatalog
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Text('更新目录'),
            )
          : null,
      child: content,
    );
  }

  Widget _taskContent(Book book, CrawlTask task) {
    final ThemeData theme = Theme.of(context);
    final ColorScheme scheme = theme.colorScheme;
    final int total = task.totalChapters;
    final int done = task.succeededCount + task.failedCount;
    final bool active =
        task.state == CrawlState.waiting ||
        task.state == CrawlState.running ||
        task.state == CrawlState.paused;

    final List<Widget> children = <Widget>[];
    if (active) {
      children.add(
        ClipRRect(
          borderRadius: BorderRadius.circular(3),
          child: LinearProgressIndicator(
            value: total > 0 ? (done / total).clamp(0.0, 1.0) : 0,
            minHeight: 4,
            backgroundColor: scheme.surfaceContainerHighest,
          ),
        ),
      );
      children.add(const SizedBox(height: AppSpacing.xs));
      children.add(
        Text(switch (task.state) {
          CrawlState.waiting => '排队中 · 共 $total 章',
          CrawlState.running =>
            '下载中 · 成功 ${task.succeededCount} · 失败 ${task.failedCount} / 共 $total',
          _ =>
            '已暂停 · 成功 ${task.succeededCount} · 失败 ${task.failedCount} / 共 $total',
        }, style: theme.textTheme.bodyMedium),
      );
    } else if (task.state == CrawlState.completed) {
      children.add(
        Text(
          '下载完成 · 成功 ${task.succeededCount} / 共 $total'
          '${task.failedCount > 0 ? '（失败 ${task.failedCount}）' : ''}',
          style: theme.textTheme.bodyMedium,
        ),
      );
    } else if (task.state == CrawlState.failed) {
      children.add(
        Text(
          '下载中断 · 成功 ${task.succeededCount} · 失败 ${task.failedCount} / 共 $total',
          style: theme.textTheme.bodyMedium?.copyWith(color: scheme.error),
        ),
      );
    } else {
      children.add(
        Text(
          '已取消 · 成功 ${task.succeededCount} / 共 $total',
          style: theme.textTheme.bodyMedium,
        ),
      );
    }

    if (task.currentChapterTitle != null &&
        task.currentChapterTitle!.trim().isNotEmpty) {
      children.add(
        Padding(
          padding: const EdgeInsets.only(top: AppSpacing.xs),
          child: Text(
            '当前：第${task.currentChapterIndex + 1}章 ${task.currentChapterTitle!.trim()}',
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: theme.textTheme.bodySmall?.copyWith(
              color: scheme.onSurfaceVariant,
            ),
          ),
        ),
      );
    }

    final bool txtGenerated =
        task.txtPath != null && task.txtPath!.trim().isNotEmpty;
    children.add(
      Padding(
        padding: const EdgeInsets.only(top: AppSpacing.xs),
        child: Row(
          children: <Widget>[
            Icon(
              txtGenerated
                  ? Icons.check_circle_outline
                  : Icons.description_outlined,
              size: 16,
              color: txtGenerated
                  ? context.semanticColors.success
                  : scheme.onSurfaceVariant,
            ),
            const SizedBox(width: AppSpacing.xs),
            Text(
              txtGenerated ? 'TXT 已生成' : 'TXT 未生成',
              style: theme.textTheme.labelMedium?.copyWith(
                color: scheme.onSurfaceVariant,
              ),
            ),
          ],
        ),
      ),
    );

    final List<Widget> actions = <Widget>[];
    if (task.state == CrawlState.waiting || task.state == CrawlState.running) {
      actions.add(
        OutlinedButton(
          onPressed: () =>
              _runTaskAction(() => widget.services.crawl.pause(task.id)),
          child: const Text('暂停'),
        ),
      );
    }
    if (task.state == CrawlState.paused) {
      actions.add(
        FilledButton(
          onPressed: () =>
              _runTaskAction(() => widget.services.crawl.resume(task.id)),
          child: const Text('继续下载'),
        ),
      );
    }
    if (task.state == CrawlState.waiting || task.state == CrawlState.running) {
      actions.add(
        OutlinedButton(
          onPressed: () =>
              _runTaskAction(() => widget.services.crawl.cancel(task.id)),
          child: const Text('取消下载'),
        ),
      );
    }
    if (task.state == CrawlState.failed ||
        (task.state == CrawlState.completed && task.failedCount > 0)) {
      actions.add(
        FilledButton(
          onPressed: () =>
              _runTaskAction(() => widget.services.crawl.retryFailed(task.id)),
          child: const Text('重试失败章'),
        ),
      );
    }
    if (!active) {
      actions.add(
        OutlinedButton(
          onPressed: _requestingDownload ? null : () => _confirmDownload(book),
          child: const Text('重新下载'),
        ),
      );
    }
    actions.add(
      TextButton(onPressed: _openCrawlCenter, child: const Text('打开采集中心')),
    );

    children.add(
      Padding(
        padding: const EdgeInsets.only(top: AppSpacing.sm),
        child: Wrap(
          spacing: AppSpacing.sm,
          runSpacing: AppSpacing.xs,
          children: actions,
        ),
      ),
    );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: children,
    );
  }
}

class _Header extends StatelessWidget {
  const _Header({required this.book});

  final Book book;

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final ColorScheme scheme = theme.colorScheme;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        AppCover(url: book.coverUrl, width: 96, height: 133, iconSize: 30),
        const SizedBox(width: AppSpacing.md),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              Text(
                nonEmptyOr(book.title, '（未识别书名）'),
                maxLines: 3,
                overflow: TextOverflow.ellipsis,
                style: theme.textTheme.headlineSmall?.copyWith(
                  fontWeight: FontWeight.w600,
                ),
              ),
              const SizedBox(height: AppSpacing.xs),
              Text(
                nonEmptyOr(book.author, '作者未知'),
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: scheme.onSurfaceVariant,
                ),
              ),
              const SizedBox(height: AppSpacing.xs),
              Text(
                book.totalChapters > 0 ? '共 ${book.totalChapters} 章' : '尚无目录',
                style: theme.textTheme.bodyMedium?.copyWith(
                  color: scheme.onSurfaceVariant,
                ),
              ),
              const SizedBox(height: AppSpacing.xs),
              Text(
                '来源：${book.sourceDomain}',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: scheme.onSurfaceVariant,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }
}
