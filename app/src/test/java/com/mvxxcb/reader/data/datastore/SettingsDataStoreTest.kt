package com.mvxxcb.reader.data.datastore

import androidx.datastore.preferences.core.edit
import com.mvxxcb.reader.data.TestSupport
import com.mvxxcb.reader.domain.model.AppPreferences
import com.mvxxcb.reader.domain.model.BackgroundTheme
import com.mvxxcb.reader.domain.model.ReaderFontFamily
import com.mvxxcb.reader.domain.model.ReaderSettings
import com.mvxxcb.reader.domain.model.ReadingMode
import com.mvxxcb.reader.domain.model.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DataStore 读写与默认值回退单测（DoD 04 §3.4 / 数据模型 03 §4）：
 * - reader_settings / app_preferences 两实例文件名正确、读写往返一致；
 * - 非法枚举字符串回退默认值并记 WARN（禁止崩溃）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsDataStoreTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun newReaderStore(logger: TestSupport.FakeLogger): ReaderSettingsDataStore =
        ReaderSettingsDataStore(
            context = TestSupport.appContext(),
            logger = logger,
            scope = scope,
            fileName = "test_reader_${System.nanoTime()}.preferences_pb",
        )

    private fun newAppStore(logger: TestSupport.FakeLogger): AppPreferencesDataStore =
        AppPreferencesDataStore(
            context = TestSupport.appContext(),
            logger = logger,
            scope = scope,
            fileName = "test_app_${System.nanoTime()}.preferences_pb",
        )

    @Test
    fun `readerSettings读写往返一致`() = runBlocking {
        val logger = TestSupport.FakeLogger()
        val store = newReaderStore(logger)

        val next = ReaderSettings(
            fontFamily = ReaderFontFamily.SERIF,
            fontSizeSp = 22f,
            lineHeightMultiplier = 2.0f,
            paragraphSpacingDp = 12f,
            boldText = true,
            horizontalPaddingDp = 32,
            backgroundTheme = BackgroundTheme.EYE_CARE_GREEN,
            readingMode = ReadingMode.PAGE_SIMULATED,
            autoPrefetchNext = false,
        )
        store.update { next }

        val restored = store.current()
        assertEquals(ReaderFontFamily.SERIF, restored.fontFamily)
        assertEquals(22f, restored.fontSizeSp)
        assertEquals(2.0f, restored.lineHeightMultiplier)
        assertEquals(12f, restored.paragraphSpacingDp)
        assertEquals(true, restored.boldText)
        assertEquals(32, restored.horizontalPaddingDp)
        assertEquals(BackgroundTheme.EYE_CARE_GREEN, restored.backgroundTheme)
        assertEquals(ReadingMode.PAGE_SIMULATED, restored.readingMode)
        assertEquals(false, restored.autoPrefetchNext)
        // 未改字段保持默认
        assertEquals(0.5f, restored.brightness)
        assertTrue(logger.warnings.isEmpty())

        // update(transform) 语义：读取-变换-写回
        store.update { it.copy(fontSizeSp = 16f) }
        assertEquals(16f, store.current().fontSizeSp)
        assertEquals(ReaderFontFamily.SERIF, store.current().fontFamily)
    }

    @Test
    fun `readerSettings非法枚举回退默认并WARN不崩溃`() = runBlocking {
        val logger = TestSupport.FakeLogger()
        val store = newReaderStore(logger)

        // 直接写非法枚举字符串（模拟历史/外部写入坏值）
        store.raw.edit { prefs ->
            prefs[SettingsKeys.Reader.FONT_FAMILY] = "GARBAGE_FONT"
            prefs[SettingsKeys.Reader.BACKGROUND_THEME] = "NOT_A_THEME"
            prefs[SettingsKeys.Reader.READING_MODE] = "SCROLLLLL"
        }

        val settings = store.settings.first() // 不得抛异常
        assertEquals(ReaderFontFamily.SYSTEM, settings.fontFamily)       // 回退默认
        assertEquals(BackgroundTheme.CREAM, settings.backgroundTheme)
        assertEquals(ReadingMode.SCROLL, settings.readingMode)
        assertEquals(3, logger.warnings.size) // 每个非法枚举一条 WARN
        assertTrue(logger.warnings.all { "非法枚举" in it })
    }

    @Test
    fun `appPreferences读写与非法枚举回退`() = runBlocking {
        val logger = TestSupport.FakeLogger()
        val store = newAppStore(logger)

        // 默认值
        assertEquals(AppPreferences(), store.current())

        store.update { it.copy(themeMode = ThemeMode.DARK, dynamicColor = true) }
        val restored = store.current()
        assertEquals(ThemeMode.DARK, restored.themeMode)
        assertTrue(restored.dynamicColor)

        // 非法值回退
        store.raw.edit { prefs ->
            prefs[SettingsKeys.App.THEME_MODE] = "NEITHER"
        }
        val fallback = store.preferences.first()
        assertEquals(ThemeMode.SYSTEM, fallback.themeMode)
        assertTrue(fallback.dynamicColor) // 未涉及字段保持存储值
        assertEquals(1, logger.warnings.size)
    }

    @Test
    fun `两个实例文件名符合03约定`() {
        assertEquals("reader_settings.preferences_pb", SettingsKeys.Reader.FILE_NAME)
        assertEquals("app_preferences.preferences_pb", SettingsKeys.App.FILE_NAME)
    }
}
