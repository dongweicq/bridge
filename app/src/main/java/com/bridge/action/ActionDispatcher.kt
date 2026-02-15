package com.bridge.action

import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Action Dispatcher - 单线程 UI 操作调度器
 *
 * 核心规则：
 * - 所有 UI 自动化操作必须在此线程执行
 * - 禁止在 Main Thread、Dispatchers.IO、Dispatchers.Default 执行 UI 操作
 */
object ActionDispatcher {

    private const val THREAD_NAME = "BridgeActionThread"

    private val handlerThread = HandlerThread(THREAD_NAME).apply {
        start()
    }

    private val handler = Handler(handlerThread.looper)

    /**
     * 协程调度器 - 用于 withContext(dispatcher) { ... }
     */
    val dispatcher: CoroutineDispatcher = handler.asCoroutineDispatcher()

    /**
     * 获取线程名称（用于调试）
     */
    val threadName: String
        get() = handlerThread.name

    /**
     * 检查是否在 Action 线程
     */
    fun isOnActionThread(): Boolean {
        return Thread.currentThread().name == THREAD_NAME
    }

    /**
     * 关闭（通常不需要调用，除非应用退出）
     */
    fun shutdown() {
        handlerThread.quitSafely()
    }
}
