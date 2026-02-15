# Bridge APK 需求与实现规范 v3

## Industrial-Grade Android Automation Bridge Specification

版本：v3.0\
状态：Production Ready\
定位：OpenClaw Android 执行层

------------------------------------------------------------------------

# 1. 设计目标

Bridge APK 是 OpenClaw 在 Android 手机上的执行代理，负责：

-   接收 OpenClaw 指令
-   自动操作微信等 App UI
-   读取聊天记录
-   自动发送消息
-   支持无人值守运行
-   保证线程安全与严格顺序执行

核心设计原则：

-   非 Root 自动化
-   单线程 UI 执行模型
-   非阻塞网络层
-   可恢复架构
-   工业级稳定性

------------------------------------------------------------------------

# 2. 并发架构模型

系统线程分层：

Main Thread\
→ UI、Service 生命周期管理

HTTP Thread Pool\
→ 接收请求\
→ enqueue 任务\
→ 立即返回 queued

BridgeActionThread（核心线程）\
→ 串行执行 UI 自动化

NotificationListener Thread\
→ 接收微信消息通知

------------------------------------------------------------------------

# 3. 非阻塞任务流

OpenClaw → HTTP POST /send_message

Bridge HTTP Server → validate request → enqueue task → return { status:
queued }

BridgeActionThread → dequeue task → execute UI automation → mark
complete

------------------------------------------------------------------------

# 4. Accessibility Action Dispatcher（核心线程隔离）

## 核心铁律

禁止在以下线程执行 UI 自动化：

-   Main Thread
-   Dispatchers.IO
-   Dispatchers.Default
-   任意线程池

原因：

-   防止 ANR
-   防止 UI 卡顿
-   防止并发错序

------------------------------------------------------------------------

## 标准实现（HandlerThread 协程调度器）

``` kotlin
object ActionDispatcher {

    private val handlerThread =
        HandlerThread("BridgeActionThread").apply {
            start()
        }

    private val handler =
        Handler(handlerThread.looper)

    val dispatcher =
        handler.asCoroutineDispatcher()
}
```

优势：

-   单线程
-   生命周期可控
-   可在 Profiler 可视化
-   不阻塞主线程

------------------------------------------------------------------------

# 5. Action Engine 执行模型

``` kotlin
class ActionEngine {

    suspend fun execute(task: Task) {

        withContext(ActionDispatcher.dispatcher) {

            wakeDeviceIfNeeded()

            openWeChat()

            openChat(task.contact)

            sendMessage(task.message)
        }
    }
}
```

保证：

严格顺序执行。

------------------------------------------------------------------------

# 6. TaskQueue 持久化

必须支持：

-   enqueue
-   dequeue
-   crash recovery

推荐实现：

-   Room 或
-   JSON persistence

------------------------------------------------------------------------

# 7. WakeLock 与 Keyguard

执行前必须：

``` kotlin
wakeLock.acquire()

if (keyguardManager.isKeyguardLocked) {
    dismissKeyguard()
}
```

确保设备可操作。

------------------------------------------------------------------------

# 8. NotificationListener Watchdog

必须实现：

``` kotlin
NotificationListenerService.requestRebind()
```

防止监听器失效。

------------------------------------------------------------------------

# 9. 输入法防护

执行发送前：

``` kotlin
performGlobalAction(GLOBAL_ACTION_BACK)
```

避免软键盘遮挡。

------------------------------------------------------------------------

# 10. HTTP 非阻塞规范

HTTP 层必须：

仅 enqueue\
立即返回 queued

禁止执行 UI 操作。

------------------------------------------------------------------------

# 11. 生产级保证

该架构保证：

零 ANR 风险\
严格执行顺序\
完全线程隔离\
可无人值守运行

------------------------------------------------------------------------

# 状态

Production Ready
