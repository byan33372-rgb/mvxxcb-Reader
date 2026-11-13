/// 安全 HTTP 出口实现（唯一网络通道）。
///
/// 对齐 Kotlin 版 `security/SecureHttpClientImpl.kt` 的语义：
///  - scheme 白名单 + userinfo 拒绝 + DNS 解析后私网/回环/链路本地/保留段校验；
///  - 手动逐跳重定向（≤5 跳），每跳重新校验，跨主机丢弃 Cookie/Authorization；
///  - 连接 10s / 读 15s 超时，响应体上限 8MB；
///  - 同主机最小间隔 2s，失败退避翻倍至 8s，连续成功 3 次回落 2s；
///  - Content-Type/meta charset 探测，UTF-8 优先，GBK/GB18030 经 package:charset；
///  - 所有失败返回 [AppResult.Failure]（不向调用方抛异常）。
library;

import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:charset/charset.dart' as charset_pkg;
import 'package:http/http.dart' as http;

import '../contracts.dart';
import '../core/app_result.dart';
import '../core/reader_error.dart';
import 'url_utils.dart';

/// 生产安全 HTTP 客户端。
class SecureHttpClientImpl implements SecureHttpClient {
  SecureHttpClientImpl({
    http.Client? client,
    this.connectTimeout = const Duration(seconds: 10),
    this.readTimeout = const Duration(seconds: 15),
    this.maxResponseBytes = 8 * 1024 * 1024,
    this.maxRedirects = 5,
    Duration minHostInterval = const Duration(seconds: 2),
    Duration maxBackoff = const Duration(seconds: 8),
    Future<List<InternetAddress>> Function(String host)? lookupHost,
  })  : _client = client ?? http.Client(),
        _lookupHost = lookupHost ?? _defaultLookup,
        _rateLimiter = _AdaptiveRateLimiter(
          minInterval: minHostInterval,
          maxBackoff: maxBackoff,
        );

  final http.Client _client;
  final Duration connectTimeout;
  final Duration readTimeout;
  final int maxResponseBytes;
  final int maxRedirects;
  final Future<List<InternetAddress>> Function(String host) _lookupHost;
  final _AdaptiveRateLimiter _rateLimiter;

  static Future<List<InternetAddress>> _defaultLookup(String host) =>
      InternetAddress.lookup(host);

  static const String _userAgent =
      'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';

  @override
  Future<AppResult<FetchedPage>> fetch(
    String url, {
    Map<String, String>? headers,
  }) async {
    try {
      return await _fetchInternal(url, headers ?? const {});
    } catch (e) {
      return Failure(_mapUnknownException(e, url));
    }
  }

  /// 关闭底层连接池（应用退出时可选调用）。
  void dispose() => _client.close();

  Future<AppResult<FetchedPage>> _fetchInternal(
    String url,
    Map<String, String> extraHeaders,
  ) async {
    final first = await _checkUrl(url);
    if (first.error != null) return Failure(first.error!);

    var currentUrl = first.normalizedUrl!;
    var currentUri = Uri.parse(currentUrl);
    var headers = Map<String, String>.from(extraHeaders);
    final visited = <String>{currentUrl};
    var redirects = 0;

    while (true) {
      final host = currentUri.host;
      await _rateLimiter.acquire(host);

      final request = http.Request('GET', currentUri)
        ..followRedirects = false
        ..persistentConnection = true;
      _applyHeaders(request, headers);

      http.StreamedResponse response;
      try {
        response = await _client.send(request).timeout(connectTimeout);
      } on TimeoutException {
        _rateLimiter.onFailure(host);
        return Failure(NetworkError(
          'CONNECT_TIMEOUT',
          url: redactUrl(currentUrl),
          debugDetail: '连接超时 ${connectTimeout.inSeconds}s',
        ));
      } on SocketException catch (e) {
        _rateLimiter.onFailure(host);
        return Failure(_mapSocketException(e, currentUrl));
      } on http.ClientException catch (e) {
        _rateLimiter.onFailure(host);
        return Failure(NetworkError(
          'REQUEST_FAILED',
          url: redactUrl(currentUrl),
          debugDetail: e.message,
        ));
      }

      final status = response.statusCode;

      if (_isRedirect(status)) {
        final location = response.headers['location'];
        await _drain(response);
        if (location == null || location.trim().isEmpty) {
          _rateLimiter.onFailure(host);
          return Failure(NetworkError(
            'HTTP_ERROR_STATUS',
            url: redactUrl(currentUrl),
            statusCode: status,
            debugDetail: '重定向响应缺少 Location',
          ));
        }
        redirects++;
        if (redirects > maxRedirects) {
          return Failure(NetworkError(
            'TOO_MANY_REDIRECTS',
            url: redactUrl(currentUrl),
            statusCode: status,
            debugDetail: '重定向次数超过上限 $maxRedirects',
          ));
        }
        Uri targetUri;
        try {
          targetUri = currentUri.resolve(location.trim());
        } catch (_) {
          return Failure(NetworkError(
            'INVALID_URL',
            url: redactUrl(currentUrl),
            statusCode: status,
            debugDetail: 'Location 无法解析',
          ));
        }
        final targetUrl = targetUri.toString();
        if (!visited.add(targetUrl)) {
          return Failure(NetworkError(
            'REDIRECT_LOOP',
            url: redactUrl(targetUrl),
            statusCode: status,
            debugDetail: '检测到重定向循环',
          ));
        }
        final check = await _checkUrl(targetUrl, fromUrl: currentUrl);
        if (check.error != null) return Failure(check.error!);
        if (targetUri.host.toLowerCase() != host.toLowerCase()) {
          headers = _stripSensitiveHeaders(headers);
        }
        currentUrl = check.normalizedUrl!;
        currentUri = Uri.parse(currentUrl);
        continue;
      }

      if (status >= 400) {
        await _drain(response);
        _rateLimiter.onFailure(host);
        return Failure(NetworkError(
          'HTTP_ERROR_STATUS',
          url: redactUrl(currentUrl),
          statusCode: status,
          debugDetail: 'HTTP 状态码 $status',
        ));
      }

      final declaredLength = response.contentLength;
      if (declaredLength != null && declaredLength > maxResponseBytes) {
        await _drain(response);
        return Failure(NetworkError(
          'RESPONSE_TOO_LARGE',
          url: redactUrl(currentUrl),
          statusCode: status,
          debugDetail: '响应体 $declaredLength 字节超过上限 $maxResponseBytes',
        ));
      }

      final bytesResult =
          await _readBodyLimited(response, host, currentUrl, status);
      if (bytesResult.error != null) return Failure(bytesResult.error!);
      final bytes = bytesResult.bytes!;
      if (bytes.isEmpty) {
        _rateLimiter.onFailure(host);
        return Failure(NetworkError(
          'EMPTY_RESPONSE',
          url: redactUrl(currentUrl),
          statusCode: status,
          debugDetail: '响应体为空',
        ));
      }

      final contentType = response.headers['content-type'];
      final declaredCharset = _charsetFromContentType(contentType);
      final decoded = _decodeHtml(bytes, declaredCharset);
      _rateLimiter.onSuccess(host);

      return Success(FetchedPage(
        finalUrl: currentUrl,
        html: decoded.html,
        charset: decoded.charset,
        statusCode: status,
      ));
    }
  }

  // ---------------- 响应体读取 ----------------

  Future<_BytesResult> _readBodyLimited(
    http.StreamedResponse response,
    String host,
    String url,
    int status,
  ) async {
    final builder = BytesBuilder(copy: false);
    var total = 0;
    try {
      await for (final chunk in response.stream.timeout(readTimeout)) {
        total += chunk.length;
        if (total > maxResponseBytes) {
          _rateLimiter.onFailure(host);
          return _BytesResult.error(NetworkError(
            'RESPONSE_TOO_LARGE',
            url: redactUrl(url),
            statusCode: status,
            debugDetail: '响应体超过上限 $maxResponseBytes，已中断',
          ));
        }
        builder.add(chunk);
      }
    } on TimeoutException {
      _rateLimiter.onFailure(host);
      return _BytesResult.error(NetworkError(
        'READ_TIMEOUT',
        url: redactUrl(url),
        statusCode: status,
        debugDetail: '读取响应体超时 ${readTimeout.inSeconds}s',
      ));
    } on SocketException catch (e) {
      _rateLimiter.onFailure(host);
      return _BytesResult.error(_mapSocketException(e, url));
    } on http.ClientException catch (e) {
      _rateLimiter.onFailure(host);
      return _BytesResult.error(NetworkError(
        'REQUEST_FAILED',
        url: redactUrl(url),
        statusCode: status,
        debugDetail: e.message,
      ));
    }
    return _BytesResult.ok(builder.takeBytes());
  }

  Future<void> _drain(http.StreamedResponse response) async {
    try {
      await response.stream.drain<void>().timeout(readTimeout);
    } catch (_) {
      // 丢弃响应体失败不影响结论
    }
  }

  bool _isRedirect(int status) =>
      status == 301 || status == 302 || status == 303 || status == 307 || status == 308;

  void _applyHeaders(http.Request request, Map<String, String> headers) {
    request.headers['User-Agent'] = _userAgent;
    request.headers['Accept'] =
        'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8';
    request.headers['Accept-Language'] = 'zh-CN,zh;q=0.9';
    for (final entry in headers.entries) {
      final name = entry.key.trim();
      if (name.isEmpty) continue;
      final lower = name.toLowerCase();
      if (lower == 'host' ||
          lower == 'content-length' ||
          lower == 'connection' ||
          lower == 'transfer-encoding') {
        continue;
      }
      try {
        request.headers[name] = entry.value;
      } catch (_) {
        // 非法头名/值：跳过而非失败整请求
      }
    }
  }

  Map<String, String> _stripSensitiveHeaders(Map<String, String> headers) {
    return Map<String, String>.fromEntries(headers.entries.where((e) {
      final k = e.key.trim().toLowerCase();
      return k != 'cookie' && k != 'authorization';
    }));
  }

  // ---------------- URL 安全校验 ----------------

  Future<_UrlCheck> _checkUrl(String rawUrl, {String? fromUrl}) async {
    final raw = rawUrl.trim();
    if (raw.isEmpty) {
      return _UrlCheck.error(SecurityError('MALFORMED_URL', url: '',
          debugDetail: '空 URL'));
    }
    Uri? uri = Uri.tryParse(raw);
    uri ??= Uri.tryParse(raw.replaceAll(' ', '%20'));
    if (uri == null) {
      return _UrlCheck.error(SecurityError('MALFORMED_URL',
          url: redactUrl(raw), debugDetail: 'URL 无法解析'));
    }

    final scheme = uri.scheme.toLowerCase();
    if (scheme != 'http' && scheme != 'https') {
      return _UrlCheck.error(SecurityError('NON_HTTP_SCHEME',
          url: redactUrl(raw), debugDetail: 'scheme=$scheme 非 http/https'));
    }
    if (uri.host.isEmpty) {
      return _UrlCheck.error(SecurityError('INVALID_HOST',
          url: redactUrl(raw), debugDetail: 'URL 缺少主机'));
    }
    if (uri.userInfo.isNotEmpty) {
      return _UrlCheck.error(SecurityError('INVALID_HOST',
          url: redactUrl(raw), debugDetail: 'URL 携带 userinfo，禁止'));
    }

    var host = uri.host.toLowerCase();
    while (host.endsWith('.')) {
      host = host.substring(0, host.length - 1);
    }
    if (host.isEmpty) {
      return _UrlCheck.error(SecurityError('INVALID_HOST',
          url: redactUrl(raw), debugDetail: '主机为空'));
    }

    // localhost 明确拒绝（即使 DNS 环境异常也不依赖解析结果）。
    if (host == 'localhost' || host.endsWith('.localhost')) {
      return _UrlCheck.error(SecurityError('LOOPBACK',
          url: redactUrl(raw), debugDetail: '主机为 localhost'));
    }

    List<InternetAddress> addresses;
    final literal = InternetAddress.tryParse(host);
    if (literal != null) {
      addresses = [literal];
    } else {
      try {
        addresses = await _lookupHost(host);
      } on SocketException catch (e) {
        return _UrlCheck.error(NetworkError(
          'DNS_FAILURE',
          url: redactUrl(raw),
          debugDetail: 'DNS 解析失败: ${e.message}',
        ));
      } catch (e) {
        return _UrlCheck.error(NetworkError(
          'DNS_FAILURE',
          url: redactUrl(raw),
          debugDetail: 'DNS 解析失败: $e',
        ));
      }
      if (addresses.isEmpty) {
        return _UrlCheck.error(NetworkError(
          'DNS_FAILURE',
          url: redactUrl(raw),
          debugDetail: 'DNS 解析结果为空',
        ));
      }
    }

    for (final address in addresses) {
      final reason = _classifyAddress(address);
      if (reason != null) {
        return _UrlCheck.error(SecurityError(
          reason,
          url: redactUrl(raw),
          debugDetail: '主机 $host 解析地址命中黑名单（$reason）',
        ));
      }
    }

    final normalized = normalizeUrl(uri.toString());
    if (normalized == null) {
      return _UrlCheck.error(SecurityError('MALFORMED_URL',
          url: redactUrl(raw), debugDetail: 'URL 规范化失败'));
    }
    return _UrlCheck.ok(normalized);
  }

  /// 地址黑名单判定；返回 null 表示公网地址放行。
  String? _classifyAddress(InternetAddress address) {
    final bytes = address.rawAddress;
    if (address.type == InternetAddressType.IPv6 || bytes.length == 16) {
      return _classifyV6(bytes);
    }
    if (bytes.length == 4) return _classifyV4(bytes);
    return 'RESERVED_ADDRESS';
  }

  String? _classifyV4(List<int> b) {
    if (b.length != 4) return 'RESERVED_ADDRESS';
    final b0 = b[0] & 0xff;
    final b1 = b[1] & 0xff;
    if (b0 == 127) return 'LOOPBACK';
    if (b0 == 10) return 'PRIVATE_ADDRESS';
    if (b0 == 172 && b1 >= 16 && b1 <= 31) return 'PRIVATE_ADDRESS';
    if (b0 == 192 && b1 == 168) return 'PRIVATE_ADDRESS';
    if (b0 == 169 && b1 == 254) return 'LINK_LOCAL';
    if (b0 == 0) return 'RESERVED_ADDRESS';
    if (b0 == 100 && (b1 & 0xC0) == 64) return 'RESERVED_ADDRESS';
    if (b0 == 192 && b1 == 0) return 'RESERVED_ADDRESS';
    if (b0 == 198 && b1 == 51) return 'RESERVED_ADDRESS';
    if (b0 == 203 && b1 == 0) return 'RESERVED_ADDRESS';
    if (b0 == 198 && (b1 == 18 || b1 == 19)) return 'RESERVED_ADDRESS';
    if (b0 >= 224) return 'RESERVED_ADDRESS';
    return null;
  }

  String? _classifyV6(List<int> b) {
    if (b.length != 16) return 'RESERVED_ADDRESS';
    int byte(int i) => b[i] & 0xff;

    // ::（未指定地址）
    if (b.every((v) => v == 0)) return 'RESERVED_ADDRESS';
    // ::1 回环
    if (b.sublist(0, 15).every((v) => v == 0) && byte(15) == 1) {
      return 'LOOPBACK';
    }

    // ::ffff:x.y.z.w（IPv4 映射）
    if (b.sublist(0, 10).every((v) => v == 0) &&
        byte(10) == 0xff &&
        byte(11) == 0xff) {
      return _classifyV4(b.sublist(12, 16));
    }
    // ::x.y.z.w（IPv4 兼容，排除全零）
    if (b.sublist(0, 12).every((v) => v == 0) &&
        !(byte(12) == 0 && byte(13) == 0 && byte(14) == 0 && byte(15) == 0)) {
      return _classifyV4(b.sublist(12, 16));
    }
    // 64:ff9b::/96 NAT64
    if (byte(0) == 0x00 &&
        byte(1) == 0x64 &&
        byte(2) == 0xff &&
        byte(3) == 0x9b &&
        b.sublist(4, 12).every((v) => v == 0)) {
      return _classifyV4(b.sublist(12, 16));
    }
    // 2002::/16 6to4
    if (byte(0) == 0x20 && byte(1) == 0x02) {
      return _classifyV4(b.sublist(2, 6));
    }
    // fe80::/10 链路本地
    if (byte(0) == 0xfe && (byte(1) & 0xc0) == 0x80) return 'LINK_LOCAL';
    // fc00::/7 ULA
    if ((byte(0) & 0xfe) == 0xfc) return 'PRIVATE_ADDRESS';
    // ff00::/8 组播
    if (byte(0) == 0xff) return 'RESERVED_ADDRESS';
    // 2001:db8::/32 文档段
    if (byte(0) == 0x20 &&
        byte(1) == 0x01 &&
        byte(2) == 0x0d &&
        byte(3) == 0xb8) {
      return 'RESERVED_ADDRESS';
    }
    return null;
  }

  // ---------------- 编码探测 ----------------

  _DecodedHtml _decodeHtml(List<int> bytes, String? contentTypeCharset) {
    // 1) BOM（UTF-8）
    if (bytes.length >= 3 &&
        bytes[0] == 0xEF &&
        bytes[1] == 0xBB &&
        bytes[2] == 0xBF) {
      final decoded = _tryDecode(utf8, bytes.sublist(3));
      if (decoded != null) return _DecodedHtml(decoded, 'utf-8');
    }

    final metaCharset = _metaCharset(bytes);
    final declared = (contentTypeCharset ?? metaCharset)?.trim();

    // 2) 站点声明的编码（UTF-8 / GBK 系列）
    if (declared != null && declared.isNotEmpty) {
      final encoding = _encodingFor(declared);
      if (encoding != null) {
        final decoded = _tryDecode(encoding, bytes);
        if (decoded != null) {
          return _DecodedHtml(decoded, declared.toLowerCase());
        }
      }
    }

    // 3) UTF-8 优先（严格校验）
    final utf8Text = _tryDecode(utf8, bytes);
    if (utf8Text != null) return _DecodedHtml(utf8Text, 'utf-8');

    // 4) GBK / GB18030 兜底
    for (final name in const ['gb18030', 'gbk']) {
      final encoding = charset_pkg.Charset.getByName(name);
      if (encoding == null) continue;
      final decoded = _tryDecode(encoding, bytes);
      if (decoded != null) return _DecodedHtml(decoded, name);
    }

    // 5) 最终兜底：宽松 UTF-8（保留字符，不失败）
    return _DecodedHtml(utf8.decode(bytes, allowMalformed: true), 'utf-8');
  }

  String? _tryDecode(Encoding encoding, List<int> bytes) {
    try {
      return encoding.decode(bytes);
    } catch (_) {
      return null;
    }
  }

  Encoding? _encodingFor(String name) {
    final normalized = name.trim().toLowerCase().replaceAll('_', '-');
    if (normalized == 'utf8' || normalized == 'utf-8') return utf8;
    if (normalized.startsWith('gb')) {
      return charset_pkg.Charset.getByName(normalized);
    }
    return charset_pkg.Charset.getByName(normalized);
  }

  String? _charsetFromContentType(String? contentType) {
    if (contentType == null || contentType.isEmpty) return null;
    final m = RegExp(
      r'''charset\s*=\s*["']?([A-Za-z0-9_.\-]+)''',
      caseSensitive: false,
    ).firstMatch(contentType);
    return m?.group(1);
  }

  String? _metaCharset(List<int> bytes) {
    if (bytes.isEmpty) return null;
    final length = bytes.length < 2048 ? bytes.length : 2048;
    final head = latin1.decode(bytes.sublist(0, length), allowInvalid: true);
    final m = RegExp(
      r'''<meta[^>]{0,400}?charset\s*=\s*["']?([A-Za-z0-9_\-]+)''',
      caseSensitive: false,
    ).firstMatch(head);
    return m?.group(1);
  }

  // ---------------- 异常映射 ----------------

  ReaderError _mapSocketException(SocketException e, String url) {
    final message = '${e.message} ${e.osError?.message ?? ''}'.toLowerCase();
    if (message.contains('failed host lookup') ||
        message.contains('no address associated') ||
        message.contains('name or service not known')) {
      return NetworkError('DNS_FAILURE', url: redactUrl(url), debugDetail: e.message);
    }
    if (message.contains('timed out') || message.contains('timeout')) {
      return NetworkError('CONNECT_TIMEOUT', url: redactUrl(url), debugDetail: e.message);
    }
    if (message.contains('refused')) {
      return NetworkError('CONNECTION_REFUSED', url: redactUrl(url), debugDetail: e.message);
    }
    return NetworkError('REQUEST_FAILED', url: redactUrl(url), debugDetail: e.message);
  }

  ReaderError _mapUnknownException(Object e, String url) {
    if (e is TimeoutException) {
      return NetworkError('CONNECT_TIMEOUT', url: redactUrl(url), debugDetail: '$e');
    }
    if (e is SocketException) return _mapSocketException(e, url);
    if (e is http.ClientException) {
      return NetworkError('REQUEST_FAILED', url: redactUrl(url), debugDetail: e.message);
    }
    return UnknownError('网络异常: ${e.runtimeType} $e');
  }
}

// ---------------- 内部辅助类型 ----------------

class _UrlCheck {
  const _UrlCheck._({this.normalizedUrl, this.error});

  factory _UrlCheck.ok(String url) => _UrlCheck._(normalizedUrl: url);

  factory _UrlCheck.error(ReaderError error) => _UrlCheck._(error: error);

  final String? normalizedUrl;
  final ReaderError? error;
}

class _BytesResult {
  const _BytesResult._({this.bytes, this.error});

  factory _BytesResult.ok(Uint8List bytes) => _BytesResult._(bytes: bytes);

  factory _BytesResult.error(ReaderError error) => _BytesResult._(error: error);

  final Uint8List? bytes;
  final ReaderError? error;
}

class _DecodedHtml {
  const _DecodedHtml(this.html, this.charset);

  final String html;
  final String? charset;
}

/// 每主机自适应限速：同主机请求串行，间隔初始 2s；失败翻倍至 8s；
/// 连续成功 3 次后回落 2s。
class _AdaptiveRateLimiter {
  _AdaptiveRateLimiter({
    required this.minInterval,
    required this.maxBackoff,
  });

  final Duration minInterval;
  final Duration maxBackoff;
  final Map<String, _HostState> _states = {};

  _HostState _stateFor(String host) =>
      _states.putIfAbsent(host.toLowerCase(), () => _HostState(minInterval));

  Future<void> acquire(String host) {
    final state = _stateFor(host);
    final previous = state.tail;
    final gate = Completer<void>();
    state.tail = gate.future;
    return previous.then((_) async {
      try {
        final now = DateTime.now();
        final nextAllowed = state.lastRequestAt == null
            ? now
            : state.lastRequestAt!.add(state.interval);
        final wait = nextAllowed.difference(now);
        if (wait > Duration.zero) {
          await Future<void>.delayed(wait);
        }
        state.lastRequestAt = DateTime.now();
      } finally {
        gate.complete();
      }
    });
  }

  void onSuccess(String host) {
    final state = _stateFor(host);
    state.consecutiveSuccesses++;
    if (state.consecutiveSuccesses >= 3) {
      state.consecutiveSuccesses = 0;
      state.interval = minInterval;
    }
  }

  void onFailure(String host) {
    final state = _stateFor(host);
    state.consecutiveSuccesses = 0;
    final doubled = state.interval * 2;
    state.interval = doubled > maxBackoff ? maxBackoff : doubled;
  }
}

class _HostState {
  _HostState(this.interval);

  Duration interval;
  int consecutiveSuccesses = 0;
  DateTime? lastRequestAt;
  Future<void> tail = Future<void>.value();
}