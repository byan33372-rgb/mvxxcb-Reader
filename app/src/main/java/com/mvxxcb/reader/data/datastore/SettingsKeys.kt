package com.mvxxcb.reader.data.datastore

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * DataStore Key 清单（数据模型 03 §4）。
 *
 * 两个实例文件：reader_settings.preferences_pb / app_preferences.preferences_pb。
 * 规则：key 只增不删；废弃 key 保留不读写；枚举存 `name` 字符串，
 * 解析失败回退默认值并记 WARN（禁止崩溃）。
 */
object SettingsKeys {

    /** reader_settings 实例（阅读设置） */
    object Reader {
        const val FILE_NAME = "reader_settings.preferences_pb"

        val FONT_FAMILY = stringPreferencesKey("reader.fontFamily")          // SYSTEM/SANS/SERIF/MONOSPACE
        val FONT_SIZE = floatPreferencesKey("reader.fontSize")               // 14..28 (sp)
        val LINE_HEIGHT = floatPreferencesKey("reader.lineHeight")           // 1.2..2.4
        val PARAGRAPH_SPACING = floatPreferencesKey("reader.paragraphSpacing") // 0..24 (dp)
        val BOLD = booleanPreferencesKey("reader.bold")
        val HORIZONTAL_PADDING = intPreferencesKey("reader.horizontalPadding") // 0..48 (dp)
        val BACKGROUND_THEME = stringPreferencesKey("reader.backgroundTheme") // WHITE/CREAM/EYE_CARE_GREEN/DARK_GRAY/BLACK
        val READING_MODE = stringPreferencesKey("reader.readingMode")         // SCROLL/PAGE_SIMULATED
        val BRIGHTNESS_MODE = stringPreferencesKey("reader.brightnessMode")   // SYSTEM/CUSTOM
        val BRIGHTNESS = floatPreferencesKey("reader.brightness")             // 0..1
        val SCREEN_ORIENTATION = stringPreferencesKey("reader.screenOrientation") // SYSTEM/PORTRAIT/LANDSCAPE
        val AUTO_SAVE_PROGRESS = booleanPreferencesKey("reader.autoSaveProgress")
        val KEEP_SCREEN_ON = booleanPreferencesKey("reader.keepScreenOn")
        val AUTO_PREFETCH_NEXT = booleanPreferencesKey("reader.autoPrefetchNext")
    }

    /** app_preferences 实例（应用偏好） */
    object App {
        const val FILE_NAME = "app_preferences.preferences_pb"

        val THEME_MODE = stringPreferencesKey("app.themeMode")                // SYSTEM/LIGHT/DARK
        val DYNAMIC_COLOR = booleanPreferencesKey("app.dynamicColor")
    }
}
