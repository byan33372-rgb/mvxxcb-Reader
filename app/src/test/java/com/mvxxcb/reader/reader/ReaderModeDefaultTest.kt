package com.mvxxcb.reader.reader

// WP-CR（2.0 B2，07 §9 Q1）：ReadingMode 新装默认值测试。
// 归属说明：按批次纪律测试仅可落在 app/src/test/java/com/mvxxcb/reader/reader/**，
// 被测对象在 data/datastore（ReaderSettingsDataStore），故以 reader 包命名空间承载。
// 口径：
//  - 新装（READING_MODE key 从未写入）→ CONTINUOUS（2.0 默认）；
//  - 老用户已存 SCROLL/PAGE_SIMULATED → 原值保留（DataStore 已存值优先）；
//  - 已存非法值 → 回退 SCROLL（03 §4 EnumParser 口径，SettingsDataStoreTest 冻结），读后自愈写新值。

import com.mvxxcb.reader.data.TestSupport
import com.mvxxcb.reader.data.datastore.ReaderSettingsDataStore
import com.mvxxcb.reader.data.datastore.SettingsKeys
import com.mvxxcb.reader.domain.model.ReadingMode
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderModeDefaultTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun newStore(): ReaderSettingsDataStore = ReaderSettingsDataStore(
        context = TestSupport.appContext(),
        logger = TestSupport.FakeLogger(),
        scope = scope,
        fileName = "test_reader_mode_default_${System.nanoTime()}.preferences_pb",
    )

    @Test
    fun `新装默认为CONTINUOUS`() = runBlocking {
        val store = newStore()
        assertEquals(ReadingMode.CONTINUOUS, store.current().readingMode)
    }

    @Test
    fun `老用户已存SCROLL与PAGE_SIMULATED自动保留`() = runBlocking {
        val store = newStore()
        store.update { it.copy(readingMode = ReadingMode.SCROLL) }
        assertEquals(ReadingMode.SCROLL, store.current().readingMode)

        store.update { it.copy(readingMode = ReadingMode.PAGE_SIMULATED) }
        assertEquals(ReadingMode.PAGE_SIMULATED, store.current().readingMode)

        // 回读再写不漂移：CONTINUOUS 也可显式持久化
        store.update { it.copy(readingMode = ReadingMode.CONTINUOUS) }
        assertEquals(ReadingMode.CONTINUOUS, store.current().readingMode)
    }

    @Test
    fun `已存非法值回退SCROLL不崩溃`() = runBlocking {
        val store = newStore()
        store.raw.edit { prefs ->
            prefs[SettingsKeys.Reader.READING_MODE] = "SCROLLLLL"
        }
        assertEquals(ReadingMode.SCROLL, store.current().readingMode)
    }
}
