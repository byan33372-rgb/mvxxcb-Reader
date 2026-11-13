// 简阅 · 目录页
// 万章级惰性列表（itemExtent + 稳定 key）、正/倒序、300ms 防抖搜索、
// 当前章「当前阅读」标记、已缓存「N字」标记、点击进入阅读器。
// ignore_for_file: deprecated_member_use

import 'dart:async';

import 'package:flutter/material.dart';

import '../contracts.dart';
import '../core/app_result.dart';
import 'reader_page.dart';
import 'theme.dart';
import 'widgets.dart';

class CatalogPage extends StatefulWidget {
  const CatalogPage({
    super.key,
    required this.services,
    required this.bookId,
    this.selectOnly = false,
  });

  final AppServices services;
  final int bookId;

  /// true：由阅读器打开，点击章节时回传 chapterIndex 由宿主跳转。
  final bool selectOnly;

  @override
  State<CatalogPage> createState() => _CatalogPageState();
}

class _CatalogPageState extends State<CatalogPage> {
  static const double _itemExtent = 64;

  final TextEditingController _searchController = TextEditingController();
  final ScrollController _scrollController = ScrollController();

  Book? _book;
  int? _currentChapterId;
  bool _descending = false;
  String _query = '';
  Timer? _debounce;
  int _streamKey = 0;
  bool _didAutoScroll = false;
  late Stream<List<ChapterMeta>> _metasStream;

  @override
  void initState() {
    super.initState();
    _metasStream = widget.services.chapters.observeChapterMetas(widget.bookId);
    _loadMeta();
  }

  @override
  void dispose() {
    _debounce?.cancel();
    _searchController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  void _reloadMetas() {
    setState(() {
      _metasStream = widget.services.chapters.observeChapterMetas(
        widget.bookId,
      );
      _streamKey++;
      _didAutoScroll = false;
    });
  }

  Future<void> _loadMeta() async {
    try {
      final Book? book = await widget.services.books.getBook(widget.bookId);
      final ReadingProgress? progress = await widget.services.progress
          .getProgress(widget.bookId);
      if (!mounted) return;
      setState(() {
        _book = book;
        _currentChapterId = progress?.chapterId ?? book?.currentChapterId;
      });
    } catch (_) {
      // 书籍元信息失败不阻塞目录列表展示。
    }
  }

  void _onSearchChanged(String value) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 300), () {
      if (!mounted) return;
      setState(() => _query = value.trim().toLowerCase());
    });
  }

  void _clearSearch() {
    _debounce?.cancel();
    _searchController.clear();
    setState(() => _query = '');
  }

  List<ChapterMeta> _displayList(List<ChapterMeta> chapters) {
    List<ChapterMeta> list = chapters;
    if (_query.isNotEmpty) {
      list = list
          .where(
            (ChapterMeta item) => item.title.toLowerCase().contains(_query),
          )
          .toList(growable: false);
    } else {
      list = List<ChapterMeta>.of(chapters);
    }
    if (_descending) {
      return list.reversed.toList(growable: false);
    }
    return list;
  }

  void _autoScrollToCurrent(List<ChapterMeta> display) {
    if (_didAutoScroll || _currentChapterId == null) return;
    final int index = display.indexWhere(
      (ChapterMeta item) => item.id == _currentChapterId,
    );
    if (index < 0) return;
    _didAutoScroll = true;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || !_scrollController.hasClients) return;
      final double target =
          (index - 2).clamp(0, display.length - 1) * _itemExtent;
      final double max = _scrollController.position.maxScrollExtent;
      _scrollController.jumpTo(target.clamp(0.0, max));
    });
  }

  void _openChapter(ChapterMeta chapter) {
    if (widget.selectOnly) {
      Navigator.of(context).pop(chapter.chapterIndex);
      return;
    }
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => ReaderPage(
          services: widget.services,
          bookId: widget.bookId,
          initialChapterIndex: chapter.chapterIndex,
        ),
      ),
    );
  }

  Future<void> _updateCatalog() async {
    final String? catalogUrl = _book?.catalogUrl?.trim();
    final String target = (catalogUrl != null && catalogUrl.isNotEmpty)
        ? catalogUrl
        : (_book?.sourceUrl ?? '');
    if (target.isEmpty) {
      showAppSnack(context, '缺少目录页网址，无法更新目录');
      return;
    }
    try {
      final AppResult<ParsedCatalog> parsed = await widget.services.parser
          .parseCatalog(target);
      if (!mounted) return;
      if (parsed is Failure<ParsedCatalog>) {
        showErrorSnack(context, parsed.error);
        return;
      }
      final AppResult<int> result = await widget.services.chapters
          .replaceCatalog(
            widget.bookId,
            (parsed as Success<ParsedCatalog>).value,
          );
      if (!mounted) return;
      if (result is Failure<int>) {
        showErrorSnack(context, result.error);
        return;
      }
      showAppSnack(context, '目录已更新（${(result as Success<int>).value} 章）');
      _reloadMetas();
      await _loadMeta();
    } catch (error) {
      if (!mounted) return;
      showAppSnack(context, '更新目录失败：$error');
    }
  }

  @override
  Widget build(BuildContext context) {
    final String title = nonEmptyOr(_book?.title, '目录');
    return Scaffold(
      appBar: AppBar(
        title: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Text(title, maxLines: 1, overflow: TextOverflow.ellipsis),
            Text(
              '共 ${_book?.totalChapters ?? 0} 章 · ${_descending ? '倒序' : '正序'}',
              style: Theme.of(context).textTheme.labelSmall?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
          ],
        ),
        actions: <Widget>[
          IconButton(
            tooltip: _descending ? '切换正序' : '切换倒序',
            onPressed: () {
              setState(() {
                _descending = !_descending;
                _didAutoScroll = false;
              });
            },
            icon: Icon(
              _descending ? Icons.swap_vert : Icons.swap_vert_outlined,
            ),
          ),
          IconButton(
            tooltip: '更新目录',
            onPressed: _updateCatalog,
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: Column(
        children: <Widget>[
          Padding(
            padding: const EdgeInsets.fromLTRB(
              AppSpacing.screenH,
              AppSpacing.sm,
              AppSpacing.screenH,
              AppSpacing.xs,
            ),
            child: TextField(
              controller: _searchController,
              onChanged: _onSearchChanged,
              textInputAction: TextInputAction.search,
              decoration: InputDecoration(
                hintText: '搜索章节标题',
                prefixIcon: const Icon(Icons.search, size: 20),
                suffixIcon: _searchController.text.isEmpty
                    ? null
                    : IconButton(
                        tooltip: '清除',
                        onPressed: _clearSearch,
                        icon: const Icon(Icons.close, size: 18),
                      ),
              ),
            ),
          ),
          Expanded(
            child: StreamBuilder<List<ChapterMeta>>(
              key: ValueKey<int>(_streamKey),
              stream: _metasStream,
              builder:
                  (
                    BuildContext context,
                    AsyncSnapshot<List<ChapterMeta>> snapshot,
                  ) {
                    if (snapshot.hasError) {
                      return Padding(
                        padding: const EdgeInsets.all(AppSpacing.md),
                        child: AppErrorBanner(
                          message: '目录加载失败：${snapshot.error}',
                          onRetry: _reloadMetas,
                        ),
                      );
                    }
                    if (!snapshot.hasData) {
                      return const AppLoading(text: '正在加载目录…');
                    }
                    final List<ChapterMeta> chapters =
                        snapshot.data ?? const <ChapterMeta>[];
                    if (chapters.isEmpty) {
                      return AppEmptyState(
                        title: '目录为空',
                        description: '可尝试更新目录或重新导入',
                        actionText: '更新目录',
                        onAction: _updateCatalog,
                      );
                    }
                    final List<ChapterMeta> display = _displayList(chapters);
                    if (display.isEmpty) {
                      return const AppEmptyState(
                        title: '没有匹配的章节',
                        description: '换个关键词试试',
                        icon: Icons.search_off,
                      );
                    }
                    _autoScrollToCurrent(display);
                    return ListView.builder(
                      controller: _scrollController,
                      itemExtent: _itemExtent,
                      padding: const EdgeInsets.only(bottom: AppSpacing.lg),
                      itemCount: display.length,
                      itemBuilder: (BuildContext context, int index) {
                        final ChapterMeta chapter = display[index];
                        return _CatalogRow(
                          key: ValueKey<int>(chapter.id),
                          chapter: chapter,
                          isCurrent: chapter.id == _currentChapterId,
                          onTap: () => _openChapter(chapter),
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

class _CatalogRow extends StatelessWidget {
  const _CatalogRow({
    super.key,
    required this.chapter,
    required this.isCurrent,
    required this.onTap,
  });

  final ChapterMeta chapter;
  final bool isCurrent;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final ColorScheme scheme = theme.colorScheme;
    final String title = nonEmptyOr(chapter.title, '（无标题）');

    String? subtitle;
    IconData? subtitleIcon;
    if (isCurrent) {
      subtitle = '当前阅读';
      subtitleIcon = Icons.check_circle;
    } else if (chapter.isMissing) {
      subtitle = '站点已缺失';
    } else if (chapter.isCached) {
      subtitle =
          '已缓存 · ${chapter.wordCount > 0 ? '${chapter.wordCount} 字' : '字数未知'}';
    } else if (chapter.wordCount > 0) {
      subtitle = '约 ${chapter.wordCount} 字';
    }

    return Material(
      color: isCurrent
          ? scheme.primaryContainer.withValues(alpha: 0.32)
          : Colors.transparent,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenH),
          child: Row(
            children: <Widget>[
              Expanded(
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.center,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    Text(
                      title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: theme.textTheme.bodyLarge?.copyWith(
                        fontWeight: isCurrent
                            ? FontWeight.w600
                            : FontWeight.w400,
                        color: chapter.isMissing && !isCurrent
                            ? scheme.onSurfaceVariant
                            : scheme.onSurface,
                      ),
                    ),
                    if (subtitle != null) ...<Widget>[
                      const SizedBox(height: 2),
                      Row(
                        children: <Widget>[
                          if (subtitleIcon != null) ...<Widget>[
                            Icon(subtitleIcon, size: 13, color: scheme.primary),
                            const SizedBox(width: 3),
                          ],
                          Flexible(
                            child: Text(
                              subtitle,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: theme.textTheme.labelSmall?.copyWith(
                                color: isCurrent
                                    ? scheme.primary
                                    : scheme.onSurfaceVariant,
                              ),
                            ),
                          ),
                        ],
                      ),
                    ],
                  ],
                ),
              ),
              if (chapter.isCached)
                Padding(
                  padding: const EdgeInsets.only(left: AppSpacing.sm),
                  child: Icon(
                    Icons.download_done,
                    size: 18,
                    color: scheme.primary,
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
