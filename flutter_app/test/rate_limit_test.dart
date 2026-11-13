// 自适应限速器语义测试（对齐 Kotlin AdaptiveRateLimiter，假时钟不真等）。
import 'package:flutter_test/flutter_test.dart';

import 'package:jianyue_flutter/impl/rate_limiter.dart';

void main() {
  group('AdaptiveRateLimiter', () {
    test('首次 acquire 无需等待', () async {
      final waits = <Duration>[];
      final limiter = AdaptiveRateLimiter(
        sleep: (d) async => waits.add(d),
      );
      await limiter.acquire('a.com');
      expect(waits, isEmpty);
    });

    test('连续成功 3 次降一档，地板 1s 不再降', () async {
      final limiter = AdaptiveRateLimiter(
        initialInterval: const Duration(seconds: 2),
        floor: const Duration(seconds: 1),
        cap: const Duration(seconds: 10),
        stepDown: const Duration(milliseconds: 500),
      );
      expect(limiter.intervalOf('a.com'), const Duration(seconds: 2));
      limiter.onSuccess('a.com');
      limiter.onSuccess('a.com');
      limiter.onSuccess('a.com');
      // 降档：2000 → 1500
      expect(limiter.intervalOf('a.com'), const Duration(milliseconds: 1500));
      // 再 3 次：1500 → 1000（地板）
      limiter.onSuccess('a.com');
      limiter.onSuccess('a.com');
      limiter.onSuccess('a.com');
      expect(limiter.intervalOf('a.com'), const Duration(seconds: 1));
      // 达到地板后不再降
      limiter.onSuccess('a.com');
      limiter.onSuccess('a.com');
      limiter.onSuccess('a.com');
      expect(limiter.intervalOf('a.com'), const Duration(seconds: 1));
    });

    test('失败翻倍至 cap 10s 封顶', () {
      final limiter = AdaptiveRateLimiter(
        initialInterval: const Duration(seconds: 2),
        cap: const Duration(seconds: 10),
      );
      limiter.onFailure('a.com');
      expect(limiter.intervalOf('a.com'), const Duration(seconds: 4));
      limiter.onFailure('a.com');
      expect(limiter.intervalOf('a.com'), const Duration(seconds: 8));
      limiter.onFailure('a.com');
      expect(limiter.intervalOf('a.com'), const Duration(seconds: 10));
      limiter.onFailure('a.com');
      expect(limiter.intervalOf('a.com'), const Duration(seconds: 10));
    });

    test('失败后成功计数清零，需重新累计 3 次才降档', () {
      final limiter = AdaptiveRateLimiter(
        stepDown: const Duration(milliseconds: 500),
      );
      limiter.onSuccess('a.com');
      limiter.onSuccess('a.com');
      limiter.onFailure('a.com'); // 间隔翻倍 2s → 4s，计数清零
      limiter.onSuccess('a.com');
      limiter.onSuccess('a.com');
      expect(limiter.intervalOf('a.com'), const Duration(seconds: 4));
      limiter.onSuccess('a.com');
      expect(limiter.intervalOf('a.com'), const Duration(milliseconds: 3500));
    });

    test('429 带 Retry-After → 一次性冷却，间隔不变', () async {
      var now = DateTime(2026, 1, 1, 0, 0, 0);
      final waits = <Duration>[];
      final limiter = AdaptiveRateLimiter(
        cap: const Duration(seconds: 10),
        now: () => now,
        sleep: (d) async {
          waits.add(d);
          now = now.add(d);
        },
      );
      await limiter.acquire('a.com');
      limiter.onRateLimited('a.com', const Duration(seconds: 3));
      // 冷却生效：等待 3s
      await limiter.acquire('a.com');
      expect(waits.last, const Duration(seconds: 3));
      // 一次性冷却：第二次 acquire 只受正常间隔门限（interval 2s 未变）
      await limiter.acquire('a.com');
      expect(waits.last, const Duration(seconds: 2));
    });

    test('429 无 Retry-After → 间隔直接置 cap', () {
      final limiter = AdaptiveRateLimiter(
        initialInterval: const Duration(seconds: 2),
        cap: const Duration(seconds: 10),
      );
      limiter.onRateLimited('a.com', null);
      expect(limiter.intervalOf('a.com'), const Duration(seconds: 10));
    });

    test('429 Retry-After 超过 cap → 间隔置 cap（不用冷却）', () async {
      final limiter = AdaptiveRateLimiter(
        initialInterval: const Duration(seconds: 2),
        cap: const Duration(seconds: 10),
        sleep: (d) async {},
      );
      limiter.onRateLimited('a.com', const Duration(seconds: 60));
      expect(limiter.intervalOf('a.com'), const Duration(seconds: 10));
      // 无冷却标记：下一次 acquire 等待 = interval
      await limiter.acquire('a.com');
    });

    test('同主机串行：第二次 acquire 等待 interval', () async {
      var now = DateTime(2026, 1, 1);
      final waits = <Duration>[];
      final limiter = AdaptiveRateLimiter(
        initialInterval: const Duration(seconds: 2),
        now: () => now,
        sleep: (d) async {
          waits.add(d);
          now = now.add(d);
        },
      );
      await limiter.acquire('a.com');
      await limiter.acquire('a.com');
      expect(waits, const [Duration(seconds: 2)]);
    });

    test('host 大小写与端口差异共用一桶', () async {
      var now = DateTime(2026, 1, 1);
      final waits = <Duration>[];
      final limiter = AdaptiveRateLimiter(
        initialInterval: const Duration(seconds: 2),
        now: () => now,
        sleep: (d) async {
          waits.add(d);
          now = now.add(d);
        },
      );
      await limiter.acquire('A.com');
      await limiter.acquire('a.com');
      expect(waits, hasLength(1));
      expect(waits.single, const Duration(seconds: 2));
    });
  });

  group('parseRetryAfterHeader', () {
    test('秒数', () {
      expect(
        parseRetryAfterHeader('5'),
        const Duration(seconds: 5),
      );
      expect(parseRetryAfterHeader('0'), Duration.zero);
      expect(parseRetryAfterHeader('-1'), Duration.zero);
    });

    test('无效值', () {
      expect(parseRetryAfterHeader(null), isNull);
      expect(parseRetryAfterHeader(''), isNull);
      expect(parseRetryAfterHeader('soon'), isNull);
    });
  });
}
