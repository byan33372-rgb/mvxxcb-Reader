package com.mvxxcb.reader.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.mvxxcb.reader.data.db.entities.ChapterEntity
import kotlinx.coroutines.flow.Flow

/**
 * 目录/列表投影行（03 §3：绝不带 content 列，万章目录不拖大字段）。
 * Room 按列名映射；Boolean 经 Converters。
 */
data class ChapterMetaRow(
    val id: Long,
    val bookId: Long,
    val chapterIndex: Int,
    val title: String,
    val url: String,
    val normalizedUrl: String,
    val titleFingerprint: String,
    val volumeTitle: String?,
    val isCached: Boolean,
    val isMissing: Boolean,
    val fetchedAt: Long?,
    val wordCount: Int,
)

@Dao
interface ChapterDao {

    // ———— 目录观察（投影，无 content） ————

    @Query(
        "SELECT id, bookId, chapterIndex, title, url, normalizedUrl, titleFingerprint, " +
            "volumeTitle, isCached, isMissing, fetchedAt, wordCount " +
            "FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex ASC"
    )
    fun observeChapterMetas(bookId: Long): Flow<List<ChapterMetaRow>>

    @Query(
        "SELECT id, bookId, chapterIndex, title, url, normalizedUrl, titleFingerprint, " +
            "volumeTitle, isCached, isMissing, fetchedAt, wordCount " +
            "FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex ASC"
    )
    suspend fun getChapterMetaRows(bookId: Long): List<ChapterMetaRow>

    // ———— 单章读取（全列） ————

    @Query("SELECT * FROM chapters WHERE id = :chapterId")
    suspend fun getById(chapterId: Long): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND chapterIndex = :chapterIndex LIMIT 1")
    suspend fun getByIndex(bookId: Long, chapterIndex: Int): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND normalizedUrl = :url LIMIT 1")
    suspend fun getByNormalizedUrl(bookId: Long, url: String): ChapterEntity?

    /** 当前最大章序（单章补录定位末章用；空目录返回 null） */
    @Query("SELECT MAX(chapterIndex) FROM chapters WHERE bookId = :bookId")
    suspend fun getMaxChapterIndex(bookId: Long): Int?

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterIndex ASC")
    suspend fun getAllForBook(bookId: Long): List<ChapterEntity>

    /** 指纹匹配（目录合并备用查询，03 §3） */
    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND titleFingerprint = :fingerprint")
    suspend fun findByFingerprint(bookId: Long, fingerprint: String): List<ChapterEntity>

    // ———— 目录写入（03 §7.3 合并） ————

    /** 新章节批量插入 */
    @Insert
    suspend fun insertAll(chapters: List<ChapterEntity>): List<Long>

    /** 单章插入（单章补录用），返回 rowId */
    @Insert
    suspend fun insert(chapter: ChapterEntity): Long

    @Upsert
    suspend fun upsertAll(chapters: List<ChapterEntity>)

    /** 合并第 5 步前先把旧索引整体平移到高位临时区，避免重排途中触发 UNIQUE(bookId, chapterIndex) 冲突 */
    @Query("UPDATE chapters SET chapterIndex = chapterIndex + :offset WHERE bookId = :bookId")
    suspend fun shiftChapterIndexes(bookId: Long, offset: Int)

    /** 同章保留 id：标题/指纹/换址/分卷更新，并解除 isMissing */
    @Query(
        "UPDATE chapters SET title = :title, titleFingerprint = :fingerprint, url = :url, " +
            "normalizedUrl = :normalizedUrl, volumeTitle = :volumeTitle, isMissing = 0 " +
            "WHERE id = :id"
    )
    suspend fun updateCatalogEntry(
        id: Long,
        title: String,
        fingerprint: String,
        url: String,
        normalizedUrl: String,
        volumeTitle: String?,
    )

    @Query("UPDATE chapters SET chapterIndex = :chapterIndex WHERE id = :id")
    suspend fun setChapterIndex(id: Long, chapterIndex: Int)

    /** 旧章未出现在新目录 → isMissing=1（不删除）；索引挪到目录尾部区段避免与新目录冲突 */
    @Query(
        "UPDATE chapters SET isMissing = :missing, chapterIndex = :chapterIndex WHERE id = :id"
    )
    suspend fun setMissingWithIndex(id: Long, missing: Boolean, chapterIndex: Int)

    @Query("UPDATE chapters SET isMissing = :missing WHERE id = :chapterId")
    suspend fun setMissing(chapterId: Long, missing: Boolean)

    // ———— 正文 ————

    @Query(
        "UPDATE chapters SET content = :json, contentHash = :hash, wordCount = :words, " +
            "fetchedAt = :now, isCached = 1 WHERE id = :chapterId"
    )
    suspend fun saveContent(chapterId: Long, json: String, hash: String?, words: Int, now: Long)

    /** 删除正文但保留目录与进度（03 §3） */
    @Query(
        "UPDATE chapters SET content = NULL, contentHash = NULL, wordCount = 0, " +
            "isCached = 0, fetchedAt = NULL WHERE id = :chapterId"
    )
    suspend fun deleteContent(chapterId: Long)

    @Query(
        "UPDATE chapters SET content = NULL, contentHash = NULL, wordCount = 0, " +
            "isCached = 0, fetchedAt = NULL WHERE bookId = :bookId AND isCached = 1"
    )
    suspend fun clearBookCache(bookId: Long)

    @Query(
        "UPDATE chapters SET content = NULL, contentHash = NULL, wordCount = 0, " +
            "isCached = 0, fetchedAt = NULL WHERE isCached = 1"
    )
    suspend fun clearAllCaches()

    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND isCached = 1")
    suspend fun getCachedChapters(bookId: Long): List<ChapterEntity>

    // ———— 预取 ————

    @Query(
        "SELECT * FROM chapters WHERE bookId = :bookId AND chapterIndex BETWEEN :fromIndex AND :toIndex " +
            "AND isCached = 0 AND isMissing = 0 ORDER BY chapterIndex ASC"
    )
    suspend fun getUncachedBetween(bookId: Long, fromIndex: Int, toIndex: Int): List<ChapterEntity>

    // ———— 缓存统计（03 §3） ————

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId AND isCached = 1")
    suspend fun countCached(bookId: Long): Int

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId")
    suspend fun countAll(bookId: Long): Int

    @Query("SELECT IFNULL(SUM(LENGTH(content)), 0) FROM chapters WHERE bookId = :bookId AND isCached = 1")
    suspend fun sumContentLength(bookId: Long): Long

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId AND isCached = 1")
    fun observeCachedCount(bookId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM chapters WHERE bookId = :bookId")
    fun observeTotalCount(bookId: Long): Flow<Int>

    @Query("SELECT IFNULL(SUM(LENGTH(content)), 0) FROM chapters WHERE bookId = :bookId AND isCached = 1")
    fun observeTotalChars(bookId: Long): Flow<Long>
}
