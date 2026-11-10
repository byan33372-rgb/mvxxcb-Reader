# M0 性能基准基建（benchmark 模块）使用说明

本目录存放 **2.0.0 未优化基线** 的性能基准结果与原始数据。基准基建为新增 Gradle 模块 `:benchmark`（androidx.benchmark Macrobenchmark 1.3.4）。

## 模块结构

```
benchmark/
├── build.gradle.kts                  # com.android.test + kotlin-android（无 androidx.benchmark 插件，见下）
└── src/main/java/com/mvxxcb/reader/benchmark/
    ├── BenchmarkHelpers.kt           # 冷启动/导航助手（书架→详情→目录/阅读器）
    ├── StartupBenchmark.kt           # 冷启动到书架：StartupTimingMetric ×5
    ├── ShelfScrollBenchmark.kt       # 书架滚动：FrameTimingMetric ×5
    ├── CatalogScrollBenchmark.kt     # 万章目录(10000章)打开+快速滚动 ×5
    └── ReaderScrollBenchmark.kt      # 阅读器连续滚动(CONTINUOUS) ×5
```

注：
- `com.android.test` 模块的源码放 `src/main/java`（该模块本身就是 instrumentation APK），**不是** `src/androidTest/java`。
- 未应用 `androidx.benchmark` Gradle 插件：该插件 1.3.4 仅支持 library 模块（Baseline Profile 生成用），官方明确「跑 Macrobenchmark 不需要」；本模块只依赖 `benchmark-macro-junit4` 库。
- `com.android.test` 经 plugins DSL「无版本 id」应用（根项目已加载同版本 AGP，避免 classpath 版本检查冲突，根 build.gradle.kts 未改动）。
- benchmark 1.3.x API：`CompilationMode.None()` / `CompilationMode.DEFAULT`（构造/JvmField，1.3 起不再是 object）；runner 用 `androidx.test.runner.AndroidJUnitRunner`（`AndroidBenchmarkRunner` 已弃用）。

## 对 app 模块的白名单内改动

1. `app/build.gradle.kts`：新增 `benchmark` buildType（非调试、不混淆、debug 签名、matchingFallbacks release）+ `implementation(libs.profileinstaller)`。
2. `gradle/libs.versions.toml`：追加 benchmark/uiautomator/test-ext-junit/profileinstaller 版本与库条目、`android-test`/`androidx-benchmark` 插件条目。
3. `settings.gradle.kts`：`include(":benchmark")`。

## 复现步骤

```bash
# 1. 启动模拟器（WHPX，软件 GPU）
D:/AndroidToolchain/android-sdk/emulator/emulator.exe -avd jianyue_test \
  -no-snapshot -no-boot-anim -gpu swiftshader_indirect -memory 3072 &
adb wait-for-device && adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'
adb root

# 2. 安装被测 app（benchmark 变体）并启动一次以创建 reader.db，然后注入夹具
gradlew :app:installBenchmark
adb shell am start -n com.mvxxcb.reader/.MainActivity && sleep 10
adb shell am force-stop com.mvxxcb.reader

# 3. 生成并注入夹具（书 9527 万章目录 / 书 9528 长文阅读 / 40 本填充书）
node 本目录/fixture-gen-m0-sql.js   # 产出 D:/AndroidToolchain/tmp/m0-perf.sql
adb push D:/AndroidToolchain/tmp/m0-perf.sql /data/local/tmp/
adb shell "cat /data/local/tmp/m0-perf.sql | sqlite3 /data/data/com.mvxxcb.reader/databases/reader.db"
# 校验：books=42, chapters=10200(缓存 200)

# 4. 运行基准（模拟器需官方抑制参数）
gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
```

## 结果解读

- `benchmark/build/outputs/connected_android_test_additional_output/benchmark/connected/<device>/com.mvxxcb.reader.benchmark-benchmarkData.json`
  —— RunResults：每指标带 `runs`（每帧/每次原始值），`sampledMetrics` 带库内 P50/P90/P95/P99。
- `benchmark/build/outputs/androidTest-results/connected/benchmark/TEST-*.xml` —— JUnit 结果。
- jank% 计算：`frameDurationCpuMs > 16.67ms` 的帧占比（60Hz 预算）；`frameOverrunMs > 0` 为错过 deadline 帧占比。聚合脚本见本目录 `stats-summary.json` 上游口径。

## 文件清单

| 文件 | 说明 |
|---|---|
| `baseline-2.0.0-summary.md` | 2.0.0 基线报告（逐场景 P50/P90/P95/P99、jank%、设备口径、限制） |
| `stats-summary.json` | 统计聚合（机器可读） |
| `raw-benchmarkData.json` | Macrobenchmark 原始结果快照 |
| `raw-connected-test-result.xml` | connected 测试 JUnit XML 快照 |
| `fixture-gen-m0-sql.js` | 夹具 SQL 生成脚本（node 运行） |

## 已知约束

- 模拟器（swiftshader 软件 GPU、未锁频）：绝对值偏保守，仅支持同口径相对对比；真机跑法相同（无需抑制参数）。
- Baseline Profile 生成本轮未做（`androidx.baselineprofile` 插件未接入），列为二期后续。
