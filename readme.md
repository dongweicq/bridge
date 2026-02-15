# Bridge APK

OpenClaw 微信自动化桥接服务。

## 概述

Bridge 是一个 Android 应用，作为 OpenClaw 的"微信操作手"，通过 AccessibilityService 实现微信自动化操作。

```
┌─────────────────────────────────────────────────────────┐
│                    Android 手机                          │
│                                                         │
│  ┌─────────────────┐                                    │
│  │   Bridge APK    │                                    │
│  │                 │                                    │
│  │ HTTP :7788      │                                    │
│  │ - 微信自动化    │                                    │
│  │ - 聊天读取/发送 │                                    │
│  └────────┬────────┘                                    │
│           │                                             │
└───────────┼─────────────────────────────────────────────┘
            │ HTTP :7788
            │
┌───────────┴─────────────────────────────────────────────┐
│                    Termux                                │
│                                                         │
│              ┌─────────────────────┐                    │
│              │     OpenClaw        │                    │
│              │      (大脑)         │                    │
│              └─────────────────────┘                    │
└─────────────────────────────────────────────────────────┘
```

## 功能

- HTTP API 服务（端口 7788）
- 微信自动发送消息
- 通过 AccessibilityService 实现 UI 自动化
- 前台服务保活
- 任务队列管理

## 项目结构

```
bridge/
├── build.gradle.kts              # 根构建配置
├── settings.gradle.kts           # 项目设置
├── gradle.properties             # Gradle 属性
│
└── app/
    ├── build.gradle.kts          # App 模块配置
    ├── proguard-rules.pro        # 混淆规则
    │
    └── src/main/
        ├── AndroidManifest.xml   # 清单文件
        │
        ├── java/com/bridge/
        │   ├── BridgeApp.kt                  # Application 入口
        │   ├── MainActivity.kt               # 主界面
        │   ├── BridgeService.kt              # 前台服务
        │   ├── BridgeAccessibilityService.kt # 无障碍服务
        │   │
        │   ├── action/
        │   │   ├── ActionDispatcher.kt       # 单线程调度器
        │   │   └── WeChatActionEngine.kt     # 微信操作引擎
        │   │
        │   ├── http/
        │   │   └── BridgeServer.kt           # HTTP 服务器
        │   │
        │   └── model/
        │       └── Task.kt                   # 任务模型
        │
        └── res/
            ├── layout/activity_main.xml      # 主界面布局
            ├── xml/accessibility_service_config.xml
            └── values/...                    # 字符串、颜色、主题
```

## 核心模块

| 文件 | 功能 |
|------|------|
| `BridgeServer.kt` | HTTP API (`/ping`, `/health`, `/send_message`) |
| `ActionDispatcher.kt` | 单线程 UI 调度（防止 ANR） |
| `WeChatActionEngine.kt` | 微信自动化逻辑 |
| `BridgeAccessibilityService.kt` | 无障碍服务封装 |

## 环境要求

- Android Studio (推荐) 或 仅 Android SDK
- JDK 17+
- Android 设备 (API 26+, 即 Android 8.0+)
- SDK 空间: ~500 MB (最小安装)

## 构建

1. 使用 Android Studio 打开项目
2. 同步 Gradle
3. Build → Build APK

或命令行：
```bash
./gradlew assembleDebug
```

## 安装

1. 安装 APK 到手机
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

2. 打开 Bridge 应用

3. 点击"启用无障碍服务"

4. 在列表中找到 Bridge 并开启

5. 返回应用查看状态（应显示"无障碍服务: 已启用 ✓"）

## API 接口

### GET /ping

健康检查。

```bash
curl http://127.0.0.1:7788/ping
# {"status":"ok","version":"1.0"}
```

### GET /health

获取运行状态。

```bash
curl http://127.0.0.1:7788/health
# {
#   "status": "ok",
#   "uptime": "5h12m",
#   "queue_length": 0,
#   "accessibility_enabled": true,
#   "service_running": true
# }
```

### POST /send_message

发送微信消息。

**请求：**
```bash
curl -X POST http://127.0.0.1:7788/send_message \
  -H "Content-Type: application/json" \
  -d '{"target":"张三","message":"你好，这是测试消息"}'
```

**响应：**
```json
{"status":"queued","task_id":"550e8400-e29b-41d4-a716-446655440000"}
```

**错误响应：**
```json
// 无障碍服务未启用
{"status":"error","error":"Accessibility service not enabled"}

// 多个匹配
{"status":"error","error":"找到多个匹配的联系人","candidates":["张三","张三丰"]}
```

### GET /task/{id}

查询任务状态。

```bash
curl http://127.0.0.1:7788/task/550e8400-e29b-41d4-a716-446655440000
```

**响应：**
```json
// 执行中
{"id":"...","status":"running","target":"张三"}

// 成功
{"id":"...","status":"done","target":"张三"}

// 失败
{"id":"...","status":"failed","target":"张三","error":"找不到联系人"}
```

## 架构设计

### 线程模型

```
Main Thread          → UI 生命周期
HTTP Thread Pool     → 接收请求，立即返回
BridgeActionThread   → 串行执行 UI 自动化（核心）
```

**核心规则：所有 UI 自动化操作必须在 BridgeActionThread 执行，禁止在 Main/IO 线程执行。**

### 任务流程

```
OpenClaw → POST /send_message
    ↓
Bridge HTTP Server → 验证请求 → 入队任务 → 返回 { status: queued }
    ↓
BridgeActionThread → 取出任务 → 执行 UI 自动化 → 标记完成
```

## 注意事项

1. **需要开启无障碍服务** - 否则 API 返回错误
2. **微信需要登录** - 操作前确保微信已登录
3. **联系人名称精确匹配** - 建议使用微信显示的完整名称
4. **首次使用需授权** - 在应用内启用无障碍服务
5. **固定微信版本** - 微信 UI 变更可能导致定位失败

## 调试

查看日志：
```bash
adb logcat | grep -E "Bridge|WeChatAction"
```

## 许可证

MIT
