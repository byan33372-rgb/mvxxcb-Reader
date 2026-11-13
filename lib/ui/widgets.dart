// 简阅 · 共享 UI 组件（封面/空态/错误横幅/徽标/提示等）
// ignore_for_file: deprecated_member_use

import 'package:flutter/material.dart';

import '../core/reader_error.dart';
import 'theme.dart';

/// 书籍封面：网络图 + 失败/空地址占位（不伪造图片）。
class AppCover extends StatelessWidget {
  const AppCover({
    super.key,
    required this.url,
    this.width,
    this.height,
    this.borderRadius = AppSpacing.radiusSm,
    this.iconSize = 24,
  });

  final String? url;
  final double? width;
  final double? height;
  final double borderRadius;
  final double iconSize;

  @override
  Widget build(BuildContext context) {
    final ColorScheme scheme = Theme.of(context).colorScheme;
    final BorderRadius radius = BorderRadius.circular(borderRadius);
    Widget placeholder = Container(
      width: width,
      height: height,
      color: scheme.surfaceContainerHigh,
      alignment: Alignment.center,
      child: Icon(
        Icons.menu_book_outlined,
        size: iconSize,
        color: scheme.onSurfaceVariant,
      ),
    );

    final String? src = url?.trim();
    if (src == null || src.isEmpty) {
      return ClipRRect(borderRadius: radius, child: placeholder);
    }
    return ClipRRect(
      borderRadius: radius,
      child: SizedBox(
        width: width,
        height: height,
        child: Image.network(
          src,
          fit: BoxFit.cover,
          width: width,
          height: height,
          errorBuilder:
              (BuildContext context, Object error, StackTrace? stack) =>
                  placeholder,
          loadingBuilder:
              (BuildContext context, Widget child, ImageChunkEvent? progress) {
                if (progress == null) return child;
                return Container(
                  width: width,
                  height: height,
                  color: scheme.surfaceContainerHigh,
                  alignment: Alignment.center,
                  child: const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                );
              },
        ),
      ),
    );
  }
}

/// 空态引导。
class AppEmptyState extends StatelessWidget {
  const AppEmptyState({
    super.key,
    required this.title,
    this.description,
    this.icon = Icons.menu_book_outlined,
    this.actionText,
    this.onAction,
  });

  final String title;
  final String? description;
  final IconData icon;
  final String? actionText;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    final ColorScheme scheme = Theme.of(context).colorScheme;
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(AppSpacing.lg),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            Icon(icon, size: 56, color: scheme.onSurfaceVariant),
            const SizedBox(height: AppSpacing.md),
            Text(
              title,
              style: Theme.of(context).textTheme.titleMedium,
              textAlign: TextAlign.center,
            ),
            if (description != null && description!.isNotEmpty) ...<Widget>[
              const SizedBox(height: AppSpacing.sm),
              Text(
                description!,
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                  color: scheme.onSurfaceVariant,
                ),
                textAlign: TextAlign.center,
              ),
            ],
            if (actionText != null && onAction != null) ...<Widget>[
              const SizedBox(height: AppSpacing.lg),
              FilledButton.tonal(onPressed: onAction, child: Text(actionText!)),
            ],
          ],
        ),
      ),
    );
  }
}

/// 错误横幅：统一走 userMessageOf 文案。
class AppErrorBanner extends StatelessWidget {
  const AppErrorBanner({
    super.key,
    this.error,
    this.message,
    this.onRetry,
    this.retryText = '重试',
    this.onDismiss,
    this.secondaryText,
    this.onSecondary,
  }) : assert(error != null || message != null);

  final ReaderError? error;
  final String? message;
  final VoidCallback? onRetry;
  final String retryText;
  final VoidCallback? onDismiss;
  final String? secondaryText;
  final VoidCallback? onSecondary;

  @override
  Widget build(BuildContext context) {
    final ColorScheme scheme = Theme.of(context).colorScheme;
    final String text = message ?? (error == null ? '' : userMessageOf(error!));
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: scheme.errorContainer,
        borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Icon(Icons.error_outline, color: scheme.onErrorContainer, size: 20),
          const SizedBox(width: AppSpacing.sm),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: <Widget>[
                Text(
                  text,
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                    color: scheme.onErrorContainer,
                  ),
                ),
                if (onRetry != null ||
                    onSecondary != null ||
                    onDismiss != null) ...<Widget>[
                  const SizedBox(height: AppSpacing.xs),
                  Wrap(
                    spacing: AppSpacing.sm,
                    children: <Widget>[
                      if (onRetry != null)
                        TextButton(
                          onPressed: onRetry,
                          style: TextButton.styleFrom(
                            foregroundColor: scheme.onErrorContainer,
                          ),
                          child: Text(retryText),
                        ),
                      if (onSecondary != null)
                        TextButton(
                          onPressed: onSecondary,
                          style: TextButton.styleFrom(
                            foregroundColor: scheme.onErrorContainer,
                          ),
                          child: Text(secondaryText ?? '继续'),
                        ),
                      if (onDismiss != null)
                        TextButton(
                          onPressed: onDismiss,
                          style: TextButton.styleFrom(
                            foregroundColor: scheme.onErrorContainer,
                          ),
                          child: const Text('知道了'),
                        ),
                    ],
                  ),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// 加载态（居中）。
class AppLoading extends StatelessWidget {
  const AppLoading({super.key, this.text = '正在加载…'});

  final String text;

  @override
  Widget build(BuildContext context) {
    final ColorScheme scheme = Theme.of(context).colorScheme;
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          const SizedBox(
            width: 24,
            height: 24,
            child: CircularProgressIndicator(strokeWidth: 2.4),
          ),
          const SizedBox(height: AppSpacing.md),
          Text(
            text,
            style: Theme.of(
              context,
            ).textTheme.bodyMedium?.copyWith(color: scheme.onSurfaceVariant),
          ),
        ],
      ),
    );
  }
}

/// 行内加载条（解析阶段等）。
class AppInlineLoading extends StatelessWidget {
  const AppInlineLoading({super.key, required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: <Widget>[
        const SizedBox(
          width: 16,
          height: 16,
          child: CircularProgressIndicator(strokeWidth: 2),
        ),
        const SizedBox(width: AppSpacing.sm),
        Expanded(
          child: Text(text, style: Theme.of(context).textTheme.bodyMedium),
        ),
      ],
    );
  }
}

/// 小徽标（下载态/状态 chip）。
class AppBadge extends StatelessWidget {
  const AppBadge({
    super.key,
    required this.label,
    required this.background,
    required this.foreground,
  });

  final String label;
  final Color background;
  final Color foreground;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(
        horizontal: AppSpacing.sm,
        vertical: 2,
      ),
      decoration: BoxDecoration(
        color: background,
        borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
      ),
      child: Text(
        label,
        style: Theme.of(context).textTheme.labelSmall?.copyWith(
          color: foreground,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}

/// 带标题的克制卡片。
class SectionCard extends StatelessWidget {
  const SectionCard({
    super.key,
    this.title,
    this.trailing,
    required this.child,
    this.padding = const EdgeInsets.all(AppSpacing.md),
  });

  final String? title;
  final Widget? trailing;
  final Widget child;
  final EdgeInsetsGeometry padding;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: padding,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            if (title != null)
              Padding(
                padding: const EdgeInsets.only(bottom: AppSpacing.sm),
                child: Row(
                  children: <Widget>[
                    Expanded(
                      child: Text(
                        title!,
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                    ),
                    if (trailing != null) trailing!,
                  ],
                ),
              ),
            child,
          ],
        ),
      ),
    );
  }
}

// ---------------- 提示与格式化 ----------------

void showAppSnack(BuildContext context, String message) {
  final ScaffoldMessengerState? messenger = ScaffoldMessenger.maybeOf(context);
  messenger
    ?..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(content: Text(message)));
}

void showErrorSnack(BuildContext context, ReaderError error) {
  showAppSnack(context, userMessageOf(error));
}

String formatDateTime(int? epochMillis) {
  if (epochMillis == null || epochMillis <= 0) return '—';
  final DateTime time = DateTime.fromMillisecondsSinceEpoch(epochMillis);
  String two(int value) => value.toString().padLeft(2, '0');
  return '${time.year}-${two(time.month)}-${two(time.day)} ${two(time.hour)}:${two(time.minute)}';
}

String formatFileSize(int bytes) {
  if (bytes >= 1 << 20) return '${(bytes / 1048576).toStringAsFixed(1)} MB';
  if (bytes >= 1 << 10) return '${(bytes / 1024).toStringAsFixed(1)} KB';
  return '$bytes B';
}

String nonEmptyOr(String? value, String fallback) {
  final String? trimmed = value?.trim();
  return (trimmed == null || trimmed.isEmpty) ? fallback : trimmed;
}
