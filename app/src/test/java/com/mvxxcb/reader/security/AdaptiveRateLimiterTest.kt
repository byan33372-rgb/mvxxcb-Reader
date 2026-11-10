package com.mvxxcb.reader.security

import com.mvxxcb.reader.common.time.TimeProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AdaptiveRateLimiter 单测（2.0 WP-S）。
 *
 * 纯 JVM + 假 TimeProvider（冻结时钟）：间隔计算恒取整段 interval，等待经 kotlinx
 * delay 真实挂起——在 runTest 虚拟时钟下被虚拟化，断言用 [currentTime] 的虚拟推进量
 * 精确测出每次 acquire 的等待毫秒数（不引入真实 sleep，不测墙钟）。
 * 观测约定：冻结假时钟下，第 n 次（n≥2）acquire 的虚拟推进量 = 当时的 interval。
 */
@OptIn(ExperimentalCoroutinesApi::class)   // currentTime / advanceTimeBy / runCurrent（虚拟时钟）
class AdaptiveRateLimiterTest {

    private class FakeTime(var now: Long = 1_000_000L) : TimeProvider {
        override fun nowMillis(): Long = now
    }

    private fun limits(
        initial: Long = 2_000,
        floor: Long = 1_000,
        cap: Long = 10_000,
        speedUpAfter: Int = 3,
        stepDown: Long = 500,
    ): NetworkLimits = NetworkLimits(
        minIntervalPerHostMs = initial,
        adaptiveFloorMs = floor,
        adaptiveCapMs = cap,
        speedUpAfterSuccesses = speedUpAfter,
        stepDownMs = stepDown,
    )

    // ———————————————————— 起始间隔 ————————————————————

    @Test
    fun `初始间隔等待 2000（首次不等待）`() = runTest {
        val limiter = AdaptiveRateLimiter(limits(), FakeTime())
        limiter.acquire("h")                    // 首次：无状态，立即放行
        assertEquals(0, currentTime)
        limiter.acquire("h")                    // 第二次：等待整段 interval
        assertEquals(2_000, currentTime)
        limiter.acquire("h")
        assertEquals(4_000, currentTime)
    }

    @Test
    fun `假时钟推进超过间隔则不等待`() = runTest {
        val fake = FakeTime()
        val limiter = AdaptiveRateLimiter(limits(), fake)
        limiter.acquire("h")
        fake.now += 5_000                       // 注入时钟推进超过间隔 → 无挂起
        limiter.acquire("h")
        assertEquals(0, currentTime)
    }

    // ———————————————————— 连续成功降档 ————————————————————

    @Test
    fun `连续 3 成功降档至 floor 后不再降`() = runTest {
        val limiter = AdaptiveRateLimiter(limits(), FakeTime())
        limiter.acquire("h")
        repeat(3) { limiter.onSuccess("h") }    // 2000 → 1500
        limiter.acquire("h")
        assertEquals(1_500, currentTime)
        repeat(3) { limiter.onSuccess("h") }    // 1500 → 1000（floor）
        limiter.acquire("h")
        assertEquals(2_500, currentTime)
        repeat(3) { limiter.onSuccess("h") }    // floor 不再降
        limiter.acquire("h")
        assertEquals(3_500, currentTime)
        repeat(5) { limiter.onSuccess("h") }    // 继续成功也不降
        limiter.acquire("h")
        assertEquals(4_500, currentTime)
    }

    @Test
    fun `降档未满阈值不生效（连续成功计数）`() = runTest {
        val limiter = AdaptiveRateLimiter(limits(), FakeTime())
        limiter.acquire("h")
        repeat(2) { limiter.onSuccess("h") }    // 仅 2 次（< speedUpAfterSuccesses=3）
        limiter.acquire("h")
        assertEquals(2_000, currentTime)        // 仍为起始间隔
    }

    // ———————————————————— 失败退避 ————————————————————

    @Test
    fun `失败翻倍至 cap 后不再涨且成功计数清零`() = runTest {
        val limiter = AdaptiveRateLimiter(limits(), FakeTime())
        limiter.acquire("h")
        limiter.onFailure("h")                  // 2000 → 4000
        limiter.acquire("h")
        assertEquals(4_000, currentTime)
        limiter.onFailure("h")                  // 4000 → 8000
        limiter.acquire("h")
        assertEquals(12_000, currentTime)
        limiter.onFailure("h")                  // min(16000, cap) = 10000
        limiter.acquire("h")
        assertEquals(22_000, currentTime)
        limiter.onFailure("h")                  // cap 不再涨
        limiter.acquire("h")
        assertEquals(32_000, currentTime)
        // 失败清零成功计数：1 次成功不应降档（阈值 3）
        limiter.onSuccess("h")
        limiter.acquire("h")
        assertEquals(42_000, currentTime)
        // 补满 3 次连续成功 → 从 cap 降一档（恢复通道存在）
        limiter.onSuccess("h")
        limiter.onSuccess("h")
        limiter.acquire("h")
        assertEquals(51_500, currentTime)       // 10000 → 9500
    }

    // ———————————————————— 429 / Retry-After ————————————————————

    @Test
    fun `429 带 Retry-After 一次性等待该值`() = runTest {
        val limiter = AdaptiveRateLimiter(limits(), FakeTime())
        limiter.acquire("h")
        limiter.onRateLimited("h", 5)           // Retry-After=5s ≤ cap → 一次性冷却 5s
        limiter.acquire("h")
        assertEquals(5_000, currentTime)        // max(interval 2000, blocked 5000)
        limiter.acquire("h")                    // 冷却一次性消费：回落到 interval
        assertEquals(7_000, currentTime)
    }

    @Test
    fun `429 Retry-After 超上限钳到 cap（持续 interval）`() = runTest {
        val limiter = AdaptiveRateLimiter(limits(), FakeTime())
        limiter.acquire("h")
        limiter.onRateLimited("h", 30)          // 30s > cap → interval 顶到 cap
        limiter.acquire("h")
        assertEquals(10_000, currentTime)
        limiter.acquire("h")                    // interval 持续生效（非一次性）
        assertEquals(20_000, currentTime)
    }

    @Test
    fun `429 无 Retry-After 间隔置为 cap`() = runTest {
        val limiter = AdaptiveRateLimiter(limits(), FakeTime())
        limiter.acquire("h")
        limiter.onRateLimited("h", null)
        limiter.acquire("h")
        assertEquals(10_000, currentTime)
    }

    @Test
    fun `429 非法 Retry-After（非正数）视为缺省`() = runTest {
        val limiter = AdaptiveRateLimiter(limits(), FakeTime())
        limiter.acquire("h")
        limiter.onRateLimited("h", 0)
        limiter.acquire("h")
        assertEquals(10_000, currentTime)       // interval = cap，而非 0 等待
    }

    // ———————————————————— 主机隔离与并发 ————————————————————

    @Test
    fun `不同主机状态互不影响`() = runTest {
        val limiter = AdaptiveRateLimiter(limits(), FakeTime())
        limiter.acquire("a")
        limiter.acquire("b")                    // 各自首次均放行
        assertEquals(0, currentTime)
        repeat(3) { limiter.onSuccess("a") }    // a 降档
        limiter.onFailure("b")                  // b 退避
        limiter.acquire("a")
        assertEquals(1_500, currentTime)        // a: 2000 → 1500
        limiter.acquire("b")
        assertEquals(5_500, currentTime)        // b: +4000（与 a 无关）
    }

    @Test
    fun `同主机并发 acquire 严格串行`() = runTest {
        val limiter = AdaptiveRateLimiter(limits(), FakeTime())
        val order = mutableListOf<Int>()
        val done = List(3) { CompletableDeferred<Unit>() }
        for (i in 1..3) {
            launch {
                limiter.acquire("h")
                synchronized(order) { order.add(i) }
                done[i - 1].complete(Unit)
            }
        }
        done.forEach { it.await() }
        assertEquals(listOf(1, 2, 3), order)    // 完成顺序 = 发起顺序（Mutex 串行化）
        assertEquals(4_000, currentTime)        // 0 + 2000 + 2000，逐个让出锁后才等下一段
    }

    @Test
    fun `不同主机并发 acquire 不互相阻塞`() = runTest {
        val limiter = AdaptiveRateLimiter(limits(), FakeTime())
        limiter.acquire("a")                    // a 盖戳
        limiter.acquire("b")                    // b 盖戳
        val done = CompletableDeferred<Unit>()
        launch {
            limiter.acquire("a")                // a 需等 2000
            done.complete(Unit)
        }
        limiter.acquire("b")                    // b 同样需等 2000，但不受 a 的锁阻塞
        assertEquals(2_000, currentTime)
        done.await()
        assertEquals(2_000, currentTime)        // a 与 b 同窗口并行等待完毕
    }

    // ———————————————————— 取消安全 ————————————————————

    @Test
    fun `等待期协程取消可中断且锁释放不卡死`() = runTest {
        val limiter = AdaptiveRateLimiter(limits(), FakeTime())
        limiter.acquire("h")                    // 盖戳（间隔基线）
        val job = launch { limiter.acquire("h") }   // 需等待 2000
        advanceTimeBy(500)
        job.cancel()                            // 等待中被取消：不盖戳、Mutex 经 finally 释放
        runCurrent()
        limiter.acquire("h")                    // 锁未被卡死，可重新进入等待
        // 取消者未盖戳 → 本次仍按完整 interval 等待；虚拟时钟此前已推进 500
        assertEquals(2_500, currentTime)
    }

    // ———————————————————— 禁用与自定义参数 ————————————————————

    @Test
    fun `minIntervalPerHostMs 为 0 时整体直通`() = runTest {
        val limiter = AdaptiveRateLimiter(NetworkLimits(minIntervalPerHostMs = 0), FakeTime())
        limiter.acquire("h")
        assertEquals(0, currentTime)
        repeat(5) { limiter.onSuccess("h") }
        limiter.onFailure("h")
        limiter.onRateLimited("h", 5)
        limiter.acquire("h")
        assertEquals(0, currentTime)            // 关限速语义保留：全程无等待
    }

    @Test
    fun `自定义参数（floor cap speedUp stepDown）生效`() = runTest {
        val limiter = AdaptiveRateLimiter(
            limits(initial = 800, floor = 400, cap = 3_000, speedUpAfter = 2, stepDown = 200),
            FakeTime(),
        )
        limiter.acquire("h")
        repeat(2) { limiter.onSuccess("h") }    // 800 → 600
        limiter.acquire("h")
        assertEquals(600, currentTime)
        repeat(2) { limiter.onSuccess("h") }    // 600 → 400（floor）
        limiter.acquire("h")
        assertEquals(1_000, currentTime)
        repeat(2) { limiter.onSuccess("h") }    // floor 不再降
        limiter.acquire("h")
        assertEquals(1_400, currentTime)
        repeat(2) { limiter.onFailure("h") }    // 400 → 800 → 1600 → 3200 钳到 cap 3000
        limiter.onFailure("h")
        limiter.acquire("h")
        assertEquals(4_400, currentTime)        // +3000（cap）
    }
}
