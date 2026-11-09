package com.mvxxcb.reader.e2e

import org.junit.Ignore
import org.junit.Test

/**
 * E2E：断网读缓存（04 §6 WP-Q 文件清单；对应回归清单 REG-E2E-02 / REG-READER-OFFLINE-*）。
 *
 * 【骨架声明】
 * - 本文件为 @Ignore 骨架：仅步骤注释，无任何断言逻辑；**从未真实执行**，不得声称通过。
 * - 激活条件：C2 真实解析联调后（缓存/预取链路可用），且离线控制方式确定
 *   （adb shell `svc wifi disable`+`svc data disable` 或模拟器飞行模式 / 注入 OFFLINE 假网络）。
 *
 * 步骤覆盖：
 *   预缓存若干章 → 断网 → 读已缓存章成功 → 读未缓存章失败（OFFLINE/CHAPTER_NOT_CACHED）
 *   → 「读缓存版本」入口 → 恢复网络重试成功。
 */
@Ignore("骨架：C2 联调后激活；激活前禁止在任何报告中引用本用例的执行结果")
class OfflineReadingE2ETest {

    /**
     * 断网读缓存主链路。
     *
     * 详细步骤（激活时逐条转为准驱动 + 断言）：
     *
     * 1. 联网状态下导入书籍并打开第 1 章（自动缓存当前章，autoPrefetchNext 缓存第 2 章）。
     *    - 断言：第 1、2 章 isCached=true（缓存统计页/DB 断言）。
     * 2. 开启飞行模式（或 adb 禁用 wifi/data），等待系统网络状态生效。
     * 3. 打开第 1 章。
     *    - 断言：正常渲染（fromCache=true 路径），无错误提示。
     * 4. 翻至第 2 章。
     *    - 断言：正常渲染（缓存命中）。
     * 5. 跳到第 3 章（未缓存）。
     *    - 断言：ReaderContentState.Failed(error=OFFLINE 或 CHAPTER_NOT_CACHED)；提示联网重新加载。
     * 6. 若第 3 章存在旧缓存：点「读缓存版本」。
     *    - 断言：旧缓存内容渲染成功。
     * 7. 关闭飞行模式，点「重试」。
     *    - 断言：重新抓取成功，第 3 章进入缓存；进度可继续推进。
     * 8. 清理：删除书籍与缓存（clearBookCache），恢复网络基线。
     */
    @Test
    fun offlineCachedChaptersRemainReadableAndUncachedFailsGracefully() {
        // 骨架：无实现。
    }

    /**
     * 断网期间进度持久化。
     *
     * 步骤：
     * 1. 断网状态下阅读已缓存章至章内 30%。
     * 2. 退出阅读器（ON_STOP 强制存进度）。
     * 3. 重新进入。
     *    - 断言：恢复到 30% 位置（离线不阻塞进度写入；写失败重试一次后仍失败的提示为弱提示不打断阅读）。
     */
    @Test
    fun offlineProgressStillPersistsOnStop() {
        // 骨架：无实现。
    }
}
