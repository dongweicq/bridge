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
│  │ - 工具链系统    │                                    │
│  │ - 坐标配置      │                                    │
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
- **工具链系统** - 可配置的自动化工具链
- **坐标配置** - 可视化坐标选取（悬浮窗蒙层）
- 微信自动发送消息
- 通过 AccessibilityService 实现 UI 自动化
- 前台服务保活
- 任务队列管理
- 随机延迟模拟人工操作

## 核心概念：工具链系统

Bridge 使用**工具链**系统实现自动化操作：

### 内置工具

| 工具 | 功能 |
|------|------|
| 打开微信 | 启动微信应用 |
| 设置剪贴板 | 将内容复制到剪贴板 |
| 返回 | 模拟返回键 |

### 默认用户工具

| 工具 | 说明 | 前置工具 |
|------|------|----------|
| 搜索按钮 | 微信首页右上角放大镜 | 打开微信 |
| 输入法剪贴板 | 键盘上的剪贴板位置 | 打开微信 → 搜索按钮 |
| 联系人 | 搜索结果中的联系人 | 打开微信 → 搜索按钮 → 输入法剪贴板 |
| 消息输入框 | 聊天界面底部输入框 | ... → 联系人 |
| 发送按钮 | 聊天界面发送按钮 | ... → 消息输入框 → 输入法剪贴板 |

### 工具链执行流程

```
执行"发送按钮"工具:
  1. 打开微信
  2. 点击搜索按钮
  3. 点击输入法剪贴板 (粘贴搜索词)
  4. 点击联系人 (进入聊天)
  5. 点击消息输入框
  6. 点击输入法剪贴板 (粘贴消息)
  7. 点击发送按钮
```

## 项目结构

```
bridge/
├── build.gradle.kts              # 根构建配置
├── settings.gradle.kts           # 项目设置
├── gradle.properties             # Gradle 属性
│
└── app/
    ├── build.gradle.kts          # App 模块配置
    │
    └── src/main/
        ├── java/com/bridge/
        │   ├── BridgeApp.kt                  # Application 入口
        │   ├── MainActivity.kt               # 主界面
        │   ├── ToolManagerActivity.kt        # 工具管理界面
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
        │   ├── model/
        │   │   └── Task.kt                   # 任务模型
        │   │
        │   └── util/
        │       ├── ToolManager.kt            # 工具管理器
        │       ├── Tool.kt                   # 工具数据类
        │       ├── ConfigManager.kt          # 配置管理
        │       ├── CoordinatePicker.kt       # 坐标选择器
        │       └── PinyinUtil.kt             # 拼音工具
        │
        └── res/
            └── layout/
                ├── activity_main.xml          # 主界面
                ├── activity_tool_manager.xml  # 工具管理
                ├── dialog_tool_editor.xml     # 工具编辑对话框
                └── item_tool.xml              # 工具列表项
```

## 环境要求

- Android Studio (推荐)
- JDK 17+
- Android 设备 (API 26+, 即 Android 8.0+)

## 构建

```bash
./gradlew assembleDebug
```

## 安装与配置

### 1. 安装 APK

```bash
adb install app-debug.apk
```

### 2. 启用无障碍服务

设置 → 无障碍 → Bridge → 开启

### 3. 授予悬浮窗权限（用于坐标选取）

设置 → 应用 → Bridge → 悬浮窗 → 允许

### 4. 配置工具坐标

1. 打开 Bridge 应用
2. 点击「工具管理」
3. 对于每个工具，点击「获取」按钮
4. 在悬浮窗蒙层上点击目标位置
5. 点击「确定」保存坐标

### 5. 测试

在首页输入联系人和消息内容，点击「测试发送」验证配置。

## API 接口

### GET /ping

健康检查。

```bash
curl http://127.0.0.1:7788/ping
# {"status":"ok","version":"1.0.60"}
```

### GET /health

获取运行状态。

```bash
curl http://127.0.0.1:7788/health
```

### POST /send_message

发送微信消息。使用工具链系统执行。

```bash
curl -X POST http://127.0.0.1:7788/send_message \
  -H "Content-Type: application/json" \
  -d '{"target":"张三","message":"你好"}'
```

### GET /task/{id}

查询任务状态。

```bash
curl http://127.0.0.1:7788/task/{task_id}
```

## 使用说明

### 首页功能

- **联系人/消息输入框** - 设置默认测试联系人和消息（自动保存）
- **测试搜索** - 搜索联系人并进入聊天界面
- **测试发送** - 完整的发送消息流程

### 工具管理

- **获取** - 执行前置工具链后显示坐标选择蒙层
- **测试** - 使用首页设置的联系人测试工具链
- **编辑** - 修改工具名称、坐标、前置工具
- **删除** - 删除用户自定义工具

### 前置工具配置

每个工具可以配置多个前置工具，执行时按顺序依次执行。支持拖拽排序。

## 权限配置汇总

| 权限 | 路径 | 重要性 |
|------|------|--------|
| 无障碍服务 | 设置 → 无障碍 → Bridge | 必须 |
| 悬浮窗 | 设置 → 应用 → Bridge | 必须（坐标选取） |
| 通知权限 | 设置 → 应用 → Bridge | 推荐 |
| 电池优化 | 设置 → 电池 → 电池优化 → Bridge | 推荐 |
| 自启动 | 设置 → 应用 → Bridge | 小米/OPPO等需要 |

## 调试

查看日志：
```bash
adb logcat -s ToolManager:D Bridge:D WeChatAction:D
```

## 版本更新流程

### 仅文档更新

当只修改 `*.md` 文件时，直接提交即可，**不会触发构建**：

```bash
git add *.md
git commit -m "docs: 更新文档"
git push
```

### 代码更新 (完整流程)

每次代码更新后，执行以下完整流程：

```bash
# 1. 提交代码
git add -A
git commit -m "feat: 新功能描述"
git push

# 2. 等待 GitHub Actions 构建完成 (~2-3分钟)
# 查看构建状态: https://github.com/dongaicloud/bridge/actions

# 3. 一键部署最新版本
bash auto_deploy.sh          # Linux/Mac
.\auto_deploy.ps1            # Windows

# 4. 监控运行日志
bash auto_deploy.sh logs     # Linux/Mac
.\auto_deploy.ps1 -Action logs  # Windows
```

### 手动更新步骤

1. **下载最新 APK**
   ```bash
   # 从 GitHub Releases 下载
   curl -L -o app-debug.apk "https://github.com/dongaicloud/bridge/releases/latest/download/app-debug.apk"
   ```

2. **安装到设备**
   ```bash
   adb install -r app-debug.apk
   adb shell am start -n com.bridge/.MainActivity
   ```

3. **查看运行日志**
   ```bash
   adb logcat -v time BridgeServer:V BridgeAccessibility:V *:S
   ```

### 更新日志

所有版本更新记录见 [CHANGELOG.md](CHANGELOG.md)

## 注意事项

1. **首次使用** - 需要逐个配置工具坐标
2. **微信版本** - 不同版本的 UI 布局可能有差异
3. **输入法** - 需要支持剪贴板功能的输入法
4. **延迟设置** - 已内置随机延迟模拟人工操作（x3）

## 隐私安全

### 禁止提交的文件

以下文件类型已在 `.gitignore` 中配置，**禁止提交**：

- 图片文件 (`.jpg`, `.png`, `.gif` 等)
- 截图文件 (`*screenshot*`)
- 敏感配置 (`.env`, `secrets.*`)
- 签名证书 (`.jks`, `.keystore`)
- 数据库文件 (`.db`, `.sqlite`)

### 提交前检查

```bash
# 检查待提交文件
git status

# 检查是否有敏感文件
git diff --cached --name-only | grep -E '\.(jpg|png|gif|env|secret|jks)$'
```

## 许可证

MIT
