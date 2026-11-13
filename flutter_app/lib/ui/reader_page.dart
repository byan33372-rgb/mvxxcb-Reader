// 简阅 · 阅读器（连续阅读）
// 目标章起连续渲染：章节分隔条 + 段落/图片；滚近章尾自动加载下一章（每章仅一次请求）；
// 顶部向上加载上一章（保持当前阅读锚点）；点击切换工具栏；五套主题/字号 BottomSheet；
// 滚动 500ms 防抖保存 charOffset + inChapterFraction + percentage；退出/暂停强制保存；
// 恢复按 charOffset 定位段落，缺失时以 inChapterFraction 兜底；未缓存章联网失败显示可重试占位。
// ignore_for_file: deprecated_member_use

import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart' show ScrollDirection;

import '../contracts.dart';
import '../core/app_result.dart';
import '../core/reader_error.dart';
import 'catalog_page.dart';
import 'theme.dart';
import 'widgets.dart';

/// 连续阅读项类型
enum _ReaderItemKind {
  divider,
  paragraph,
  image,
  loading,
  failed,
  empty,
  bookEnd,
}

class _ReaderItem {
  const _ReaderItem({
    required this.kind,
    required this.chapterIndex,
    required this.blockIndex,
    this.text,
    this.imageUrl,
    this.title,
    this.error,
    this.canRetry = false,
  });

  final _ReaderItemKind kind;
  final int chapterIndex;
  final int blockIndex;
  final String? text;
  final String? imageUrl;
  final String? title;
  final ReaderError? error;
  final bool canRetry;
}

class _ChapterState {
  const _ChapterState({
    required this.meta,
    this.loading = false,
    this.blocks,
    this.error,
  });

  const _ChapterState.loading(ChapterMeta meta)
    : this(meta: meta, loading: true);

  final ChapterMeta meta;
  final bool loading;
  final List<ContentBlock>? blocks;
  final ReaderError? error;

  _ChapterState copyWith({ChapterMeta? meta}) => _ChapterState(
    meta: meta ?? this.meta,
    loading: loading,
    blocks: blocks,
    error: error,
  );
}

class _ChapterExtent {
  const _ChapterExtent({required this.start, required this.height});

  final double start;
  final double height;
}

class _ItemMetrics {
  const _ItemMetrics({
    required this.heights,
    required this.offsets,
    required this.charOffsets,
    required this.extents,
    required this.totalHeight,
  });

  final List<double> heights;
  final List<double> offsets;
  final List<int> charOffsets;
  final Map<int, _ChapterExtent> extents;
  final double totalHeight;

  int indexAt(double contentOffset) {
    if (offsets.isEmpty) return 0;
    final double target = contentOffset.clamp(0.0, math.max(0.0, totalHeight));
    int low = 0;
    int high = offsets.length - 1;
    while (low < high) {
      final int mid = (low + high + 1) >> 1;
      if (offsets[mid] <= target) {
        low = mid;
      } else {
        high = mid - 1;
      }
    }
    return low;
  }
}

class _AnchorInfo {
  const _AnchorInfo({
    required this.itemIndex,
    required this.chapterIndex,
    required this.blockIndex,
    required this.charOffset,
    required this.fraction,
    required this.percentage,
  });

  final int itemIndex;
  final int chapterIndex;
  final int blockIndex;
  final int charOffset;
  final double fraction;
  final double percentage;
}

class _PositionSnapshot {
  const _PositionSnapshot({
    required this.chapterIndex,
    required this.blockIndex,
    required this.deltaFromTop,
  });

  final int chapterIndex;
  final int blockIndex;
  final double deltaFromTop;
}

class _FetchedChapter {
  const _FetchedChapter({this.blocks, this.error});

  final List<ContentBlock>? blocks;
  final ReaderError? error;
}

class ReaderPage extends StatefulWidget {
  const ReaderPage({
    super.key,
    required this.services,
    required this.bookId,
    this.initialChapterIndex = 0,
  });

  final AppServices services;
  final int bookId;
  final int initialChapterIndex;

  @override
  State<ReaderPage> createState() => _ReaderPageState();
}

class _ReaderPageState extends State<ReaderPage> with WidgetsBindingObserver {
  static const double _listTopPadding = 24;
  static const double _listBottomPadding = 48;
  static const double _paragraphGap = 12;
  static const double _dividerHeight = 96;
  static const double _placeholderHeight = 112;
  static const double _imageHeight = 244;
  static const double _bookEndHeight = 88;

  final ScrollController _scrollController = ScrollController();

  ReaderSettings? _settings;
  Book? _book;
  ReadingProgress? _savedProgress;
  List<ChapterMeta>? _metas;
  ReaderError? _metasError;
  bool _loadingMetas = true;
  bool _initialized = false;

  int _targetIndex = 0;
  int _startIndex = 0;
  int _endIndex = 0;
  int _currentChapterIndex = 0;

  final Map<int, _ChapterState> _chapters = <int, _ChapterState>{};
  final Set<int> _requested = <int>{};

  List<_ReaderItem> _items = const <_ReaderItem>[];
  _ItemMetrics? _metrics;
  final Map<int, _ChapterExtent> _chapterExtents = <int, _ChapterExtent>{};

  StreamSubscription<List<ChapterMeta>>? _metasSubscription;
  int _metaStreamKey = 0;

  bool _toolbarVisible = false;
  bool _restored = false;
  bool _restoring = false;
  bool _userScrolled = false;

  double _contentWidth = 0;
  TextScaler _textScaler = TextScaler.noScaling;

  _PositionSnapshot? _pendingPreserve;
  int? _pendingJumpChapter;

  Timer? _saveTimer;
  bool _saving = false;
  bool _saveQueued = false;
  bool _saveErrorShown = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _scrollController.addListener(_onScrollChanged);
    _bootstrap();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _metasSubscription?.cancel();
    _saveTimer?.cancel();
    _forceSave();
    _scrollController.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.detached) {
      _forceSave();
    }
  }

  Future<void> _bootstrap() async {
    ReaderSettings settings;
    try {
      settings = await widget.services.settings.load();
    } catch (_) {
      settings = const ReaderSettings();
    }
    Book? book;
    ReadingProgress? progress;
    try {
      book = await widget.services.books.getBook(widget.bookId);
    } catch (_) {
      book = null;
    }
    try {
      progress = await widget.services.progress.getProgress(widget.bookId);
    } catch (_) {
      progress = null;
    }
    if (!mounted) return;
    setState(() {
      _settings = settings;
      _book = book;
      _savedProgress = progress;
    });
    _subscribeMetas();
  }

  void _subscribeMetas() {
    _metasSubscription?.cancel();
    final int key = ++_metaStreamKey;
    _metasSubscription = widget.services.chapters
        .observeChapterMetas(widget.bookId)
        .listen(
          (List<ChapterMeta> metas) {
            if (key != _metaStreamKey) return;
            _onMetas(metas);
          },
          onError: (Object error) {
            if (key != _metaStreamKey || !mounted) return;
            setState(() {
              _loadingMetas = false;
              _metasError = UnknownError('目录加载失败：$error');
            });
          },
        );
  }

  void _onMetas(List<ChapterMeta> metas) {
    if (!mounted) return;
    if (metas.isEmpty) {
      setState(() {
        _metas = metas;
        _loadingMetas = false;
        _metasError = null;
      });
      return;
    }
    if (!_initialized) {
      final int target = widget.initialChapterIndex.clamp(0, metas.length - 1);
      setState(() {
        _metas = metas;
        _metasError = null;
        _loadingMetas = false;
        _initialized = true;
        _targetIndex = target;
        _startIndex = target;
        _endIndex = target;
        _currentChapterIndex = target;
      });
      _loadChapter(target);
      return;
    }

    setState(() {
      _metas = metas;
      _metasError = null;
      _loadingMetas = false;
      _startIndex = _startIndex.clamp(0, metas.length - 1);
      _endIndex = _endIndex.clamp(0, metas.length - 1);
      _currentChapterIndex = _currentChapterIndex.clamp(0, metas.length - 1);
      _targetIndex = _targetIndex.clamp(0, metas.length - 1);
      for (int i = _startIndex; i <= _endIndex; i++) {
        final _ChapterState? state = _chapters[i];
        if (state == null) continue;
        if (state.meta.id != metas[i].id) {
          _chapters.remove(i);
          _requested.remove(i);
        } else {
          _chapters[i] = state.copyWith(meta: metas[i]);
        }
      }
    });
    _syncItems();
    for (int i = _startIndex; i <= _endIndex; i++) {
      if (_chapters[i] == null) _loadChapter(i);
    }
  }

  // ---------------- 章节加载 ----------------

  Future<_FetchedChapter> _fetchChapter(ChapterMeta meta) async {
    ReaderContent? cached;
    try {
      cached = await widget.services.chapters.loadContent(meta.id);
    } catch (_) {
      cached = null;
    }
    if (cached != null) {
      return _FetchedChapter(blocks: cached.blocks);
    }

    final AppResult<ParsedChapterContent> parsed = await widget.services.parser
        .parseChapterContent(meta.url);
    if (parsed is Failure<ParsedChapterContent>) {
      return _FetchedChapter(error: parsed.error);
    }
    final ParsedChapterContent content =
        (parsed as Success<ParsedChapterContent>).value;
    try {
      await widget.services.chapters.saveContent(meta.id, content);
    } catch (_) {
      // 写入失败不影响本次阅读。
    }
    try {
      final ReaderContent? reloaded = await widget.services.chapters
          .loadContent(meta.id);
      if (reloaded != null) return _FetchedChapter(blocks: reloaded.blocks);
    } catch (_) {
      // 回读失败则直接使用解析结果。
    }
    return _FetchedChapter(blocks: content.blocks);
  }

  Future<void> _loadChapter(
    int index, {
    _PositionSnapshot? preserve,
    bool deferSync = false,
  }) async {
    final List<ChapterMeta>? metas = _metas;
    if (metas == null || index < 0 || index >= metas.length) return;
    if (_requested.contains(index)) return;
    _requested.add(index);
    final ChapterMeta meta = metas[index];

    setState(() => _chapters[index] = _ChapterState.loading(meta));
    if (!deferSync) _syncItems();

    _FetchedChapter fetched;
    if (meta.isMissing) {
      fetched = _FetchedChapter(
        error: StateError('CHAPTER_NOT_FOUND', '来源站点标记本章缺失'),
      );
    } else {
      try {
        fetched = await _fetchChapter(meta);
      } catch (error) {
        fetched = _FetchedChapter(error: UnknownError('章节加载失败：$error'));
      }
    }
    if (!mounted) return;

    setState(() {
      _chapters[index] = fetched.error != null
          ? _ChapterState(meta: meta, error: fetched.error)
          : _ChapterState(
              meta: meta,
              blocks: fetched.blocks ?? const <ContentBlock>[],
            );
    });
    if (preserve != null) _pendingPreserve = preserve;
    _syncItems();
  }

  void _loadNextChapter() {
    final List<ChapterMeta>? metas = _metas;
    if (metas == null || _endIndex >= metas.length - 1) return;
    if (!_isChapterSettled(_endIndex) || _chapters[_endIndex]?.error != null) {
      return;
    }
    final int next = _endIndex + 1;
    if (_requested.contains(next)) return;
    setState(() => _endIndex = next);
    _loadChapter(next);
  }

  void _loadPreviousChapter() {
    final List<ChapterMeta>? metas = _metas;
    if (metas == null || _startIndex <= 0) return;
    if (!_isChapterSettled(_startIndex) ||
        _chapters[_startIndex]?.error != null) {
      return;
    }
    final int previous = _startIndex - 1;
    if (_requested.contains(previous)) return;
    final _PositionSnapshot? snapshot = _capturePosition();
    setState(() => _startIndex = previous);
    _loadChapter(previous, preserve: snapshot, deferSync: true);
  }

  bool _isChapterSettled(int index) {
    final _ChapterState? state = _chapters[index];
    return state != null && !state.loading;
  }

  void _retryChapter(int index) {
    if (_metas == null || index < 0 || index >= _metas!.length) return;
    final _PositionSnapshot? snapshot = index <= _currentChapterIndex
        ? _capturePosition()
        : null;
    _requested.remove(index);
    _chapters.remove(index);
    _loadChapter(index, preserve: snapshot, deferSync: true);
  }

  // ---------------- 渲染项与度量 ----------------

  List<_ReaderItem> _buildItems() {
    final List<ChapterMeta>? metas = _metas;
    if (metas == null || metas.isEmpty) return const <_ReaderItem>[];
    final List<_ReaderItem> items = <_ReaderItem>[];

    for (
      int chapterIndex = _startIndex;
      chapterIndex <= _endIndex;
      chapterIndex++
    ) {
      final ChapterMeta meta = metas[chapterIndex];
      items.add(
        _ReaderItem(
          kind: _ReaderItemKind.divider,
          chapterIndex: chapterIndex,
          blockIndex: -1,
          title: meta.title,
        ),
      );
      final _ChapterState? state = _chapters[chapterIndex];
      if (state == null || state.loading) {
        items.add(
          _ReaderItem(
            kind: _ReaderItemKind.loading,
            chapterIndex: chapterIndex,
            blockIndex: -2,
          ),
        );
        continue;
      }
      if (state.error != null) {
        items.add(
          _ReaderItem(
            kind: _ReaderItemKind.failed,
            chapterIndex: chapterIndex,
            blockIndex: -3,
            error: state.error,
            canRetry: !meta.isMissing,
          ),
        );
        continue;
      }
      final List<ContentBlock> blocks = state.blocks ?? const <ContentBlock>[];
      int added = 0;
      for (int blockIndex = 0; blockIndex < blocks.length; blockIndex++) {
        final ContentBlock block = blocks[blockIndex];
        if (block is Paragraph) {
          final String text = block.text.trim();
          if (text.isEmpty) continue;
          items.add(
            _ReaderItem(
              kind: _ReaderItemKind.paragraph,
              chapterIndex: chapterIndex,
              blockIndex: blockIndex,
              text: text,
            ),
          );
          added++;
        } else if (block is ImageBlock) {
          items.add(
            _ReaderItem(
              kind: _ReaderItemKind.image,
              chapterIndex: chapterIndex,
              blockIndex: blockIndex,
              imageUrl: block.url,
            ),
          );
          added++;
        }
      }
      if (added == 0) {
        items.add(
          _ReaderItem(
            kind: _ReaderItemKind.empty,
            chapterIndex: chapterIndex,
            blockIndex: -4,
          ),
        );
      }
    }

    if (_endIndex == metas.length - 1 && _isChapterSettled(_endIndex)) {
      items.add(
        _ReaderItem(
          kind: _ReaderItemKind.bookEnd,
          chapterIndex: _endIndex,
          blockIndex: -10,
        ),
      );
    }
    return items;
  }

  TextStyle _paragraphStyle(ReaderSettings settings) {
    final ReadingThemeSpec spec = readingThemeOf(settings.backgroundTheme);
    return TextStyle(
      fontSize: settings.fontSize,
      height: settings.lineHeightMultiplier,
      color: spec.textPrimary,
    );
  }

  double _measureParagraph(String text, ReaderSettings settings) {
    final TextPainter painter = TextPainter(
      text: TextSpan(text: text, style: _paragraphStyle(settings)),
      textDirection: TextDirection.ltr,
      textScaler: _textScaler,
      maxLines: null,
    )..layout(maxWidth: math.max(1, _contentWidth));
    return painter.height + _paragraphGap;
  }

  void _syncItems() {
    if (!mounted) return;
    final ReaderSettings? settings = _settings;
    if (settings == null) return;
    if (_contentWidth <= 0 && mounted) {
      _contentWidth = math.max(
        1,
        MediaQuery.sizeOf(context).width - 2 * settings.horizontalPaddingDp,
      );
      _textScaler = MediaQuery.textScalerOf(context);
    }
    final List<_ReaderItem> items = _buildItems();
    final _ItemMetrics metrics = _computeMetrics(items, settings);
    setState(() {
      _items = items;
      _metrics = metrics;
      _chapterExtents
        ..clear()
        ..addAll(metrics.extents);
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      _afterLayout();
    });
  }

  _ItemMetrics _computeMetrics(
    List<_ReaderItem> items,
    ReaderSettings settings,
  ) {
    final List<double> heights = <double>[];
    final List<double> offsets = <double>[];
    final List<int> charOffsets = <int>[];
    final Map<int, _ChapterExtent> extents = <int, _ChapterExtent>{};

    double cursor = 0;
    int activeChapter = -1;
    double chapterStart = 0;
    int chapterChars = 0;

    void closeChapter() {
      if (activeChapter >= 0) {
        extents[activeChapter] = _ChapterExtent(
          start: chapterStart,
          height: math.max(0, cursor - chapterStart),
        );
      }
    }

    for (final _ReaderItem item in items) {
      if (item.chapterIndex != activeChapter) {
        closeChapter();
        activeChapter = item.chapterIndex;
        chapterStart = cursor;
        chapterChars = 0;
      }
      double height;
      switch (item.kind) {
        case _ReaderItemKind.paragraph:
          height = _measureParagraph(item.text ?? '', settings);
          break;
        case _ReaderItemKind.image:
          height = _imageHeight;
          break;
        case _ReaderItemKind.divider:
          height = _dividerHeight;
          break;
        case _ReaderItemKind.loading:
        case _ReaderItemKind.failed:
          height = _placeholderHeight;
          break;
        case _ReaderItemKind.empty:
          height = 96;
          break;
        case _ReaderItemKind.bookEnd:
          height = _bookEndHeight;
          break;
      }
      offsets.add(cursor);
      heights.add(height);
      charOffsets.add(chapterChars);
      if (item.kind == _ReaderItemKind.paragraph) {
        chapterChars += (item.text ?? '').length;
      }
      cursor += height;
    }
    closeChapter();

    return _ItemMetrics(
      heights: heights,
      offsets: offsets,
      charOffsets: charOffsets,
      extents: extents,
      totalHeight: cursor,
    );
  }

  // ---------------- 锚点 / 进度 ----------------

  _AnchorInfo? _anchorInfo() {
    final _ItemMetrics? metrics = _metrics;
    if (metrics == null || _items.isEmpty || !_scrollController.hasClients) {
      return null;
    }
    final double contentOffset = math.max(
      0,
      _scrollController.offset - _listTopPadding,
    );
    final int index = metrics.indexAt(contentOffset);
    final _ReaderItem item = _items[index];
    final _ChapterExtent? extent = _chapterExtents[item.chapterIndex];
    double fraction = 0;
    if (extent != null && extent.height > 0) {
      fraction = ((contentOffset - extent.start) / extent.height).clamp(
        0.0,
        1.0,
      );
    }
    final int total = _metas?.length ?? 0;
    final double percentage = total > 0
        ? ((item.chapterIndex + fraction) / total * 100).clamp(0.0, 100.0)
        : 0;
    return _AnchorInfo(
      itemIndex: index,
      chapterIndex: item.chapterIndex,
      blockIndex: item.blockIndex,
      charOffset: index < metrics.charOffsets.length
          ? metrics.charOffsets[index]
          : 0,
      fraction: fraction,
      percentage: percentage,
    );
  }

  _PositionSnapshot? _capturePosition() {
    final _AnchorInfo? anchor = _anchorInfo();
    final _ItemMetrics? metrics = _metrics;
    if (anchor == null || metrics == null) return null;
    final double itemTop = metrics.offsets[anchor.itemIndex] + _listTopPadding;
    return _PositionSnapshot(
      chapterIndex: anchor.chapterIndex,
      blockIndex: anchor.blockIndex,
      deltaFromTop: _scrollController.offset - itemTop,
    );
  }

  void _jumpToOffset(double target) {
    if (!_scrollController.hasClients) return;
    final double clamped = target.clamp(
      0.0,
      _scrollController.position.maxScrollExtent,
    );
    _scrollController.jumpTo(clamped);
  }

  void _afterLayout() {
    final _PositionSnapshot? preserve = _pendingPreserve;
    if (preserve != null) {
      _pendingPreserve = null;
      _applyPreserve(preserve);
    }
    if (!_restored) _applyRestore();
    final int? jump = _pendingJumpChapter;
    if (jump != null) {
      _pendingJumpChapter = null;
      _applyJumpToChapter(jump);
    }
    _checkAutoLoad();
  }

  void _applyPreserve(_PositionSnapshot snapshot) {
    if (_metrics == null) return;
    final int index = _items.indexWhere(
      (_ReaderItem item) =>
          item.chapterIndex == snapshot.chapterIndex &&
          item.blockIndex == snapshot.blockIndex,
    );
    if (index < 0) return;
    _jumpToOffset(
      _metrics!.offsets[index] + _listTopPadding + snapshot.deltaFromTop,
    );
  }

  void _applyRestore() {
    if (_restoring || _restored) return;
    final _ItemMetrics? metrics = _metrics;
    final ReadingProgress? progress = _savedProgress;
    final List<ChapterMeta>? metas = _metas;
    if (metrics == null || metas == null || _items.isEmpty) return;

    final int chapterIndex = _targetIndex;
    if (chapterIndex < 0 || chapterIndex >= metas.length) {
      _restored = true;
      return;
    }
    if (chapterIndex != _currentChapterIndex) {
      _restored = true;
      return;
    }

    final _ChapterState? state = _chapters[chapterIndex];
    if (state == null || state.loading) return;
    if (state.error != null || state.blocks == null) {
      _restored = true;
      return;
    }

    _restoring = true;
    bool jumped = false;
    if (progress != null && progress.chapterIndex == chapterIndex) {
      final bool sameChapter = progress.chapterId == metas[chapterIndex].id;
      final int charOffset = progress.charOffset;
      if (sameChapter && charOffset >= 0) {
        int candidate = -1;
        for (int i = 0; i < _items.length; i++) {
          final _ReaderItem item = _items[i];
          if (item.chapterIndex != chapterIndex) continue;
          if (item.kind == _ReaderItemKind.divider) {
            if (candidate < 0) candidate = i;
            continue;
          }
          if (item.kind == _ReaderItemKind.loading ||
              item.kind == _ReaderItemKind.failed ||
              item.kind == _ReaderItemKind.empty ||
              item.kind == _ReaderItemKind.bookEnd) {
            continue;
          }
          if (i < metrics.charOffsets.length &&
              metrics.charOffsets[i] <= charOffset) {
            candidate = i;
          } else {
            break;
          }
        }
        if (charOffset <= 0) {
          final int divider = _items.indexWhere(
            (_ReaderItem item) =>
                item.kind == _ReaderItemKind.divider &&
                item.chapterIndex == chapterIndex,
          );
          if (divider >= 0) candidate = divider;
        }
        if (candidate >= 0) {
          _jumpToOffset(metrics.offsets[candidate] + _listTopPadding);
          jumped = true;
        }
      }
      if (!jumped && progress.inChapterFraction > 0) {
        final _ChapterExtent? extent = _chapterExtents[chapterIndex];
        if (extent != null && extent.height > 0) {
          _jumpToOffset(
            extent.start +
                progress.inChapterFraction * extent.height +
                _listTopPadding,
          );
          jumped = true;
        }
      }
    }
    if (!jumped) _jumpToOffset(0);
    _restored = true;
    _restoring = false;
    _currentChapterIndex = chapterIndex;
  }

  void _applyJumpToChapter(int chapterIndex) {
    if (_metrics == null) return;
    final int index = _items.indexWhere(
      (_ReaderItem item) =>
          item.kind == _ReaderItemKind.divider &&
          item.chapterIndex == chapterIndex,
    );
    if (index < 0) return;
    _jumpToOffset(_metrics!.offsets[index] + _listTopPadding);
    setState(() => _currentChapterIndex = chapterIndex);
  }

  void _jumpToChapter(int chapterIndex) {
    final List<ChapterMeta>? metas = _metas;
    if (metas == null || metas.isEmpty) return;
    final int target = chapterIndex.clamp(0, metas.length - 1);
    final int existing = _items.indexWhere(
      (_ReaderItem item) =>
          item.kind == _ReaderItemKind.divider && item.chapterIndex == target,
    );
    if (existing >= 0 && _metrics != null) {
      setState(() => _currentChapterIndex = target);
      _jumpToOffset(_metrics!.offsets[existing] + _listTopPadding);
      return;
    }
    setState(() {
      _startIndex = target;
      _endIndex = target;
      _currentChapterIndex = target;
      _pendingJumpChapter = target;
    });
    if (_chapters[target] == null) {
      _loadChapter(target, deferSync: true);
    }
    _syncItems();
  }

  void _onScrollChanged() {
    if (!_restored || _restoring) return;
    final _AnchorInfo? anchor = _anchorInfo();
    if (anchor != null && anchor.chapterIndex != _currentChapterIndex) {
      setState(() => _currentChapterIndex = anchor.chapterIndex);
    }
    _scheduleSave();
    _checkAutoLoad();
  }

  void _checkAutoLoad() {
    final _ItemMetrics? metrics = _metrics;
    final List<ChapterMeta>? metas = _metas;
    if (metrics == null || metas == null || _items.isEmpty) return;
    if (!_scrollController.hasClients) return;
    final double contentOffset = math.max(
      0,
      _scrollController.offset - _listTopPadding,
    );
    final double viewport = _scrollController.position.viewportDimension;
    final int firstIndex = metrics.indexAt(contentOffset);
    final int lastIndex = metrics
        .indexAt(contentOffset + viewport)
        .clamp(0, _items.length - 1);

    if (_endIndex - _currentChapterIndex < 2 &&
        _items.length - (lastIndex + 1) <= 3) {
      _loadNextChapter();
    }
    if (_userScrolled && firstIndex <= 1) {
      _loadPreviousChapter();
    }
  }

  void _scheduleSave() {
    _saveTimer?.cancel();
    _saveTimer = Timer(const Duration(milliseconds: 500), _saveNow);
  }

  void _forceSave() {
    _saveTimer?.cancel();
    unawaited(_saveNow());
  }

  Future<void> _saveNow() async {
    if (!_restored || _restoring) return;
    final _AnchorInfo? anchor = _anchorInfo();
    final List<ChapterMeta>? metas = _metas;
    if (anchor == null ||
        metas == null ||
        anchor.chapterIndex >= metas.length) {
      return;
    }
    if (_saving) {
      _saveQueued = true;
      return;
    }
    _saving = true;
    try {
      final ReadingProgress progress = ReadingProgress(
        bookId: widget.bookId,
        chapterId: metas[anchor.chapterIndex].id,
        chapterIndex: anchor.chapterIndex,
        inChapterFraction: anchor.fraction,
        charOffset: anchor.charOffset,
        progressPercentage: anchor.percentage,
        updatedAt: DateTime.now().millisecondsSinceEpoch,
      );
      final AppResult<void> result = await widget.services.progress
          .saveProgress(progress);
      if (result is Failure<void> && mounted && !_saveErrorShown) {
        _saveErrorShown = true;
        showErrorSnack(context, result.error);
      }
    } catch (_) {
      // 静默失败：下一次滚动/退出仍会尝试保存。
    } finally {
      _saving = false;
      if (_saveQueued) {
        _saveQueued = false;
        unawaited(_saveNow());
      }
    }
  }

  // ---------------- 设置 ----------------

  Future<void> _saveSettings(ReaderSettings settings) async {
    try {
      await widget.services.settings.save(settings);
    } catch (_) {
      // 保存失败不阻塞即时生效。
    }
  }

  void _updateSettings(ReaderSettings next) {
    final _PositionSnapshot? snapshot = _capturePosition();
    setState(() => _settings = next);
    _pendingPreserve ??= snapshot;
    unawaited(_saveSettings(next));
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      _syncItems();
    });
  }

  Future<void> _openSettingsSheet() async {
    final ReaderSettings? settings = _settings;
    if (settings == null) return;
    await showModalBottomSheet<void>(
      context: context,
      builder: (BuildContext sheetContext) {
        return StatefulBuilder(
          builder: (BuildContext context, StateSetter setSheetState) {
            final ReaderSettings current = _settings ?? settings;
            String label(ReadingThemeSpec spec) => spec.label;
            return SafeArea(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(
                  AppSpacing.md,
                  AppSpacing.sm,
                  AppSpacing.md,
                  AppSpacing.lg,
                ),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    Text(
                      '阅读主题',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: AppSpacing.sm),
                    Wrap(
                      spacing: AppSpacing.md,
                      runSpacing: AppSpacing.sm,
                      children: kReadingThemes
                          .map((ReadingThemeSpec spec) {
                            final bool selected =
                                current.backgroundTheme == spec.index;
                            return InkWell(
                              borderRadius: BorderRadius.circular(28),
                              onTap: () {
                                _updateSettings(
                                  current.copyWith(backgroundTheme: spec.index),
                                );
                                setSheetState(() {});
                              },
                              child: Column(
                                mainAxisSize: MainAxisSize.min,
                                children: <Widget>[
                                  Container(
                                    width: 44,
                                    height: 44,
                                    decoration: BoxDecoration(
                                      color: spec.background,
                                      shape: BoxShape.circle,
                                      border: Border.all(
                                        color: selected
                                            ? Theme.of(
                                                context,
                                              ).colorScheme.primary
                                            : Theme.of(
                                                context,
                                              ).colorScheme.outlineVariant,
                                        width: selected ? 3 : 1,
                                      ),
                                    ),
                                  ),
                                  const SizedBox(height: 2),
                                  Text(
                                    label(spec),
                                    style: Theme.of(context)
                                        .textTheme
                                        .labelMedium
                                        ?.copyWith(
                                          color: selected
                                              ? Theme.of(
                                                  context,
                                                ).colorScheme.primary
                                              : Theme.of(
                                                  context,
                                                ).colorScheme.onSurfaceVariant,
                                        ),
                                  ),
                                ],
                              ),
                            );
                          })
                          .toList(growable: false),
                    ),
                    const SizedBox(height: AppSpacing.md),
                    Row(
                      children: <Widget>[
                        Text(
                          '字号',
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                        const Spacer(),
                        TextButton(
                          onPressed: () {
                            final double nextSize =
                                (current.fontSize - AppFontSizes.readerStep)
                                    .clamp(
                                      AppFontSizes.readerMin,
                                      AppFontSizes.readerMax,
                                    );
                            _updateSettings(
                              current.copyWith(fontSize: nextSize),
                            );
                            setSheetState(() {});
                          },
                          child: const Text('A−'),
                        ),
                        SizedBox(
                          width: 40,
                          child: Text(
                            current.fontSize.toStringAsFixed(0),
                            textAlign: TextAlign.center,
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                        ),
                        TextButton(
                          onPressed: () {
                            final double nextSize =
                                (current.fontSize + AppFontSizes.readerStep)
                                    .clamp(
                                      AppFontSizes.readerMin,
                                      AppFontSizes.readerMax,
                                    );
                            _updateSettings(
                              current.copyWith(fontSize: nextSize),
                            );
                            setSheetState(() {});
                          },
                          child: const Text('A+'),
                        ),
                      ],
                    ),
                    const SizedBox(height: AppSpacing.sm),
                    Text(
                      '设置即时生效并自动保存',
                      style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                ),
              ),
            );
          },
        );
      },
    );
  }

  // ---------------- 目录跳转 ----------------

  Future<void> _openCatalog() async {
    final int? selected = await Navigator.of(context).push<int>(
      MaterialPageRoute<int>(
        builder: (_) => CatalogPage(
          services: widget.services,
          bookId: widget.bookId,
          selectOnly: true,
        ),
      ),
    );
    if (!mounted || selected == null) return;
    _jumpToChapter(selected);
  }

  void _handleViewportMetrics(double width, TextScaler scaler) {
    final double next = math.max(1, width);
    final bool first = _contentWidth <= 0;
    final bool widthChanged = (_contentWidth - next).abs() > 0.5;
    final bool scalerChanged = !first && _textScaler != scaler;
    if (!first && !widthChanged && !scalerChanged) return;
    final _PositionSnapshot? snapshot = first ? null : _capturePosition();
    _contentWidth = next;
    _textScaler = scaler;
    if (first) return;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      _pendingPreserve ??= snapshot;
      _syncItems();
    });
  }

  // ---------------- 渲染 ----------------

  Color _toolbarColor(ReadingThemeSpec spec) {
    return Color.alphaBlend(
      spec.textPrimary.withValues(alpha: spec.isDark ? 0.12 : 0.05),
      spec.background,
    );
  }

  @override
  Widget build(BuildContext context) {
    final ReaderSettings? settings = _settings;
    if (settings == null) {
      return const Scaffold(body: AppLoading(text: '正在准备阅读…'));
    }

    final ReadingThemeSpec spec = readingThemeOf(settings.backgroundTheme);
    final List<ChapterMeta>? metas = _metas;

    if (_metasError != null && metas == null) {
      return Scaffold(
        backgroundColor: spec.background,
        appBar: AppBar(
          backgroundColor: spec.background,
          title: const Text('阅读'),
        ),
        body: Padding(
          padding: const EdgeInsets.all(AppSpacing.md),
          child: AppErrorBanner(
            error: _metasError,
            onRetry: () {
              setState(() {
                _metasError = null;
                _loadingMetas = true;
              });
              _subscribeMetas();
            },
          ),
        ),
      );
    }

    if (metas == null || _loadingMetas) {
      return const Scaffold(body: AppLoading(text: '正在加载目录…'));
    }

    if (metas.isEmpty) {
      return Scaffold(
        backgroundColor: spec.background,
        appBar: AppBar(
          backgroundColor: spec.background,
          title: Text(nonEmptyOr(_book?.title, '阅读')),
        ),
        body: const AppEmptyState(
          title: '目录为空',
          description: '请先在书籍详情页更新目录',
          icon: Icons.menu_book_outlined,
        ),
      );
    }

    final String bookTitle = nonEmptyOr(_book?.title, '阅读中');
    final String chapterTitle =
        _currentChapterIndex >= 0 && _currentChapterIndex < metas.length
        ? metas[_currentChapterIndex].title
        : '';

    return PopScope<Object?>(
      canPop: true,
      onPopInvokedWithResult: (bool didPop, Object? result) {
        if (didPop) _forceSave();
      },
      child: Scaffold(
        backgroundColor: spec.background,
        body: LayoutBuilder(
          builder: (BuildContext context, BoxConstraints constraints) {
            _handleViewportMetrics(
              constraints.maxWidth - 2 * settings.horizontalPaddingDp,
              MediaQuery.textScalerOf(context),
            );
            return Stack(
              children: <Widget>[
                Positioned.fill(
                  child: NotificationListener<UserScrollNotification>(
                    onNotification: (UserScrollNotification notification) {
                      if (notification.direction != ScrollDirection.idle) {
                        _userScrolled = true;
                      }
                      return false;
                    },
                    child: GestureDetector(
                      behavior: HitTestBehavior.translucent,
                      onTap: () =>
                          setState(() => _toolbarVisible = !_toolbarVisible),
                      child: ListView.builder(
                        controller: _scrollController,
                        padding: const EdgeInsets.only(
                          top: _listTopPadding,
                          bottom: _listBottomPadding,
                        ),
                        cacheExtent: 900,
                        itemCount: _items.length,
                        itemBuilder: (BuildContext context, int index) {
                          return _buildItem(
                            context,
                            _items[index],
                            settings,
                            spec,
                          );
                        },
                      ),
                    ),
                  ),
                ),
                if (!_restored)
                  Positioned.fill(
                    child: IgnorePointer(
                      child: ColoredBox(
                        color: spec.background.withValues(alpha: 0.92),
                        child: AppLoading(text: '正在恢复阅读位置…'),
                      ),
                    ),
                  ),
                _buildTopBar(spec, bookTitle, chapterTitle),
                _buildBottomBar(spec, metas),
              ],
            );
          },
        ),
      ),
    );
  }

  Widget _buildItem(
    BuildContext context,
    _ReaderItem item,
    ReaderSettings settings,
    ReadingThemeSpec spec,
  ) {
    final double horizontal = settings.horizontalPaddingDp;
    final Key key = ValueKey<String>(
      'r-${item.kind.name}-${item.chapterIndex}-${item.blockIndex}',
    );
    switch (item.kind) {
      case _ReaderItemKind.divider:
        return SizedBox(
          key: key,
          height: _dividerHeight,
          child: Padding(
            padding: EdgeInsets.fromLTRB(horizontal, 40, horizontal, 4),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Divider(
                  height: 0.5,
                  thickness: 0.5,
                  color: spec.textSecondary.withValues(alpha: 0.25),
                ),
                const SizedBox(height: 12),
                Text(
                  nonEmptyOr(item.title, '（无标题）'),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontSize: 17,
                    fontWeight: FontWeight.w600,
                    color: spec.textSecondary,
                  ),
                ),
              ],
            ),
          ),
        );

      case _ReaderItemKind.paragraph:
        return Padding(
          key: key,
          padding: EdgeInsets.fromLTRB(
            horizontal,
            0,
            horizontal,
            _paragraphGap,
          ),
          child: Text(item.text ?? '', style: _paragraphStyle(settings)),
        );

      case _ReaderItemKind.image:
        return Padding(
          key: key,
          padding: EdgeInsets.fromLTRB(
            horizontal,
            0,
            horizontal,
            _paragraphGap,
          ),
          child: SizedBox(
            height: _imageHeight - _paragraphGap,
            width: double.infinity,
            child: Image.network(
              item.imageUrl ?? '',
              fit: BoxFit.contain,
              errorBuilder:
                  (BuildContext context, Object error, StackTrace? stack) {
                    return Center(
                      child: Text(
                        '图片加载失败',
                        style: TextStyle(
                          color: spec.textSecondary,
                          fontSize: 12,
                        ),
                      ),
                    );
                  },
            ),
          ),
        );

      case _ReaderItemKind.loading:
        return SizedBox(
          key: key,
          height: _placeholderHeight,
          child: Center(
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: <Widget>[
                const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                ),
                const SizedBox(width: AppSpacing.sm),
                Text(
                  '正在加载下一章…',
                  style: TextStyle(color: spec.textSecondary, fontSize: 12),
                ),
              ],
            ),
          ),
        );

      case _ReaderItemKind.failed:
        return SizedBox(
          key: key,
          height: _placeholderHeight,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: AppSpacing.md),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: <Widget>[
                Text(
                  item.canRetry ? '章节加载失败' : '本章在来源站点已缺失',
                  style: TextStyle(
                    color: spec.textPrimary,
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  item.error == null ? '请重试' : userMessageOf(item.error!),
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  textAlign: TextAlign.center,
                  style: TextStyle(color: spec.textSecondary, fontSize: 12),
                ),
                if (item.canRetry)
                  TextButton(
                    onPressed: () => _retryChapter(item.chapterIndex),
                    style: TextButton.styleFrom(
                      foregroundColor: spec.textPrimary,
                    ),
                    child: const Text('重试'),
                  ),
              ],
            ),
          ),
        );

      case _ReaderItemKind.empty:
        return SizedBox(
          key: key,
          height: 96,
          child: Center(
            child: Text(
              '本章暂无内容',
              style: TextStyle(color: spec.textSecondary, fontSize: 13),
            ),
          ),
        );

      case _ReaderItemKind.bookEnd:
        return SizedBox(
          key: key,
          height: _bookEndHeight,
          child: Center(
            child: Text(
              '已到全书末尾',
              style: TextStyle(color: spec.textSecondary, fontSize: 13),
            ),
          ),
        );
    }
  }

  Widget _buildTopBar(
    ReadingThemeSpec spec,
    String bookTitle,
    String chapterTitle,
  ) {
    return Positioned(
      top: 0,
      left: 0,
      right: 0,
      child: IgnorePointer(
        ignoring: !_toolbarVisible,
        child: AnimatedOpacity(
          opacity: _toolbarVisible ? 1 : 0,
          duration: const Duration(milliseconds: 180),
          child: AnimatedSlide(
            offset: _toolbarVisible ? Offset.zero : const Offset(0, -0.25),
            duration: const Duration(milliseconds: 180),
            child: Material(
              color: _toolbarColor(spec),
              elevation: 2,
              child: SafeArea(
                bottom: false,
                child: Padding(
                  padding: const EdgeInsets.symmetric(
                    horizontal: AppSpacing.xs,
                    vertical: AppSpacing.xs,
                  ),
                  child: Row(
                    children: <Widget>[
                      IconButton(
                        onPressed: () {
                          _forceSave();
                          Navigator.of(context).pop();
                        },
                        tooltip: '返回',
                        color: spec.textPrimary,
                        icon: const Icon(Icons.arrow_back),
                      ),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: <Widget>[
                            Text(
                              bookTitle,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                color: spec.textPrimary,
                                fontSize: 16,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                            Text(
                              chapterTitle,
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                color: spec.textSecondary,
                                fontSize: 12,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildBottomBar(ReadingThemeSpec spec, List<ChapterMeta> metas) {
    final _AnchorInfo? anchor = _anchorInfo();
    final String progress = anchor == null
        ? '正在加载…'
        : '本章 ${(anchor.fraction * 100).round()}% · 全书 ${anchor.percentage.toStringAsFixed(1)}%';
    final bool hasPrevious = _currentChapterIndex > 0;
    final bool hasNext = _currentChapterIndex < metas.length - 1;

    return Positioned(
      left: 0,
      right: 0,
      bottom: 0,
      child: IgnorePointer(
        ignoring: !_toolbarVisible,
        child: AnimatedOpacity(
          opacity: _toolbarVisible ? 1 : 0,
          duration: const Duration(milliseconds: 180),
          child: AnimatedSlide(
            offset: _toolbarVisible ? Offset.zero : const Offset(0, 0.25),
            duration: const Duration(milliseconds: 180),
            child: Material(
              color: _toolbarColor(spec),
              elevation: 2,
              child: SafeArea(
                top: false,
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(
                    AppSpacing.sm,
                    AppSpacing.xs,
                    AppSpacing.sm,
                    AppSpacing.xs,
                  ),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: <Widget>[
                      Align(
                        alignment: Alignment.centerLeft,
                        child: Padding(
                          padding: const EdgeInsets.symmetric(
                            horizontal: AppSpacing.sm,
                          ),
                          child: Text(
                            progress,
                            style: TextStyle(
                              color: spec.textSecondary,
                              fontSize: 12,
                            ),
                          ),
                        ),
                      ),
                      Row(
                        children: <Widget>[
                          TextButton(
                            onPressed: hasPrevious
                                ? () => _jumpToChapter(_currentChapterIndex - 1)
                                : null,
                            style: TextButton.styleFrom(
                              foregroundColor: spec.textPrimary,
                            ),
                            child: const Text('上一章'),
                          ),
                          const Spacer(),
                          IconButton(
                            onPressed: _openCatalog,
                            tooltip: '目录',
                            color: spec.textPrimary,
                            icon: const Icon(Icons.format_list_bulleted),
                          ),
                          IconButton(
                            onPressed: _openSettingsSheet,
                            tooltip: '阅读设置',
                            color: spec.textPrimary,
                            icon: const Icon(Icons.tune),
                          ),
                          const Spacer(),
                          TextButton(
                            onPressed: hasNext
                                ? () => _jumpToChapter(_currentChapterIndex + 1)
                                : null,
                            style: TextButton.styleFrom(
                              foregroundColor: spec.textPrimary,
                            ),
                            child: const Text('下一章'),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
