package com.mvxxcb.reader.ui.reader

// WP-U（04 §4）：阅读器主屏。核心交互（04 §4 DoD 2 / 01 §5）：
//  - 点击屏幕中央显隐工具栏；上下滚动 + 模拟翻页双模式
//  - 设置变更 → TextPager 重算 → 按 inChapterFraction 映射恢复位置（契约 02 §8.6 注）
//  - ON_STOP 调 forceSaveProgress；临近章尾预取（VM 内）
//  - 亮度遮罩 / 系统或自定义亮度 / 常亮 / 沉浸全屏
//  - 上下章切换、目录跳转、简版书签入口

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mvxxcb.reader.di.AppContainer
import com.mvxxcb.reader.domain.model.BrightnessMode
import com.mvxxcb.reader.domain.model.ReaderSettings
import com.mvxxcb.reader.domain.model.ReadingMode
import com.mvxxcb.reader.presentation.reader.ReaderContentState
import com.mvxxcb.reader.presentation.reader.ReaderEvent
import com.mvxxcb.reader.presentation.reader.ReaderViewModel
import com.mvxxcb.reader.reader.theme.ReaderThemes
import com.mvxxcb.reader.ui.components.ErrorText
import com.mvxxcb.reader.ui.components.LoadingState
import com.mvxxcb.reader.ui.components.toUserMessage
import com.mvxxcb.reader.ui.theme.spacing

@Composable
fun ReaderScreen(
    container: AppContainer,
    bookId: Long,
    initialChapterIndex: Int,
    onBack: () -> Unit,
    onOpenCatalog: (bookId: Long) -> Unit,
    onOpenReaderSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val viewModel: ReaderViewModel = viewModel(
        key = "reader_${bookId}_$initialChapterIndex",
        factory = ReaderViewModel.factory(container, bookId, initialChapterIndex),
    )
    val state by viewModel.uiState.collectAsState()
    val pagination by viewModel.pagination.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()

    val theme = ReaderThemes.of(state.settings.backgroundTheme)
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val activity = context.findActivity()

    var showSettingsSheet by remember { mutableStateOf(false) }
    var showBookmarkSheet by remember { mutableStateOf(false) }

    // —— 一次性事件 → 弱提示（契约 02 §8.6 ReaderEvent） ——
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ReaderEvent.BookmarkAdded -> snackbarHostState.showSnackbar("书签已添加")
                is ReaderEvent.ShowMessage -> {
                    val text = event.error?.toUserMessage(context) ?: "操作完成"
                    snackbarHostState.showSnackbar(text)
                }
            }
        }
    }

    // —— 主布局 ——
    val density = LocalDensity.current
    val settingsState = rememberUpdatedState(state.settings)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(theme.background))
            .onGloballyPositioned { coords ->
                // 视口上报（宽度扣除左右边距 = 分页内容宽；契约 02 §9：调用方换算 sp/dp→px 的依据）
                val padPx = (settingsState.value.horizontalPaddingDp * 2).coerceAtLeast(0)
                viewModel.onViewportChanged(
                    widthPx = (coords.size.width - padPx).coerceAtLeast(1),
                    heightPx = coords.size.height.coerceAtLeast(1),
                    density = density.density,
                    fontScale = density.fontScale,
                )
            },
    ) {
        // —— 内容区 ——
        when (val contentState = state.contentState) {
            is ReaderContentState.Loading -> LoadingState(text = "正在加载章节…")

            is ReaderContentState.Failed -> Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    // 错误态下点击错误卡片同样显隐工具栏（阅读器核心交互在 Failed 态保持可用，
                    // 避免工具栏被隐藏时用户卡死在错误页；重试/读缓存按钮自消费点击不受影响）
                    .clickable { viewModel.toggleToolbar() }
                    .padding(MaterialTheme.spacing.lg),
            ) {
                Text("章节加载失败", style = MaterialTheme.typography.titleMedium)
                ErrorText(
                    error = contentState.error,
                    modifier = Modifier.padding(vertical = MaterialTheme.spacing.sm),
                )
                Row(modifier = Modifier.padding(top = MaterialTheme.spacing.sm)) {
                    // canRetry 语义：Network（含 OFFLINE）等可重试错误渲染「重试」；
                    // Security 等不可重试错误不渲染（契约 02 §10 行为，验收确认项）
                    if (contentState.canRetry) {
                        Button(onClick = { viewModel.retryLoad() }) { Text("重试") }
                    }
                    if (contentState.cachedAvailable) {
                        OutlinedButton(
                            onClick = { viewModel.loadCachedVersion() },
                            modifier = Modifier.padding(start = MaterialTheme.spacing.sm),
                        ) { Text("读已缓存章节") }
                    }
                }
            }

            is ReaderContentState.Ready -> when (state.settings.readingMode) {
                ReadingMode.SCROLL -> ReaderScrollHost(
                    content = contentState.content,
                    settings = state.settings,
                    theme = theme,
                    pagination = pagination,
                    restoreFraction = state.scrollFraction,
                    onScrolled = viewModel::onScrolled,
                    onToggleToolbar = viewModel::toggleToolbar,
                )

                ReadingMode.PAGE_SIMULATED -> ReaderPageHost(
                    content = contentState.content,
                    settings = state.settings,
                    theme = theme,
                    pagination = pagination,
                    restorePage = state.currentPage,
                    onPageChanged = viewModel::onPageChanged,
                    onToggleToolbar = viewModel::toggleToolbar,
                    onPrevChapter = viewModel::goToPreviousChapter,
                    onNextChapter = viewModel::goToNextChapter,
                )
            }
        }

        // —— 自定义亮度遮罩（内容之上、工具栏之下；纯背景不拦截点击） ——
        if (state.settings.brightnessMode == BrightnessMode.CUSTOM) {
            val maskAlpha = ((1f - state.settings.brightness) * 0.6f).coerceIn(0f, 0.6f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = maskAlpha)),
            )
        }

        // —— 工具栏（覆盖层） ——
        ReaderTopBar(
            visible = state.toolbarVisible,
            bookTitle = state.bookTitle,
            chapterTitle = state.chapterTitle,
            onBack = onBack,
            onToggleBookmarkSheet = { showBookmarkSheet = true },
        )
        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            ReaderBottomBar(
                visible = state.toolbarVisible,
                progressText = "本章 ${(state.inChapterFraction * 100).toInt()}% · 全书 " +
                    "${"%.1f".format(state.overallProgress)}%",
                hasPrevious = state.hasPrevious,
                hasNext = state.hasNext,
                isPrefetchingNext = state.isPrefetchingNext,
                savingProgressHint = state.savingProgressHint,
                onPrevChapter = viewModel::goToPreviousChapter,
                onNextChapter = viewModel::goToNextChapter,
                onOpenCatalog = { onOpenCatalog(state.bookId) },
                onOpenSettings = { showSettingsSheet = true },
            )
        }

        // —— 提示 ——
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    // —— 系统亮度（CUSTOM 时接管 window.screenBrightness） ——
    DisposableEffect(activity, state.settings.brightnessMode, state.settings.brightness) {
        val window = activity?.window
        if (window != null && state.settings.brightnessMode == BrightnessMode.CUSTOM) {
            val attrs = window.attributes
            attrs.screenBrightness = state.settings.brightness.toFloat().coerceIn(0.01f, 1f)
            window.attributes = attrs
        }
        onDispose {
            if (window != null) {
                val attrs = window.attributes
                attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = attrs
            }
        }
    }

    // —— 屏幕常亮 ——
    DisposableEffect(activity, state.settings.keepScreenOn) {
        val window = activity?.window
        if (window != null && state.settings.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // —— 沉浸全屏：工具栏隐藏时收起系统栏 ——
    val view = LocalView.current
    DisposableEffect(activity, view, state.toolbarVisible) {
        val window = activity?.window
        val controller = if (window != null) {
            WindowCompat.getInsetsController(window, view)
        } else {
            null
        }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (controller != null) {
            if (state.toolbarVisible) {
                controller.show(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }

    // —— ON_STOP 强制保存进度（契约 02 §8.6：Screen 必须调用） ——
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.forceSaveProgress()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // —— 快捷设置面板 ——
    ReaderSettingsSheet(
        visible = showSettingsSheet,
        settings = state.settings,
        onDismiss = { showSettingsSheet = false },
        onSettingsChange = { transform -> viewModel.updateSettings(transform) },
        onOpenFullSettings = {
            showSettingsSheet = false
            onOpenReaderSettings()
        },
    )

    // —— 简版书签面板（主 Agent 裁定 2） ——
    BookmarkSheet(
        visible = showBookmarkSheet,
        bookmarks = bookmarks,
        chapterTitle = state.chapterTitle,
        currentChapterIndex = state.chapterIndex,
        onDismiss = { showBookmarkSheet = false },
        onAddBookmark = { viewModel.addBookmark(selectedText = null) },
        onRemoveBookmark = { id -> viewModel.removeBookmark(id) },
        onJumpToBookmark = { bookmark ->
            showBookmarkSheet = false
            if (bookmark.chapterIndex != state.chapterIndex) {
                viewModel.jumpToChapter(bookmark.chapterIndex)
            }
        },
    )
}

private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
