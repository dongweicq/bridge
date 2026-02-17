# BRIDGE_SPEC_v2.md — Bridge APK 需求与实现规范（生产级，含评审改进）
版本：2.0
目的：为 OpenClaw（在 Termux 上运行的大脑）提供在无 root Android 手机上稳定运行的“身体”——Bridge APK。
本版本已吸纳同行评审建议（包括唤醒屏幕/解锁、Android 13+ 权限适配、NotificationListener Watchdog、非阻塞队列执行等）。

---
## 0 说明（简短）

本规范详细列出 Bridge APK 的需求、HTTP 接口、关键实现要点、改进点（来自工程评审）以及代码片段示例，便于直接交付 Android Studio 项目实现与后续迭代。

---
## 1 关键实体（一次性标注）

- OpenClaw — Agent“大脑”，运行在 Termux。  
- Android — 手机系统（Bridge 通过无障碍与其交互）。

---
## 2 核心新增要点（来自评审并已合入）

1. **屏幕唤醒与解锁（WakeLock + Keyguard）**：在执行 openApp / 点击前，Bridge 必须尝试唤醒屏幕并解除键盘锁（在用户允许的范围内），避免在屏幕锁定时操作失败。  
2. **Android 13+ 安装与无障碍开关引导**：对 Android 13/14，提供额外用户引导说明，必要时引导用户到“应用详情”开启“允许受限制的设置”。  
3. **NotificationListener Watchdog**：定期自检 NotificationListener 是否活跃；若检测到“假死”，通过 requestRebind() 或引导用户重启权限。  
4. **非阻塞 HTTP → 队列化执行**：HTTP 接口快速返回 `queued`，将实际 UI 操作放入单线程 Action Engine（HandlerThread / Coroutine）消费，避免阻塞 HTTP 线程池或造成超时。  
5. **发送前隐藏软键盘 / 保证发送按钮可见**：在发送前尝试收起键盘（GLOBAL_ACTION_BACK）并滚动确保发送按钮可见，以避免输入法遮挡。  
6. **持久化未发送队列与降级策略**：失败时持久化 pending queue，系统错误达到阈值后自动进入“安全模式”（仅监听、不发送）。

---
## 3 API 规范（更新）

> 默认监听地址：`http://127.0.0.1:7788`

### GET /ping
返回：`{ "status": "ok", "version": "2.0" }`

### GET /health
返回示例：
```json
{
  "status":"ok",
  "uptime":"5h12m",
  "last_error":null,
  "queue_length":2,
  "mode":"normal",
  "notification_listener_alive": true,
  "wakelock_held": false
}
```

### GET /chat_list
同 v1，返回联系人摘要。

### GET /chat_history?target=NAME&limit=N
会将任务入队，HTTP 返回 `queued`；当读取完成，可通过 Webhook/SSE 或轮询 `/task_status?id=...` 获取结果。

### POST /send_message
- Body JSON：`{ "target":"张三", "message":"你好", "mode":"immediate|queue", "callback":"http://127.0.0.1:9000/cb" }`
- 返回（同步）：`{ "status":"queued", "task_id":"uuid-v4" }`
- 任务消费完成后（或失败），Bridge POST 到 `callback`（如果提供），或在 `/task_status` 查询到结果。

### GET /task_status?id=TASK_ID
返回任务当前状态：`queued|running|done|failed` 与 detail / error 信息。

### POST /config, /inspect 保持不变（需 token 认证）

---
## 4 非阻塞队列与 Action Engine（设计细节）

### 4.1 架构概念
- **HTTP Layer**：接收请求、验证、把动作封装为 `Task` 入 `TaskQueue`，立刻返回 `queued`。
- **TaskQueue**：持久化队列（文件或嵌入 DB），可保证重启后恢复。
- **Action Engine（单线程消费者）**：从队列中取 Task，按序执行 UI 操作，执行结果写回 Task 状态并触发 callback。

### 4.2 Task 数据结构（示例）
```json
{
  "id":"uuid-v4",
  "type":"send_message|read_history|inspect",
  "target":"张三",
  "message":"...",
  "retries":0,
  "max_retries":3,
  "status":"queued",
  "created_at":"...",
  "callback":"http://127.0.0.1:9000/cb"
}
```

### 4.3 消费器行为（伪代码）
```kotlin
// Consumer runs on a dedicated HandlerThread / CoroutineScope(Dispatchers.Main) for UI actions
while (running) {
  task = taskQueue.poll() // blocking with timeout
  if (task == null) { sleep(200); continue }
  setTaskStatus(task.id, "running")
  try {
     acquireWakeIfNeeded()
     ensureScreenUnlockedIfNeeded()
     performActionSequence(task) // open app, locate, input, send, verify
     setTaskStatus(task.id, "done")
     if (task.callback) postCallback(task.callback, result)
  } catch (e) {
     task.retries += 1
     if (task.retries <= task.max_retries) {
        requeueWithBackoff(task)
     } else {
        setTaskStatus(task.id, "failed", e.message)
     }
  } finally {
     releaseWakeIfHeldIfApplicable()
  }
}
```

---
## 5 屏幕唤醒与解锁（实现建议）

### 5.1 WakeLock（示例 Kotlin）
```kotlin
val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
val wl = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "Bridge:WakeLock")
wl.acquire(30000) // 最多 30s
// 记得 try/finally 中释放 wl.release()
```

### 5.2 Keyguard 解锁（示例 Kotlin）
```kotlin
val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
if (km.isKeyguardLocked) {
  val keyguardIntent = Intent(Settings.ACTION_SECURITY_SETTINGS)
  keyguardIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  startActivity(keyguardIntent) // 或使用 KeyguardManager.requestDismissKeyguard(Activity, callback) 在 Activity 场景
  // 注意：完全解锁受限于系统设置；不能绕过安全锁
}
```

**注意**：不能绕过受密码/指纹保护的锁屏。建议用户在使用该功能的设备上设置滑动解锁或允许在可信环境下操作，并在安装/引导时提示。

---
## 6 NotificationListener Watchdog（实现）

- Watchdog 定期（例如每 60s）檢查 `isNotificationListenerEnabled()` 和内部最近接收时间戳。  
- 若检测到异常，可调用 `requestRebind(ComponentName(this, MyNotifListener::class.java))`（Android 7+），并在 `/health` 报告 `notification_listener_alive:false` 并写入日志。  
- 同时触发本地通知引导用户手动重新授权（如果自动重绑失败）。

---
## 7 隐藏键盘与保证发送按钮可见

在输入完文本后执行：
1. `performGlobalAction(GLOBAL_ACTION_BACK)` 试图收起软键盘。  
2. 若仍未见发送按钮，尝试滚动聊天窗口 `ACTION_SCROLL_FORWARD` / `ACTION_SCROLL_BACKWARD` 直到发送按钮可见。  
3. 作为最后手段，可使用剪贴板 + 长按粘贴以避免键盘行为阻挡。

---
## 8 Android 13+ 权限引导（用户向导）

- 检测系统版本：`if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)`  
- 在首次引导页面加入步骤：  
  1. 安装来源设置（若非应用商店安装，提示用户允许“允许受限制的设置”）并提供跳转 `ACTION_MANAGE_UNKNOWN_APP_SOURCES`。  
  2. 引导到无障碍设置页并说明开启方法（并高亮说明 Android 13 特殊项）。  
- 在 README 和安装文档中加入按系统版本的“快速修复指南”。

---
## 9 并发、线程与 HTTP 设计注意事项

- NanoHTTPD（或 Ktor）仅做请求解析与入队，不在 HTTP 线程执行长时 UI 操作。  
- Action Engine 为单线程，保证 UI 操作顺序性与稳定性。  
- 对实时通知（NotificationListener）与轮询请求使用 separate queue 或优先级队列以处理紧急消息（例如：未回复的问题优先）。

---
## 10 失败率监测与安全模式

- 指标收集：连续失败计数、平均执行时间、最近成功 timestamp。  
- 规则示例：连续 10 次 send 失败 → 进入 `degraded` 模式（仅监听，不发送），并把问题写入 log/health，等待人工干预。

---
## 11 代码片段补充（关键 APIs & patterns）

### 11.1 requestRebind 示例（Kotlin）
```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
    try {
        requestRebind(ComponentName(this, MyNotifListener::class.java))
    } catch (e: Exception) {
        Log.w(TAG, "requestRebind failed", e)
    }
}
```

### 11.2 非阻塞 HTTP Handler 示例（伪）
```kotlin
override fun serve(session: IHTTPSession): Response {
  val body = parseBody(session)
  val task = Task.from(body)
  TaskQueue.enqueue(task)
  return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"queued\",\"task_id\":\"${task.id}\"}")
}
```

### 11.3 单线程消费者示例（Kotlin Coroutine）
```kotlin
val consumerScope = CoroutineScope(Dispatchers.Default)
consumerScope.launch {
  while(isActive) {
    val task = taskQueue.poll() // suspending
    if (task != null) {
       withContext(Dispatchers.Main) { performUiActions(task) } // UI actions on main thread
    } else {
       delay(200)
    }
  }
}
```

---
## 12 测试与回归策略（补充）

1. **屏幕锁/解锁测试**：在不同锁屏状态（无锁、滑动、PIN、指纹）验证唤醒与行为，记录支持矩阵。  
2. **Android 13/14 权限路径测试**：针对非市场安装场景做安装引导测试。  
3. **回归脚本**：用 ADB + UIAutomator 批量运行“打开聊天→读取→发送→校验”脚本并输出差异报告。  
4. **故障熔断**：当错误率突增触发自动降级并发送本地通知给用户。

---
## 13 文档与交付（补充）

交付物清单：

- Android Studio 项目（Kotlin）源码
- 签名 APK（可选）
- README（安装步骤、Android 13 特殊引导）
- Postman collection（API 调试）
- 回归脚本（ADB + UIAutomator）
- health & metrics dashboard 示例（可选）

---
## 14 隐私与合规（补充）

- 初次授权提示与日志（记录用户同意时间戳）为必须项；推荐在 UI 上提供“权限历史”查看功能。  
- 本地存储使用 Android Keystore 加密密钥，AES 加密消息历史/待发送队列。  
- 若需上传日志到远端（用于错误分析），必须先取得用户二次授权并提供可选开关。

---
## 15 最后说明与建议路线图

1. 按 v2 规范实现 Bridge 的最小可用版本（MVP）：HTTP 接口 + Accessibility 基本动作 + Queue Consumer + Foreground Service。  
2. 在 MVP 基础上加入 NotificationListener 与 Watchdog、WakeLock/Keyguard 支持、Android 13 引导。  
3. 编写回归脚本并在 CI（可选，需连接测试设备）对每次某信升级跑回归。  
4. 逐步开放主动发送策略（先白名单联系人 → 小范围试点 → 全自动）。

---
# 附件
已生成：`/mnt/data/BRIDGE_SPEC_v2.md`
