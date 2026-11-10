package com.mvxxcb.reader.benchmark

import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M0 基线：书架滚动帧率（FrameTimingMetric）。
 * 夹具：42 本书（万章书 + 长文书 + 40 本填充书），保证书架列表可滚动。
 * 编译模式：CompilationMode.Default（装机态）。
 */
@RunWith(AndroidJUnit4::class)
class ShelfScrollBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun scroll() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.DEFAULT,
        iterations = 5,
                setupBlock = {
            device.wakeUp()
            launchColdToShelf()
        },
    ) {
        // 4 次上滑 fling（约 3~4 屏），随后 1 次下滑回顶方向
        repeat(4) {
            device.swipe(540, 1800, 540, 400, 8)
        }
        device.swipe(540, 500, 540, 1900, 8)
    }
}
