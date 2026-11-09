package com.mvxxcb.reader.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.mvxxcb.reader.data.db.dao.BookDao
import com.mvxxcb.reader.data.db.dao.BookmarkDao
import com.mvxxcb.reader.data.db.dao.ChapterDao
import com.mvxxcb.reader.data.db.dao.ReadingProgressDao
import com.mvxxcb.reader.data.db.dao.SourceRuleDao
import com.mvxxcb.reader.data.db.entities.BookEntity
import com.mvxxcb.reader.data.db.entities.BookmarkEntity
import com.mvxxcb.reader.data.db.entities.ChapterEntity
import com.mvxxcb.reader.data.db.entities.ReadingProgressEntity
import com.mvxxcb.reader.data.db.entities.SourceRuleEntity

/**
 * 应用数据库（数据模型 03 §2/§6）。
 *
 * version = 1；五实体：books / chapters / reading_progress / bookmarks / source_rules。
 * schema 导出：`app/schemas/1.json`（KSP room.schemaLocation，见 app/build.gradle.kts）。
 * 迁移策略（03 §6）：任何 schema 变更 = 契约级变更；禁止 release 构建使用
 * fallbackToDestructiveMigration；迁移后必须跑 MigrationTestHelper 校验。
 */
@Database(
    version = 1,
    exportSchema = true,
    entities = [
        BookEntity::class,
        ChapterEntity::class,
        ReadingProgressEntity::class,
        BookmarkEntity::class,
        SourceRuleEntity::class,
    ],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun sourceRuleDao(): SourceRuleDao

    companion object {
        const val DB_NAME = "reader.db"
    }
}
