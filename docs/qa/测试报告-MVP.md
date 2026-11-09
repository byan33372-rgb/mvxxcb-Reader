# 测试报告-MVP（WP-Q）

> 版本：v1.0（阶段 6 执行版）｜ 日期：2026-11-09 ｜ 作者：reader-qa
> 依据：docs/04 §6（WP-Q DoD「输出《测试报告-MVP》并给出放行/拦截结论」）、docs/05 整合日志。
> 口径声明：本报告所有结论均来自真实执行（标准通道测试脚本 + 测试结果 XML），无任何预填/虚构；
> 放行结论为**阶段 7 模拟器验收前的中间结论**，最终放行以阶段 7 全链路验收为准。

---

## 1. 测试环境

| 项 | 值 |
|---|---|
| 工具链 | JDK 21（D:/AndroidToolchain/jdk-21，构建与单测）· Gradle 8.10.2 · AGP 8.7.3 · Kotlin 2.0.21（KSP 2.0.21-1.0.27） |
| Android SDK | compileSdk 35 / minSdk 26 / targetSdk 35（platforms;android-35） |
| 单测执行通道 | **ASCII 副本标准通道**：`scripts/run-unit-tests.sh`（robocopy /MIR 同步真树 → D:/reader-jianyue-verify 执行；规避中文路径下 JVM 按 GBK 解析 @argfile 的 CNFE 缺陷，见 docs/05；真树保持唯一源） |
| 命令 | `MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL="*" bash scripts/run-unit-tests.sh [--tests "…"]` |
| Robolectric | 4.13（NATIVE，@Config(sdk=[34])；WP-D/WP-U/WP-Q 共用） |
| 被测版本 | 阶段 5 整合后真树（契约 v1.0.3；app-debug.apk 19.7MB 已出包） |
| 仪器/设备 | 阶段 6 未用；阶段 7 = 模拟器（system-images;android-35;google_apis;x86_64 已就绪） |

---

## 2. 测试范围与矩阵总览

| 范围 | 载体 | 数量 | 状态 |
|---|---|---|---|
| 导入/阅读流集成（**C1 已切真实 Default*Service**） | `test/integration/{ImportFlowIntegrationTest,ReaderFlowIntegrationTest}.kt` | 23 | **26/26 通过**（2026-11-09） |
| refreshCatalog 真实仓库集成（原 @Ignore 桩位改写） | `test/integration/RefreshCatalogC1IntegrationTest.kt` | 3 | 通过（Robolectric + Room） |
| 回归矩阵（错误树/合并/映射/样本/E2E/性能） | `test/regression/回归用例清单.md` | 120 | 64 通过 + 1 豁免 + 55 待阶段 7 |
| E2E 骨架 | `androidTest/e2e/{ImportToReadE2ETest,OfflineReadingE2ETest}.kt` | 2 类 4 方法（@Ignore） | 未激活（待阶段 7） |
| 各 WP 单测（parser/data/reader/security） | 各 WP 独占目录 | 195 | 全绿（同一次全量执行） |

---

## 3. 执行结果汇总

### 3.1 全量单测（阶段 6，2026-11-09 02:32 新鲜执行）

**221 用例 / 0 失败 / 0 错误 / 0 跳过（22 个测试类）**

| 模块 | 用例数 | 测试类 |
|---|---|---|
| parser | 93 | EncodingDetector 9 · ParserPipelineCatalog 10 · Content 9 · Detail 10 · RuleEngineImpl 10 · SelectorExecutor 12 · TextCleanerAndPagination 13 · UrlClassifierImpl 10 · UrlNormalizer 10 |
| data | 29 | ContentCacheManagerImpl 6 · SettingsDataStore 4 · ChapterAppend 6 · MergeAlgorithm 10 · ProgressAndCascade 3 |
| reader | 16 | TextPagerImplTest 16 |
| security | 57 | ContentSanitizerImpl 14 · DnsPinningResolver 5 · SecureHttpClientImpl 21 · UrlSecurityValidatorImpl 17 |
| **integration（WP-Q）** | **26** | **ImportFlow 13 · ReaderFlow 10 · RefreshCatalogC1 3** |

证据：`D:/reader-jianyue-verify/app/build/test-results/testDebugUnitTest/TEST-*.xml`（22 文件，tests/skipped/failures/errors 逐类 0 失败 0 跳过）。

### 3.2 C1 切换结果（阶段 6 核心事项）

| 切换点 | 切换前 | 切换后 | 结果 |
|---|---|---|---|
| ImportFlowIntegrationTest.Harness.service | 测试内 FakeImportService（参考实现） | **DefaultImportService(classifier, pipeline, bookRepository, chapterRepository, logger)** | 13/13 通过 |
| ReaderFlowIntegrationTest.Harness.service | 测试内 FakeReadingService（参考实现） | **DefaultReadingService(bookRepository, chapterRepository, pipeline, timeProvider=FixedTime(FAKE_NOW), logger)** | 10/10 通过 |
| @Ignore 合并桩位 | refreshCatalogMergeKeepsChapterIdsAndReportsChanges（@Ignore） | **改写为 RefreshCatalogC1IntegrationTest**（Robolectric + Room 内存库 + 真实 BookRepositoryImpl/ChapterRepositoryImpl/CatalogMerger，基建复用 WP-D TestSupport），@Ignore 移除 | 3/3 通过 |

**语义一致性结论：真实实现与骨架期参考语义零冲突。**
- 全部既有断言零修改通过（含调用序 classify→parseDetail→parseCatalog→upsert→replaceCatalog、Phase 顺序、recovery 映射、弱侧置信度、mergedExisting、缓存优先、失败数据返回、取消不落库）。
- 主 Agent 预告的差异点全部实证符合：① 章节页路径单章持久化（v1.0.3 §13.2.6）——**新增**断言验证临时书可直接阅读（appendChapter+saveContent 落库），通过；② DefaultReadingService 不做书行前置校验——触达更新在无书行时失败被忽略，不扰阅读，通过；③ ReaderContent.title 用目录标题（§13.2.2），通过；④ loadChapterByUrl 未命中返回 CHAPTER_NOT_FOUND 不自动补录（§13.2.3），通过。
- 无需走变更回路；无断言/实现被单方修改。

### 3.3 回归清单判定（120 条）

| 判定 | 数量 | 说明 |
|---|---|---|
| 通过（单元级） | 42 | 错误对象/枚举断言有真实单测证据：NET 9 + PARSE 5 + SEC 10 + STO 1 + STA-04 1 + SAMPLE 16 |
| 通过（集成级） | 22 | 真实 Default*Service / 真实 Room 仓库端到端：IMP 7 + SVC 6 + MERGE 7 + STA 2 |
| 豁免（一期不做） | 1 | REG-IMP-08 目录回溯（v1.0.3 §13.2.6 明确 backtracked=false） |
| 未执行（待阶段 7） | 55 | 需设备/真网络/UI 渲染/性能：NET 5 + PARSE 7 + STO 3 + STA 1 + UNK 1 + READER 10 + MAP 21 + IMP 2 + E2E 2 + PERF 3 |

> 明细与逐条证据见 `test/regression/回归用例清单.md` v0.2（每条"通过"附测试类与用例名）。

### 3.4 E2E 统计

| 用例 | 状态 | 结果 |
|---|---|---|
| ImportToReadE2ETest（2 方法） | @Ignore 未激活 | 未执行（待阶段 7；激活方式主 Agent 已裁定：Compose Test + 注入假网络） |
| OfflineReadingE2ETest（2 方法） | @Ignore 未激活 | 未执行（待阶段 7） |

---

## 4. 缺陷清单

| 缺陷 ID | 发现轮次 | 用例 ID | 现象/证据 | 归属 WP | 状态 |
|---|---|---|---|---|---|
| （阶段 6：无新增） | — | — | C1 切换复跑 26 用例零失败，真实实现与参考语义零冲突；历史缺陷（WP-D 全角转半角等 3 处、WP-U/UI 阻塞编译）已在阶段 4/5 闭环，见 docs/05 | — | — |

> 骨架阶段遗留阻塞项状态更新：~~WP-M Default*Service 未就位~~（已交付并切换）；~~WP-P 样本未交付~~（18 文件在位并被单测真实加载）；~~WP-D 合并算法未就位~~（已交付并由 MergeAlgorithmTest 10 用例 + RefreshCatalogC1 3 用例双重覆盖）。

---

## 5. 激活条件与阻塞项（阶段 6 更新）

| 块 | 激活条件 | 状态 |
|---|---|---|
| 集成测试切真实实现 | C1：DefaultImportService/DefaultReadingService 就位 | ✅ **已完成**（阶段 6） |
| 目录合并算法 | C1：ChapterRepositoryImpl + CatalogMerger | ✅ **已完成**（MergeAlgorithmTest 10 + RefreshCatalogC1 3） |
| 解析样本回归 | C2：16 样本基线 | ✅ **已完成**（18 文件在位，管线单测真实加载） |
| State/Storage 部分错误路径 | C1：Room/Repository 就位 | ✅ 部分完成（STA-01/02、STO-04、PARSE-03 已通过；损坏 DB/满盘注入待补） |
| 错误树全树 | C2→**阶段 7**：真网络/设备 | ⬜ CONNECT_TIMEOUT/TLS/RATE_LIMITED/OFFLINE 等 12 项待阶段 7 |
| 错误映射渲染 | 阶段 7：模拟器 UI | ⬜ REG-MAP-01..21（45 个 error_* key 静态核验已在位） |
| 断网读缓存 / 位置恢复 | 阶段 7：模拟器 | ⬜ REG-READER-* 10 项 |
| E2E | 阶段 7：模拟器 + Compose Test | ⬜ 骨架就绪待激活 |
| 性能预算 | 阶段 7：模拟器帧统计 | ⬜ REG-PERF-01..03 |

---

## 6. 结论（阶段 7 前的中间结论）

> **[ ] 放行 ｜ [✓] 有条件放行（单元/集成层） ｜ [ ] 拦截**
>
> **中间结论：单元与集成层有条件放行。** 依据：
> 1. 全量单测 221 用例 0 失败 0 跳过（阶段 6 新鲜执行，标准通道）；
> 2. C1 切换后真实 Default*Service 与冻结契约语义一致（26 集成用例零冲突）；
> 3. 回归清单 64 条通过 + 1 条契约豁免，每条附真实证据；
> 4. 无未闭环 P0/P1 缺陷。
>
> **放行条件（阶段 7 必须达成后方可终局放行）：**
> 1. 模拟器安装 app-debug.apk 完成 §22 全链路核心流程验收（粘贴→导入→书架→阅读→进度恢复→断网读缓存）；
> 2. REG-MAP-01..21 错误映射渲染与恢复入口逐行核验；
> 3. REG-READER-POS/OFFLINE 位置恢复与断网读缓存通过；
> 4. REG-PERF-01..03 性能预算达标（万章目录 / 3 万字翻页 / 冷启动 1.5s）;
> 5. WP-S 安全终审通过（并行进行中）。

---

## 附录 A：WP-Q 测试产物索引

| 文件 | 说明 |
|---|---|
| `app/src/test/java/com/mvxxcb/reader/integration/ImportFlowIntegrationTest.kt` | 导入流契约语义（**真实 DefaultImportService**，13 用例；边界 Fake 脚本化） |
| `app/src/test/java/com/mvxxcb/reader/integration/ReaderFlowIntegrationTest.kt` | 阅读服务契约语义（**真实 DefaultReadingService**，10 用例） |
| `app/src/test/java/com/mvxxcb/reader/integration/RefreshCatalogC1IntegrationTest.kt` | refreshCatalog 真实仓库集成（Robolectric+Room+CatalogMerger，3 用例；原 @Ignore 桩位改写产物） |
| `app/src/test/java/com/mvxxcb/reader/regression/回归用例清单.md` | 120 条回归矩阵 v0.2（64 通过 + 1 豁免 + 55 待阶段 7，逐条证据） |
| `app/src/androidTest/java/com/mvxxcb/reader/e2e/ImportToReadE2ETest.kt` | E2E 骨架（@Ignore，阶段 7 激活） |
| `app/src/androidTest/java/com/mvxxcb/reader/e2e/OfflineReadingE2ETest.kt` | E2E 骨架（@Ignore，阶段 7 激活） |
| `docs/qa/测试报告-MVP.md` | 本报告 |
| 测试结果 XML | `D:/reader-jianyue-verify/app/build/test-results/testDebugUnitTest/TEST-*.xml`（22 类） |

## 附录 B：自检记录

### B.1 骨架阶段（2026-11-09，已归档）

| # | 命令 | 结果 | 备注 |
|---|---|---|---|
| B-1 | `gradle :app:compileDebugUnitTestKotlin`（首次） | 失败（非 WP-Q 文件） | `:app:compileDebugKotlin` 先失败：WP-U 主源 `presentation/rules/RuleManagerViewModel.kt` 与 `ui/components/*` 未解析引用 |
| B-2 | 同上，等 60s 重试 ×3 | 3 次均失败（非 WP-Q 文件） | 错误集持续演变（`spacing` token 编写中）；按纪律不改他人文件 |
| B-3 | 独立部分编译（JDK17 + kotlin-compiler-embeddable 2.0.21） | 通过（exit 0） | 契约子集（除 LogcatLogger.kt）+ WP-Q 测试源；当时 Default*Service 未就绪的等价验证 |
| B-4 | 独立 JUnitCore 执行 | OK (23 tests) + 1 @Ignore | 过程暴露 5 处用例自身缺陷并修复（3 处 Confidence ordinal 误用——冻结契约声明顺序 HIGH,MEDIUM,LOW、2 处脚本误绑） |

### B.2 阶段 6（2026-11-09）

| # | 命令 | 结果 | 备注 |
|---|---|---|---|
| B-5 | `run-unit-tests.sh --tests "com.mvxxcb.reader.integration.*"`（C1 切换后首跑） | 第 1 次编译失败（WP-Q 新文件漏传 ParseOutcome.Success 必填参数），修复后 **26/26 通过，0 跳过** | 唯一编译缺陷在 WP-Q 自有新文件，即时修复 |
| B-6 | `run-unit-tests.sh`（全量，02:32 新鲜执行） | **221 / 0 失败 / 0 错误 / 0 跳过**（22 类） | integration 26 为本阶段真实执行；其余模块同批新鲜执行 |
| B-7 | `grep strings.xml 'name="error_'` | 45 个 error_* key 与 02 §10 逐行对应 | REG-MAP 静态核验证据 |
