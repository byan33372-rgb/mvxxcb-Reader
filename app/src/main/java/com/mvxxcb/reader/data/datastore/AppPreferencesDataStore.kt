package com.mvxxcb.reader.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.domain.model.AppPreferences
import com.mvxxcb.reader.domain.model.ThemeMode
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * app_preferences.preferences_pb 实例（数据模型 03 §4 app_preferences key）。
 * 非法枚举字符串回退默认值并 WARN（禁止崩溃）。
 */
class AppPreferencesDataStore(
    context: Context,
    private val logger: AppLogger,
    scope: CoroutineScope,
    fileName: String = SettingsKeys.App.FILE_NAME,
) {

    /** 测试可直写原始 key（非法值回退用例） */
    internal val raw: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) {
            File(context.filesDir, fileName).absoluteFile
        }

    val preferences: Flow<AppPreferences> = raw.data
        .catch { e ->
            logger.w(EnumParser.TAG, "app_preferences 读取失败，回退默认值", e)
            emit(emptyPreferences())
        }
        .map(::toPreferences)

    suspend fun current(): AppPreferences = preferences.first()

    suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        val next = transform(current())
        raw.edit { prefs ->
            prefs[SettingsKeys.App.THEME_MODE] = next.themeMode.name
            prefs[SettingsKeys.App.DYNAMIC_COLOR] = next.dynamicColor
        }
    }

    private fun toPreferences(prefs: Preferences): AppPreferences = AppPreferences(
        themeMode = EnumParser.parseOr(
            prefs[SettingsKeys.App.THEME_MODE], "app.themeMode",
            ThemeMode.SYSTEM, logger,
        ),
        dynamicColor = prefs[SettingsKeys.App.DYNAMIC_COLOR] ?: false,
    )
}
