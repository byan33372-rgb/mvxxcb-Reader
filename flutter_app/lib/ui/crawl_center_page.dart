// 简阅 · 采集中心
// 整本下载任务列表：书名/来源/总数/成功/失败/当前章/状态 chip/TXT 状态；
// 操作按状态机显示（暂停/继续/取消/重试失败章/打开书籍），全部真实接 CrawlService。
// ignore_for_file: deprecated_member_use

import 'package:flutter/material.dart';

import '../contracts.dart';
import '../core/app_result.dart';
import '../core/reader_error.dart';
import 'detail_page.dart';
import 'theme.dart';
import 'widgets.dart';

class CrawlCenterPage extends StatefulWidget {
  const CrawlCenterPage({super.key, required this.services});

  final AppServices services;

  @override
  State<CrawlCenterPage> createState() => _CrawlCenterPageState();
}

class _CrawlCenterPageState extends State<CrawlCenterPage> {
  ReaderError? _actionError;
  final Set<int> _busyTasks = <int>{};
  int _streamKey = 0;
  late Stream<List<Book>> _booksStream;
  late Stream<List<CrawlTask>> _tasksStream;

  @override
  void initState() {
    super.initState();
    _booksStream = widget.services.books.observeShelf();
    _tasksStream = widget.services.crawl.observeTasks();
  }

  void _reload() {
    setState(() {
      _booksStream = widget.services.books.observeShelf();
      _tasksStream = widget.services.crawl.observeTasks();
      _streamKey++;
    });
  }

  Future<void> _runAction(
    int taskId,
    Future<AppResult<void>> Function() action,
  ) async {
    if (_busyTasks.contains(taskId)) return;
    setState(() {
      _busyTasks.add(taskId);
      _actionError = null;
    });
    try {
      final AppResult<void> result = await action();
      if (!mounted) return;
      if (result is Failure<void>) {
        setState(() => _actionError = result.error);
      }
    } catch (error) {
      if (!mounted) return;
      setState(() => _actionError = UnknownError('操作失败：$error'));
    } finally {
      if (mounted) setState(() => _busyTasks.remove(taskId));
    }
  }

  void _openBook(int bookId) {
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) =>
            BookDetailPage(services: widget.services, bookId: bookId),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('采集中心'),
        actions: <Widget>[
          IconButton(
            tooltip: '刷新',
            onPressed: _reload,
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: Column(
        children: <Widget>[
          if (_actionError != null)
            Padding(
              padding: const EdgeInsets.fromLTRB(
                AppSpacing.screenH,
                AppSpacing.sm,
                AppSpacing.screenH,
                0,
              ),
              child: AppErrorBanner(
                error: _actionError,
                onDismiss: () => setState(() => _actionError = null),
              ),
            ),
          Expanded(
            child: StreamBuilder<List<Book>>(
              stream: _booksStream,
              builder:
                  (
                    BuildContext context,
                    AsyncSnapshot<List<Book>> bookSnapshot,
                  ) {
                    final Map<int, Book> bookById = <int, Book>{
                      for (final Book book
                          in bookSnapshot.data ?? const <Book>[])
                        book.id: book,
                    };
                    return StreamBuilder<List<CrawlTask>>(
                      key: ValueKey<int>(_streamKey),
                      stream: _tasksStream,
                      builder:
                          (
                            BuildContext context,
                            AsyncSnapshot<List<CrawlTask>> taskSnapshot,
                          ) {
                            if (taskSnapshot.hasError) {
                              return Padding(
                                padding: const EdgeInsets.all(AppSpacing.md),
                                child: AppErrorBanner(
                                  message: '采集任务加载失败：${taskSnapshot.error}',
                                  onRetry: _reload,
                                ),
                              );
                            }
                            if (!taskSnapshot.hasData) {
                              return const AppLoading(text: '正在加载采集任务…');
                            }
                            final List<CrawlTask> tasks =
                                List<CrawlTask>.of(
                                  taskSnapshot.data ?? const <CrawlTask>[],
                                )..sort(
                                  (CrawlTask a, CrawlTask b) =>
                                      b.updatedAt.compareTo(a.updatedAt),
                                );
                            if (tasks.isEmpty) {
                              return const AppEmptyState(
                                title: '暂无采集任务',
                                description: '在书籍详情页点击「下载整本小说」即可开始整本下载',
                                icon: Icons.cloud_download_outlined,
                              );
                            }
                            return ListView.builder(
                              padding: const EdgeInsets.fromLTRB(
                                AppSpacing.screenH,
                                AppSpacing.screenV,
                                AppSpacing.screenH,
                                AppSpacing.lg,
                              ),
                              itemCount: tasks.length,
                              itemBuilder: (BuildContext context, int index) {
                                final CrawlTask task = tasks[index];
                                final Book? book = bookById[task.bookId];
                                return Padding(
                                  padding: const EdgeInsets.only(
                                    bottom: AppSpacing.cardGap,
                                  ),
                                  child: _CrawlTaskCard(
                                    key: ValueKey<int>(task.id),
                                    task: task,
                                    book: book,
                                    busy: _busyTasks.contains(task.id),
                                    onOpenBook: () => _openBook(task.bookId),
                                    onPause: () => _runAction(
                                      task.id,
                                      () =>
                                          widget.services.crawl.pause(task.id),
                                    ),
                                    onResume: () => _runAction(
                                      task.id,
                                      () =>
                                          widget.services.crawl.resume(task.id),
                                    ),
                                    onCancel: () => _runAction(
                                      task.id,
                                      () =>
                                          widget.services.crawl.cancel(task.id),
                                    ),
                                    onRetryFailed: () => _runAction(
                                      task.id,
                                      () => widget.services.crawl.retryFailed(
                                        task.id,
                                      ),
                                    ),
                                  ),
                                );
                              },
                            );
                          },
                    );
                  },
            ),
          ),
        ],
      ),
    );
  }
}

class _CrawlTaskCard extends StatelessWidget {
  const _CrawlTaskCard({
    super.key,
    required this.task,
    required this.book,
    required this.busy,
    required this.onOpenBook,
    required this.onPause,
    required this.onResume,
    required this.onCancel,
    required this.onRetryFailed,
  });

  final CrawlTask task;
  final Book? book;
  final bool busy;
  final VoidCallback onOpenBook;
  final VoidCallback onPause;
  final VoidCallback onResume;
  final VoidCallback onCancel;
  final VoidCallback onRetryFailed;

  bool get _active =>
      task.state == CrawlState.waiting ||
      task.state == CrawlState.running ||
      task.state == CrawlState.paused;

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final ColorScheme scheme = theme.colorScheme;
    final String title = nonEmptyOr(book?.title, '书籍 #${task.bookId}');
    final String domain = nonEmptyOr(book?.sourceDomain, '');
    final int total = task.totalChapters;
    final int done = task.succeededCount + task.failedCount;

    final List<Widget> actions = <Widget>[
      TextButton(onPressed: onOpenBook, child: const Text('打开小说')),
    ];
    if (task.state == CrawlState.waiting || task.state == CrawlState.running) {
      actions.add(
        OutlinedButton(
          onPressed: busy ? null : onPause,
          child: const Text('暂停'),
        ),
      );
    }
    if (task.state == CrawlState.paused) {
      actions.add(
        FilledButton(
          onPressed: busy ? null : onResume,
          child: const Text('继续'),
        ),
      );
    }
    if (task.state == CrawlState.waiting || task.state == CrawlState.running) {
      actions.add(
        OutlinedButton(
          onPressed: busy ? null : onCancel,
          child: const Text('取消'),
        ),
      );
    }
    if (task.state == CrawlState.failed) {
      actions.add(
        FilledButton(
          onPressed: busy ? null : onRetryFailed,
          child: const Text('重试失败章'),
        ),
      );
    }

    final bool txtGenerated =
        task.txtPath != null && task.txtPath!.trim().isNotEmpty;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(AppSpacing.md),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Row(
              children: <Widget>[
                Container(
                  width: 48,
                  height: 48,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: scheme.surfaceContainerHigh,
                    borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
                  ),
                  child: Text(
                    title.isEmpty
                        ? '书'
                        : String.fromCharCode(title.runes.first),
                    style: theme.textTheme.titleLarge?.copyWith(
                      color: scheme.onSurfaceVariant,
                    ),
                  ),
                ),
                const SizedBox(width: AppSpacing.md),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Text(
                        title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: theme.textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                      if (domain.isNotEmpty)
                        Text(
                          domain,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: scheme.onSurfaceVariant,
                          ),
                        ),
                    ],
                  ),
                ),
                const SizedBox(width: AppSpacing.sm),
                _StateChip(state: task.state),
              ],
            ),
            const SizedBox(height: AppSpacing.sm),
            if (_active) ...<Widget>[
              ClipRRect(
                borderRadius: BorderRadius.circular(3),
                child: LinearProgressIndicator(
                  value: total > 0 ? (done / total).clamp(0.0, 1.0) : 0,
                  minHeight: 4,
                  backgroundColor: scheme.surfaceContainerHighest,
                ),
              ),
              const SizedBox(height: AppSpacing.xs),
              Text(
                '已推进 $done / $total（成功 ${task.succeededCount} · 失败 ${task.failedCount}）',
                style: theme.textTheme.bodySmall?.copyWith(
                  color: scheme.onSurfaceVariant,
                ),
              ),
            ] else
              Text(
                '成功 ${task.succeededCount} · 失败 ${task.failedCount} · 共 $total 章',
                style: theme.textTheme.bodyMedium,
              ),
            if (task.currentChapterTitle != null &&
                task.currentChapterTitle!.trim().isNotEmpty) ...<Widget>[
              const SizedBox(height: AppSpacing.xs),
              Text(
                '当前：第${task.currentChapterIndex + 1}章 ${task.currentChapterTitle!.trim()}',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: theme.textTheme.bodySmall?.copyWith(
                  color: scheme.onSurfaceVariant,
                ),
              ),
            ],
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
            const SizedBox(height: AppSpacing.sm),
            Wrap(
              spacing: AppSpacing.sm,
              runSpacing: AppSpacing.xs,
              children: actions,
            ),
          ],
        ),
      ),
    );
  }
}

class _StateChip extends StatelessWidget {
  const _StateChip({required this.state});

  final CrawlState state;

  String get _label {
    switch (state) {
      case CrawlState.waiting:
        return '排队中';
      case CrawlState.running:
        return '进行中';
      case CrawlState.paused:
        return '已暂停';
      case CrawlState.failed:
        return '有失败';
      case CrawlState.completed:
        return '已完成';
      case CrawlState.cancelled:
        return '已取消';
    }
  }

  @override
  Widget build(BuildContext context) {
    final ColorScheme scheme = Theme.of(context).colorScheme;
    Color background;
    Color foreground;
    switch (state) {
      case CrawlState.waiting:
        background = scheme.secondaryContainer;
        foreground = scheme.onSecondaryContainer;
        break;
      case CrawlState.running:
        background = scheme.primaryContainer;
        foreground = scheme.onPrimaryContainer;
        break;
      case CrawlState.paused:
        background = scheme.tertiaryContainer;
        foreground = scheme.onTertiaryContainer;
        break;
      case CrawlState.failed:
        background = scheme.errorContainer;
        foreground = scheme.onErrorContainer;
        break;
      case CrawlState.completed:
        background = context.semanticColors.successContainer;
        foreground = context.semanticColors.onSuccessContainer;
        break;
      case CrawlState.cancelled:
        background = scheme.surfaceContainerHighest;
        foreground = scheme.onSurfaceVariant;
        break;
    }
    return AppBadge(
      label: _label,
      background: background,
      foreground: foreground,
    );
  }
}
