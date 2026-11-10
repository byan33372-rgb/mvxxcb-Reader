# M0 性能基准基线报告（2.0.0 未优化基线）

- 采集日期：2026-11-11（本机时区）
- 执行方式：**androidx.benchmark Macrobenchmark（方案一）**，`:benchmark:connectedBenchmarkAndroidTest`，`FrameTimingMetric` / `StartupTimingMetric`；未使用 gfxinfo 降级方案。
- 被测版本：versionName 2.0.0 / versionCode 2（git 描述 `v2.0.0-2-g413c1b8`，工作树含 WP-S 在途 security/log 改动；本次未改任何 app/src 业务代码，benchmark 变体未混淆、非调试、无 Baseline Profile）。

## 1. 设备与构建口径

| 项 | 值 |
|---|---|
| 设备 | 模拟器 jianyue_test（Pixel 6 布局 / emu64xa，google_apis x86_64） |
| 系统 | Android 15（API 35，AE3A.240806.043，userdebug） |
| 屏幕/刷新率 | 1080×2400 / **60Hz（唯一模式，固定 60fps）** |
| 渲染 | swiftshader_indirect（**软件 GPU**） |
| CPU/内存 | 4 核（上限 2.0GHz）/ 3.0GB |
| 编译模式 | 滚动场景 `CompilationMode.DEFAULT`（装机态，run-from-apk）；启动场景 `CompilationMode.None()` |
| 运行抑制 | `androidx.benchmark.suppressErrors=EMULATOR`（官方参数；模拟器绝对值偏保守，仅用于相对对比） |
| 迭代 | 每场景 5 次重复（无预热迭代），setup 不计入指标 |
| 夹具 | 42 本书：9527「万章目录」10000 章（无缓存正文）、9528「长文阅读」80 章全缓存（每章 150 段×约 60 字，阅读模式装机默认 CONTINUOUS）、40 本填充书 |

## 2. 逐场景基线数字（真实执行采集）

### 2.1 StartupBenchmark — 冷启动到书架（StartupTimingMetric，5 次冷启动）

| 指标 | 最小 | 中位 | 最大 |
|---|---|---|---|
| timeToInitialDisplayMs | 704.5 | **756.0** | 828.2 |

- 五次原始值：755.96 / 704.48 / 707.08 / 828.21 / 758.86 ms。
- 补充口径（任务要求）：`am force-stop` 后 `am start -W` 三次 TotalTime：623 / 600 / 580 ms，**中位数 600 ms**。
  （am start -W 为系统 Activity 启动计时，与 Macrobenchmark TTID 口径不同，两者均如实记录。）

### 2.2 ShelfScrollBenchmark — 书架滚动（FrameTimingMetric，5 次迭代，269 帧）

| 指标(ms) | P50 | P90 | P95 | P99 |
|---|---|---|---|---|
| frameDurationCpuMs | 28.04 | 31.72 | 32.30 | 34.03 |
| frameOverrunMs | 13.17 | 16.42 | 16.91 | 20.74 |

- jank 比例（frameDurationCpuMs > 16.67ms，60Hz 帧预算）：**56.13%**
- frameOverrunMs > 0 的帧占比：64.31%

### 2.3 CatalogScrollBenchmark — 万章目录（10000 章）打开+快速滚动（5 次迭代，539 帧）

| 指标(ms) | P50 | P90 | P95 | P99 |
|---|---|---|---|---|
| frameDurationCpuMs | 15.65 | 31.59 | 33.52 | 40.36 |
| frameOverrunMs | 0.82 | 16.68 | 19.13 | 34.37 |

- jank 比例（frameDurationCpuMs > 16.67ms）：**44.90%**
- frameOverrunMs > 0 的帧占比：57.70%

### 2.4 ReaderScrollBenchmark — 阅读器连续滚动（CONTINUOUS 宿主，5 次迭代，593 帧）

| 指标(ms) | P50 | P90 | P95 | P99 |
|---|---|---|---|---|
| frameDurationCpuMs | 14.34 | 21.83 | 28.67 | 32.28 |
| frameOverrunMs | -1.18 | 11.68 | 13.77 | 17.14 |

- jank 比例（frameDurationCpuMs > 16.67ms）：**15.01%**
- frameOverrunMs > 0 的帧占比：23.78%

### 2.5 汇总速览

| 场景 | 帧数 | P50 | P90 | P95 | P99 | jank%(CPU>16.67) | overrun>0 占比 |
|---|---|---|---|---|---|---|---|
| 冷启动（TTID ms） | 5 次 | 756.0 | — | — | — | — | — |
| 书架滚动 | 269 | 28.0 | 31.7 | 32.3 | 34.0 | 56.1% | 64.3% |
| 万章目录滚动 | 539 | 15.6 | 31.6 | 33.5 | 40.4 | 44.9% | 57.7% |
| 阅读器连续滚动 | 593 | 14.3 | 21.8 | 28.7 | 32.3 | 15.0% | 23.8% |

## 3. 与既有 gfxinfo 基线的关系

M2 期间 gfxinfo 口径的「万章目录滚动 2.79% jank」与本轮 Macrobenchmark 口径**不可直接对比**：
（a）本轮 jank 按 `frameDurationCpuMs > 16.67ms`（帧 CPU 时长）统计，gfxinfo 按 deadline overrun 统计；
（b）本轮注入路径为书架→详情→目录+连续 fling，含导航帧；
（c）本轮 FrameTimingMetric 的 frameOverrunMs>0 占比 57.7% 与 gfxinfo「deadline 错过」口径更接近（仍含导航帧差异）。
后续 UI/流畅度优化的对比请统一使用本轮口径（同模块、同脚本、同夹具重跑）。

## 4. 已知限制（如实记录）

1. **模拟器 + 软件 GPU（swiftshader）**：渲染在 CPU 上完成，绝对值显著差于真机；Macrobenchmark 库默认禁止模拟器运行，本次用官方参数 `androidx.benchmark.suppressErrors=EMULATOR` 抑制。绝对值仅作 2.0.0 内部基线，优化收益需同口径重跑对比。
2. **未锁频 / 未锁核**（cpuLocked=false），吞吐受宿主机负载波动影响；未做thermal 节流等待。
3. **无 Baseline Profile**：本轮即「未优化 2.0.0」基线（profileinstaller 依赖已加入 app，但未生成基线 profile；Baseline Profile 生成列为二期后续，见 §6）。
4. **滚动基准 CompilationMode.DEFAULT（run-from-apk）**：装机后 JIT/AOT 状态未固定，波动介于 None 与 Full 之间。
5. **UiAutomator 驱动**：fling 步数/时长固定但非物理手指；每迭代 setup 冷启动（force-stop）保证状态确定，setup 不计帧。
6. 目录页为窗口加载（10000 章），fling 过程会持续触发窗口加载，测量包含该真实负载。

## 5. 复现步骤（详见 README.md）

1. 启动模拟器 → `adb root` → 注入夹具（fixture-gen-m0-sql.js 生成 SQL 注入 reader.db）；
2. `gradlew :app:installBenchmark` 并启动一次 app（创建 DB）后 force-stop 再注入；
3. `gradlew :benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`；
4. 结果位于 `benchmark/build/outputs/connected_android_test_additional_output/`（本目录已存 raw-benchmarkData.json 快照）。

## 6. 原始产物

| 文件 | 内容 |
|---|---|
| `raw-benchmarkData.json` | Macrobenchmark 原始 RunResults（含每帧原始值 runs 数组、设备上下文） |
| `raw-connected-test-result.xml` | connectedAndroidTest JUnit XML（4/4 通过） |
| `stats-summary.json` | 本报告统计的机器可读版（含每帧聚合 P50/P90/P95/P99、jank%） |
| `fixture-gen-m0-sql.js` | 夹具 SQL 生成脚本（10000 章目录书 + 80 章全缓存长文书 + 40 填充书） |
