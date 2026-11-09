package com.mvxxcb.reader.presentation.navigation

// 路由表 —— WP-M 完整生成（契约 02 §8.1，纯常量/纯函数，无 TODO；导航参数由 WP-U 逐字对齐）。
object Routes {
    // 顶部 Tab（顶层目的地）
    const val SHELF = "shelf"                       // 书架
    const val DISCOVER = "discover"                 // 发现·导入
    const val MINE = "mine"                         // 我的（设置入口/阅读历史入口）
    const val BOOK_DETAIL = "book/{bookId}"
    const val CATALOG = "book/{bookId}/catalog?focusChapterId={focusChapterId}"
    const val READER = "read/{bookId}/{chapterIndex}"
    const val SETTINGS = "settings"
    const val READER_SETTINGS = "settings/reader"
    const val RULES = "settings/rules"
    const val RULE_EDIT = "settings/rules/edit?domain={domain}"    // domain 为空 = 新建
    fun bookDetail(bookId: Long) = "book/$bookId"
    fun catalog(bookId: Long, focusChapterId: Long? = null) =
        "book/$bookId/catalog" + (focusChapterId?.let { "?focusChapterId=$it" } ?: "")
    fun reader(bookId: Long, chapterIndex: Int) = "read/$bookId/$chapterIndex"
    fun ruleEdit(domain: String?) = "settings/rules/edit" + (domain?.let { "?domain=$it" } ?: "")
}
