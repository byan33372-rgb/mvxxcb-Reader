package com.mvxxcb.reader.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

/** 被测应用包名（app 模块 applicationId） */
const val PACKAGE_NAME = "com.mvxxcb.reader"

/** 书架注入书 9527：万章目录压测书（无缓存正文，仅供目录基准） */
const val CATALOG_BOOK_TITLE = "性能压测·万章目录"

/** 书架注入书 9528：长文阅读压测书（全部章节带缓存正文，供阅读器基准） */
const val READER_BOOK_TITLE = "性能压测·长文阅读"

/** 阅读器正文段落前缀（夹具 SQL 生成，等待渲染完成的信号） */
const val READER_CONTENT_PREFIX = "万章压测正文·书9528"

/** 常规等待时长 */
const val DEFAULT_WAIT_MS = 15_000L

/**
 * 等待书架页渲染完成（顶栏标题「书架」）。
 */
fun MacrobenchmarkScope.waitShelf(timeoutMs: Long = DEFAULT_WAIT_MS) {
    val ok = device.wait(Until.hasObject(By.text("书架")), timeoutMs)
    checkNotNull(ok) { "等待书架页超时（${timeoutMs}ms）" }
}

/**
 * 每次迭代前冷启动到书架：force-stop 清掉返回栈与列表滚动位置，
 * 保证导航与书架列表状态确定（setup 不计帧指标）。
 */
fun MacrobenchmarkScope.launchColdToShelf() {
    device.executeShellCommand("am force-stop $PACKAGE_NAME")
    Thread.sleep(500)
    startActivityAndWait()
    waitShelf()
}

/**
 * 从书架点开一本书进入详情页（带重试：详情页初挂载有多路 flow 重组）。
 */
fun MacrobenchmarkScope.openBookDetail(title: String, timeoutMs: Long = DEFAULT_WAIT_MS) {
    waitShelf(timeoutMs)
    val card = device.findObject(By.text(title))
        ?: error("书架未找到《$title》卡片")
    card.click()
    repeat(3) {
        if (device.wait(Until.hasObject(By.text("查看目录")), timeoutMs) != null) {
            // 等 UI 稳定再取（hasObject 与 findObject 之间可能撞上重组）
            if (device.findObject(By.text("查看目录")) != null) return
        }
        Thread.sleep(2_000)
    }
    error("进入《$title》详情页后未稳定出现「查看目录」按钮")
}

/**
 * 打开万章压测书的目录页（详情页 →「查看目录」→ 等待「共 10000 章」副标题出现）。
 */
fun MacrobenchmarkScope.openCatalog(timeoutMs: Long = 20_000) {
    openBookDetail(CATALOG_BOOK_TITLE, timeoutMs)
    val button = device.findObject(By.text("查看目录"))
        ?: error("详情页未找到「查看目录」按钮")
    button.click()
    val ok = device.wait(Until.hasObject(By.textContains("共 10000 章")), timeoutMs)
    checkNotNull(ok) { "目录页打开超时（等待「共 10000 章」）" }
    // 列表首项渲染完成再返回（目录窗口加载为异步）
    device.wait(Until.hasObject(By.textContains("第1章 性能压测")), timeoutMs)
}

/**
 * 打开长文阅读压测书的阅读器（详情页 →「开始阅读」/「继续阅读」→ 等待正文渲染）。
 * 阅读进度存在时按钮为「继续阅读 · 第N章」，两种前缀都兼容。
 */
fun MacrobenchmarkScope.openReader(timeoutMs: Long = 20_000) {
    openBookDetail(READER_BOOK_TITLE, timeoutMs)
    val startButton = device.findObject(By.textStartsWith("开始阅读"))
        ?: device.findObject(By.textStartsWith("继续阅读"))
        ?: error("详情页未找到「开始阅读/继续阅读」按钮")
    startButton.click()
    val ok = device.wait(Until.hasObject(By.textContains(READER_CONTENT_PREFIX)), timeoutMs)
    checkNotNull(ok) { "阅读器打开超时（等待正文渲染）" }
}
