// M0 性能基准模块（WP-Bench）：Macrobenchmark 标准模板（androidx.benchmark 1.3.4 库依赖）
// 目标：:app（未优化的 2.0.0 基线，benchmark 变体非调试、不混淆）。
// 运行：gradlew :benchmark:connectedBenchmarkAndroidTest（需模拟器/真机）
//
// 插件说明：com.android.test 与根项目已加载的 com.android.application 同属 AGP 构件。
// 根 build.gradle.kts 未声明 android.test（M0 变更白名单不含根文件），子项目 plugins{} DSL
// 带版本请求会报 "plugin is already on the classpath with an unknown version"；此处按官方
// 文档用「无版本 id」应用类路径上已有的插件（根 plugins 已加载 AGP 8.7.3，单实例）。
// androidx.benchmark Gradle 插件仅支持 library 模块（用于 Baseline Profile 生成），
// 官方明确「跑 Macrobenchmark 不需要该插件」——故此处不应用，仅依赖 macro 库。
plugins {
    id("com.android.test")
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mvxxcb.reader.benchmark"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        targetSdk = 35
        // benchmark 1.3 起 AndroidBenchmarkRunner 弃用，官方改用 AndroidJUnitRunner
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 模拟器基准（M0 口径）：软件渲染 + 固定 60Hz，仅同口径相对对比；真机验证需移除本行
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    // 基准模块自身变体：可调试（instrumentation 需要），与目标 app 的 benchmark 变体同名对齐
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    // 与 :app 一致（JVM 17），避免 Kotlin/Java target 不一致
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.benchmark.macro.junit4)
    implementation(libs.uiautomator)
}
