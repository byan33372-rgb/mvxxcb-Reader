package com.mvxxcb.reader.reader.engine

/** 排版参数（由 ReaderSettings 推导；sp/dp → px 由调用方按 density+fontScale 换算后传入） */
data class ReaderTypography(
    val fontFamily: com.mvxxcb.reader.domain.model.ReaderFontFamily,
    val fontSizeSp: Float,
    val lineHeightMultiplier: Float,
    val paragraphSpacingDp: Float,
    val bold: Boolean,
    val horizontalPaddingDp: Int,
)

data class ReaderLayoutSpec(
    val typography: ReaderTypography,
    val viewportWidthPx: Int,        // 内容区（已扣左右边距）
    val viewportHeightPx: Int,
    val fontScale: Float,            // 系统字体缩放
)

/** 一页内容范围：块区间 + 起止字符（跨章恢复与页码↔分数换算的依据） */
data class PageSpan(
    val blockStartIndex: Int,
    val blockEndIndexExclusive: Int,
    val startCharOffset: Int,        // 相对章首累计字符（含图片占位 1 字符）
    val endCharOffsetExclusive: Int,
)

data class PaginationResult(
    val pages: List<PageSpan>,
    val layoutVersion: Long,         // 排版参数指纹（参数变化 → 版本变化 → 缓存失效）
    val overflow: Boolean,           // 内容超出 maxContentChars 被截断提示
)

/**
 * 分页器实现要求：基于 android.graphics.TextPaint 测量（可在 Dispatchers.Default 执行，
 * 不依赖 Compose TextMeasurer；与 Compose 绘制必须使用同一字体族/字号/行距参数，保证度量一致）。
 */
interface TextPager {
    fun paginate(
        content: com.mvxxcb.reader.domain.model.ReaderContent,
        spec: ReaderLayoutSpec,
    ): PaginationResult
}
