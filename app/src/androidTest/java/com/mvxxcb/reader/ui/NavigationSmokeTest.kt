package com.mvxxcb.reader.ui

// WP-U UI 冒烟骨架（04 §4：androidTest/ui/**.kt 编译即可，不要求真机跑）。
// 真机/模拟器执行条件：MainActivity 接入 AppNavHost（WP-M 集成步骤）+ AppContainer 真实装配。
// 两个冒烟场景：底部导航三 Tab 流转；阅读器进入与工具栏显隐。

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mvxxcb.reader.MainActivity
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@Ignore("UI 冒烟骨架：待 MainActivity 接入 AppNavHost 且 AppContainer 完成真实装配后启用（WP-U/WP-Q 联调）")
class NavigationSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun bottomNavigation_showsThreeTabs_andSwitches() {
        // 骨架断言占位：三 Tab 存在并可切换（真机联调后启用）
        composeRule.onNodeWithText("书架").assertExists()
        composeRule.onNodeWithText("发现·导入").assertExists()
        composeRule.onNodeWithText("我的").assertExists()
    }
}
