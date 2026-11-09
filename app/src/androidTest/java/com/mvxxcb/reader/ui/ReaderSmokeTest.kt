package com.mvxxcb.reader.ui

// WP-U UI 冒烟骨架（04 §4）：阅读器冒烟（进入阅读器 → 点击中央 → 工具栏显隐）。
// 依赖真实数据流（ReadingService/Repository 装配），真机联调后启用。

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mvxxcb.reader.MainActivity
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@Ignore("UI 冒烟骨架：待 AppContainer 装配真实实现后启用（C1 垂直切片联调）")
class ReaderSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun readerToolbarTogglesOnCenterTap() {
        // 骨架占位：导航至阅读器 → 点击屏幕中央 → 断言工具栏节点出现
        // （真实步骤见 04 §4 DoD 2；联调后补齐）
    }
}
