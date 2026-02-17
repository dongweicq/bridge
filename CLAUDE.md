# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Bridge APK is an Android automation bridge for OpenClaw (an AI agent running on Termux). It enables UI automation on Android without root access by using AccessibilityService and NotificationListenerService to interact with apps like WeChat.

**Status**: Early development phase - specifications complete, source code to be implemented.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    HTTP Layer (NanoHTTPD)               │
│                  Non-blocking, enqueue-only             │
│                    Port: 127.0.0.1:7788                 │
└─────────────────────┬───────────────────────────────────┘
                      │ Task Queue (persistent)
┌─────────────────────▼───────────────────────────────────┐
│               Action Engine (HandlerThread)             │
│           Single-threaded UI automation execution       │
└─────────────────────┬───────────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────────┐
│              AccessibilityBridgeService                 │
│           UI reading, clicking, input, scroll           │
└─────────────────────────────────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────────┐
│            NotificationListenerService                  │
│               Monitor incoming messages                 │
└─────────────────────────────────────────────────────────┘
```

## Threading Model (Critical)

**UI automation MUST run on BridgeActionThread (HandlerThread), never on:**
- Main Thread
- Dispatchers.IO
- Dispatchers.Default
- Any thread pool

```kotlin
object ActionDispatcher {
    private val handlerThread = HandlerThread("BridgeActionThread").apply { start() }
    private val handler = Handler(handlerThread.looper)
    val dispatcher = handler.asCoroutineDispatcher()
}
```

## HTTP API (Port 7788)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/ping` | GET | Health check |
| `/health` | GET | Status, uptime, queue_length, mode |
| `/chat_list` | GET | Recent contacts |
| `/chat_history?target=NAME&limit=N` | GET | Message history |
| `/send_message` | POST | Queue message send |
| `/task_status?id=TASK_ID` | GET | Task execution status |
| `/config` | POST | Dynamic configuration |

## Technology Stack

- **Language**: Kotlin
- **HTTP Server**: NanoHTTPD (lightweight)
- **Concurrency**: Kotlin Coroutines + HandlerThread
- **Persistence**: Room or JSON file
- **Min SDK**: Android 9+ (API 28)

## Key Modules (Planned)

1. **BridgeServer**: HTTP routing, authentication, request queueing
2. **AccessibilityBridgeService**: UI operations (openApp, findAndClick, setText, scroll)
3. **NotificationListener**: Message monitoring with watchdog
4. **UILocator**: Multi-strategy node finding (text → content-desc → resource-id → class+index)
5. **ActionEngine**: High-level API to action sequence mapping
6. **StateManager**: Running state, error counting, safe mode
7. **ConfigManager**: Dynamic parameters, black/white lists, rate limiting

## Critical Implementation Rules

1. **HTTP layer only enqueues tasks, returns `queued` immediately** - never blocks on UI
2. **TaskQueue must persist** for crash recovery
3. **WakeLock + Keyguard dismiss** before UI operations
4. **Hide keyboard** (`GLOBAL_ACTION_BACK`) before clicking send button
5. **NotificationListener watchdog**: call `requestRebind()` periodically
6. **Safe mode**: after N consecutive failures, stop sending (only monitor)

## Node Location Strategy

Priority order for finding UI nodes:
1. Text match
2. Content-description
3. Resource-id
4. ClassName + index

Always implement fallbacks - WeChat UI changes between versions.

## Documentation

Detailed specifications in `doc/`:
- `BRIDGE_SPEC.md` - v1 initial spec
- `BRIDGE_SPEC_v2.md` - v2 with engineering review improvements
- `BRIDGE_SPEC_v3.md` - v3 production-ready spec (authoritative)

## Development Workflow

### GitHub Repository

**Repository URL**: https://github.com/dongaicloud/bridge

---

## 版本更新自动化流程 (重要)

每次版本更新必须按照以下自动化流程执行：

### 仅文档更新 (不触发构建)

当**只修改文档文件**时，只需提交，不需要触发构建和部署：

```bash
# 仅提交文档
git add *.md
git commit -m "docs: 更新文档说明"
git push

# 不需要执行构建和部署流程
```

**仅文档更新的文件类型：**
- `*.md` - Markdown 文档
- `LICENSE` - 许可证文件
- `.gitignore` - Git 忽略规则

### 代码更新 (触发完整流程)

当修改**代码文件**时，执行完整流程：

```bash
# 提交代码
git add -A
git commit -m "feat: 新功能"
git push

# 等待构建 → 下载 → 安装 → 监控
```

**需要触发构建的文件类型：**
- `*.kt`, `*.java` - Kotlin/Java 代码
- `*.xml` - 布局和配置文件
- `*.gradle*` - Gradle 构建配置
- `*.yml`, `*.yaml` - CI/CD 配置

---

### 1. 提交代码 (Commit)
```bash
git add -A
git commit -m "feat: 描述本次更新的内容"
git push origin phase1-foundation
```

### 2. 等待 GitHub Actions 构建 (~2-3 分钟)
- 查看构建状态: https://github.com/dongaicloud/bridge/actions
- 构建完成后会自动创建 Release

### 3. 下载 APK
```bash
# 使用自动部署脚本下载最新版本
bash auto_deploy.sh download
# 或 Windows
.\auto_deploy.ps1 -Action download
```

### 4. 安装到设备
```bash
# 完整流程：下载 → 安装 → 启动
bash auto_deploy.sh
# 或 Windows
.\auto_deploy.ps1
```

### 5. 监控日志
```bash
bash auto_deploy.sh logs
# 或 Windows
.\auto_deploy.ps1 -Action logs
```

### 6. 更新 CHANGELOG.md (必须)
每次版本更新后，**必须**在 `CHANGELOG.md` 中记录更新内容：

```markdown
## [v1.0.XX] - YYYY-MM-DD

### Added
- 新增功能描述

### Changed
- 修改内容描述

### Fixed
- 修复问题描述
```

### 一键完整更新流程
```bash
# 1. 提交代码
git add -A && git commit -m "feat: 新功能描述" && git push

# 2. 等待构建完成后，执行自动部署
bash auto_deploy.sh

# 3. 监控运行日志
bash auto_deploy.sh logs
```

---

### Quick Deploy (推荐)

使用自动部署脚本一键下载、安装、启动：

**Windows PowerShell:**
```powershell
# 完整流程：下载最新版本 → 安装 → 启动
.\auto_deploy.ps1

# 监控日志
.\auto_deploy.ps1 -Action logs

# 测试 API
.\auto_deploy.ps1 -Action test
```

**Git Bash / Linux:**
```bash
# 完整流程
bash auto_deploy.sh

# 监控日志
bash auto_deploy.sh logs

# 测试 API
bash auto_deploy.sh test
```

**可用命令:**

| 命令 | 功能 |
|------|------|
| `full` (默认) | 下载最新 APK → 安装 → 启动 |
| `download` | 仅下载最新 APK |
| `install` | 仅安装本地 APK |
| `start` | 启动应用 |
| `logs` | 实时监控 Bridge 日志 |
| `test` | 测试 API 端点 |
| `quick` | 快速更新（使用本地 APK） |

### Manual Build & Deploy Process

This project uses GitHub Actions for CI/CD. **Do NOT build locally** - always use GitHub Actions.

#### Steps to Deploy:

1. **Commit and Push Changes**:
   ```bash
   git add -A
   git commit -m "Your commit message"
   git push origin main
   ```

2. **Wait for GitHub Actions Build** (~2-3 minutes):
   - View build status: https://github.com/dongaicloud/bridge/actions
   - Build creates both `app-debug.apk` and `app-release-unsigned.apk`

3. **Download APK from Releases**:
   - Releases URL: https://github.com/dongaicloud/bridge/releases
   - Download command:
   ```bash
   curl -L -o app-debug.apk "https://github.com/dongaicloud/bridge/releases/download/v1.0.XX/app-debug.apk"
   ```

4. **Install APK via ADB**:
   ```bash
   adb uninstall com.bridge
   adb install -r app-debug.apk
   adb shell am start -n com.bridge/.MainActivity
   ```

### Version Numbering

- Version is automatically incremented by GitHub Actions
- Format: `v1.0.XX` where XX is the build number

### Coordinate Configuration Feature (v1.0.31+)

Bridge APP includes a coordinate calibration system for device-specific touch positioning:

1. **Grant Permissions**:
   - Enable Accessibility Service (无障碍服务)
   - Grant Overlay Permission (悬浮窗权限)

2. **Calibrate 5 Steps**:
   - Step 1: Search button (微信首页右上角放大镜)
   - Step 2: IME clipboard (输入法剪贴板位置)
   - Step 3: Contact (搜索结果联系人)
   - Step 4: Message input (消息输入框)
   - Step 5: Send button (发送按钮)

3. **How to Calibrate**:
   - Click "获取" button for each step
   - APP auto-executes pre-steps (opens WeChat, navigates to target screen)
   - An overlay appears - tap the target position
   - Coordinates are saved as screen ratios (0.0-1.0)

4. **Test Configuration**:
   - Click "测试发送消息" to verify calibration

---

## 自动化规则 (重要)

### 版本发布后必须执行

每次版本构建完成后，**必须**执行以下步骤：

1. **更新 CHANGELOG.md**
   - 在文件顶部添加新版本的更新记录
   - 格式：
     ```markdown
     ## [v1.0.XX] - YYYY-MM-DD

     ### Added
     - 新增功能

     ### Changed
     - 修改内容

     ### Fixed
     - 修复问题
     ```

2. **提交变更日志更新**
   ```bash
   git add CHANGELOG.md
   git commit -m "docs: update CHANGELOG for v1.0.XX"
   git push
   ```

3. **验证部署**
   ```bash
   bash auto_deploy.sh logs  # 监控运行状态
   bash auto_deploy.sh test  # 测试 API
   ```

### Claude 代码修改后自动流程

当 Claude 修改代码后，应自动执行：

```bash
# 1. 提交代码
git add -A
git commit -m "描述修改内容"
git push

# 2. 等待构建 (~3分钟)

# 3. 下载安装
bash auto_deploy.sh

# 4. 监控日志
bash auto_deploy.sh logs
```

### 检查清单

- [ ] 代码已提交
- [ ] GitHub Actions 构建成功
- [ ] APK 已下载安装
- [ ] 应用正常启动
- [ ] CHANGELOG.md 已更新
- [ ] 日志无异常错误

---

## 提交前隐私检查 (强制)

每次提交代码前，**必须**检查是否包含隐私敏感文件：

### 禁止提交的文件类型

| 类型 | 扩展名 | 说明 |
|------|--------|------|
| 图片 | `.jpg`, `.jpeg`, `.png`, `.gif`, `.bmp`, `.webp` | 可能包含敏感信息 |
| 截图 | `*screenshot*`, `*截图*` | 禁止提交任何截图 |
| 敏感配置 | `.env`, `secrets.*`, `*_private*` | 密钥、令牌等 |
| 证书 | `.jks`, `.keystore` | 签名证书 |
| 数据库 | `.db`, `.sqlite` | 本地数据 |
| 压缩包 | `.zip`, `.rar`, `.7z` | 可能包含敏感数据 |

### 提交前检查命令

```bash
# 1. 查看待提交文件
git status

# 2. 检查是否有敏感文件
git diff --cached --name-only | grep -E '\.(jpg|png|gif|jpeg|env|secret|private|jks|keystore)$'

# 3. 如果有敏感文件，从暂存区移除
git reset HEAD <敏感文件路径>

# 4. 确认后再提交
git commit -m "描述"
```

### 自动检查脚本

在提交前自动检查：

```bash
# 添加到 .git/hooks/pre-commit
#!/bin/bash
# 检查敏感文件
SENSITIVE=$(git diff --cached --name-only | grep -E '\.(jpg|png|gif|jpeg|env|secret|private|jks)$' || true)
if [ -n "$SENSITIVE" ]; then
    echo "错误: 检测到敏感文件，禁止提交:"
    echo "$SENSITIVE"
    exit 1
fi
```

### 敏感文件处理

如果误提交了敏感文件：

```bash
# 从 Git 历史中彻底删除
git filter-branch --force --index-filter \
  'git rm --cached --ignore-unmatch 敏感文件路径' \
  --prune-empty --tag-name-filter cat -- --all

# 强制推送 (谨慎使用)
git push origin --force --all
```
