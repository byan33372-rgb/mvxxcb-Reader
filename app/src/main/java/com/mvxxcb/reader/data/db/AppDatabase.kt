package com.mvxxcb.reader.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.mvxxcb.reader.data.db.migrations.AppMigrations
import com.mvxxcb.reader.data.db.dao.BookDao
import com.mvxxcb.reader.data.db.dao.BookmarkDao
import com.mvxxcb.reader.data.db.dao.ChapterDao
import com.mvxxcb.reader.data.db.dao.CrawlTaskDao
import com.mvxxcb.reader.data.db.dao.LocalBookDao
import com.mvxxcb.reader.data.db.dao.ReadingProgressDao
import com.mvxxcb.reader.data.db.dao.SourceRuleDao
import com.mvxxcb.reader.data.db.entities.BookEntity
import com.mvxxcb.reader.data.db.entities.BookmarkEntity
import com.mvxxcb.reader.data.db.entities.ChapterEntity
import com.mvxxcb.reader.data.db.entities.CrawlTaskEntity
import com.mvxxcb.reader.data.db.entities.LocalBookEntity
import com.mvxxcb.reader.data.db.entities.ReadingProgressEntity
import com.mvxxcb.reader.data.db.entities.SourceRuleEntity

/**
 * 应用数据库（数据模型 03 §2/§6；2.0 DB v2 = 设计 07 §3.1）。
 *
 * version = 2；v1 五实体：books / chapters / reading_progress / bookmarks / source_rules；
 * v2 增：crawl_tasks / local_books 两表 + 四处加列
 * （reading_progress.charOffset、chapters.contentPath·contentSource、books.sourceType）。
 * schema 导出：`app/schemas/<N>.json`（KSP room.schemaLocation，见 app/build.gradle.kts）。
 * 迁移策略（03 §6）：任何 schema 变更 = 契约级变更；禁止 release 构建使用
 * fallbackToDestructiveMigration；迁移后必须跑 MigrationTestHelper 校验。
 */
@Database(
    version = 2,
    exportSchema = true,
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        ReadingProgressEntity::class,
        BookmarkEntity::class,
        SourceRuleEntity::class,
        CrawlTaskEntity::class,
        LocalBookEntity::class,
    ],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun sourceRuleDao(): SourceRuleDao
    abstract fun crawlTaskDao(): CrawlTaskDao
    abstract fun localBookDao(): LocalBookDao

    companion object {
        const val DB_NAME = "reader.db"

        /**
         * 数据库唯一构建点（WP-M / T-D4）：统一经 [AppMigrations] 注册表挂载迁移。
         * v2 起 schema 变更只改 [AppMigrations.ALL]，构建入口无需再动；
         * [com.mvxxcb.reader.di.AppContainer] 生产装配已改走此方法。
         *
         * @param context 调用方 Context（内部取 applicationContext）
         */
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                .addMigrations(*AppMigrations.ALL)
                .build()
    }
}
