package com.mvxxcb.reader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mvxxcb.reader.domain.model.AppPreferences
import com.mvxxcb.reader.presentation.navigation.AppNavHost
import com.mvxxcb.reader.ui.theme.AppTheme

/**
 * 应用宿主（阶段 5 集成版）：AppContainer 装配 + AppTheme + AppNavHost；
 * 系统分享（ACTION_SEND text/plain）进入时作为「发现·导入」页的初始文本。
 */
class MainActivity : ComponentActivity() {

    /** 分享进入的文本（冷启动读取；onNewIntent 需 singleTask 启动模式才触发，MVP 以冷启动为主） */
    private var sharedText: String? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as ReaderApplication).container
        sharedText = extractSharedText(intent)

        setContent {
            val prefs by container.appPreferencesRepository.preferences
                .collectAsStateWithLifecycle(initialValue = AppPreferences())
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
