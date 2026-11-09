package com.mvxxcb.reader.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.mvxxcb.reader.data.db.migrations.AppMigrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 数据库迁移测试基建（WP-M / T-D4；docs/phase2/调研-二期测试方案.md T-D4、
 * docs/phase2/调研-本地内容管理与DB迁移.md §7/§8）。
 *
 * 前置：Room schema JSON 已挂为 test assets（app/build.gradle.kts sourceSets），
 * MigrationTestHelper 经 KSP 导出的 `com.mvxxcb.reader.data.db.AppDatabase/<version>.json`
 * 做 schema 校验；迁移注册表见 data/db/migrations/AppMigrations.kt。
 *
 * 类名落 com.mvxxcb.reader.data.db 包：现有 `--tests "com.mvxxcb.reader.data.*"`
 * 过滤即覆盖，scripts/run-unit-tests.sh 零改动（调研 §8.4）。
 *
 * 2.0（WP-M0）：migration1To2 用例已激活——v1 代表性行 → MIGRATION_1_2 →
 * 旧行保全 + 新列默认值 + 新表/索引存在 + 新表默认值/UNIQUE/FK 行为校验（07 §3.3 硬门禁）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {

    companion object {
        /** 迁移测试专用库文件名（Robolectric 每用例独立临时目录，用例间不串） */
        private const val TEST_DB = "migration-test.db"

        /** v1 五实体表（数据模型 03 §2，表名见各 @Entity(tableName)） */
        private val V1_TABLES = arrayOf("books", "chapters", "reading_progress", "bookmarks", "source_rules")

        /** v2 新增表（07 §3.1-b/c） */
        private val V2_TABLES = arrayOf("crawl_tasks", "local_books")

        /** v2 新增索引（07 §3.1-b/c：crawl_tasks(bookId,state)、crawl_tasks(updatedAt DESC)、local_books(bookId) UNIQUE） */
        private val V2_INDICES = arrayOf(
            "index_crawl_tasks_bookId_state",
            "index_crawl_tasks_updatedAt",
            "index_local_books_bookId",
        )
    }

    /** Instrumentation 由 Robolectric 注册到 androidx.test（robolectric 4.13 传递 monitor） */
    @get:Rule
    val helper: MigrationTestHelper =
        MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    /** 用例 1：v1 基线库可创建、可打开、五表齐备且版本号正确。 */
    @Test
    fun v1SchemaOpensAndAllFiveTablesExist() {
        val db = helper.createDatabase(TEST_DB, 1)
        try {
            assertTrue("createDatabase 应按请求版本建库（实际 version=${db.version}）", db.version == 1)
            for (table in V1_TABLES) {
                db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { cursor ->
                    assertTrue("v1 schema 应包含表 $table", cursor.moveToFirst())
                }
            }
        } finally {
            db.close()
        }
    }

    /**
     * 用例 2（已激活，WP-M0）：v1→v2 迁移校验（07 §3.3 硬门禁）。
     *
     * v1 建库并写入代表性行（book + 2 章节（1 缓存含正文/1 未缓存）+ progress + bookmark）
     * → runMigrationsAndValidate(2, MIGRATION_1_2)（schema 结构级校验含新表/新列/FK/索引）→
     * 断言：旧行保全、新列默认值（charOffset=-1、contentPath=NULL、contentSource=0、sourceType=0）、
     * crawl_tasks/local_books 存在且索引齐备、新表 DEFAULT 生效、local_books.bookId UNIQUE 生效、
     * 新表 FK→books CASCADE 生效。
     */
    @Test
    fun migration1To2_preservesRowsAndAppliesNewDefaults() {
        // ———— 1. v1 建库 + 代表性行 ————
        val db = helper.createDatabase(TEST_DB, 1)
        try {
            db.execSQL(
                "INSERT INTO books (title, sourceUrl, normalizedSourceUrl, sourceDomain, createdAt, updatedAt) " +
                    "VALUES ('测试书', 'https://a.example/book/1', 'https://a.example/book/1', 'a.example', 1000, 1000)",
            )
            // 已缓存章（含正文）
            db.execSQL(
                "INSERT INTO chapters (bookId, chapterIndex, title, url, normalizedUrl, titleFingerprint, " +
                    "content, isCached, wordCount) " +
                    "VALUES (1, 0, '第一章', 'https://a.example/c/1', 'https://a.example/c/1', 'fp1', " +
                   "'[{\"t\":\"p\",\"v\":\"正文段落\"}]', 1, 4)",
            )
            // 未缓存章
            db.execSQL(
                "INSERT INTO chapters (bookId, chapterIndex, title, url, normalizedUrl, titleFingerprint, isCached) " +
                    "VALUES (1, 1, '第二章', 'https://a.example/c/2', 'https://a.example/c/2', 'fp2', 0)",
            )
            db.execSQL(
                "INSERT INTO reading_progress (bookId, chapterId, chapterIndex, inChapterFraction, " +
                    "progressPercentage, updatedAt) VALUES (1, 1, 0, 0.5, 10.0, 1000)",
            )
            db.execSQL(
                "INSERT INTO bookmarks (bookId, chapterId, chapterIndex, inChapterFraction, selectedText, createdAt) " +
                    "VALUES (1, 1, 0, 0.2, '摘录', 1000)",
            )
        } finally {
            db.close()
        }

        // ———— 2. 跑迁移并做 schema 结构校验（对 2.json：列/默认值/FK/索引） ————
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 2, true, AppMigrations.MIGRATION_1_2)
        try {
            assertEquals("迁移后版本应为 2", 2, migrated.version)

            // ———— 3. 旧行保全（数据完整性） ————
            migrated.query("SELECT title, sourceUrl FROM books WHERE id = 1").use { c ->
                assertTrue("books 旧行应保留", c.moveToFirst())
                assertEquals("测试书", c.getString(0))
                assertEquals("https://a.example/book/1", c.getString(1))
            }
            migrated.query(
                "SELECT id, title, content, isCached, wordCount FROM chapters WHERE bookId = 1 ORDER BY chapterIndex",
            ).use { c ->
                assertTrue("chapters 第 1 行应保留", c.moveToFirst())
                assertEquals(1L, c.getLong(0))
                assertEquals("第一章", c.getString(1))
                assertEquals("已缓存章正文应逐字节保留", "[{\"t\":\"p\",\"v\":\"正文段落\"}]", c.getString(2))
                assertEquals(1, c.getInt(3))
                assertEquals(4, c.getInt(4))
                assertTrue("chapters 第 2 行应保留", c.moveToNext())
                assertEquals("第二章", c.getString(1))
                assertNull("未缓存章 content 应仍为 NULL", c.getString(2))
                assertFalse("chapters 应恰有 2 行", c.moveToNext())
            }
            migrated.query(
                "SELECT chapterId, chapterIndex, inChapterFraction, progressPercentage FROM reading_progress WHERE bookId = 1",
            ).use { c ->
                assertTrue("reading_progress 旧行应保留", c.moveToFirst())
                assertEquals(1L, c.getLong(0))
                assertEquals(0, c.getInt(1))
                assertEquals(0.5, c.getDouble(2), 0.0)
                assertEquals(10.0, c.getDouble(3), 0.0)
            }
            migrated.query("SELECT chapterId, selectedText FROM bookmarks WHERE bookId = 1").use { c ->
                assertTrue("bookmarks 旧行应保留", c.moveToFirst())
                assertEquals(1L, c.getLong(0))
                assertEquals("摘录", c.getString(1))
            }

            // ———— 4. 新列默认值（老数据 sentinel） ————
            migrated.query("SELECT charOffset FROM reading_progress WHERE bookId = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("旧进度 charOffset 应回填 -1（07 §3.1-a）", -1L, c.getLong(0))
            }
            migrated.query("SELECT contentPath, contentSource FROM chapters WHERE bookId = 1 ORDER BY chapterIndex").use { c ->
                assertTrue(c.moveToFirst())
                assertNull("contentPath 应回填 NULL（07 §3.1-d）", c.getString(0))
                assertEquals("contentSource 应回填 0（DB 正文现行为）", 0, c.getInt(1))
                assertTrue(c.moveToNext())
                assertNull(c.getString(0))
                assertEquals(0, c.getInt(1))
            }
            migrated.query("SELECT sourceType FROM books WHERE id = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("老数据 sourceType 应回填 0（WEB，07 §3.1-e）", 0, c.getInt(0))
            }

            // ———— 5. 新表 + 新索引存在 ————
            for (table in V2_TABLES) {
                migrated.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { c ->
                    assertTrue("v2 schema 应包含表 $table", c.moveToFirst())
                }
            }
            for (index in V2_INDICES) {
                migrated.query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?", arrayOf(index)).use { c ->
                    assertTrue("v2 schema 应包含索引 $index", c.moveToFirst())
                }
            }
            migrated.query("SELECT sql FROM sqlite_master WHERE type = 'index' AND name = 'index_local_books_bookId'").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue("local_books.bookId 索引应为 UNIQUE（07 §3.1-c）", c.getString(0).uppercase().contains("UNIQUE"))
            }

            // ———— 6. 新表 DEFAULT 生效（v2 新列默认值逐项落库） ————
            migrated.execSQL(
                "INSERT INTO crawl_tasks (bookId, createdAt, updatedAt) VALUES (1, 2000, 2000)",
            )
            migrated.query("SELECT state, totalChapters, succeededCount, failedCount, currentChapterIndex, txtGenerated FROM crawl_tasks WHERE bookId = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("crawl_tasks.state 默认 'WAITING'", "WAITING", c.getString(0))
                assertEquals(0, c.getInt(1))
                assertEquals(0, c.getInt(2))
                assertEquals(0, c.getInt(3))
                assertEquals(0, c.getInt(4))
                assertEquals("txtGenerated 默认 0", 0, c.getInt(5))
            }
            migrated.execSQL(
                "INSERT INTO local_books (bookId, filePath, format, createdAt, updatedAt) VALUES (1, '/data/txt/1.txt', 'TXT', 2000, 2000)",
            )
            migrated.query("SELECT fileSize, chapterCount, completedChapterCount, isComplete FROM local_books WHERE bookId = 1").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(0L, c.getLong(0))
                assertEquals(0, c.getInt(1))
                assertEquals(0, c.getInt(2))
                assertEquals("isComplete 默认 0", 0, c.getInt(3))
            }

            // ———— 7. local_books.bookId UNIQUE 行为生效 ————
            var duplicateRejected = false
            try {
                migrated.execSQL(
                    "INSERT INTO local_books (bookId, filePath, format, createdAt, updatedAt) VALUES (1, '/data/txt/1-b.txt', 'TXT', 2001, 2001)",
                )
            } catch (e: Exception) {
                duplicateRejected = true
            }
            assertTrue("同 bookId 第二条 local_books 应被 UNIQUE 约束拒绝", duplicateRejected)

            // ———— 8. 新表 FK→books CASCADE 行为生效 ————
            migrated.execSQL("PRAGMA foreign_keys = ON")
            migrated.execSQL("DELETE FROM books WHERE id = 1")
            migrated.query("SELECT COUNT(*) FROM crawl_tasks").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("删书应级联清理 crawl_tasks（FK CASCADE）", 0, c.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM local_books").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("删书应级联清理 local_books（FK CASCADE）", 0, c.getInt(0))
            }
        } finally {
            migrated.close()
        }
    }
}
