package com.mvxxcb.reader

import android.app.Application
import com.mvxxcb.reader.di.AppContainer

/**
 * v0（04 §1）：仅初始化 AppContainer（手动 DI 单例容器）。
 * 保持 onCreate 无阻塞网络请求（01 §8 冷启动预算 ≤ 1.5s）；
 * 后续全局初始化（崩溃兜底等）在此追加，由 WP-M 维护。
 */
class ReaderApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // 2.0 采集引擎：进程重启后对齐 WAITING/RUNNING 任务与 WorkManager 队列（07 风险#7；
        // 异步执行，不阻塞冷启动预算）
        container.onAppStart()
    }
}
