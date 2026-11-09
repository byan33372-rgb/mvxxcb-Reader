package com.mvxxcb.reader.reader.theme

import com.mvxxcb.reader.domain.model.BackgroundTheme

/**
 * 五套阅读主题预设（契约 02 §9）。
 * 注意：色值为 WP-M 按「正文与背景对比度 ≥ 4.5:1（WCAG AA）」给出的基线，
 * WP-U 视觉验收可微调；微调时必须保持对比度达标。
 */
data class ReaderTheme(
    val background: Long,        // ARGB
    val textPrimary: Long,
    val textSecondary: Long,
    val isDark: Boolean,
)

object ReaderThemes {
    val WHITE: ReaderTheme = ReaderTheme(
        background = 0xFFFFFFFF, textPrimary = 0xFF1A1A1A, textSecondary = 0xFF666666, isDark = false,
    )
    val CREAM: ReaderTheme = ReaderTheme(
        background = 0xFFF8F1E3, textPrimary = 0xFF2E2519, textSecondary = 0xFF6E5B44, isDark = false,
    )
    val EYE_CARE_GREEN: ReaderTheme = ReaderTheme(
        background = 0xFFC7EDCC, textPrimary = 0xFF1F3320, textSecondary = 0xFF4A6B4C, isDark = false,
    )
    val DARK_GRAY: ReaderTheme = ReaderTheme(
        background = 0xFF2B2B2B, textPrimary = 0xFFE8E4DE, textSecondary = 0xFFA8A29B, isDark = true,
    )
    val BLACK: ReaderTheme = ReaderTheme(
        background = 0xFF000000, textPrimary = 0xFFEDEAE6, textSecondary = 0xFFA09A94, isDark = true,
    )

    fun of(theme: BackgroundTheme): ReaderTheme = when (theme) {
        BackgroundTheme.WHITE -> WHITE
        BackgroundTheme.CREAM -> CREAM
        BackgroundTheme.EYE_CARE_GREEN -> EYE_CARE_GREEN
        BackgroundTheme.DARK_GRAY -> DARK_GRAY
        BackgroundTheme.BLACK -> BLACK
    }
}
