package com.mvxxcb.reader.domain.model

data class Book(
    val id: Long,                       // 0 表示未入库（新建前）
    val title: String,                  // 允许为空字符串（详情未解析出时的占位，禁止伪造）
    val author: String?,
    val coverUrl: String?,
    val description: String?,
    val sourceUrl: String,              // 已规范化的来源 URL（详情页或章节页）
    val sourceDomain: String,
    val catalogUrl: String?,            // 规范化目录页 URL
    val latestChapterTitle: String?,
    val totalChapters: Int,             // 0 = 尚无目录
    val currentChapterId: Long?,
    val lastReadAt: Long?,              // null = 从未阅读
    val createdAt: Long,
    val updatedAt: Long,
    val isTemporary: Boolean,           // true = 临时阅读记录（仅解析到正文时建立）
    val sourceEncoding: String?,        // 来源站编码（如 "GBK"），重取时提示
)

/** 目录/列表用轻量章节（不含正文，避免大字段拖垮列表） */
data class ChapterMeta(
    val id: Long,
    val bookId: Long,
    val chapterIndex: Int,              // 0-based，书内规范顺序
    val title: String,
    val url: String,                    // 已规范化
    val isCached: Boolean,
    val isMissing: Boolean,             // 目录更新后站点已不存在的章节
    val wordCount: Int,                 // 0 = 未缓存
    val fetchedAt: Long?,
)

/** 含正文的完整章节 */
data class Chapter(
    val id: Long, val bookId: Long, val chapterIndex: Int,
    val title: String, val url: String,
    val content: String?,               // JSON 序列化的内容块数组（见 03 §2.2）
    val contentHash: String?,           // 规范化正文 SHA-256，用于完整性校验
    val isCached: Boolean, val isMissing: Boolean,
    val fetchedAt: Long?, val wordCount: Int,
)

data class ReadingProgress(
    val bookId: Long,                   // 主键：一书一条
    val chapterId: Long,
    val chapterIndex: Int,
    val scrollPosition: Double?,        // 滚动模式：章内滚动分数 0.0..1.0
    val pageIndex: Int?,                // 翻页模式：页序号 0-based
    val inChapterFraction: Double,      // 统一章内位置 0.0..1.0（重排版恢复用，必填）
    val progressPercentage: Double,     // 全书进度 0.0..100.0
    val updatedAt: Long,
)

data class Bookmark(
    val id: Long,                       // 0 = 未入库
    val bookId: Long,
    val chapterId: Long,
    val chapterIndex: Int,
    val scrollPosition: Double?,
    val pageIndex: Int?,
    val inChapterFraction: Double,
    val selectedText: String?,          // 划选摘录，可空
    val createdAt: Long,
)

data class SourceRule(
    val ruleId: String,                 // "<slug>.<ver>" 或人工规则 "manual.<domain>"
    val domain: String,                 // 如 "www.example.com"（小写、去 www 保留原样由导入方决定）
    val version: Int,
    val ruleJson: String,               // 完整规则 JSON（Schema 见 03 §6），domain 层不解析
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

data class ReaderSettings(
    val fontFamily: ReaderFontFamily = ReaderFontFamily.SYSTEM,
    val fontSizeSp: Float = 18f,                    // 14..28
    val lineHeightMultiplier: Float = 1.6f,         // 1.2..2.4
    val paragraphSpacingDp: Float = 8f,             // 0..24
    val boldText: Boolean = false,
    val horizontalPaddingDp: Int = 20,              // 0..48
    val backgroundTheme: BackgroundTheme = BackgroundTheme.CREAM,
    val readingMode: ReadingMode = ReadingMode.SCROLL,
    val brightnessMode: BrightnessMode = BrightnessMode.SYSTEM,
    val brightness: Float = 0.5f,                   // CUSTOM 时生效 0.0..1.0
    val screenOrientation: ScreenOrientation = ScreenOrientation.PORTRAIT,
    val autoSaveProgress: Boolean = true,
    val keepScreenOn: Boolean = false,
    val autoPrefetchNext: Boolean = true,
)

enum class ReaderFontFamily { SYSTEM, SANS, SERIF, MONOSPACE }        // 自定义字体文件 = 二期
enum class BackgroundTheme { WHITE, CREAM, EYE_CARE_GREEN, DARK_GRAY, BLACK }
enum class ReadingMode { SCROLL, PAGE_SIMULATED }
enum class BrightnessMode { SYSTEM, CUSTOM }
enum class ScreenOrientation { SYSTEM, PORTRAIT, LANDSCAPE }

data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,        // 应用明暗
    val dynamicColor: Boolean = false,                  // Android 12+ 动态取色
)
enum class ThemeMode { SYSTEM, LIGHT, DARK }
