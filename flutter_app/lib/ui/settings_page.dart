// 简阅 · 我的（设置）
// 阅读设置：背景主题/字号/行距即时生效并持久化；采集中心入口；关于（版本 1.0.0 Flutter 版）。
// ignore_for_file: deprecated_member_use

import 'dart:async';

import 'package:flutter/material.dart';

import '../contracts.dart';
import '../core/reader_error.dart';
import 'crawl_center_page.dart';
import 'theme.dart';
import 'widgets.dart';

class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key, required this.services});

  final AppServices services;

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  ReaderSettings? _settings;
  ReaderError? _loadError;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _loadError = null;
    });
    try {
      final ReaderSettings settings = await widget.services.settings.load();
      if (!mounted) return;
      setState(() {
        _settings = settings;
        _loading = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _loadError = UnknownError('读取设置失败：$error');
      });
    }
  }

  Future<void> _save(ReaderSettings settings) async {
    try {
      await widget.services.settings.save(settings);
    } catch (error) {
      if (!mounted) return;
      showAppSnack(context, '设置保存失败：$error');
    }
  }

  void _setAndSave(ReaderSettings next) {
    setState(() => _settings = next);
    unawaited(_save(next));
  }

  void _setLocal(ReaderSettings next) {
    setState(() => _settings = next);
  }

  void _openCrawlCenter() {
    Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => CrawlCenterPage(services: widget.services),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final ReaderSettings? settings = _settings;
    return Scaffold(
      appBar: AppBar(title: const Text('我的')),
      body: _loading && settings == null
          ? const AppLoading(text: '正在加载设置…')
          : settings == null
          ? Padding(
              padding: const EdgeInsets.all(AppSpacing.md),
              child: AppErrorBanner(error: _loadError, onRetry: _load),
            )
          : ListView(
              padding: const EdgeInsets.fromLTRB(
                AppSpacing.screenH,
                AppSpacing.screenV,
                AppSpacing.screenH,
                AppSpacing.xl,
              ),
              children: <Widget>[
                _ReaderPreview(settings: settings),
                const SizedBox(height: AppSpacing.cardGap),
                _readingSettingsCard(settings),
                const SizedBox(height: AppSpacing.cardGap),
                _entryCard(
                  icon: Icons.cloud_download_outlined,
                  title: '采集中心',
                  subtitle: '整本下载任务：进度/暂停/重试/TXT 导出',
                  onTap: _openCrawlCenter,
                ),
                const SizedBox(height: AppSpacing.cardGap),
                _aboutCard(context),
              ],
            ),
    );
  }

  Widget _readingSettingsCard(ReaderSettings settings) {
    final ThemeData theme = Theme.of(context);
    final ColorScheme scheme = theme.colorScheme;
    return SectionCard(
      title: '阅读设置',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text('背景主题', style: theme.textTheme.bodyMedium),
          const SizedBox(height: AppSpacing.sm),
          Wrap(
            spacing: AppSpacing.md,
            runSpacing: AppSpacing.sm,
            children: kReadingThemes
                .map((ReadingThemeSpec spec) {
                  final bool selected = settings.backgroundTheme == spec.index;
                  return InkWell(
                    borderRadius: BorderRadius.circular(28),
                    onTap: () => _setAndSave(
                      settings.copyWith(backgroundTheme: spec.index),
                    ),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: <Widget>[
                        Container(
                          width: 44,
                          height: 44,
                          decoration: BoxDecoration(
                            color: spec.background,
                            shape: BoxShape.circle,
                            border: Border.all(
                              color: selected
                                  ? scheme.primary
                                  : scheme.outlineVariant,
                              width: selected ? 3 : 1,
                            ),
                          ),
                          alignment: Alignment.center,
                          child: Text(
                            spec.label,
                            style: TextStyle(
                              color: spec.textPrimary,
                              fontSize: 12,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ),
                        Text(
                          spec.label,
                          style: theme.textTheme.labelSmall?.copyWith(
                            color: selected
                                ? scheme.primary
                                : scheme.onSurfaceVariant,
                          ),
                        ),
                      ],
                    ),
                  );
                })
                .toList(growable: false),
          ),
          const Divider(height: AppSpacing.lg),
          Row(
            children: <Widget>[
              Text('字号', style: theme.textTheme.bodyMedium),
              const Spacer(),
              TextButton(
                onPressed: () {
                  final double next =
                      (settings.fontSize - AppFontSizes.readerStep).clamp(
                        AppFontSizes.readerMin,
                        AppFontSizes.readerMax,
                      );
                  _setAndSave(settings.copyWith(fontSize: next));
                },
                child: const Text('A−'),
              ),
              SizedBox(
                width: 42,
                child: Text(
                  settings.fontSize.toStringAsFixed(0),
                  textAlign: TextAlign.center,
                  style: theme.textTheme.titleMedium,
                ),
              ),
              TextButton(
                onPressed: () {
                  final double next =
                      (settings.fontSize + AppFontSizes.readerStep).clamp(
                        AppFontSizes.readerMin,
                        AppFontSizes.readerMax,
                      );
                  _setAndSave(settings.copyWith(fontSize: next));
                },
                child: const Text('A+'),
              ),
            ],
          ),
          Text(
            '行距 ${settings.lineHeightMultiplier.toStringAsFixed(1)}x',
            style: theme.textTheme.bodyMedium,
          ),
          Slider(
            value: settings.lineHeightMultiplier.clamp(1.2, 2.4),
            min: 1.2,
            max: 2.4,
            divisions: 12,
            label: '${settings.lineHeightMultiplier.toStringAsFixed(1)}x',
            onChanged: (double value) =>
                _setLocal(settings.copyWith(lineHeightMultiplier: value)),
            onChangeEnd: (double value) {
              _save(
                (_settings ?? settings).copyWith(lineHeightMultiplier: value),
              );
            },
          ),
          Text(
            '主题、字号、行距即时生效，阅读器同步使用',
            style: theme.textTheme.bodySmall?.copyWith(
              color: scheme.onSurfaceVariant,
            ),
          ),
        ],
      ),
    );
  }

  Widget _entryCard({
    required IconData icon,
    required String title,
    required String subtitle,
    required VoidCallback onTap,
  }) {
    final ThemeData theme = Theme.of(context);
    final ColorScheme scheme = theme.colorScheme;
    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.md),
          child: Row(
            children: <Widget>[
              Icon(icon, color: scheme.onSurfaceVariant),
              const SizedBox(width: AppSpacing.md),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    Text(title, style: theme.textTheme.bodyLarge),
                    Text(
                      subtitle,
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: scheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                ),
              ),
              const Icon(Icons.chevron_right, size: 20),
            ],
          ),
        ),
      ),
    );
  }

  Widget _aboutCard(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final ColorScheme scheme = theme.colorScheme;
    return SectionCard(
      title: '关于',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Row(
            children: <Widget>[
              Container(
                width: 44,
                height: 44,
                decoration: BoxDecoration(
                  color: scheme.primaryContainer,
                  borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
                ),
                alignment: Alignment.center,
                child: Icon(Icons.menu_book, color: scheme.onPrimaryContainer),
              ),
              const SizedBox(width: AppSpacing.md),
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: <Widget>[
                  Text('简阅', style: theme.textTheme.titleMedium),
                  Text(
                    '版本 1.0.0（Flutter 版）',
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: scheme.onSurfaceVariant,
                    ),
                  ),
                ],
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.sm),
          Text(
            '本地优先的小说阅读器：粘贴网址导入、连续阅读、整本下载与 TXT 导出。',
            style: theme.textTheme.bodySmall?.copyWith(
              color: scheme.onSurfaceVariant,
            ),
          ),
        ],
      ),
    );
  }
}

class _ReaderPreview extends StatelessWidget {
  const _ReaderPreview({required this.settings});

  final ReaderSettings settings;

  @override
  Widget build(BuildContext context) {
    final ReadingThemeSpec spec = readingThemeOf(settings.backgroundTheme);
    final TextStyle base = TextStyle(
      fontSize: settings.fontSize,
      height: settings.lineHeightMultiplier,
      color: spec.textPrimary,
    );
    return Container(
      width: double.infinity,
      padding: EdgeInsets.symmetric(
        horizontal: settings.horizontalPaddingDp,
        vertical: AppSpacing.md,
      ),
      decoration: BoxDecoration(
        color: spec.background,
        borderRadius: BorderRadius.circular(AppSpacing.radiusLg),
        border: Border.all(color: Theme.of(context).colorScheme.outlineVariant),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text(
            '第 3 章 预览',
            style: base.copyWith(
              fontSize: settings.fontSize + 2,
              fontWeight: FontWeight.w600,
            ),
          ),
          const SizedBox(height: AppSpacing.sm),
          Text('这是正文预览。主题、字号与行距会立即应用到阅读器，并在下次打开时保留。', style: base),
        ],
      ),
    );
  }
}
