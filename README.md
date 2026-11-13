# 简阅 · mvxxcb-Reader

一款 Android 原生小说阅读器：粘贴小说网址，自动识别整本小说、抓取目录与正文，生成可离线阅读的本地书籍。

> **分支说明**：`main` 为双端合一仓库（Kotlin 版 + Flutter 版）；仅需 Kotlin 版请用 `kotlin` 分支；纯 Flutter 工程（根目录即 Flutter 项目）请用 `flutter` 分支。

内置多网站解析引擎，支持从详情页、目录页或**任意章节**网址反向识别整本书；支持整本采集为 TXT、跨章节连续阅读与阅读进度精确恢复。

---

## 功能

### 导入与识别
- 粘贴 / 手输 / 系统分享小说网址
- 支持详情页、目录页、章节页三种入口
- 粘贴**任意章节**网址即可反向识别所属小说、详情页与完整目录
- 自动提取书名、作者、封面、简介、连载状态
- 多层解析：结构化数据（JSON-LD / Open Graph）→ 站点规则 → 通用智能提取 → 人工规则
- 解析带置信度提示，低置信度不会伪装成功；失败时明确告知原因

### 目录
- 完整目录抓取（目录分页自动合并）
- 正序 / 倒序切换、章节搜索、当前章节定位
- 自动去重、广告链接过滤、分卷识别
- 目录更新按 URL + 标题指纹智能合并，不破坏阅读进度

### 整本采集
- 详情页一键【下载整本小说】
- 逐章抓取、清洗、即时保存（不等全部完成）
- 断点续传、暂停、继续、取消、单章重试
- 采集中心实时查看进度（成功 / 失败 / 当前章节）
- 应用重启后自动恢复未完成任务
- 自适应限速：对站点友好，失败自动退避，冷却后自动恢复速度

### TXT 与本地书架
- 采集完成自动生成 UTF-8 TXT（书名 / 作者 / 来源 / 时间 / 章节数 + 章节分隔）
- 自动加入书架，与原始书籍共用同一条目
- 文件原子写入 + SHA-256 完整性校验
- 支持导出 TXT 到手机任意目录

### 阅读器
- **连续阅读**（默认）：读完一章自动衔接下一章，无需手动翻章
- 滚动 / 模拟翻页 / 连续滚动三种模式
- 五套阅读主题（白 / 米黄 / 护眼绿 / 深灰 / 黑）
- 字号、行距、段距、边距、亮度可调
- 沉浸式全屏，点击屏幕中央呼出工具栏
- 阅读进度精确到章节内字符位置，退出重进准确恢复
- 章节预加载，断网可读已缓存内容

### 书架与管理
- 书架分组：全部 / 在线 / 下载中 / 已下载 / 本地导入
- 封面、进度、最新章节、缓存状态展示
- 长按删除（级联清理章节缓存与任务记录）
- 网站规则管理：按域名配置解析规则，支持增删改查与导入导出
- 「反馈数据」一键生成诊断文件（设备信息 / 数据库统计 / 最近操作日志，不含正文，URL 自动脱敏）

### 其他
- Pixel 全面屏适配：挖孔屏、状态栏、手势导航区、软键盘均不遮挡内容
- 深色 / 浅色主题，跟随系统动态切换
- 全部数据仅存本地，不上传任何用户数据

---

## 使用

### 安装
1. 下载 `artifacts/简阅-2.0.0-debug.apk`
2. 传输到 Android 手机（Android 8.0 及以上）安装
3. 首次安装需允许「安装未知应用」

### 从网址到离线阅读（完整流程）
1. 复制小说网站任意页面的网址（详情页、目录页或某一章均可）
2. 打开简阅 → 底部「发现·导入」→ 粘贴网址 → 开始解析
3. 识别成功后进入书籍详情页，可查看目录或直接开始阅读
4. 点击【下载整本小说】→ 在「采集中心」查看进度
5. 采集完成后自动生成 TXT 并进入书架「已下载」分组
6. 点开书籍即读，章节自动衔接；断网也能继续阅读
7. 退出后重新打开，自动回到上次位置

### 常见问题
- **某些网站无法解析**：不同网站结构差异很大，应用会给出明确的失败原因。可在「我的 → 规则管理」为该网站配置解析规则，或补充目录页网址重试。
- **下载很慢或失败**：站点可能限制访问频率。应用会自动退避重试；也可在采集中心手动「重试失败章」，已完成的章节不会重复下载。
- **不在支持范围的网站**：页面依赖 JavaScript 动态渲染的站点暂不支持，应用会明确提示。

---

## 构建

```bash
# 需要 JDK 17+ 与 Android SDK 35；本仓库不携带 gradlew wrapper，
# 请使用本地 Gradle 8.10.2（或先执行 gradle wrapper --gradle-version 8.10.2 生成）
gradle :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# 单元测试（建议统一走脚本；Windows 中文路径会自动镜像到 ASCII 目录执行）
bash scripts/run-unit-tests.sh
```

### 开发环境（换机指引）

- 依赖一律来自环境变量与 PATH：`JAVA_HOME`、`ANDROID_HOME`/`ANDROID_SDK_ROOT`、`flutter`、`gradle`。仓库内**不含任何机器绝对路径**。
- 机器级个性化配置放 `scripts/local-env.sh`（已 gitignore，模板见 `scripts/local-env.example.sh`），可由 `FLUTTER_BIN`、`GRADLE_CMD`、`JIANYUE_BUILD_DIR` 等变量覆盖探测结果。
- 仅当源码路径含非 ASCII 字符时（Windows 下 Flutter AOT / Gradle @argfile 的已知限制），脚本才自动启用 ASCII 镜像构建；源码路径本来就是 ASCII 时原地构建。

---

## Flutter 版（简阅·Flutter）

本仓库同时包含基于 **Flutter 3.50** 开发的 Flutter 重写版（`flutter_app/`），与 Kotlin 版共享同一套架构分层与核心功能。**兼容 Flutter ≥3.38（Dart ≥3.5）**：仓库不锁定 `pubspec.lock`，各版本 Flutter 在 `pub get` 时自动解析兼容依赖。下限由 `workmanager 0.10.x`（后台隔离依赖）决定，已在 `pubspec.yaml` 注释备案。

| 能力 | Kotlin 版 | Flutter 版 |
|---|---|---|
| 网址导入（详情/目录/任意章节反向识别整本） | ✅ | ✅ |
| 四层解析（人工规则 → 结构化 → 通用智能提取，含置信度） | ✅ | ✅ |
| 站点规则可视化编辑器（按域名自定义解析，fieldErrors 回显） | ✅ | ✅ |
| 完整目录（分页/去重/排序/分卷） | ✅ | ✅ |
| 整本采集（断点续传/暂停/恢复/重试） | ✅ | ✅ |
| WorkManager 后台隔离采集（前台服务 + 进度通知） | ✅ | ✅（Android） |
| 自适应限速（2s→1s 降档 / 失败翻倍 10s 封顶 / 429 Retry-After） | ✅ | ✅ |
| 站点编码提示（GBK/GB18030/BIG5 优先解码） | ✅ | ✅ |
| TXT 生成（原子写 + SHA-256）+ 自动进书架 | ✅ | ✅ |
| 跨章节连续阅读 + 进度精确恢复 | ✅ | ✅ |
| 五套阅读主题 / 字号 / 行距 | ✅ | ✅ |
| 诊断反馈（脱敏文本 + 系统分享导出） | ✅ | ✅ |
| Pixel 全面屏适配 | ✅ | ✅ |

- Flutter 源码：`flutter_app/`（`lib/contracts.dart` 为冻结接口，`lib/impl/` 引擎层，`lib/ui/` 界面层）
- Flutter APK：`artifacts/简阅-Flutter-1.1.0.apk`（包名 `com.mvxxcb.reader.flutter`，可与 Kotlin 版共存安装）
- Flutter 构建：

```bash
cd flutter_app
flutter pub get
flutter build apk --release
# 产物：flutter_app/build/app/outputs/flutter-apk/app-release.apk

# 便捷脚本（自动探测 Flutter；仅在源码路径含非 ASCII 字符时启用 ASCII 镜像，
# 产物按 pubspec 版本自动命名到 flutter_app/artifacts/）：
bash scripts/flutter_build_ascii.sh
```

### Flutter 版已知限制（v1.1.0）

v1 的四项限制（站点规则编辑器 / WorkManager 后台隔离 / 诊断反馈 / 自适应限速）已在 v1.1.0 全部移植完毕。当前仍存在以下如实声明的能力边界：

1. **iOS 后台采集未接入**：WorkManager 后台隔离仅在 Android 上注册（`BGTaskScheduler` 配置超出范围）；非 Android 平台调度器为空实现，采集仍可正常进行（前台队列执行）。
2. **后台进度通知为简化版**：前台服务通知为静态文案（"正在下载章节…"），不含 Kotlin 版的逐章进度百分比更新；`flutter_local_notifications`/`permission_handler` 未引入（保持依赖面最小，通知使用 WorkManager 自带渠道创建）。
3. **规则清洗规则的一期口径**：编辑器仅支持逐行正则（regex）清洗；`selector`/`whitespace` 两类清洗无法用行文本表达，需直接导入规则 JSON（与 Kotlin 版编辑器一致的已知限制）。
4. **Baseline Profile 为宿主侧补充**：`android/app/src/main/baseline-prof.txt` 仅覆盖 Application/Activity、androidx.startup（WorkManager 初始化路径）与 WorkManager 核心类；Flutter 的 Dart AOT 启动热路径由引擎自带的 baseline profile 覆盖（flutter gradle 插件自动引入 `profileinstaller`），本文件不重复该部分。
5. **诊断反馈的日志口径**：仅导出应用内环形缓冲的操作摘要（最近 120 行），不含系统 logcat；URL 一律脱敏（去 query/fragment + 截断），TXT 路径只展示文件名。

---

## 许可证

[MIT](LICENSE)

## 声明

本应用仅用于个人阅读使用，不提供任何小说内容。采集功能仅保存用户有权访问且允许保存的网页内容，遵守合理请求频率，不绕过任何付费墙、登录验证、验证码或其他访问控制。请遵守目标网站的服务条款及当地法律法规。