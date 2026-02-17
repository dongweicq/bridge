# Bridge APK 更新日志

所有重要的更改都将记录在此文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [Unreleased]

### 计划中
- 持续优化和 Bug 修复

---

## [v1.0.85] - 2025-02-17

### Changed
- 优化工具链执行稳定性
- 改进坐标配置系统

## [v1.0.64] - 2025-02-17

### Added
- 新增工具管理界面
- 支持拖拽排序工具链

### Changed
- 优化悬浮窗蒙层交互

## [v1.0.63] - 2025-02-17

### Added
- 新增坐标配置功能
- 支持5步坐标校准

### Changed
- 改进 AccessibilityService 稳定性

---

## [v1.0.31+] - 2025-02

### Added
- 坐标配置系统
  - 搜索按钮坐标
  - 输入法剪贴板坐标
  - 联系人坐标
  - 消息输入框坐标
  - 发送按钮坐标
- 悬浮窗蒙层坐标选取
- 前置工具链配置

---

## [v1.0.0] - 2025-02

### Added
- 初始版本发布
- HTTP API 服务 (端口 7788)
  - GET /ping - 健康检查
  - GET /health - 运行状态
  - GET /chat_list - 最近联系人
  - GET /chat_history - 消息历史
  - POST /send_message - 发送消息
- AccessibilityService UI 自动化
- 前台服务保活
- 任务队列管理
- 随机延迟模拟人工操作

---

## 版本号说明

- **主版本号 (Major)**: 重大架构变更
- **次版本号 (Minor)**: 新功能添加
- **修订号 (Patch)**: Bug 修复和小改进

版本号由 GitHub Actions 自动生成，格式为 `v1.0.XX`，其中 XX 为构建号。

---

## 自动化流程

每次版本更新时执行：

1. **提交代码** → `git push`
2. **自动构建** → GitHub Actions 构建并发布
3. **下载安装** → `bash auto_deploy.sh`
4. **监控日志** → `bash auto_deploy.sh logs`
5. **更新日志** → 更新此 CHANGELOG.md 文件

> **注意**: 仅修改 `*.md` 文档文件不会触发构建，直接提交即可。
