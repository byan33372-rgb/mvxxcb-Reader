/// URL 规范化、相对链接解析、标题指纹与目录锚点有用的纯函数。
///
/// 语义对齐 Kotlin 版 `parser/util/UrlNormalizer.kt`、`data/util/TitleFingerprint.kt`、
/// `parser/util/CatalogAnchorFilter.kt`、`parser/util/PaginationMerger.kt`。
/// 本文件不依赖网络/平台插件，可在纯 Dart 单测中直接调用。
library;

import 'dart:convert';

import 'package:crypto/crypto.dart';

// ---------------- URL 规范化 ----------------

/// 跟踪参数精确清单（与 Kotlin 版一致）。
const Set<String> _exactTrackingParams = {
  'from',
  'spm',
  'fr',
  'gclid',
  'fbclid',
  'refer_flag',
  'share_token',
  'vd_source',
  'clickid',
  'tt_from',
  'f_from',
};

/// 前缀型跟踪参数。
const List<String> _trackingParamPrefixes = ['utm_', 'share_', 'spm_'];

/// 规范化 URL：小写 scheme/host、去默认端口与 fragment、去跟踪参数、
/// 空 path 补 `/`、非根 path 去一个尾 `/`、保留 query 顺序。
///
/// [baseUrl] 非空时把 [rawUrl] 视作相对链接并按基址解析。
/// 非法 URL（非 http/https、无 host）返回 null。
String? normalizeUrl(String rawUrl, {String? baseUrl}) {
  final raw = rawUrl.trim();
  if (raw.isEmpty) return null;

  Uri? resolved;
  if (baseUrl != null && baseUrl.trim().isNotEmpty) {
    final base = _lenientUri(baseUrl.trim());
    if (base == null) return null;
    resolved = _resolve(base, raw);
  } else {
    resolved = _lenientUri(raw);
  }
  if (resolved == null) return null;

  final scheme = resolved.scheme.toLowerCase();
  if (scheme != 'http' && scheme != 'https') return null;
  final host = resolved.host.toLowerCase();
  if (host.isEmpty) return null;

  var path = resolved.path;
  if (path.isEmpty) path = '/';
  if (path.length > 1 && path.endsWith('/')) {
    path = path.replaceFirst(RegExp(r'/+$'), '');
    if (path.isEmpty) path = '/';
  }

  final query = _filterQuery(resolved.query);

  final sb = StringBuffer()
    ..write(scheme)
    ..write('://');
  if (host.contains(':')) {
    sb.write('[$host]');
  } else {
    sb.write(host);
  }
  if (resolved.hasPort) sb.write(':${resolved.port}');
  sb.write(path);
  if (query != null) sb.write('?$query');
  return sb.toString();
}

/// 相对链接 → 绝对规范化 URL。
String? resolveUrl(String baseUrl, String relative) => normalizeUrl(relative, baseUrl: baseUrl);

/// 提取小写 host；非法/无 host 返回 null。
String? hostOf(String url) {
  final uri = _lenientUri(url.trim());
  final host = uri?.host.toLowerCase();
  if (host == null || host.isEmpty) return null;
  return host;
}

/// 目录前缀：`scheme://host[:port] + path 至最后一个 '/'（含）`。
String? directoryPrefix(String url) {
  final uri = _lenientUri(url.trim());
  if (uri == null) return null;
  final scheme = uri.scheme.toLowerCase();
  final host = uri.host.toLowerCase();
  if (scheme.isEmpty || host.isEmpty) return null;
  final rawPath = uri.path.isEmpty ? '/' : uri.path;
  final slash = rawPath.lastIndexOf('/');
  final dir = slash >= 0 ? rawPath.substring(0, slash + 1) : '/';
  final sb = StringBuffer('$scheme://');
  sb.write(host.contains(':') ? '[$host]' : host);
  if (uri.hasPort) sb.write(':${uri.port}');
  sb.write(dir);
  return sb.toString();
}

/// 跟踪参数判定（精确 + 前缀）。
bool isTrackingParam(String key) {
  final k = key.trim().toLowerCase();
  if (k.isEmpty) return false;
  if (_exactTrackingParams.contains(k)) return true;
  return _trackingParamPrefixes.any(k.startsWith);
}

String? _filterQuery(String rawQuery) {
  if (rawQuery.isEmpty) return null;
  final kept = <String>[];
  for (final pair in rawQuery.split('&')) {
    if (pair.isEmpty) continue;
    final eq = pair.indexOf('=');
    final rawKey = eq >= 0 ? pair.substring(0, eq) : pair;
    String key;
    try {
      key = Uri.decodeQueryComponent(rawKey);
    } catch (_) {
      key = rawKey;
    }
    if (isTrackingParam(key)) continue;
    kept.add(pair);
  }
  return kept.isEmpty ? null : kept.join('&');
}

Uri? _lenientUri(String text) {
  final direct = Uri.tryParse(text);
  if (direct != null) return direct;
  final encoded = text.replaceAll(' ', '%20');
  return Uri.tryParse(encoded);
}

Uri? _resolve(Uri base, String relative) {
  try {
    return base.resolve(relative);
  } catch (_) {
    try {
      return base.resolve(relative.replaceAll(' ', '%20'));
    } catch (_) {
      return null;
    }
  }
}

/// 日志/错误对象脱敏：去掉 query/fragment 并截断。
String redactUrl(String url) {
  final trimmed = url.trim();
  var cut = trimmed;
  final q = cut.indexOf('?');
  if (q >= 0) cut = cut.substring(0, q);
  final h = cut.indexOf('#');
  if (h >= 0) cut = cut.substring(0, h);
  return cut.length > 96 ? '${cut.substring(0, 96)}…' : cut;
}

// ---------------- 标题指纹 ----------------

const List<String> _decorWords = ['最新章节', 'VIP章节', '正文'];

/// 标题指纹归一化（03 §7.2）：折叠空白、全角→半角、去装饰/书名号/引号、
/// 小写、编号空白归一，最终移除全部空白。
String normalizeTitleForFingerprint(String title) {
  var s = title.trim();
  if (s.isEmpty) return '';
  s = s.replaceAll(RegExp(r'\s+'), ' ');
  s = _fullWidthToHalfWidth(s);
  for (final word in _decorWords) {
    s = s.replaceAll(word, '');
  }
  s = s.replaceAll(RegExp('[《》「」『』〔〕【】“”‘’"\']'), '');
  s = s.toLowerCase();
  s = s.replaceAllMapped(
    RegExp(r'第\s*(\d+)\s*([章回节卷])'),
    (m) => '第${m.group(1)}${m.group(2)}',
  );
  s = s.replaceAllMapped(
    RegExp(r'chapter\s*(\d+)', caseSensitive: false),
    (m) => 'chapter${m.group(1)}',
  );
  return s.replaceAll(' ', '');
}

/// 标题指纹：SHA-256(归一化标题) 前 32 hex；空标题返回空串。
String titleFingerprint(String title) {
  final normalized = normalizeTitleForFingerprint(title);
  if (normalized.isEmpty) return '';
  return sha256.convert(utf8.encode(normalized)).toString().substring(0, 32);
}

String _fullWidthToHalfWidth(String text) {
  final sb = StringBuffer();
  for (final rune in text.runes) {
    if (rune == 0x3000) {
      sb.write(' ');
    } else if (rune >= 0xFF01 && rune <= 0xFF5E) {
      sb.writeCharCode(rune - 0xFEE0);
    } else {
      sb.writeCharCode(rune);
    }
  }
  return sb.toString();
}

// ---------------- 目录/章节链接特征 ----------------

/// 非章节路径首段（文库筛选/分类/排行/站务等，通用启发式）。
const Set<String> nonChapterFirstSegments = {
  'wenku', 'huihu', 'huiku', 'shuku', 'class', 'classify', 'category', 'categ',
  'fenlei', 'sort', 'tags', 'tag', 'label',
  'rank', 'ranking', 'paihang', 'top', 'toplist', 'lastupdate', 'zuixin', 'tops',
  'bookcase', 'bookshelf', 'review', 'pinglun', 'author', 'zuozhe', 'search',
  'user', 'member', 'login', 'register', 'app', 'download', 'help', 'about',
  'contact', 'links', 'friendlink', 'news', 'mobile', 'api', 'vote', 'tuijian',
  'recommend', 'ad', 'ads', 'advert', 'advertisement', 'banner', 'promote',
  'promotion',
};

final RegExp _volumeAnchorRe =
    RegExp(r'^(?:vol|volume|juan|fenjuan)[_\-]?\d+\.x?html?$');

final RegExp _textChapterRe =
    RegExp(r'第\s*[0-9零〇一二两三四五六七八九十百千]+\s*[章节回]');

final RegExp _urlChapterRe =
    RegExp(r'\d+[/_\-]?\d*\.x?html?\b', caseSensitive: false);

final RegExp _numericPathRe =
    RegExp(r'/\d+(?:[/_\-]\d+)*(?:\.x?html?)?(?:$|[?#])', caseSensitive: false);

final RegExp _pageNavTextRe = RegExp(
  r'^(下一页|上一页|下页|上页|首页|末页|尾页|下一章|上一章|下一节|上一节|next|previous|prev)$',
  caseSensitive: false,
);

final RegExp _chapterNumberRe =
    RegExp(r'第\s*([0-9０-９]+|[零〇一二两三四五六七八九十百千]+)\s*[章节回]');

/// 页码/章节导航文本（目录提取时排除）。
bool isPageNavText(String text) => _pageNavTextRe.hasMatch(text.trim());

/// 是否为非章节路径（只看路径首段，避免误杀 `/novel/5544/346134.html`）。
bool isNonChapterPath(String url) {
  final path = _lenientUri(url.trim())?.path;
  if (path == null) return false;
  final trimmed = path.startsWith('/') ? path.substring(1) : path;
  final first = trimmed.split('/').first.toLowerCase();
  if (first.isEmpty) return false;
  return nonChapterFirstSegments.contains(first);
}

/// 分卷锚（`vol_123.html` / `volume-12.html` / `juan3.html`）。
bool isVolumeAnchor(String url) {
  final path = _lenientUri(url.trim())?.path;
  if (path == null) return false;
  final last = path.split('/').last.toLowerCase();
  return _volumeAnchorRe.hasMatch(last);
}

/// 章节链接特征：文本含「第N章/节/回」或数字形 `.html` 链接 + 短文本。
bool matchesChapterPattern(String text, String href) {
  if (_textChapterRe.hasMatch(text)) return true;
  if (text.length > 50) return false;
  return _urlChapterRe.hasMatch(href) || _numericPathRe.hasMatch(href);
}

/// 从标题提取章节编号（支持中文数字 ≤ 9999）；无法提取返回 null。
int? extractChapterNumber(String title) {
  final m = _chapterNumberRe.firstMatch(title);
  if (m == null) return null;
  final raw = m.group(1)!;
  final ascii = raw.replaceAllMapped(
    RegExp('[０-９]'),
    (mm) => String.fromCharCode(mm.group(0)!.codeUnitAt(0) - 0xFF10 + 0x30),
  );
  if (RegExp(r'^\d+$').hasMatch(ascii)) return int.tryParse(ascii);
  return _chineseNumberToInt(ascii);
}

const Map<String, int> _cnDigits = {
  '零': 0, '〇': 0, '一': 1, '二': 2, '两': 2, '三': 3, '四': 4,
  '五': 5, '六': 6, '七': 7, '八': 8, '九': 9,
};

int? _chineseNumberToInt(String text) {
  if (text.isEmpty) return null;
  var total = 0;
  var current = 0;
  for (final ch in text.split('')) {
    final digit = _cnDigits[ch];
    if (digit != null) {
      current = current * 10 + digit;
    } else if (ch == '十') {
      total += (current == 0 ? 1 : current) * 10;
      current = 0;
    } else if (ch == '百') {
      total += (current == 0 ? 1 : current) * 100;
      current = 0;
    } else if (ch == '千') {
      total += (current == 0 ? 1 : current) * 1000;
      current = 0;
    } else {
      return null;
    }
  }
  return total + current;
}

/// 页标题归一（正文分页标题一致性）：剥离页码装饰。
String normalizePageTitle(String title) {
  var t = title.trim();
  final patterns = [
    RegExp(r'\s*[（(【\[]\s*(第\s*\d+\s*页|\d+\s*/\s*\d+)\s*[）)】\]]\s*$'),
    RegExp(r'[\s_\-]{1,3}\d+\s*$'),
    RegExp(r'\s*\(\d+\)\s*$'),
  ];
  for (final p in patterns) {
    t = t.replaceAll(p, '');
  }
  return t.replaceAll(RegExp(r'\s+'), ' ').trim();
}

/// 相邻章节编号递减占比（≥3 个编号才有意义）。
double descendingRatio(Iterable<String> titles) {
  final numbers = titles.map(extractChapterNumber).whereType<int>().toList();
  if (numbers.length < 3) return 0;
  var descending = 0;
  for (var i = 0; i < numbers.length - 1; i++) {
    if (numbers[i] > numbers[i + 1]) descending++;
  }
  return descending / (numbers.length - 1);
}

/// 文件名清洗（TXT 导出）：非法字符 → `_`，空白折叠，长度 ≤ 80。
String sanitizeFileName(String raw) {
  final illegal = RegExp(r'[\\/:*?"<>|\u0000-\u001F]');
  var cleaned = raw.replaceAll(illegal, '_').replaceAll(RegExp(r'\s+'), ' ').trim();
  cleaned = cleaned.replaceAll(RegExp(r'^[._ ]+|[._ ]+$'), '');
  if (cleaned.length > 80) {
    cleaned = cleaned.substring(0, 80).replaceAll(RegExp(r'^[._ ]+|[._ ]+$'), '');
  }
  return cleaned;
}