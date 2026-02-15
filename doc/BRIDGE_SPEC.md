# Bridge APK 需求与实现规范（生产级）
版本：1.0
目的：为 OpenClaw（运行在 Termux 的“大脑”）提供一个在无 root Android 手机上“身体”——Bridge APK。
功能目标：通过 Android AccessibilityService + NotificationListener + 内置 HTTP Server 提供感知与执行接口，供 OpenClaw 读取聊天界面、滚动历史、监听新消息并发送消息。

---
## 1 需求总览（需求清单）

### 功能需求（必须）
1. 本地 HTTP 服务（建议端口：127.0.0.1:7788），提供 REST API：`/ping`、`/chat_list`、`/chat_history`、`/send_message`、`/health`。
2. AccessibilityService：读取屏幕 UI、定位聊天列表与聊天消息、执行点击/输入/滚动等操作；能滚动并抓取聊天历史文本。
3. NotificationListenerService：监听新消息通知，推送最小结构化消息（title、text、package、timestamp）到 Agent 或供 Agent 轮询。
4. 前台服务（Foreground Service）：启动时进入前台，展示常驻通知，避免被系统杀死。
5. 可配置项（UI 或配置文件）：HTTP 端口、调试模式、模拟输入延迟、黑名单联系人、静默时间窗口、日志级别。
6. 安全性：仅监听本机回环请求（127.0.0.1）或允许用户明确配置的远程控制口；支持基础认证 / token 验证（可选但强烈建议）。
7. 日志与错误回报：提供 `/health` 与日志端点，错误率超过阈值时进入“安全模式”（只通知不发送）。
8. 权限提示与用户同意：在首次运行时弹出明确说明，告知需开启无障碍与通知权限，并要求用户确认。

### 非功能需求（重要）
1. 无需 root；使用官方 API（Accessibility、NotificationListener）实现所有功能。
2. 尽量避免依赖硬编码 resource-id；实现多策略 UI 定位器以兼容微信升级。
3. 响应延迟小于 5s（常规操作）；发送消息应有 95% 成功率（稳定实现目标）。
4. 支持 Android 9+（优先），并向上兼容 Android 13/14 的行为差异（例如后台限制）。
5. 能在前台服务被暂时停止时做优雅降级（记录未发送队列与恢复逻辑）。

---
## 2 API 规范（本地 HTTP 接口）

> 默认监听地址：`http://127.0.0.1:7788`（注意在 Android 里只能从本机访问）

### GET /ping
- 描述：健康检查。
- 返回：`{ "status": "ok", "version": "1.0" }`

### GET /health
- 描述：返回运行状态与统计（uptime、last_error、queue_length、mode）
- 返回示例：
```json
{
  "status":"ok",
  "uptime":"5h12m",
  "last_error":null,
  "queue_length":0,
  "mode":"normal"
}
```

### GET /chat_list
- 描述：扫描聊天列表并返回最近联系人条目（基于 UI 可见或最近消息）；如果数据不足可只返回最近 20 条。
- 返回字段：`[{ "name":"张三", "last_snippet":"你好", "last_time":"2026-02-14T09:22:00", "unread":2 }, ...]`

### GET /chat_history?target=NAME&limit=N
- 描述：返回 `target` 的最近 N 条消息（会在后台执行：打开聊天、滚动、读取文本、关闭或返回）
- 返回字段：[ { "role":"remote|local", "text":"...", "time":"..." }, ... ]

### POST /send_message
- 描述：向指定联系人发送消息。
- Body JSON：`{ "target":"张三", "message":"你好", "mode":"immediate|queue" }`
- 返回：`{ "status":"ok", "sent":true, "detail":"..." }` 或 `{ "status":"error", "error":"..." }`

### POST /config
- 描述：动态调整配置（需认证）
- Body 可选项：`{ "simulate_delay":true, "min_delay_ms":300, "max_delay_ms":1000, "blacklist":["A","B"] }`

### Webhook / SSE (可选)
- 描述：当 NotificationListener 捕获新消息时，Bridge 可以主动通过 Webhook 或 Server-Sent Events 推送到 `http://127.0.0.1:PORT/callback`（由 OpenClaw 在初始协商时注册）。

---
## 3 技术实现建议（关键模块）

### 技术栈建议
- 语言：Kotlin（官方）或 Java
- HTTP Server：NanoHTTPD（轻量）或 Ktor Embedded（更现代）
- 用到的系统 API：AccessibilityService、NotificationListenerService、Foreground Service、InputMethodManager (ACTION_SET_TEXT via Bundle)、AccessibilityNodeInfo.performAction(ACTION_CLICK / ACTION_SET_TEXT / ACTION_SCROLL_FORWARD)
- 并发：使用 HandlerThread / Coroutine（Kotlin）执行 UI 操作序列，HTTP 请求到来后在非 UI 线程调度到 Accessibility 主线程执行

### Gradle 依赖示例（Kotlin 项目）
```gradle
implementation 'org.nanohttpd:nanohttpd:2.3.1'
implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4'
// 其他 AndroidX 依赖
```

### 模块划分
1. `BridgeServer`：NanoHTTPD 启动、路由解析、身份验证、请求队列化
2. `AccessibilityBridgeService`：实现 AccessibilityService，封装常用动作（openApp, findAndClick, setText, scroll）
3. `NotificationListener`：实现 NotificationListenerService，解析并缓存通知，提供轻量 callback 推送
4. `UI Locator`：多策略定位器（text match, className path, sibling relation, fuzzy match）
5. `Action Engine`：把高层 API（send_message）映射为动作序列（open chat → wait → input → send → verify）
6. `State Manager`：维护运行状态、前台通知、错误计数和安全模式
7. `Config Manager`：动态参数调整、黑白名单、节奏控制（随机延迟）

---
## 4 Accessibility 操作实现要点（工程级细节）

### 节点定位策略（强烈建议）
1. **文本优先**：`root.findAccessibilityNodeInfosByText("某关键文本")` 有时可以直接定位消息或按钮。
2. **層级回溯**：从已知稳定节点（例如顶部 Title、聊天列表容器）回溯到消息项。
3. **模糊匹配**：当 resource-id 变化时，用 className + 文本模式 + index 匹配。
4. **容错重试**：每个查找尝试 3 次，每次等待 300-600ms。
5. **滚动读取**：对聊天窗口执行 `performAction(ACTION_SCROLL_FORWARD)` 或 `ACTION_SCROLL_BACKWARD` 并累积节点文本直到满足 `limit`。
6. **避免硬编码坐标**：仅在无法用节点定位时作为最后手段（并提供设备分辨率校准工具）。

### 输入文本细节（兼容性）
- 优先使用 `AccessibilityNodeInfo.ACTION_SET_TEXT`（更稳定，Android 7+ 支持）
- 兼容方案：通过剪贴板 + 长按粘贴（在某些微信版本 ACTION_SET_TEXT 受限）
- 模拟输入节奏：加入 100–400ms 的字符间延迟或统一随机延迟以防检测
- 点击发送后需检测发送是否成功（读取最新消息时间与文本以校验）

### 权限与用户引导
- 首次运行弹窗引导用户：打开无障碍服务、允许通知访问、将应用设为“不受电池优化”
- 在设置页展示权限说明与风险提示，取得明确同意并在本地记录时间戳

---
## 5 UI 解析与稳定性对策（应对微信变更）

1. **多策略定位器**：按优先级尝试 text → content-desc → resource-id → class+index。若一项失败，则回退到下一项。
2. **回退模式**：若主定位策略连续 N 次失败（N 可配置，推荐 5 次），进入“安全模式”：仅做通知监听不发消息，并上报错误（可写入 `/health`）
3. **自动化回归脚本**：在开发阶段提供一组 UI 测试脚本（ADB + UIAutomator）用于在微信更新后快速定位变化节点并生成新策略规则
4. **人机调试模式**：Bridge 提供 “inspect” API：打开指定聊天并把 UI 节点树序列化返回（仅在调试模式下启用）

---
## 6 性能、并发与安全

- HTTP 请求应排队执行，避免多个并发 send 导致 UI 冲突。
- 建议单线程 Action Engine（即请求按序执行）；对于通知监听可并发入队，但实际 UI 操作必须串行。
- 支持 token 验证（bridge 启动时生成随机 token，OpenClaw 在第一次连接时读取并保存）
- 日志级别与敏感数据屏蔽：日志中避免记录完整消息文本时可用摘要或哈希替代

---
## 7 错误处理与恢复策略

- 每个 send 操作应返回 `status:ok|error|retry`，并附带 `detail` 字段
- 错误重试策略：可重试错误（UI timeout, transient failure）最多 3 次，指数退避
- 如果连续 10 次错误（可配置），Bridge 进入“降级模式”并通知 Agent（返回 `mode:degraded`）
- 持久化未发送队列：发送失败的消息记录到本地文件 `pending_queue.json`，Bridge 重启后自动恢复或通知 Agent

---
## 8 采集隐私与合规（必须实现）

- 安装与首次运行时必须展示明确告知并记录同意（存本地）
- 本地消息存储（history、pending_queue）需要加密（可用 Android Keystore + AES）
- 如果要上传任何消息或日志至远端（服务器/第三方 API），必须二次确认用户并提供选择开关
- 提供“紧急停止”按钮使用户立即关闭无障碍与停止所有自动化行为

---
## 9 构建、调试与部署建议

### 构建（Android Studio）
1. Clone 项目
2. Open in Android Studio（使用 Kotlin + Gradle）
3. Add NanoHTTPD & Coroutines dependency
4. Build → Generate Signed Bundle / APK（为长期运行建议签名并在安装后设置为受保护应用）

### 调试流程
1. 用 ADB 观察 Logcat：`adb logcat | grep Bridge`
2. 使用 Postman 调用 `/send_message`，观察 UI 行为
3. 开发 `inspect` API 在调试阶段导出 UI 节点树，帮助定位
4. 每次微信升级后运行回归脚本（ADB + UIAutomator）

### 部署
- 打包签名 APK 并通过 USB / 本地网络安装
- 第一次运行完成权限引导并测试 `/ping` 与 `/send_message`
- 在 Termux 启动 OpenClaw 并测试端到端功能

---
## 10 示例代码片段（关键片段）

### AccessibilityService（Kotlin 伪代码，核心发送逻辑）
```kotlin
class AccessibilityBridge : AccessibilityService() {

    companion object { var instance: AccessibilityBridge? = null }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun sendMessage(target: String, message: String): Boolean {
        // 1. 打开微信主界面
        val pm = packageManager.getLaunchIntentForPackage("com.tencent.mm")
        pm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(pm)
        // wait for UI
        Thread.sleep(1200)

        // 2. 点击搜索或联系人入口 - 通过定位器实现
        val searchNode = findNodeByText("搜索") ?: findNodeById("com.tencent.mm:id/search")
        searchNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Thread.sleep(500)

        // 3. 输入 target
        val inputNode = rootInActiveWindow.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, target)
        inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Thread.sleep(700)

        // 4. 点击联系人项并进入聊天，读取确认，然后输入 message
        // ...寻找聊天项并点击...

        // 5. 输入消息
        val msgBox = findMessageInputBox()
        val setArgs = Bundle()
        setArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
        msgBox.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
        Thread.sleep(300)

        // 6. 点击发送
        val sendBtn = findSendButton()
        sendBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Thread.sleep(500)

        // 7. 校验：读取最近消息确认发送成功
        val latest = readLatestMessage()
        return latest?.text?.contains(message.take(8)) ?: true
    }
}
```

### NanoHTTPD 简单路由（Kotlin/Java 伪代码）
```kotlin
class BridgeServer(port:Int): NanoHTTPD(port) {
  override fun serve(session:IHTTPSession): Response {
    when (session.uri) {
      "/ping" -> return newFixedLengthResponse("{\"status\":\"ok\"}")
      "/send_message" -> {
         val body = parseBody(session)
         AccessibilityBridge.instance?.let { it.sendMessage(body.target, body.message) }
         return newFixedLengthResponse("{\"status\":\"ok\"}")
      }
    }
    return newFixedLengthResponse("{\"status\":\"unknown\"}")
  }
}
```

---
## 11 QA（常见问题）

**Q1：Bridge 是否会被微信或系统认为是“机器人”并封号？**  
A：Bridge 模拟真人操作（随机延迟、打字模拟、限制高频），并且不使用非官方 API。风险低，但依然建议保守的发送频率与白名单机制。

**Q2：Bridge 在微信升级后会失效吗？**  
A：可能。需要运行时日志与 inspect 工具快速定位变化并更新定位策略。

**Q3：消息数据是否安全？**  
A：只要消息保存在本地并启用加密，不上传外部，安全性是可控的。

---
## 12 开源合规与许可证建议
- 使用 MIT/Apache 2.0 许可证开源 Bridge 源码（如果内部使用可选择私有仓库）
- 明确第三方库许可（NanoHTTPD 等）并在 README 记录

---
## 13 交付物与后续工作建议
- 交付：完整 Android Studio 项目源码（Kotlin）、签名 APK、README（安装与权限指引）、调试与回归脚本、示例 Postman collection、Bridge API 文档
- 后续：实现自动化回归（GitHub Actions + ADB + UIAutomator），并把问题回报纳入告警系统

---
## 14 附：用户首次授权文案（示例）
> 本应用为辅助自动化工具，需要启用“无障碍服务”和“通知读取”权限以实现自动读取聊天内容与发送消息。应用将在本机本地工作，不会上传聊天内容到任何外部服务器，除非您明确授权。启用后，您可以在应用设置中随时撤销权限并停止所有自动化行为。

---
# 完
