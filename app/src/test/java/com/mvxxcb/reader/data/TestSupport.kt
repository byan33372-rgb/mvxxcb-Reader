package com.mvxxcb.reader.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.mvxxcb.reader.common.log.AppLogger
import com.mvxxcb.reader.common.time.TimeProvider
import com.mvxxcb.reader.data.db.AppDatabase
import com.mvxxcb.reader.data.db.entities.BookEntity
import com.mvxxcb.reader.data.repository.CatalogMerger
import com.mvxxcb.reader.domain.error.ReaderError
import com.mvxxcb.reader.domain.model.ChapterRef
import com.mvxxcb.reader.domain.model.ParsedCatalog
import org.robolectric.RuntimeEnvironment

/** data 层单测公共支撑：内存库 / 假日志 / 假时钟 / 样本构造。 */
object TestSupport {

    /** Robolectric 应用上下文（避免引入 androidx.test:core 依赖） */
    fun appContext(): Context = RuntimeEnvironment.getApplication()

    fun buildDb(): AppDatabase =
        Room.inMemoryDatabaseBuilder(
            appContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

    class FakeLogger : AppLogger {
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()

        override fun d(tag: String, message: String) {}
        override fun i(tag: String, message: String) {}
        override fun w(tag: String, message: String, throwable: Throwable?) {
            warnings += "$tag: $message"
        }

        override fun e(tag: String, message: String, throwable: Throwable?) {
            errors += "$tag: $message"
        }

        override fun logError(error: ReaderError, context: String) {
            errors += context
        }
    }

    /** 可步进假时钟 */
    class FakeTime(start: Long = 1_700_000_000_000L) : TimeProvider {
        private var current = start
        override fun nowMillis(): Long = ++current
    }

    fun newMerger(db: AppDatabase, logger: AppLogger): CatalogMerger =
        CatalogMerger(db, db.chapterDao(), db.bookDao(), logger)

    suspend fun insertBook(db: AppDatabase, title: String = "测试书", url: String = "https://www.example.com/book/1"): Long {
        val id = db.bookDao().upsert(
            BookEntity(
                title = title,
                sourceUrl = url,
                normalizedSourceUrl = url,
                sourceDomain = "www.example.com",
                createdAt = 1L,
                updatedAt = 1L,
            )
        )
        return id
    }

    fun chapterRef(order: Int, title: String, url: String): ChapterRef =
        ChapterRef(order = order, title = title, url = url)

    fun catalogOf(
        refs: List<ChapterRef>,
        sourceUrl: String = "https://www.example.com/catalog",
        reversed: Boolean = false,
    ): ParsedCatalog = ParsedCatalog(
        sourceUrl = sourceUrl,
        chapters = refs,
        isLikelyReversed = reversed,
        paginationTraversed = 1,
        truncatedByLimit = false,
    )
}
