package com.mvxxcb.reader.benchmark

import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M0 基线：阅读器连续滚动（FrameTimingMetric）。
 * 夹具：《性能压测·长文阅读》9528，80 章全部缓存正文（每章 150 段 × 约 60 字）。
 * 阅读模式：装机默认（CONTINUOUS 连续滚动宿主，07 §5.3）。
 * 编译模式：CompilationMode.Default（装机态）。
 */
@RunWith(AndroidJUnit4::class)
class ReaderScrollBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun continuousScroll() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.DEFAULT,
        iterations = 5,
                setupBlock = {
            device.wakeUp()
            launchColdToShelf()
            openReader()
        },
    ) {
        // 连续滚动：10 次上滑（跨章节加载窗口随滚动推进）
        repeat(10) {
            device.swipe(540, 1750, 540, 350, 10)
        }
    }
}
