# 简阅 · Android 网页小说阅读器

> 粘贴小说网址 → 自动识别详情/目录/正文 → 沉浸式本地阅读。
> Kotlin · Jetpack Compose · Material 3 · MVVM · Room · DataStore · OkHttp/Jsoup。

---

## 功能总览（MVP 第一阶段）

| 模块 | 能力 |
|---|---|
| 书架 | 封面/进度/最新章/缓存信息、长按删除（级联清理）、排序 |
| 导入 | 粘贴/输入网址、系统分享进入、显式读剪贴板、四层解析（人工规则→JSON-LD/OG→站点规则→通用智能提取）、置信度与解析依据展示、同 URL 去重合并、临时书 |
| 目录 | 正/倒序、搜索、当前章定位、缓存标记、更新目录（URL+指纹合并保护进度） |
| 阅读器 | 滚动+模拟翻页双模式、五主题（白/米/绿/灰/黑，对比度≥4.5:1）、字号/行距/边距、点击中央工具栏、书签、上下章、章尾预取、章内位置恢复（inChapterFraction 锚点） |
| 规则引擎 | 按域名 CSS/正则/JSONPath 选择器规则、清洗规则、启停/导入导出、人工规则兜底 |
| 缓存 | 当前±1 章按需缓存、手动批量、完整性校验（SHA-256）、断网续读 |
| 安全 | 全站 SSRF 防护（IP 解析级校验+逐跳重定向复查+私网/回环/元数据端点拦截）、响应大小/次数/频率限制、正文净化防 XSS |

## 项目结构

```
app/src/main/java/com/mvxxcb/reader/
├── ui/            Compose 页面与 M3 设计系统（WP-U）
├── presentation/  ViewModel + UiState + 导航（WP-U）
├── reader/        阅读引擎（TextPager 分页/位置映射/主题）（WP-U）
├── domain/        纯 Kotlin：模型/错误体系/全部接口契约（主 Agent）
├── data/          Room/DataStore/Repository/缓存（WP-D）
├── parser/        四层解析管线/规则引擎/编码/规范化/分页合并（WP-P）
├── security/      SSRF 校验/SecureHttpClient/内容净化（WP-S）
├── service/       装配层领域服务（Import/Reading）（主 Agent）
├── di/            AppContainer 手动 DI（主 Agent）
└── common/        AppResult/调度器/时间/日志（主 Agent）
```

架构文档：`docs/01-架构方案.md`（方案）· `docs/02-接口契约.md`（冻结契约 v1.0.3，跨模块唯一依据）· `docs/03-数据模型.md` · `docs/04-开发计划.md` · `docs/05-整合日志.md`（全程决策/缺陷/裁定台账）。

## 构建与运行

环境：JDK 17+（本机验证 17/21）、Android SDK 35、AGP 8.7.3 / Kotlin 2.0.21（版本见 `gradle/libs.versions.toml`；仓库已配阿里云镜像，Gradle Wrapper 走腾讯镜像）。

```bash
# 构建
./gradlew :app:assembleDebug          # 产物 app/build/outputs/apk/debug/app-debug.apk（约 19.7MB）
# 安装
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 单元测试（Windows 中文路径注意：JVM 按 GBK 读 argfile，单测经 ASCII 镜像执行）
bash scripts/run-unit-tests.sh        # 自动 robocopy 镜像到 D:/reader-jianyue-verify 后执行
```

minSdk 26 · target/compile 35 · applicationId `com.mvxxcb.reader` · 应用名「简阅」（`res/values/strings.xml` 可改）。

## 测试

- 单元/集成 **232 用例**（parser 102 + data 29 + reader 16 + security 57 + integration 28），全部真实执行通过；固定 HTML 样本 19 个（`app/src/test/resources/html-samples/`）+ MockWebServer + Robolectric + Room 内存库。
- 模拟器验收：`docs/qa/模拟器验收执行单.md`（执行单 + 记录 + 结论）；真实站点全链路（linovelib.com）截图证据 20+ 张。

## 已知问题与限制

1. DNS 不可达时安全校验报「主机名无效」（语义未细分网络失败/非法主机，fail-closed 行为正确，fail-closed 拦截后可退出重进恢复）。
2. 目录页头部「共 N 章」在目录更新后可能短暂不同步（展示层缓存）。
3. 一期不做：WebView 动态页渲染（明确提示 DYNAMIC_PAGE_REQUIRED）、XPath 选择器执行器、目录自动更新、批量缓存 WorkManager 全量实现、书签独立页。
4. 不同网站结构差异大，不承诺全部网址可自动解析；失败时给出明确原因 + 人工规则兜底（我的 → 规则管理）。
5. Windows 中文路径下 Gradle 单测 argfile 编码问题——单测经 `scripts/run-unit-tests.sh` 镜像执行（构建不受影响）。

> 阶段 7 验收缺陷（目录筛选栏污染、错误态导航失效、可空 Long 导航崩溃）均已修复并通过模拟器真机复验，详见 `docs/05-整合日志.md` 缺陷台账与 `docs/qa/模拟器验收执行单.md` 终签记录。

## 二期路线

更多站点规则与通用提取优化 · WebView 辅助解析 · 目录自动更新 · 正文分页自动合并增强 · 翻页动画与字体管理 · 书签/历史独立页 · 阅读统计 · 规则导入导出分享 · TXT/EPUB 导入 · TTS · 平板布局。

## 隐私声明

应用不上传用户书架、阅读记录与小说网址；日志对 URL 脱敏；仅请求用户主动输入的网址；网络访问经安全层统一管控。
