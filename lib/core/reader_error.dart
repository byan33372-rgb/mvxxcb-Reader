/// 错误体系（对应 Kotlin 版 ReaderError，Flutter 版精简但语义对齐）。
sealed class ReaderError {
  final String? debugDetail;
  const ReaderError([this.debugDetail]);
}

class NetworkError extends ReaderError {
  final String failure; // INVALID_URL/DNS_FAILURE/CONNECT_TIMEOUT/READ_TIMEOUT/HTTP_ERROR_STATUS/RATE_LIMITED/OFFLINE/EMPTY_RESPONSE/REDIRECT_LOOP/RESPONSE_TOO_LARGE
  final String? url;
  final int? statusCode;
  NetworkError(this.failure, {this.url, this.statusCode, String? debugDetail})
      : super(debugDetail);
}

class ParseError extends ReaderError {
  final String failure; // UNSUPPORTED_STRUCTURE/DETAIL_INCOMPLETE/CATALOG_EMPTY/CATALOG_PAGINATION_FAILED/CONTENT_EMPTY/CONTENT_PAGINATION_FAILED/ENCODING_FAILED/DYNAMIC_PAGE_REQUIRED/CATALOG_UNRESOLVED
  ParseError(this.failure, [String? debugDetail]) : super(debugDetail);
}

class SecurityError extends ReaderError {
  final String reason; // NON_HTTP_SCHEME/INVALID_HOST/MALFORMED_URL/LOOPBACK/PRIVATE_ADDRESS/LINK_LOCAL/RESERVED_ADDRESS/REDIRECT_LOOP
  final String? url;
  SecurityError(this.reason, {this.url, String? debugDetail}) : super(debugDetail);
}

class StorageError extends ReaderError {
  final String failure; // DB_ERROR/DISK_IO/TXT_WRITE_FAILED/CACHE_CORRUPT
  StorageError(this.failure, [String? debugDetail]) : super(debugDetail);
}

class StateError extends ReaderError {
  final String failure; // BOOK_NOT_FOUND/CHAPTER_NOT_FOUND/CHAPTER_NOT_CACHED/INVALID_ARGUMENT/TASK_NOT_FOUND
  StateError(this.failure, [String? debugDetail]) : super(debugDetail);
}

class UnknownError extends ReaderError {
  UnknownError([String? debugDetail]) : super(debugDetail);
}

/// 错误 → 用户可读文案（对齐 Kotlin 版 §10 映射要点）。
String userMessageOf(ReaderError e) {
  if (e is NetworkError) {
    switch (e.failure) {
      case 'INVALID_URL':
        return '网址格式不正确';
      case 'DNS_FAILURE':
        return '无法解析网站地址';
      case 'CONNECT_TIMEOUT':
      case 'READ_TIMEOUT':
        return '网站响应超时，请重试';
      case 'HTTP_ERROR_STATUS':
        return '网站返回错误${e.statusCode != null ? '（${e.statusCode}）' : ''}';
      case 'RATE_LIMITED':
        return '网站限流，请稍后再试';
      case 'OFFLINE':
        return '当前无网络连接';
      case 'EMPTY_RESPONSE':
        return '网站返回空内容';
      case 'REDIRECT_LOOP':
      case 'TOO_MANY_REDIRECTS':
        return '网站重定向异常（超过安全限制）';
      case 'RESPONSE_TOO_LARGE':
        return '网站响应过大（超过安全限制）';
      default:
        return '网络请求失败';
    }
  }
  if (e is SecurityError) return '出于安全考虑已阻止该地址（${e.reason}）';
  if (e is ParseError) {
    switch (e.failure) {
      case 'UNSUPPORTED_STRUCTURE':
        return '暂不支持该网站结构，可配置解析规则兜底';
      case 'DETAIL_INCOMPLETE':
        return '未能识别书籍信息';
      case 'CATALOG_EMPTY':
        return '未找到章节目录';
      case 'CATALOG_PAGINATION_FAILED':
        return '目录获取失败（分页中断）';
      case 'CONTENT_EMPTY':
        return '正文获取失败（内容为空）';
      case 'CONTENT_PAGINATION_FAILED':
        return '正文获取失败（分页中断）';
      case 'ENCODING_FAILED':
        return '网页编码无法识别';
      case 'DYNAMIC_PAGE_REQUIRED':
        return '该页面需浏览器渲染，暂不支持';
      case 'CATALOG_UNRESOLVED':
        return '无法从当前章节回溯整本小说，请补充目录页网址';
      default:
        return '网页解析失败';
    }
  }
  if (e is StorageError) return '本地存储异常（${e.failure}）';
  if (e is StateError) {
    switch (e.failure) {
      case 'BOOK_NOT_FOUND':
        return '书籍不存在';
      case 'CHAPTER_NOT_FOUND':
        return '章节不存在';
      case 'CHAPTER_NOT_CACHED':
        return '该章节未缓存，请联网加载';
      case 'TASK_NOT_FOUND':
        return '任务不存在或已清除';
      default:
        return '操作未执行（参数无效）';
    }
  }
  return '发生未知错误';
}
