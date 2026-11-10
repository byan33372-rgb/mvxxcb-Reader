package com.mvxxcb.reader.security

import com.mvxxcb.reader.common.time.TimeProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 每主机自适应限速器（2.0 WP-S）。
 *
 * 语义（参数全部来自 [NetworkLimits]，host 维度各自独立维护状态）：
 *  - 起始间隔 = [NetworkLimits.minIntervalPerHostMs]（原静态最小间隔，语义改为自适应起点）；
 *  - [onSuccess]：连续成功达 [NetworkLimits.speedUpAfterSuccesses] 次后间隔降一档
 *    [NetworkLimits.stepDownMs]，但不低于 [NetworkLimits.adaptiveFloorMs]（合规保底 1s，
 *    红线不可降）；达到 floor 后不再降；降档后成功计数清零、重新累计；
 *  - [onFailure]：间隔翻倍（上限 [NetworkLimits.adaptiveCapMs]），成功计数清零；
 *  - [onRateLimited]：429/限流专用——Retry-After 有值、>0 且折算毫秒 ≤ cap 时设置一次性
 *    冷却截止（下次 [acquire] 优先等待该值），否则间隔直接置为 cap；成功计数清零。
 *
 * 线程安全与并发语义：
 *  - 每主机一把 [Mutex]，[acquire] 在锁内完成"计算等待 → 挂起到点 → 盖戳"：同主机并发
 *    acquire 严格串行（后者既等间隔也等前者让出锁），锁内 [delay] 挂起可被协程取消打断，
 *    finally 释放锁（取消安全）；
 *  - 结果回调（onSuccess/onFailure/onRateLimited）为非挂起快速修改，走 synchronized
 *    (HostState) 保护 interval/成功计数，与 acquire 的锁内字段读写互不破坏（回调不持
 *    Mutex，不阻塞挂起等待）；
 *  - 不同主机状态完全隔离，互不影响。
 *
 * 禁用条件：`minIntervalPerHostMs <= 0` 时整体直通（保留既有调用方/测试的关限速语义），
 * [acquire] 立即返回、全部回调 no-op。
 *
 * 时间源注入 [TimeProvider]：等待计算与盖戳全部基于注入时钟；等待经 kotlinx [delay]
 * 真实挂起。与原 SecureHttpClientImpl 静态间隔一致，等待结束后直接以当前时刻盖戳、
 * 不回环重算——注入时钟若不前进（冻结假时钟）回环重算会导致 wait 恒为正而死循环。
 *
 * 与采集器分层说明：同书串行由 data/work 采集器保证（不在本层语义内），本层仅做
 * HTTP 每主机节流；SSRF/重定向/大小限制零改动。
 */
class AdaptiveRateLimiter(
    private val limits: NetworkLimits,
    private val timeProvider: TimeProvider,
) {

    private class HostState {
        var intervalMs: Long = 0L
        var consecutiveSuccesses: Int = 0
        var lastRequestAtMs: Long? = null   // 上次请求起点（epoch ms，注入时钟）
        var blockedUntilMs: Long = 0L       // 429 一次性冷却截止（epoch ms；0 = 无）
        val mutex = Mutex()
    }

    private val states = ConcurrentHashMap<String, HostState>()

    private val enabled: Boolean = limits.minIntervalPerHostMs > 0

    private fun stateFor(host: String): HostState =
        states.computeIfAbsent(host) {
            HostState().apply { intervalMs = limits.minIntervalPerHostMs }
        }

    /**
     * 发起请求前调用：距上次请求不足当前间隔则挂起到点（等待期间可被协程取消）。
     * 同主机并发调用经每主机 Mutex 严格串行；结束后以当前时刻盖戳并消费一次性 429 冷却。
     */
    suspend fun acquire(host: String) {
        if (!enabled) return
        val state = stateFor(host)
        state.mutex.withLock {
            val wait = synchronized(state) {
                val now = timeProvider.nowMillis()
                val intervalWait = state.lastRequestAtMs?.let { state.intervalMs - (now - it) } ?: 0L
                val blockedWait = state.blockedUntilMs - now
                maxOf(intervalWait, blockedWait).coerceAtLeast(0L)
            }
            if (wait > 0) delay(wait)
            synchronized(state) {
                state.lastRequestAtMs = timeProvider.nowMillis()
                state.blockedUntilMs = 0L   // 429 冷却一次性消费
            }
        }
    }

    /** 请求成功：累计连续成功计数，达阈值降一档（不低于 floor），降档后计数清零。 */
    fun onSuccess(host: String) {
        if (!enabled) return
        val state = stateFor(host)
        synchronized(state) {
            state.consecutiveSuccesses++
            if (state.consecutiveSuccesses >= limits.speedUpAfterSuccesses) {
                state.consecutiveSuccesses = 0
                if (state.intervalMs > limits.adaptiveFloorMs) {
                    state.intervalMs = (state.intervalMs - limits.stepDownMs)
                        .coerceAtLeast(limits.adaptiveFloorMs)
                }
            }
        }
    }

    /** 请求失败（传输异常/HTTP 错误态）：间隔翻倍（上限 cap），成功计数清零。 */
    fun onFailure(host: String) {
        if (!enabled) return
        val state = stateFor(host)
        synchronized(state) {
            state.intervalMs = (state.intervalMs * 2).coerceAtMost(limits.adaptiveCapMs)
            state.consecutiveSuccesses = 0
        }
    }

    /**
     * 429/限流专用：Retry-After 有值（>0）且折算毫秒 ≤ cap 时设置一次性冷却等待该值
     * （不改动自适应 interval）；否则间隔直接置为 cap。两种情况均清零成功计数。
     */
    fun onRateLimited(host: String, retryAfterSeconds: Long?) {
        if (!enabled) return
        val state = stateFor(host)
        synchronized(state) {
            val retryMs = retryAfterSeconds?.takeIf { it > 0 }?.let { it * 1_000 }
            if (retryMs != null && retryMs <= limits.adaptiveCapMs) {
                state.blockedUntilMs = timeProvider.nowMillis() + retryMs
            } else {
                state.intervalMs = limits.adaptiveCapMs
            }
            state.consecutiveSuccesses = 0
        }
    }
}
