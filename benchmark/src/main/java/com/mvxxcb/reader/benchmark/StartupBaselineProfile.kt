package com.mvxxcb.reader.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M5（WP-G）：Baseline Profile 生成器。
 *
 * 由 androidx.baselineprofile 插件驱动（:app:generateReleaseBaselineProfile，
 * 插件会以 :app 的 nonMinifiedRelease 变体安装目标、运行本测试收集 profile）。
 * 生成步骤与前置条件见 docs/phase2/benchmarks/baseline-profile-setup.md。
 *
 * 三段生成旅程（全部复用 BenchmarkHelpers 导航逻辑）：
 * 1. 冷启动到书架（启动主路径，Startup Profile 的核心来源）；
 * 2. 打开万章目录（压测书 9527，覆盖目录加载路径）；
 * 3. 重新冷启动后打开长文阅读器（压测书 9528，覆盖正文渲染路径）。
 *
 * 注意：本测试由插件在生成流程中单独过滤执行（androidx.benchmark.enabledRules=BaselineProfile），
 * 与同模块四个 Macrobenchmark 互不干扰。
 */
@RunWith(AndroidJUnit4::class)
class StartupBaselineProfile {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
        // Startup Profile（系统启动阶段加载）随 Baseline Profile 一并产出；
        // 三段旅程均为用户高频首路径，按任务口径统一纳入。
        includeInStartupProfile = true,
    ) {
        // 段 1：冷启动到书架 —— 覆盖 Application/Activity 初始化、书架首帧渲染路径
        launchColdToShelf()

        // 段 2：打开万章目录（书架 → 详情 → 目录），覆盖目录页加载与列表首屏路径
        openCatalog()

        // 段 3：重新冷启动（目录页返回栈深，直接回退会引入不确定的中间态）
        // 后打开长文阅读器（书架 → 详情 → 阅读），覆盖阅读器首屏渲染路径
        launchColdToShelf()
        openReader()
    }
}
