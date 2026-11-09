package com.mvxxcb.reader.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 全应用迁移注册表（WP-M / T-D4 迁移基建；docs/phase2/调研-本地内容管理与DB迁移.md §7/§8）。
 *
 * 唯一登记点：[com.mvxxcb.reader.data.db.AppDatabase.build] 经
 * `addMigrations(*AppMigrations.ALL)` 统一挂载；AppContainer 及后续任何构建入口
 * 不得自行散挂 Migration，避免迁移清单双轨。
 *
 * 约定（03 §6 红线）：
 * - 每个 schema 变更 = 契约级变更；release 构建禁止 fallbackToDestructiveMigration；
 * - 每个候选变更拆为独立 Migration 对象，再组合进对应 MIGRATION_N_M（单测可逐个跑）；
 * - schemas/<N>.json 随 KSP 导出并随仓库提交，迁移必跑 MigrationTestHelper 校验
 *   （app/schemas 已挂 test assets，见 app/build.gradle.kts sourceSets）。
 *
 * v2（2.0 升级 WP-M0，设计 07 §3.1 五变更逐条，a→e 顺序执行）：
 * - a. reading_progress.charOffset INTEGER NOT NULL DEFAULT -1（字符偏移主锚点）
 * - b. crawl_tasks 新表（表+2 索引；FK→books CASCADE）
 * - c. local_books 新表（bookId UNIQUE；FK→books CASCADE）
 * - d. chapters.contentPath TEXT + contentSource INTEGER NOT NULL DEFAULT 0（随版预建，默认不启用）
 * - e. books.sourceType INTEGER NOT NULL DEFAULT 0（0=WEB/1=LOCAL_TXT/2=EPUB）
 *
 * SQL 与实体 @ColumnInfo(defaultValue) 逐字段一致（MigrationTestHelper 校验基线）。
 */
object AppMigrations {

    /** a：字符偏移主锚点（-1=旧数据 sentinel 自愈） */
    internal val MIGRATION_1_2_A: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `reading_progress` ADD COLUMN `charOffset` INTEGER NOT NULL DEFAULT -1")
        }
    }

    /** b：crawl_tasks 新表 + 索引（列清单/默认值/FK 严格对齐 CrawlTaskEntity） */
    internal val MIGRATION_1_2_B: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `crawl_tasks` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`bookId` INTEGER NOT NULL, " +
                    "`state` TEXT NOT NULL DEFAULT 'WAITING', " +
                    "`totalChapters` INTEGER NOT NULL DEFAULT 0, " +
                    "`succeededCount` INTEGER NOT NULL DEFAULT 0, " +
                    "`failedCount` INTEGER NOT NULL DEFAULT 0, " +
                    "`currentChapterIndex` INTEGER NOT NULL DEFAULT 0, " +
                    "`currentChapterTitle` TEXT, " +
                    "`txtPath` TEXT, " +
                    "`txtGenerated` INTEGER NOT NULL DEFAULT 0, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, " +
                    "`startedAt` INTEGER, " +
                    "`finishedAt` INTEGER, " +
                    "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_crawl_tasks_bookId_state` " +
                    "ON `crawl_tasks` (`bookId`, `state`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_crawl_tasks_updatedAt` " +
                    "ON `crawl_tasks` (`updatedAt` DESC)",
            )
        }
    }

    /** c：local_books 新表（bookId UNIQUE 对齐 LocalBookEntity） */
    internal val MIGRATION_1_2_C: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `local_books` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`bookId` INTEGER NOT NULL, " +
                    "`filePath` TEXT NOT NULL, " +
                    "`format` TEXT NOT NULL, " +
                    "`fileSize` INTEGER NOT NULL DEFAULT 0, " +
                    "`chapterCount` INTEGER NOT NULL DEFAULT 0, " +
                    "`completedChapterCount` INTEGER NOT NULL DEFAULT 0, " +
                    "`isComplete` INTEGER NOT NULL DEFAULT 0, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`bookId`) REFERENCES `books`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_books_bookId` " +
                    "ON `local_books` (`bookId`)",
            )
        }
    }

    /** d：正文文件化双列（随版建好、默认不启用，contentSource=0 全链路现行为） */
    internal val MIGRATION_1_2_D: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `chapters` ADD COLUMN `contentPath` TEXT")
            db.execSQL("ALTER TABLE `chapters` ADD COLUMN `contentSource` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** e：书来源类型（0=WEB / 1=LOCAL_TXT / 2=EPUB；老数据 default 语义正确） */
    internal val MIGRATION_1_2_E: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `books` ADD COLUMN `sourceType` INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** v1→v2 组合迁移：顺序执行 a→e（07 §3.2）。 */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            MIGRATION_1_2_A.migrate(db)
            MIGRATION_1_2_B.migrate(db)
            MIGRATION_1_2_C.migrate(db)
            MIGRATION_1_2_D.migrate(db)
            MIGRATION_1_2_E.migrate(db)
        }
    }

    /** 全部已登记迁移，按版本升序组合。 */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
