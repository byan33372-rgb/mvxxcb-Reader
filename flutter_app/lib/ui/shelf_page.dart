// 简阅 · 书架页
// 分组筛选（全部/在线/下载中/已下载）、真实书架流 + 采集任务流合并、卡片进度、
// 继续阅读角标、长按删除确认、下拉刷新、空态引导。
// ignore_for_file: deprecated_member_use

import 'dart:async';

import 'package:flutter/material.dart';

import '../contracts.dart';
import '../core/app_result.dart';
import 'detail_page.dart';
import 'theme.dart';
import 'widgets.dart';

/// 书架分组（任务书冻结：全部/在线/下载中/已下载）
enum _ShelfGroup {
  all('全部'),
  online('在线'),
  downloading('下载中'),
  downloaded('已下载');

  const _ShelfGroup(this.label);

  final String label;
}

/// 卡片下载态
enum _DownloadBadge {
  downloading('下载中'),
  downloaded('已下载');

  const _DownloadBadge(this.label);

  final String label;
}

class ShelfPage extends StatefulWidget {
  const ShelfPage({
    super.key,
    required this.services,
    required this.onGoImport,
  });

  final AppServices services;
  final VoidCallback onGoImport;

  @override
  State<ShelfPage> createState() => _ShelfPageState();
}

class _ShelfPageState extends State<ShelfPage> {
  _ShelfGroup _group = _ShelfGroup.all;
  int _refreshToken = 0;
  late Stream<List<Book>> _booksStream;
  late Stream<List<CrawlTask>> _tasksStream;

  @override
  void initState() {
    super.initState();
    _booksStream = widget.services.books.observeShelf();
    _tasksStream = widget.services.crawl.observeTasks();
  }

  Future<void> _refresh() async {
    setState(() {
      _booksStream = widget.services.books.observeShelf();
      _tasksStream = widget.services.crawl.observeTasks();
      _refreshToken++;
    });
    await Future<void>.delayed(const Duration(milliseconds: 350));
  }

  /// 每本书的最近任务（同书可能存在多条历史任务，以 updatedAt 最新者为准）。
  Map<int, CrawlTask> _latestTasksByBook(List<CrawlTask> tasks) {
    final Map<int, CrawlTask> result = <int, CrawlTask>{};
    for (final CrawlTask task in tasks) {
      final CrawlTask? current = result[task.bookId];
      if (current == null || task.updatedAt >= current.updatedAt) {
        result[task.bookId] = task;
      }
    }
    return result;
  }

  _DownloadBadge? _badgeOf(CrawlTask? task) {
    if (task == null) return null;
    switch (task.state) {
      case CrawlState.waiting:
      case CrawlState.running:
      case CrawlState.paused:
        return _DownloadBadge.downloading;
      case CrawlState.completed:
        return _DownloadBadge.downloaded;
      case CrawlState.failed:
      case CrawlState.cancelled:
        return null;
    }
  }

  bool _inGroup(Book book, Map<int, CrawlTask> tasks, _ShelfGroup group) {
    if (group == _ShelfGroup.all) return true;
    final CrawlTask? task = tasks[book.id];
    switch (group) {
      case _ShelfGroup.all:
        return true;
      case _ShelfGroup.online:
        return task == null ||
            task.state == CrawlState.failed ||
            task.state == CrawlState.cancelled;
      case _ShelfGroup.downloading:
        return task != null &&
            (task.state == CrawlState.waiting ||
                task.state == CrawlState.running ||
                task.state == CrawlState.paused);
      case _ShelfGroup.downloaded:
        return task != null && task.state == CrawlState.completed;
    }
  }

  Future<void> _confirmDelete(Book book) async {
    final String title = nonEmptyOr(book.title, '未命名书籍');
    final bool? confirmed = await showDialog<bool>(
      context: context,
      builder: (BuildContext dialogContext) => AlertDialog(
        title: const Text('删除确认'),
        content: Text(
          '确定要删除《$title》吗？\n'
          '章节缓存、阅读进度与采集任务记录将一并清除，且不可恢复。',
        ),
        actions: <Widget>[
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('删除'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    final AppResult<void> result = await widget.services.books.deleteBook(
      book.id,
    );
    if (!mounted) return;
    if (result is Failure<void>) {
      showErrorSnack(context, result.error);
    } else {
      showAppSnack(context, '已删除《$title》');
    }
  }

  void _openBook(Book book) {
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) =>
            BookDetailPage(services: widget.services, bookId: book.id),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('书架'),
        actions: <Widget>[
          IconButton(
            onPressed: _refresh,
            tooltip: '刷新',
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: StreamBuilder<List<Book>>(
        key: ValueKey<int>(_refreshToken),
        stream: _booksStream,
        builder:
            (BuildContext context, AsyncSnapshot<List<Book>> booksSnapshot) {
              if (booksSnapshot.hasError) {
                return Padding(
                  padding: const EdgeInsets.all(AppSpacing.md),
                  child: AppErrorBanner(
                    message: '书架加载失败：${booksSnapshot.error}',
                    onRetry: _refresh,
                  ),
                );
              }
              if (!booksSnapshot.hasData) {
                return const AppLoading(text: '正在加载书架…');
              }
              final List<Book> books = booksSnapshot.data ?? const <Book>[];
              if (books.isEmpty) {
                return RefreshIndicator(
                  onRefresh: _refresh,
                  child: ListView(
                    physics: const AlwaysScrollableScrollPhysics(),
                    children: <Widget>[
                      SizedBox(
                        height: MediaQuery.sizeOf(context).height * 0.6,
                        child: AppEmptyState(
                          title: '书架还是空的',
                          description: '粘贴小说网址，一键导入开始阅读',
                          actionText: '去发现·导入',
                          onAction: widget.onGoImport,
                        ),
                      ),
                    ],
                  ),
                );
              }

              return StreamBuilder<List<CrawlTask>>(
                key: ValueKey<int>(_refreshToken + 1),
                stream: _tasksStream,
                builder:
                    (
                      BuildContext context,
                      AsyncSnapshot<List<CrawlTask>> taskSnapshot,
                    ) {
                      final Map<int, CrawlTask> tasks = _latestTasksByBook(
                        taskSnapshot.data ?? const <CrawlTask>[],
                      );
                      final List<Book> filtered = books
                          .where((Book book) => _inGroup(book, tasks, _group))
                          .toList(growable: false);

                      return Column(
                        children: <Widget>[
                          _GroupChips(
                            selected: _group,
                            onSelected: (_ShelfGroup group) =>
                                setState(() => _group = group),
                          ),
                          Expanded(
                            child: RefreshIndicator(
                              onRefresh: _refresh,
                              child: filtered.isEmpty
                                  ? ListView(
                                      key: const PageStorageKey<String>(
                                        'shelf-group-empty',
                                      ),
                                      physics:
                                          const AlwaysScrollableScrollPhysics(),
                                      padding: const EdgeInsets.symmetric(
                                        horizontal: AppSpacing.screenH,
                                        vertical: AppSpacing.xl,
                                      ),
                                      children: <Widget>[
                                        Text(
                                          '「${_group.label}」分组暂无书籍',
                                          textAlign: TextAlign.center,
                                          style: Theme.of(context)
                                              .textTheme
                                              .bodyMedium
                                              ?.copyWith(
                                                color: Theme.of(
                                                  context,
                                                ).colorScheme.onSurfaceVariant,
                                              ),
                                        ),
                                      ],
                                    )
                                  : ListView.builder(
                                      key: const PageStorageKey<String>(
                                        'shelf-list',
                                      ),
                                      physics:
                                          const AlwaysScrollableScrollPhysics(),
                                      padding: const EdgeInsets.fromLTRB(
                                        AppSpacing.screenH,
                                        AppSpacing.sm,
                                        AppSpacing.screenH,
                                        AppSpacing.screenV,
                                      ),
                                      itemCount: filtered.length,
                                      itemBuilder:
                                          (BuildContext context, int index) {
                                            final Book book = filtered[index];
                                            return Padding(
                                              padding: const EdgeInsets.only(
                                                bottom: AppSpacing.cardGap,
                                              ),
                                              child: _ShelfBookCard(
                                                key: ValueKey<int>(book.id),
                                                services: widget.services,
                                                book: book,
                                                badge: _badgeOf(tasks[book.id]),
                                                onTap: () => _openBook(book),
                                                onLongPress: () =>
                                                    _confirmDelete(book),
                                              ),
                                            );
                                          },
                                    ),
                            ),
                          ),
                        ],
                      );
                    },
              );
            },
      ),
    );
  }
}

class _GroupChips extends StatelessWidget {
  const _GroupChips({required this.selected, required this.onSelected});

  final _ShelfGroup selected;
  final ValueChanged<_ShelfGroup> onSelected;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 56,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(
          horizontal: AppSpacing.screenH,
          vertical: AppSpacing.sm,
        ),
        itemCount: _ShelfGroup.values.length,
        separatorBuilder: (BuildContext context, int index) =>
            const SizedBox(width: AppSpacing.sm),
        itemBuilder: (BuildContext context, int index) {
          final _ShelfGroup group = _ShelfGroup.values[index];
          return ChoiceChip(
            label: Text(group.label),
            selected: selected == group,
            onSelected: (bool value) {
              if (value) onSelected(group);
            },
          );
        },
      ),
    );
  }
}

class _ShelfBookCard extends StatefulWidget {
  const _ShelfBookCard({
    super.key,
    required this.services,
    required this.book,
    required this.badge,
    required this.onTap,
    required this.onLongPress,
  });

  final AppServices services;
  final Book book;
  final _DownloadBadge? badge;
  final VoidCallback onTap;
  final VoidCallback onLongPress;

  @override
  State<_ShelfBookCard> createState() => _ShelfBookCardState();
}

class _ShelfBookCardState extends State<_ShelfBookCard> {
  late final Stream<ReadingProgress?> _progressStream;

  @override
  void initState() {
    super.initState();
    _progressStream = widget.services.progress.observeProgress(widget.book.id);
  }

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final ColorScheme scheme = theme.colorScheme;
    final Book book = widget.book;
    final String title = nonEmptyOr(book.title, '（未识别书名）');
    final String author = nonEmptyOr(book.author, '作者未知');

    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: widget.onTap,
        onLongPress: widget.onLongPress,
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.md),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: <Widget>[
              AppCover(url: book.coverUrl, width: 64, height: 89, iconSize: 22),
              const SizedBox(width: AppSpacing.md),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: <Widget>[
                        Expanded(
                          child: Text(
                            title,
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                            style: theme.textTheme.titleMedium?.copyWith(
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                        if (book.lastReadAt != null) ...<Widget>[
                          const SizedBox(width: AppSpacing.xs),
                          AppBadge(
                            label: '继续阅读',
                            background: scheme.secondaryContainer,
                            foreground: scheme.onSecondaryContainer,
                          ),
                        ],
                        if (widget.badge != null) ...<Widget>[
                          const SizedBox(width: AppSpacing.xs),
                          AppBadge(
                            label: widget.badge!.label,
                            background:
                                widget.badge == _DownloadBadge.downloading
                                ? scheme.primaryContainer
                                : context.semanticColors.successContainer,
                            foreground:
                                widget.badge == _DownloadBadge.downloading
                                ? scheme.onPrimaryContainer
                                : context.semanticColors.onSuccessContainer,
                          ),
                        ],
                      ],
                    ),
                    const SizedBox(height: AppSpacing.xs),
                    Text(
                      author,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: scheme.onSurfaceVariant,
                      ),
                    ),
                    if (nonEmptyOr(book.latestChapterTitle, '').isNotEmpty)
                      Text(
                        '最新：${book.latestChapterTitle!.trim()}',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.bodySmall?.copyWith(
                          color: scheme.onSurfaceVariant,
                        ),
                      ),
                    const SizedBox(height: AppSpacing.xs),
                    Text(
                      book.totalChapters > 0
                          ? '共 ${book.totalChapters} 章'
                          : '尚无目录',
                      style: theme.textTheme.labelMedium?.copyWith(
                        color: scheme.onSurfaceVariant,
                      ),
                    ),
                    const SizedBox(height: AppSpacing.xs),
                    StreamBuilder<ReadingProgress?>(
                      stream: _progressStream,
                      builder:
                          (
                            BuildContext context,
                            AsyncSnapshot<ReadingProgress?> snapshot,
                          ) {
                            final ReadingProgress? progress = snapshot.data;
                            final double? fraction = progress == null
                                ? null
                                : (progress.progressPercentage / 100).clamp(
                                    0.0,
                                    1.0,
                                  );
                            return Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: <Widget>[
                                Row(
                                  children: <Widget>[
                                    Icon(
                                      book.lastReadAt != null
                                          ? Icons.access_time
                                          : Icons.today_outlined,
                                      size: 12,
                                      color: scheme.onSurfaceVariant,
                                    ),
                                    const SizedBox(width: AppSpacing.xs),
                                    Expanded(
                                      child: Text(
                                        progress != null
                                            ? '已读 ${progress.progressPercentage.toStringAsFixed(1)}% · 第 ${progress.chapterIndex + 1} 章'
                                            : '未开始阅读',
                                        maxLines: 1,
                                        overflow: TextOverflow.ellipsis,
                                        style: theme.textTheme.labelMedium
                                            ?.copyWith(
                                              color: scheme.onSurfaceVariant,
                                            ),
                                      ),
                                    ),
                                    Text(
                                      formatDateTime(
                                        book.lastReadAt ?? book.updatedAt,
                                      ),
                                      style: theme.textTheme.labelMedium
                                          ?.copyWith(
                                            color: scheme.onSurfaceVariant,
                                          ),
                                    ),
                                  ],
                                ),
                                if (fraction != null &&
                                    fraction > 0) ...<Widget>[
                                  const SizedBox(height: AppSpacing.xs),
                                  ClipRRect(
                                    borderRadius: BorderRadius.circular(2),
                                    child: LinearProgressIndicator(
                                      value: fraction,
                                      minHeight: 3,
                                      backgroundColor:
                                          scheme.surfaceContainerHighest,
                                    ),
                                  ),
                                ],
                              ],
                            );
                          },
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
