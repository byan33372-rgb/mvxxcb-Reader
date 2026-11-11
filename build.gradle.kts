// 根构建脚本 —— WP-M 生成（04 §1）
// 插件版本统一由 gradle/libs.versions.toml 声明（alias 方式），子模块按需应用。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    // M5（WP-G）：Baseline Profile 生成/集成（app 消费端 + benchmark 生产端同版本声明）
    alias(libs.plugins.androidx.baselineprofile) apply false
}
