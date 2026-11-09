package com.mvxxcb.reader.presentation.navigation

// 实现归属 WP-U（04 §4）。契约依据：02 §8.1（路由表逐字一致，禁止改路由格式）。
// 底部导航三 Tab：书架 / 发现·导入 / 我的（01 架构图）。
// MainActivity 集成（WP-M，04 §1 占位注释）：setContent { AppTheme { AppNavHost(container) } }

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mvxxcb.reader.di.AppContainer
import com.mvxxcb.reader.ui.bookdetail.BookDetailScreen
import com.mvxxcb.reader.ui.catalog.CatalogScreen
import com.mvxxcb.reader.ui.crawl.CrawlCenterScreen
import com.mvxxcb.reader.ui.importing.ImportScreen
import com.mvxxcb.reader.ui.reader.ReaderScreen
import com.mvxxcb.reader.ui.rules.RuleEditScreen
import com.mvxxcb.reader.ui.rules.RuleManagerScreen
import com.mvxxcb.reader.ui.settings.ReaderSettingsScreen
import com.mvxxcb.reader.ui.settings.SettingsScreen
import com.mvxxcb.reader.ui.shelf.ShelfScreen

/** 底部 Tab 定义（Routes.SHELF / DISCOVER / MINE，契约 02 §8.1） */
private data class TopTab(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val TOP_TABS = listOf(
    TopTab(Routes.SHELF, "书架", Icons.Filled.AutoStories, Icons.Outlined.AutoStories),
    TopTab(Routes.DISCOVER, "发现·导入", Icons.Filled.Explore, Icons.Outlined.Explore),
    TopTab(Routes.MINE, "我的", Icons.Filled.Person, Icons.Outlined.Person),
)

@Composable
fun AppNavHost(
    container: AppContainer,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    initialSharedText: String? = null,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = TOP_TABS.any { it.route == currentRoute }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TOP_TABS.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = tab.label,
                                )
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.SHELF,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.SHELF) {
                ShelfScreen(
                    container = container,
                    onOpenBook = { bookId -> navController.navigate(Routes.bookDetail(bookId)) },
                    onGoDiscover = { navController.navigateToTopTab(Routes.DISCOVER) },
                )
            }

            composable(Routes.DISCOVER) {
                ImportScreen(
                    container = container,
                    initialSharedText = initialSharedText,
                    onOpenRules = { domain -> navController.navigate(Routes.ruleEdit(domain)) },
                    onOpenBook = { bookId -> navController.navigate(Routes.bookDetail(bookId)) },
                    onOpenReader = { bookId, chapterIndex ->
                        navController.navigate(Routes.reader(bookId, chapterIndex))
                    },
                )
            }

            composable(Routes.MINE) {
                SettingsScreen(
                    container = container,
                    onOpenReaderSettings = { navController.navigate(Routes.READER_SETTINGS) },
                    onOpenRuleManager = { navController.navigate(Routes.RULES) },
                    onOpenCrawlCenter = { navController.navigate(Routes.CRAWL_CENTER) },
                )
            }

            composable(
                route = Routes.BOOK_DETAIL,
                arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
            ) { entry ->
                val bookId = entry.arguments?.getLong("bookId") ?: 0L
                BookDetailScreen(
                    container = container,
                    bookId = bookId,
                    onBack = { navController.popBackStack() },
                    onGoCatalog = { id -> navController.navigate(Routes.catalog(id)) },
                    onGoReader = { id, chapterIndex -> navController.navigate(Routes.reader(id, chapterIndex)) },
                    onOpenCrawlCenter = { navController.navigate(Routes.CRAWL_CENTER) },
                )
            }

            composable(
                route = Routes.CATALOG,
                arguments = listOf(
                    navArgument("bookId") { type = NavType.LongType },
                    navArgument("focusChapterId") {
                        // Navigation 不允许可空 Long（运行期校验），以 String 承载后自行解析
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val bookId = entry.arguments?.getLong("bookId") ?: 0L
                val focusChapterId = entry.arguments?.getString("focusChapterId")?.toLongOrNull()
                CatalogScreen(
                    container = container,
                    bookId = bookId,
                    focusChapterId = focusChapterId,
                    onBack = { navController.popBackStack() },
                    onOpenReader = { id, chapterIndex ->
                        navController.navigate(Routes.reader(id, chapterIndex))
                    },
                )
            }

            composable(
                route = Routes.READER,
                arguments = listOf(
                    navArgument("bookId") { type = NavType.LongType },
                    navArgument("chapterIndex") { type = NavType.IntType },
                ),
            ) { entry ->
                val bookId = entry.arguments?.getLong("bookId") ?: 0L
                val chapterIndex = entry.arguments?.getInt("chapterIndex") ?: 0
                ReaderScreen(
                    container = container,
                    bookId = bookId,
                    initialChapterIndex = chapterIndex,
                    onBack = { navController.popBackStack() },
                    onOpenCatalog = { id ->
                        navController.navigate(Routes.catalog(id)) {
                            popUpTo(Routes.READER) { inclusive = true }
                        }
                    },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    container = container,
                    onOpenReaderSettings = { navController.navigate(Routes.READER_SETTINGS) },
                    onOpenRuleManager = { navController.navigate(Routes.RULES) },
                    onOpenCrawlCenter = { navController.navigate(Routes.CRAWL_CENTER) },
                )
            }

            // WP-L（07 §6.2）：采集任务管理（契约 v1.1 K13 路由 crawl）
            composable(Routes.CRAWL_CENTER) {
                CrawlCenterScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                    onOpenBook = { bookId -> navController.navigate(Routes.bookDetail(bookId)) },
                )
            }

            composable(Routes.READER_SETTINGS) {
                ReaderSettingsScreen(container = container, onBack = { navController.popBackStack() })
            }

            composable(Routes.RULES) {
                RuleManagerScreen(
                    container = container,
                    onBack = { navController.popBackStack() },
                    onEditRule = { domain -> navController.navigate(Routes.ruleEdit(domain)) },
                    onCreateRule = { navController.navigate(Routes.ruleEdit(null)) },
                )
            }

            composable(
                route = Routes.RULE_EDIT,
                arguments = listOf(
                    navArgument("domain") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val domain = entry.arguments?.getString("domain")
                RuleEditScreen(container = container, routeDomain = domain, onBack = { navController.popBackStack() })
            }
        }
    }
}

private fun NavHostController.navigateToTopTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
