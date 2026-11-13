# 简阅·Flutter（mvxxcb-Reader）

> **分支说明**：本分支（`flutter`）为**纯 Flutter 工程**——仓库根目录即 Flutter 项目，不含 Kotlin 版源码；Kotlin 版见 `kotlin` 分支，双端合一的完整仓库见 `main`。

一款 Android 小说阅读器（Flutter 重写版）：粘贴小说网址，自动识别整本小说、抓取目录与正文，生成可离线阅读的本地书籍。

内置四层解析引擎，支持从详情页、目录页或**任意章节**网址反向识别整本书；支持整本采集为 TXT、WorkManager 后台隔离采集、跨章节连续阅读与阅读进度精确恢复。

---

## 功能

### 导入与识别
- 粘贴 / 手输 / 系统分享小说网址
- 详情页、目录页、章节页三种入口；任意章节网址反向识别整本
- 四层解析：人工规则 → 结构化（JSON-LD / OG / meta）→ 站点规则 → 通用智能提取（带置信度）
- 站点规则可视化编辑器：按域名配置 12 个选择器、逐字段校验回显、编码提示（GBK/GB18030/BIG5）

### 目录与采集
- 完整目录：分页自动合并、去重、排序、分卷识别
- 整本采集：断点续传 / 暂停 / 继续 / 取消 / 单章重试
- **WorkManager 后台隔离采集（Android）**：前台服务 + 常驻通知，App 退后台/被杀后继续；前台与后台以 DB 租约互斥，不会双跑
- 自适应限速：起始 2s → 连续成功降档至 1s；失败翻倍至 10s 封顶；429 按 Retry-After 冷却

### TXT 与阅读
- 采集完成自动生成 UTF-8 TXT（原子写 + SHA-256），自动进书架
- 连续阅读 / 滚动 / 模拟翻页三种模式；五套主题；字号、行距、边距可调
- 阅读进度精确到章节内字符位置，退出重进准确恢复；断网可读已缓存内容

### 其他
- 诊断反馈：设备与数据库统计 / 采集任务 / 阅读设置 / 最近操作日志，URL 脱敏，系统分享导出
- Pixel 全面屏适配；深浅色主题跟随系统；数据仅存本地

---

## 环境要求

- **Flutter ≥3.38（Dart ≥3.5）**：下限由 `workmanager 0.10.x` 决定；不锁定 `pubspec.lock`，各版本 Flutter 自行解析兼容依赖
- JDK 17+、Android SDK 36（minSdk 26）

## 构建与运行

```bash
flutter pub get
flutter run                      # 调试运行
flutter build apk --release      # 产物：build/app/outputs/flutter-apk/app-release.apk
```

便捷构建脚本（自动探测 Flutter；仅在源码路径含非 ASCII 字符时启用 ASCII 镜像；产物按 pubspec 版本自动命名到 `artifacts/`）：

```bash
bash scripts/flutter_build_ascii.sh          # release
bash scripts/flutter_build_ascii.sh --debug  # debug
```

### 开发环境（换机指引）

- 依赖一律来自环境变量与 PATH：`FLUTTER_BIN`、`JAVA_HOME`、`ANDROID_HOME`/`ANDROID_SDK_ROOT`。仓库内**不含任何机器绝对路径**。
- 机器级个性化配置放 `scripts/local-env.sh`（已 gitignore，模板见 `scripts/local-env.example.sh`）。
- 仅当源码路径含非 ASCII 字符时（Windows 下 Flutter AOT 的已知限制），脚本才自动启用 ASCII 镜像；可用 `JIANYUE_BUILD_DIR` 指定镜像目录。

## 测试

```bash
flutter analyze
flutter test
```

当前 69 个测试全绿（解析 / 目录合并 / TXT / URL / 站点规则 Schema / 自适应限速 / 诊断 / DB v2 与租约 / v1→v2 迁移）。

## 代码结构

| 路径 | 说明 |
|---|---|
| `lib/contracts.dart` | 冻结契约（领域模型 + 服务接口） |
| `lib/impl/` | 引擎层：安全 HTTP / 自适应限速 / 规则引擎 / 解析 / sqflite / 采集内核 / 后台调度 / 诊断 |
| `lib/ui/` | 界面层：书架 / 导入 / 详情 / 目录 / 阅读器 / 采集中心 / 站点规则 / 诊断 |
| `lib/core/` | 规则模型、错误体系、环形日志 |
| `test/` | 单元测试 |
| `android/app/src/main/baseline-prof.txt` | 宿主侧 Baseline Profile 补充 |
| `docs/` | 产品设计与历史文档（跨分支保留） |

## APK

`artifacts/简阅-Flutter-1.1.0.apk`（包名 `com.mvxxcb.reader.flutter`，可与 Kotlin 版共存安装）。

## 已知限制

1. **iOS 后台采集未接入**：WorkManager 后台隔离仅 Android；其他平台前台队列执行。
2. **后台进度通知为简化版**：静态文案，不含逐章百分比（未引入 `flutter_local_notifications`）。
3. **规则清洗一期口径**：编辑器仅支持逐行正则（regex）清洗；`selector`/`whitespace` 需导入规则 JSON。
4. **Baseline Profile**：仅宿主侧补充（MainActivity / androidx.startup / WorkManager）；Dart AOT 启动热路径由引擎自带 profile 覆盖。
5. **诊断反馈日志口径**：仅应用内环形缓冲（最近 120 行），不含系统 logcat；URL 一律脱敏。

---

## 许可证

[MIT](LICENSE)

## 声明

本应用仅用于个人阅读使用，不提供任何小说内容。采集功能仅保存用户有权访问且允许保存的网页内容，遵守合理请求频率，不绕过任何付费墙、登录验证、验证码或其他访问控制。请遵守目标网站的服务条款及当地法律法规。