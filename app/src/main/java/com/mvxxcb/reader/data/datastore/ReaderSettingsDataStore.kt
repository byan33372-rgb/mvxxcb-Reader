package com.mvxxcb.reader.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.domain.model.BackgroundTheme
import com.mvxxcb.reader.domain.model.BrightnessMode
import com.mvxxcb.reader.domain.model.ReaderFontFamily
import com.mvxxcb.reader.domain.model.ReaderSettings
import com.mvxxcb.reader.domain.model.ReadingMode
import com.mvxxcb.reader.domain.model.ScreenOrientation
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * reader_settings.preferences_pb 实例（数据模型 03 §4 reader_settings 全部 key）。
 *
 * 非法枚举字符串回退默认值并 WARN（禁止崩溃，03 §4）；
 * 数值范围（14..28 等）为 UI 输入校验职责，data 层如实存取不做二次裁剪。
 *
 * @param scope   DataStore 专用作用域（由装配层经 DispatcherProvider 提供，01 §5 禁用全局 Dispatchers）
 * @param fileName 可注入以便测试隔离；生产默认 03 §4 指定文件名。
 */
class ReaderSettingsDataStore(
    context: Context,
    private val logger: AppLogger,
    scope: CoroutineScope,
    fileName: String = SettingsKeys.Reader.FILE_NAME,
) {

    /** 测试可直写原始 key（非法值回退用例） */
    internal val raw: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) {
            File(context.filesDir, fileName).absoluteFile
        }

    val settings: Flow<ReaderSettings> = raw.data
        .catch { e ->
            // DataStore 文件损坏：回退空偏好（03 §4 禁止崩溃）；Storage 错误日志
            logger.w(EnumParser.TAG, "reader_settings 读取失败，回退默认值", e)
            emit(emptyPreferences())
        }
        .map(::toSettings)

    suspend fun current(): ReaderSettings = settings.first()

    suspend fun update(transform: (ReaderSettings) -> ReaderSettings) {
        val next = transform(current())
        raw.edit { prefs ->
            prefs[SettingsKeys.Reader.FONT_FAMILY] = next.fontFamily.name
            prefs[SettingsKeys.Reader.FONT_SIZE] = next.fontSizeSp
            prefs[SettingsKeys.Reader.LINE_HEIGHT] = next.lineHeightMultiplier
            prefs[SettingsKeys.Reader.PARAGRAPH_SPACING] = next.paragraphSpacingDp
            prefs[SettingsKeys.Reader.BOLD] = next.boldText
            prefs[SettingsKeys.Reader.HORIZONTAL_PADDING] = next.horizontalPaddingDp
            prefs[SettingsKeys.Reader.BACKGROUND_THEME] = next.backgroundTheme.name
            prefs[SettingsKeys.Reader.READING_MODE] = next.readingMode.name
            prefs[SettingsKeys.Reader.BRIGHTNESS_MODE] = next.brightnessMode.name
            prefs[SettingsKeys.Reader.BRIGHTNESS] = next.brightness
            prefs[SettingsKeys.Reader.SCREEN_ORIENTATION] = next.screenOrientation.name
            prefs[SettingsKeys.Reader.AUTO_SAVE_PROGRESS] = next.autoSaveProgress
            prefs[SettingsKeys.Reader.KEEP_SCREEN_ON] = next.keepScreenOn
            prefs[SettingsKeys.Reader.AUTO_PREFETCH_NEXT] = next.autoPrefetchNext
        }
    }

    private fun toSettings(prefs: Preferences): ReaderSettings = ReaderSettings(
        fontFamily = EnumParser.parseOr(
            prefs[SettingsKeys.Reader.FONT_FAMILY], "reader.fontFamily",
            ReaderFontFamily.SYSTEM, logger,
        ),
        fontSizeSp = prefs[SettingsKeys.Reader.FONT_SIZE] ?: 18f,
        lineHeightMultiplier = prefs[SettingsKeys.Reader.LINE_HEIGHT] ?: 1.6f,
        paragraphSpacingDp = prefs[SettingsKeys.Reader.PARAGRAPH_SPACING] ?: 8f,
        boldText = prefs[SettingsKeys.Reader.BOLD] ?: false,
        horizontalPaddingDp = prefs[SettingsKeys.Reader.HORIZONTAL_PADDING] ?: 20,
        backgroundTheme = EnumParser.parseOr(
            prefs[SettingsKeys.Reader.BACKGROUND_THEME], "reader.backgroundTheme",
            BackgroundTheme.CREAM, logger,
        ),
        readingMode = EnumParser.parseOr(
            prefs[SettingsKeys.Reader.READING_MODE], "reader.readingMode",
            ReadingMode.SCROLL, logger,
        ),
        brightnessMode = EnumParser.parseOr(
            prefs[SettingsKeys.Reader.BRIGHTNESS_MODE], "reader.brightnessMode",
            BrightnessMode.SYSTEM, logger,
        ),
        brightness = prefs[SettingsKeys.Reader.BRIGHTNESS] ?: 0.5f,
        screenOrientation = EnumParser.parseOr(
            prefs[SettingsKeys.Reader.SCREEN_ORIENTATION], "reader.screenOrientation",
            ScreenOrientation.PORTRAIT, logger,
        ),
        autoSaveProgress = prefs[SettingsKeys.Reader.AUTO_SAVE_PROGRESS] ?: true,
        keepScreenOn = prefs[SettingsKeys.Reader.KEEP_SCREEN_ON] ?: false,
        autoPrefetchNext = prefs[SettingsKeys.Reader.AUTO_PREFETCH_NEXT] ?: true,
    )
}
