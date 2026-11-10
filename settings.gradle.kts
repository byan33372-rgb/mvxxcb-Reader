// 简阅（JianYue）工程设置 —— WP-M 生成（04 §1）
// 仓库顺序（防国内下载失败）：阿里云镜像优先，官方源兜底。
pluginManagement {
    repositories {
        // 阿里云 gradle-plugin 镜像在 google() 之前
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
    }
}

rootProject.name = "JianYue"
include(":app")

// M0 性能基准（WP-Bench）：Macrobenchmark 模块（com.android.test + androidx.benchmark 1.3.4）
include(":benchmark")
