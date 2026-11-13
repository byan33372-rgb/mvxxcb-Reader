// 简阅 · 应用主题与视觉 token（Flutter 版）
// 设计基线：墨青主色（#1E6B5A）+ Material 3 明暗双主题；卡片克制、无重阴影；
// 阅读区五套主题与 Kotlin 版一致：白 / 米 / 绿 / 深灰 / 黑。
// ignore_for_file: deprecated_member_use

import 'package:flutter/material.dart';

// ---------------- 品牌与基础色 ----------------

abstract final class AppColors {
  /// 墨青主色（品牌 seed）
  static const Color inkTeal = Color(0xFF1E6B5A);

  /// 书架/页面底色（浅色）
  static const Color lightScaffold = Color(0xFFF6F7F4);

  /// 书架/页面底色（深色）
  static const Color darkScaffold = Color(0xFF101312);
}

// ---------------- 间距 / 字号 / 圆角 ----------------

abstract final class AppSpacing {
  static const double xs = 4;
  static const double sm = 8;
  static const double md = 16;
  static const double lg = 24;
  static const double xl = 32;

  /// 屏幕水平内边距
  static const double screenH = 16;

  /// 屏幕垂直内边距
  static const double screenV = 12;

  /// 列表卡片间距
  static const double cardGap = 12;

  /// 最小触控目标（无障碍）
  static const double touchTarget = 48;

  static const double radiusSm = 8;
  static const double radiusMd = 12;
  static const double radiusLg = 16;
}

abstract final class AppFontSizes {
  static const double caption = 11;
  static const double label = 12;
  static const double body = 14;
  static const double bodyLarge = 16;
  static const double title = 18;
  static const double headline = 22;

  /// 阅读器字号范围（与 Kotlin 版一致 14–28）
  static const double readerMin = 14;
  static const double readerMax = 28;
  static const double readerDefault = 18;

  static const double readerStep = 1;
}

// ---------------- 阅读主题（五套，背景/正文对） ----------------

@immutable
class ReadingThemeSpec {
  const ReadingThemeSpec({
    required this.index,
    required this.label,
    required this.background,
    required this.textPrimary,
    required this.textSecondary,
    required this.isDark,
  });

  /// 与 ReaderSettings.backgroundTheme 对应：0 白 1 米 2 绿 3 灰 4 黑
  final int index;
  final String label;
  final Color background;
  final Color textPrimary;
  final Color textSecondary;
  final bool isDark;
}

const List<ReadingThemeSpec> kReadingThemes = <ReadingThemeSpec>[
  ReadingThemeSpec(
    index: 0,
    label: '白',
    background: Color(0xFFFAFAF8),
    textPrimary: Color(0xFF1F2421),
    textSecondary: Color(0xFF5C635E),
    isDark: false,
  ),
  ReadingThemeSpec(
    index: 1,
    label: '米',
    background: Color(0xFFF5EDE0),
    textPrimary: Color(0xFF3B3226),
    textSecondary: Color(0xFF7A6A55),
    isDark: false,
  ),
  ReadingThemeSpec(
    index: 2,
    label: '绿',
    background: Color(0xFFDCE8D7),
    textPrimary: Color(0xFF26312A),
    textSecondary: Color(0xFF56655A),
    isDark: false,
  ),
  ReadingThemeSpec(
    index: 3,
    label: '深灰',
    background: Color(0xFF2A2A2A),
    textPrimary: Color(0xFFC9C9C9),
    textSecondary: Color(0xFF9A9A9A),
    isDark: true,
  ),
  ReadingThemeSpec(
    index: 4,
    label: '黑',
    background: Color(0xFF000000),
    textPrimary: Color(0xFFBDBDBD),
    textSecondary: Color(0xFF8A8A8A),
    isDark: true,
  ),
];

ReadingThemeSpec readingThemeOf(int backgroundTheme) {
  if (backgroundTheme < 0 || backgroundTheme >= kReadingThemes.length) {
    return kReadingThemes[1];
  }
  return kReadingThemes[backgroundTheme];
}

// ---------------- 语义状态色（成功/警告，M3 ColorScheme 未覆盖） ----------------

@immutable
class AppSemanticColors extends ThemeExtension<AppSemanticColors> {
  const AppSemanticColors({
    required this.success,
    required this.onSuccess,
    required this.successContainer,
    required this.onSuccessContainer,
    required this.warning,
    required this.onWarning,
    required this.warningContainer,
    required this.onWarningContainer,
  });

  final Color success;
  final Color onSuccess;
  final Color successContainer;
  final Color onSuccessContainer;
  final Color warning;
  final Color onWarning;
  final Color warningContainer;
  final Color onWarningContainer;

  static const AppSemanticColors light = AppSemanticColors(
    success: Color(0xFF1E6C41),
    onSuccess: Color(0xFFFFFFFF),
    successContainer: Color(0xFFB0F0BE),
    onSuccessContainer: Color(0xFF00210E),
    warning: Color(0xFF7A5900),
    onWarning: Color(0xFFFFFFFF),
    warningContainer: Color(0xFFFFE08C),
    onWarningContainer: Color(0xFF261A00),
  );

  static const AppSemanticColors dark = AppSemanticColors(
    success: Color(0xFF84DA9D),
    onSuccess: Color(0xFF00391D),
    successContainer: Color(0xFF005328),
    onSuccessContainer: Color(0xFFB0F0BE),
    warning: Color(0xFFF3BF3B),
    onWarning: Color(0xFF412D00),
    warningContainer: Color(0xFF5E4300),
    onWarningContainer: Color(0xFFFFE08C),
  );

  @override
  AppSemanticColors copyWith({
    Color? success,
    Color? onSuccess,
    Color? successContainer,
    Color? onSuccessContainer,
    Color? warning,
    Color? onWarning,
    Color? warningContainer,
    Color? onWarningContainer,
  }) {
    return AppSemanticColors(
      success: success ?? this.success,
      onSuccess: onSuccess ?? this.onSuccess,
      successContainer: successContainer ?? this.successContainer,
      onSuccessContainer: onSuccessContainer ?? this.onSuccessContainer,
      warning: warning ?? this.warning,
      onWarning: onWarning ?? this.onWarning,
      warningContainer: warningContainer ?? this.warningContainer,
      onWarningContainer: onWarningContainer ?? this.onWarningContainer,
    );
  }

  @override
  AppSemanticColors lerp(ThemeExtension<AppSemanticColors>? other, double t) {
    if (other is! AppSemanticColors) return this;
    return AppSemanticColors(
      success: Color.lerp(success, other.success, t) ?? success,
      onSuccess: Color.lerp(onSuccess, other.onSuccess, t) ?? onSuccess,
      successContainer:
          Color.lerp(successContainer, other.successContainer, t) ??
          successContainer,
      onSuccessContainer:
          Color.lerp(onSuccessContainer, other.onSuccessContainer, t) ??
          onSuccessContainer,
      warning: Color.lerp(warning, other.warning, t) ?? warning,
      onWarning: Color.lerp(onWarning, other.onWarning, t) ?? onWarning,
      warningContainer:
          Color.lerp(warningContainer, other.warningContainer, t) ??
          warningContainer,
      onWarningContainer:
          Color.lerp(onWarningContainer, other.onWarningContainer, t) ??
          onWarningContainer,
    );
  }
}

extension AppThemeX on BuildContext {
  AppSemanticColors get semanticColors =>
      Theme.of(this).extension<AppSemanticColors>() ?? AppSemanticColors.light;
}

// ---------------- 明暗主题 ----------------

ThemeData buildAppTheme(Brightness brightness) {
  final bool isDark = brightness == Brightness.dark;
  final ColorScheme scheme =
      ColorScheme.fromSeed(
        seedColor: AppColors.inkTeal,
        brightness: brightness,
      ).copyWith(
        surface: isDark ? const Color(0xFF161918) : const Color(0xFFFCFCF9),
        onSurface: isDark ? const Color(0xFFE3E6E2) : const Color(0xFF1B1F1D),
        surfaceContainerLowest: isDark
            ? const Color(0xFF0C0F0E)
            : const Color(0xFFFFFFFF),
        surfaceContainerLow: isDark
            ? const Color(0xFF1B1F1D)
            : const Color(0xFFF5F6F3),
        surfaceContainer: isDark
            ? const Color(0xFF202422)
            : const Color(0xFFEFF1ED),
        surfaceContainerHigh: isDark
            ? const Color(0xFF2A2E2C)
            : const Color(0xFFE9EBE7),
        surfaceContainerHighest: isDark
            ? const Color(0xFF353936)
            : const Color(0xFFE3E5E1),
      );

  final ThemeData base = ThemeData(
    useMaterial3: true,
    brightness: brightness,
    colorScheme: scheme,
    scaffoldBackgroundColor: isDark
        ? AppColors.darkScaffold
        : AppColors.lightScaffold,
    visualDensity: VisualDensity.standard,
  );

  return base.copyWith(
    textTheme: base.textTheme.apply(
      bodyColor: scheme.onSurface,
      displayColor: scheme.onSurface,
    ),
    appBarTheme: AppBarTheme(
      backgroundColor: scheme.surface,
      foregroundColor: scheme.onSurface,
      surfaceTintColor: Colors.transparent,
      elevation: 0,
      scrolledUnderElevation: 1,
      centerTitle: false,
      titleTextStyle: base.textTheme.titleLarge?.copyWith(
        color: scheme.onSurface,
        fontSize: AppFontSizes.title,
        fontWeight: FontWeight.w600,
      ),
    ),
    cardTheme: CardThemeData(
      elevation: 0,
      margin: EdgeInsets.zero,
      color: scheme.surfaceContainerLow,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(AppSpacing.radiusLg),
        side: BorderSide(color: scheme.outlineVariant.withValues(alpha: 0.5)),
      ),
    ),
    dividerTheme: DividerThemeData(
      color: scheme.outlineVariant,
      thickness: 0.5,
      space: AppSpacing.md,
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: scheme.surfaceContainerLow,
      contentPadding: const EdgeInsets.symmetric(
        horizontal: AppSpacing.md,
        vertical: 12,
      ),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
        borderSide: BorderSide(color: scheme.outlineVariant),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
        borderSide: BorderSide(color: scheme.outlineVariant),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
        borderSide: BorderSide(color: scheme.primary, width: 1.6),
      ),
    ),
    navigationBarTheme: NavigationBarThemeData(
      backgroundColor: scheme.surface,
      surfaceTintColor: Colors.transparent,
      indicatorColor: scheme.secondaryContainer,
      elevation: 0,
      height: 68,
      labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
    ),
    snackBarTheme: SnackBarThemeData(
      behavior: SnackBarBehavior.floating,
      backgroundColor: scheme.inverseSurface,
      contentTextStyle: base.textTheme.bodyMedium?.copyWith(
        color: scheme.onInverseSurface,
      ),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
      ),
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: scheme.surfaceContainerLow,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(AppSpacing.radiusLg),
      ),
    ),
    bottomSheetTheme: BottomSheetThemeData(
      backgroundColor: scheme.surfaceContainerLow,
      surfaceTintColor: Colors.transparent,
      showDragHandle: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
    ),
    chipTheme: base.chipTheme.copyWith(
      side: BorderSide(color: scheme.outlineVariant),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
      ),
    ),
    extensions: <ThemeExtension<dynamic>>[
      isDark ? AppSemanticColors.dark : AppSemanticColors.light,
    ],
  );
}
