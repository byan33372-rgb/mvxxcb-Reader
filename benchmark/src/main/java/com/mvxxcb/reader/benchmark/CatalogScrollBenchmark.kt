package com.mvxxcb.reader.benchmark

import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M0 基线：万章目录（10000 章）打开 + 快速滚动（FrameTimingMetric）。
 * 路径：书架 → 《性能压测·万章目录》详情 →「查看目录」→ 列表 fling。
 * 编译模式：CompilationMode.Default（装机态）。
 */
@RunWith(AndroidJUnit4::class)
class CatalogScrollBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun openAndFastScroll() = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.DEFAULT,
        iterations = 5,
                setupBlock = {
            device.wakeUp()
            launchColdToShelf()
            // 导航路径在 setup 完成（不计入帧指标）；测量块内连续 fling
            openCatalog()
        },
    ) {
        // 快速连续 fling：8 次上滑 + 2 次下滑
        repeat(8) {
            device.swipe(540, 1800, 540, 400, 8)
        }
        repeat(2) {
            device.swipe(540, 500, 540, 1900, 8)
        }
    }
}
