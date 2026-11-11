// app 模块构建脚本 —— WP-M 生成（04 §1）；M5（WP-G）追加 Baseline Profile 消费端接线
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.baselineprofile)
}

// M5（WP-G）：Baseline Profile 消费端配置（androidx.baselineprofile 1.3.4）
// - profile 由 :benchmark 模块的 StartupBaselineProfile 生成，经下方 baselineProfile(project(":benchmark"))
//   依赖流入本模块；生成任务 :app:generateReleaseBaselineProfile 需连接模拟器/真机，详见
//   docs/phase2/benchmarks/baseline-profile-setup.md。
// - automaticGenerationDuringBuild = false：关闭「构建 release 变体时自动触发生成」的默认行为，
//   避免无设备环境构建 release 失败；生成一律走显式任务，产物提交进仓库后随 release 打包。
// - 本模块无 productFlavors，profile 默认仅进 release 变体（debug/benchmark 变体不受影响）。
baselineProfile {
    automaticGenerationDuringBuild = false
}

android {
    namespace = "com.mvxxcb.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mvxxcb.reader"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0.0"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
        // M0 性能基准（WP-Bench）：Macrobenchmark 目标变体——不可调试（Macrobenchmark 硬性要求）、
        // 不混淆、debug 签名，与 release 行为对齐。
        create("benchmark") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // LogcatLogger 依赖 BuildConfig.DEBUG（common/log），AGP 8 默认关闭，显式开启
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // Robolectric 需要（01 §4.2 / 04 WP-Q）
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        // WP-M（T-D4）：Room schema JSON 挂载，供 Robolectric 单测的 MigrationTestHelper
        // 按 com.mvxxcb.reader.data.db.AppDatabase/<version>.json 装载。
        //
        // 实证（AGP 8.7.3）：test sourceSet 挂 assets 不生效——插件无 mergeDebugUnitTestAssets
        // 任务，test_config.properties 的 android_merged_assets 指向应用 debug 变体的
        // mergeDebugAssets（仅合并 main+debug buildType），Robolectric 资产只认该目录。
        // 故 schema 需挂 debug 变体 assets：单测可用，release APK 不含 schema。
        getByName("debug") {
            assets.srcDir("$projectDir/schemas")
        }
    }
}

ksp {
    // Room schema 导出（03 §6：schema JSON 随仓库提交，QA 迁移测试依据）
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // M5（WP-G）：Baseline Profile 生产者（:benchmark 模块的 BaselineProfileRule 测试）。
    // 该 configuration 仅被 :app:generateReleaseBaselineProfile 任务消费，不参与常规构建。
    baselineProfile(project(":benchmark"))

    // —— Compose（BOM 统一版本） ——
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    // —— AndroidX ——
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)

    // —— Room（KSP） ——
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // —— DataStore / 协程 / 后台任务 ——
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    implementation(libs.work.runtime.ktx)

    // —— 网络 / 解析 / 序列化 / 图片 ——
    // jsoup 与 okhttp 为单模块工程，所有层共用（依赖红线靠 import 纪律约束，01 §2）
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)

    // M0 性能基准（WP-Bench）：Baseline Profile 运行时安装器（benchmark 变体与后续基线生成必备）
    implementation(libs.profileinstaller)

    // —— debug 专用 ——
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // —— 单元测试（WP-P/WP-D/WP-S/WP-Q 共用声明） ——
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    testImplementation(libs.room.testing)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockwebserver)
    // WP-M（T-D1/T-D4）：work-testing（WorkManagerTestInitHelper，二期 Worker 测试用）
    testImplementation(libs.work.testing)

    // —— 仪器测试（WP-U/WP-Q） ——
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.coroutines.test)
}
