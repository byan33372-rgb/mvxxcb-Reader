package com.mvxxcb.reader

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mvxxcb.reader.domain.model.AppPreferences
import com.mvxxcb.reader.domain.model.ThemeMode
import com.mvxxcb.reader.presentation.navigation.AppNavHost
import com.mvxxcb.reader.ui.theme.AppTheme
import com.mvxxcb.reader.ui.theme.rememberAppDarkTheme

/**
 * 应用宿主（阶段 5 集成版）：AppContainer 装配 + AppTheme + AppNavHost；
 * 系统分享（ACTION_SEND text/plain）进入时作为「发现·导入」页的初始文本。
 *
 * WP-INS（07 §6.1.1）：onCreate 在 setContent 前**唯一一次** enableEdgeToEdge()；
 * 状态栏/导航栏图标明暗随应用主题（非系统主题）同步（SyncSystemBarAppearance）；
 * edge-to-edge 下窗口不再自动避让软键盘，显式 ADJUST_RESIZE 保证 API<30 的 ime insets 上报
 * （API 30+ 由 Compose WindowInsets.ime 经 imePadding 消费）。
 */
class MainActivity : ComponentActivity() {

    /** 分享进入的文本（冷启动读取；onNewIntent 需 singleTask 启动模式才触发，MVP 以冷启动为主） */
    private var sharedText: String? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val container = (application as ReaderApplication).container
        sharedText = extractSharedText(intent)

        setContent {
            val prefs by container.appPreferencesRepository.preferences
                .collectAsStateWithLifecycle(initialValue = AppPreferences())
            // 系统栏图标明暗随应用主题：浅色主题深图标 / 深色主题浅图标（07 §6 状态栏随主题）
            SyncSystemBarAppearance(activity = this, darkTheme = rememberAppDarkTheme(prefs.themeMode))
            AppTheme(themeMode = prefs.themeMode, dynamicColor = prefs.dynamicColor) {
                AppNavHost(container = container, initialSharedText = sharedText)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractSharedText(intent)?.let { sharedText = it }
    }

    private fun extractSharedText(intent: Intent?): String? =
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else {
            null
        }
}

/** 系统栏外观与应用主题同步：仅设置图标明暗 flag，不改变沉浸显隐（显隐归阅读器管） */
@Composable
private fun SyncSystemBarAppearance(activity: Activity, darkTheme: Boolean) {
    val view = LocalView.current
    DisposableEffect(activity, view, darkTheme) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.isAppearanceLightStatusBars = !darkTheme
        controller.isAppearanceLightNavigationBars = !darkTheme
        onDispose { }
    }
}
