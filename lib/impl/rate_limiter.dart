/// 每主机自适应限速器（对齐 Kotlin 版 `security/AdaptiveRateLimiter.kt` 全语义，
/// 参数对齐 `NetworkLimits`）。
///
/// 语义（host 维度各自独立维护状态）：
///  - 起始间隔 = [initialInterval]（2s，合规姿态）；
///  - [onSuccess]：连续成功达 [speedUpAfterSuccesses]（3）次后间隔降一档
///    [stepDown]（500ms），但不低于 [floor]（1s 合规保底，达到后不再降）；
///    降档后成功计数清零、重新累计；
///  - [onFailure]：间隔翻倍（上限 [cap] 10s），成功计数清零；
///  - [onRateLimited]：429 专用——Retry-After 有值、>0 且折算毫秒 ≤ cap 时设置
///    一次性冷却截止（下次 [acquire] 优先等待该值），否则间隔直接置为 cap；
///    成功计数清零。
///
/// 并发语义：同主机 acquire 严格串行（链式 Future，后者既等间隔也等前者让出）；
/// 时间与睡眠均可注入（单测用假时钟，不真等）。
library;

import 'dart:async';
import 'dart:io' show HttpDate;

/// 单主机自适应限速器集合（按 host 小写分桶）。
class AdaptiveRateLimiter {
  AdaptiveRateLimiter({
    this.initialInterval = const Duration(seconds: 2),
    this.floor = const Duration(seconds: 1),
    this.cap = const Duration(seconds: 10),
    this.speedUpAfterSuccesses = 3,
    this.stepDown = const Duration(milliseconds: 500),
    DateTime Function()? now,
    Future<void> Function(Duration)? sleep,
  })  : _now = now ?? DateTime.now,
        _sleep = sleep ?? _defaultSleep;

  /// 起始间隔（同 Kotlin minIntervalPerHostMs）。
  final Duration initialInterval;

  /// 降档地板（合规保底 1s，达到后不再降）。
  final Duration floor;

  /// 退避天花板（失败翻倍上限；429 无有效 Retry-After 时直接置为此值）。
  final Duration cap;

  /// 连续成功多少次后间隔降一档。
  final int speedUpAfterSuccesses;

  /// 每次降档减少的毫秒数。
  final Duration stepDown;

  final DateTime Function() _now;
  final Future<void> Function(Duration) _sleep;

  final Map<String, _HostRateState> _states = <String, _HostRateState>{};

  static Future<void> _defaultSleep(Duration duration) =>
      Future<void>.delayed(duration);

  _HostRateState _stateFor(String host) {
    final key = host.toLowerCase();
    return _states.putIfAbsent(key, () => _HostRateState(initialInterval));
  }

  /// 当前间隔（测试观测用）。
  Duration intervalOf(String host) => _stateFor(host).interval;

  /// 请求前等待：同主机串行（链式），等待 = max(间隔门限, 429 冷却剩余)。
  Future<void> acquire(String host) {
    final state = _stateFor(host);
    final previous = state.tail;
    final gate = Completer<void>();
    state.tail = gate.future;
    return previous.then((_) async {
      try {
        var wait = Duration.zero;
        final now = _now();
        final last = state.lastRequestAt;
        if (last != null) {
          final nextAllowed = last.add(state.interval);
          final gap = nextAllowed.difference(now);
          if (gap > wait) wait = gap;
        }
        final cooldownUntil = state.cooldownUntil;
        if (cooldownUntil != null) {
          final remaining = cooldownUntil.difference(now);
          if (remaining > wait) wait = remaining;
          state.cooldownUntil = null; // 一次性冷却
        }
        if (wait > Duration.zero) {
          await _sleep(wait);
        }
        state.lastRequestAt = _now();
      } finally {
        gate.complete();
      }
    });
  }

  /// 请求成功：连续成功计数，达阈值后降一档（不低于地板）。
  void onSuccess(String host) {
    final state = _stateFor(host);
    state.consecutiveSuccesses++;
    if (state.consecutiveSuccesses >= speedUpAfterSuccesses) {
      state.consecutiveSuccesses = 0;
      final stepped = state.interval - stepDown;
      state.interval = stepped < floor ? floor : stepped;
    }
  }

  /// 请求失败：间隔翻倍至天花板，成功计数清零。
  void onFailure(String host) {
    final state = _stateFor(host);
    state.consecutiveSuccesses = 0;
    final doubled = state.interval * 2;
    state.interval = doubled > cap ? cap : doubled;
  }

  /// 429/限流：Retry-After 有效（>0 且 ≤ cap）→ 一次性冷却；
  /// 否则间隔直接置 cap。成功计数清零。
  void onRateLimited(String host, Duration? retryAfter) {
    final state = _stateFor(host);
    state.consecutiveSuccesses = 0;
    if (retryAfter != null &&
        retryAfter > Duration.zero &&
        retryAfter <= cap) {
      state.cooldownUntil = _now().add(retryAfter);
    } else {
      state.interval = cap;
    }
  }
}

class _HostRateState {
  _HostRateState(this.interval);

  Duration interval;
  int consecutiveSuccesses = 0;
  DateTime? lastRequestAt;
  DateTime? cooldownUntil;
  Future<void> tail = Future<void>.value();
}

/// 解析 Retry-After 头（秒数或 HTTP 日期）；无效返回 null。
Duration? parseRetryAfterHeader(String? raw, {DateTime Function()? now}) {
  final trimmed = raw?.trim();
  if (trimmed == null || trimmed.isEmpty) return null;
  final clock = now ?? DateTime.now;
  final seconds = int.tryParse(trimmed);
  if (seconds != null) {
    return seconds > 0 ? Duration(seconds: seconds) : Duration.zero;
  }
  try {
    final date = HttpDate.parse(trimmed);
    final diff = date.difference(clock().toUtc());
    return diff > Duration.zero ? diff : Duration.zero;
  } catch (_) {
    return null;
  }
}
