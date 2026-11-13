// 简阅 · 站点规则管理（WP-U，对齐 Kotlin ui/rules/RuleManagerScreen）
// 列表展示 source_rules（域名/开关/版本/更新时间）；点击进入编辑；
// FAB 按域名新建。删除需确认；开关即改即存。
// ignore_for_file: deprecated_member_use

import 'package:flutter/material.dart';

import '../contracts.dart';
import '../core/app_result.dart';
import '../core/reader_error.dart';
import '../core/site_rule.dart';
import 'rule_edit_page.dart';
import 'theme.dart';
import 'widgets.dart';

class RuleManagerPage extends StatefulWidget {
  const RuleManagerPage({super.key, required this.services});

  final AppServices services;

  @override
  State<RuleManagerPage> createState() => _RuleManagerPageState();
}

class _RuleManagerPageState extends State<RuleManagerPage> {
  List<StoredSiteRule>? _rules;
  ReaderError? _error;

  @override
  void initState() {
    super.initState();
    _reload();
  }

  Future<void> _reload() async {
    try {
      final rules = await widget.services.rules.listRules();
      if (!mounted) return;
      setState(() {
        _rules = rules;
        _error = null;
      });
    } on ReaderError catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e;
        _rules = const <StoredSiteRule>[];
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = StorageError('DB_ERROR', '读取规则失败: $e');
        _rules = const <StoredSiteRule>[];
      });
    }
  }

  Future<void> _openEdit(String domain, {StoredSiteRule? existing}) async {
    final saved = await Navigator.of(context).push<bool>(
      MaterialPageRoute<bool>(
        builder: (_) => RuleEditPage(
          services: widget.services,
          initialDomain: domain,
          existing: existing,
        ),
      ),
    );
    if (saved == true) _reload();
  }

  Future<void> _createNew() async {
    final domain = await _askDomain();
    if (domain == null || domain.isEmpty) return;
    if (!mounted) return;
    final conflict = _rules
        ?.where((rule) => rule.domain == domain.trim().toLowerCase())
        .toList(growable: false);
    if (conflict != null && conflict.isNotEmpty) {
      await _openEdit(domain, existing: conflict.first);
      return;
    }
    await _openEdit(domain);
  }

  Future<String?> _askDomain() {
    final controller = TextEditingController();
    return showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('新建站点规则'),
        content: TextField(
          controller: controller,
          autofocus: true,
          keyboardType: TextInputType.url,
          decoration: const InputDecoration(
            labelText: '站点域名',
            hintText: '如 www.example.com',
          ),
          onSubmitted: (value) =>
              Navigator.of(dialogContext).pop(value.trim()),
        ),
        actions: <Widget>[
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () =>
                Navigator.of(dialogContext).pop(controller.text.trim()),
            child: const Text('下一步'),
          ),
        ],
      ),
    );
  }

  Future<void> _toggle(StoredSiteRule rule, bool enabled) async {
    final result = await widget.services.rules.setEnabled(rule.ruleId, enabled);
    final error = result.errorOrNull;
    if (error != null) {
      if (mounted) showErrorSnack(context, error);
      return;
    }
    _reload();
  }

  Future<void> _confirmDelete(StoredSiteRule rule) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('删除规则'),
        content: Text('删除 ${rule.domain} 的站点规则？该操作不可撤销。'),
        actions: <Widget>[
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: const Text('删除'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    final result = await widget.services.rules.deleteRule(rule.ruleId);
    final error = result.errorOrNull;
    if (!mounted) return;
    if (error != null) {
      showErrorSnack(context, error);
      return;
    }
    showAppSnack(context, '规则已删除');
    _reload();
  }

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);
    final ColorScheme scheme = theme.colorScheme;
    final List<StoredSiteRule>? rules = _rules;

    return Scaffold(
      appBar: AppBar(title: const Text('站点规则')),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: _createNew,
        icon: const Icon(Icons.add),
        label: const Text('新建规则'),
      ),
      body: _error != null
          ? Padding(
              padding: const EdgeInsets.all(AppSpacing.md),
              child: AppErrorBanner(error: _error, onRetry: _reload),
            )
          : rules == null
              ? const Center(child: CircularProgressIndicator())
              : rules.isEmpty
                  ? Center(
                      child: Padding(
                        padding: const EdgeInsets.all(AppSpacing.xl),
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: <Widget>[
                            Icon(Icons.rule_outlined,
                                size: 48, color: scheme.onSurfaceVariant),
                            const SizedBox(height: AppSpacing.md),
                            Text(
                              '还没有站点规则',
                              style: theme.textTheme.titleMedium,
                            ),
                            const SizedBox(height: AppSpacing.sm),
                            Text(
                              '为特定站点自定义书名/目录/正文的选取方式，'
                              '解析失败时可手动纠正。',
                              textAlign: TextAlign.center,
                              style: theme.textTheme.bodySmall?.copyWith(
                                color: scheme.onSurfaceVariant,
                              ),
                            ),
                          ],
                        ),
                      ),
                    )
                  : ListView.separated(
                      padding: const EdgeInsets.fromLTRB(
                        AppSpacing.screenH,
                        AppSpacing.screenV,
                        AppSpacing.screenH,
                        AppSpacing.xl,
                      ),
                      itemCount: rules.length,
                      separatorBuilder: (_, __) =>
                          const SizedBox(height: AppSpacing.sm),
                      itemBuilder: (BuildContext context, int index) {
                        final StoredSiteRule rule = rules[index];
                        return _ruleCard(theme, scheme, rule);
                      },
                    ),
    );
  }

  Widget _ruleCard(
    ThemeData theme,
    ColorScheme scheme,
    StoredSiteRule rule,
  ) {
    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () => _openEdit(rule.domain, existing: rule),
        child: Padding(
          padding: const EdgeInsets.all(AppSpacing.md),
          child: Row(
            children: <Widget>[
              Icon(
                rule.ruleId.startsWith('manual.')
                    ? Icons.handyman_outlined
                    : Icons.auto_fix_high_outlined,
                color: scheme.onSurfaceVariant,
              ),
              const SizedBox(width: AppSpacing.md),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: <Widget>[
                    Text(rule.domain, style: theme.textTheme.bodyLarge),
                    Text(
                      '${rule.ruleId} · v${rule.version} · '
                      '更新于 ${formatDateTime(rule.updatedAt)}',
                      style: theme.textTheme.bodySmall?.copyWith(
                        color: scheme.onSurfaceVariant,
                      ),
                    ),
                  ],
                ),
              ),
              Switch(
                value: rule.enabled,
                onChanged: (bool value) => _toggle(rule, value),
              ),
              IconButton(
                icon: const Icon(Icons.delete_outline, size: 20),
                tooltip: '删除',
                onPressed: () => _confirmDelete(rule),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
