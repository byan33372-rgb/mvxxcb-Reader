// 简阅 · 应用根组件
// MaterialApp（明暗双主题、墨青 seed）→ HomeShell（书架 / 发现 / 我的 三 Tab）。
// 页面间统一 Navigator.push(MaterialPageRoute) 传递 AppServices 与参数。
// ignore_for_file: deprecated_member_use

import 'package:flutter/material.dart';

import 'contracts.dart';
import 'ui/import_page.dart';
import 'ui/settings_page.dart';
import 'ui/shelf_page.dart';
import 'ui/theme.dart';

class JianYueApp extends StatelessWidget {
  const JianYueApp({super.key, required this.services});

  final AppServices services;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '简阅',
      debugShowCheckedModeBanner: false,
      theme: buildAppTheme(Brightness.light),
      darkTheme: buildAppTheme(Brightness.dark),
      themeMode: ThemeMode.system,
      home: HomeShell(services: services),
    );
  }
}

class HomeShell extends StatefulWidget {
  const HomeShell({super.key, required this.services});

  final AppServices services;

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int _index = 0;
  int _settingsEpoch = 0;

  void _selectTab(int index) {
    setState(() {
      _index = index;
      // 阅读器内改过的主题/字号，回到「我的」时重新读取，避免显示旧值。
      if (index == 2) _settingsEpoch++;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: IndexedStack(
        index: _index,
        children: <Widget>[
          ShelfPage(services: widget.services, onGoImport: () => _selectTab(1)),
          ImportPage(services: widget.services),
          SettingsPage(
            key: ValueKey<int>(_settingsEpoch),
            services: widget.services,
          ),
        ],
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _index,
        onDestinationSelected: _selectTab,
        destinations: const <NavigationDestination>[
          NavigationDestination(
            icon: Icon(Icons.menu_book_outlined),
            selectedIcon: Icon(Icons.menu_book),
            label: '书架',
          ),
          NavigationDestination(
            icon: Icon(Icons.explore_outlined),
            selectedIcon: Icon(Icons.explore),
            label: '发现',
          ),
          NavigationDestination(
            icon: Icon(Icons.person_outline),
            selectedIcon: Icon(Icons.person),
            label: '我的',
          ),
        ],
      ),
    );
  }
}
