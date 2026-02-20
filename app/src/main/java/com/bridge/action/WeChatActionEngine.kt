package com.bridge.action

import android.content.Context
import android.util.Log
import com.bridge.BridgeAccessibilityService
import com.bridge.model.Task
import com.bridge.model.TaskResult
import com.bridge.util.Tool
import com.bridge.util.ToolManager
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * 某信动作引擎
 * 负责执行某信自动化操作
 * 使用工具链系统执行操作
 */
class MoxinActionEngine {

    companion object {
        private const val TAG = "MoxinActionEngine"
    }

    // 随机延迟函数
    private suspend fun randomDelay(minMs: Long, maxMs: Long) {
        delay(minMs + Random.nextLong(maxMs - minMs))
    }

    // 常用延迟配置 - 模拟正常人操作频率 (x3)
    private suspend fun delayAfterClick() = randomDelay(1500, 3600)
    private suspend fun delayAfterOpenApp() = randomDelay(6000, 12000)
    private suspend fun delayAfterInput() = randomDelay(1800, 4500)
    private suspend fun delayAfterSearch() = randomDelay(4500, 9000)
    private suspend fun delayAfterIME() = randomDelay(4500, 9000)
    private suspend fun delayKeyboardShow() = randomDelay(3000, 6000)

    // ==================== 导航方法 ====================

    /**
     * 导航到某信通讯录界面
     * 使用3层回退策略：工具配置 → AccessibilityService检测 → 默认位置
     * @return TaskResult 表示导航结果
     */
    suspend fun navigateToContacts(service: BridgeAccessibilityService): TaskResult {
        return try {
            Log.d(TAG, "导航到通讯录界面...")

            // 先导航到首页
            val homeResult = navigateToMoxinHome(service)
            if (!homeResult.success) {
                return homeResult
            }

            var clicked = false

            // 策略1: 使用工具配置
            val allTools = ToolManager.getAllTools(service)
            val contactsTabTool = allTools.find { it.name == "通讯录" }

            if (contactsTabTool != null && contactsTabTool.x > 0 && contactsTabTool.y > 0) {
                Log.d(TAG, "使用工具配置点击通讯录")
                clickToolCoordinate(contactsTabTool, service)
                clicked = true
            }

            // 策略2: 使用AccessibilityService检测
            if (!clicked) {
                val contactsTab = service.findContactsTab()
                if (contactsTab != null) {
                    Log.d(TAG, "使用AccessibilityService检测点击通讯录")
                    clicked = clickNode(contactsTab)
                }
            }

            // 策略3: 使用默认位置（底部导航栏第二个）
            if (!clicked) {
                Log.w(TAG, "通讯录工具未配置且AccessibilityService未检测到，使用默认位置")
                val screenBounds = service.getScreenBounds()
                // 底部导航: 微信(0.125), 通讯录(0.375), 发现(0.625), 我(0.875)
                val defaultX = (screenBounds.width() * 0.375).toInt()
                val defaultY = (screenBounds.height() * 0.95).toInt()
                service.clickAt(defaultX, defaultY)
                clicked = true
            }

            delayAfterClick()

            if (clicked) {
                Log.d(TAG, "已到达通讯录界面")
                TaskResult.ok("已进入通讯录")
            } else {
                TaskResult.fail("无法点击通讯录标签")
            }
        } catch (e: Exception) {
            Log.e(TAG, "导航到通讯录失败", e)
            TaskResult.fail("导航失败: ${e.message}")
        }
    }

    /**
     * 点击节点
     */
    private fun clickNode(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
        return try {
            if (node.isClickable) {
                node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                // 查找可点击的父节点
                var current: android.view.accessibility.AccessibilityNodeInfo? = node.parent
                while (current != null) {
                    if (current.isClickable) {
                        current.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    }
                    current = current.parent
                }
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "点击节点失败", e)
            false
        }
    }

    /**
     * 导航到某信首页
     * @return TaskResult 表示导航结果
     */
    suspend fun navigateToMoxinHome(service: BridgeAccessibilityService): TaskResult {
        return try {
            Log.d(TAG, "导航到某信首页...")

            // 打开某信
            if (!service.openMoxin()) {
                return TaskResult.fail("无法打开某信")
            }
            delayAfterOpenApp()

            // 等待 UI 稳定
            delay(2000)

            // 检查是否在某信应用中
            val root = service.getRootNode()
            val isInMoxin = root?.packageName?.toString() == BridgeAccessibilityService.MOXIN_PACKAGE

            if (isInMoxin) {
                Log.d(TAG, "已到达某信: ${root?.packageName}")
                TaskResult.ok("已打开某信")
            } else {
                Log.w(TAG, "未能打开某信, package=${root?.packageName}")
                TaskResult.fail("未能打开某信")
            }
        } catch (e: Exception) {
            Log.e(TAG, "导航到首页失败", e)
            TaskResult.fail("导航失败: ${e.message}")
        }
    }

    /**
     * 导航到指定联系人的聊天界面
     * @param contactName 联系人名称
     * @return TaskResult 表示导航结果
     */
    suspend fun navigateToChat(
        service: BridgeAccessibilityService,
        contactName: String
    ): TaskResult {
        return try {
            Log.d(TAG, "导航到聊天界面: $contactName")

            // 获取工具
            val allTools = ToolManager.getAllTools(service)
            val searchBtnTool = allTools.find { it.name == "搜索按钮" }
            val imeClipboardTool = allTools.find { it.name == "输入法剪贴板" }
            val contactTool = allTools.find { it.name == "联系人" }

            if (searchBtnTool == null || contactTool == null) {
                return TaskResult.fail("工具未配置，请先在工具管理中配置坐标")
            }

            // 1. 打开某信
            if (!service.openMoxin()) {
                return TaskResult.fail("无法打开某信")
            }
            delayAfterOpenApp()

            // 2. 设置剪贴板为联系人名称
            setClipboard(service, contactName)
            delayAfterInput()

            // 3. 点击搜索按钮
            if (searchBtnTool.x > 0 && searchBtnTool.y > 0) {
                clickToolCoordinate(searchBtnTool, service)
                delayAfterSearch()
            } else {
                return TaskResult.fail("搜索按钮坐标未配置")
            }

            // 4. 点击输入法剪贴板粘贴
            if (imeClipboardTool != null && imeClipboardTool.x > 0 && imeClipboardTool.y > 0) {
                delayKeyboardShow()  // 等待键盘弹出
                clickToolCoordinate(imeClipboardTool, service)
                delayAfterIME()
            }

            // 5. 点击联系人
            if (contactTool.x > 0 && contactTool.y > 0) {
                clickToolCoordinate(contactTool, service)
                delayAfterClick()
            } else {
                return TaskResult.fail("联系人坐标未配置")
            }

            Log.d(TAG, "已进入聊天界面: $contactName")
            TaskResult.ok("已进入聊天界面")
        } catch (e: Exception) {
            Log.e(TAG, "导航到聊天界面失败", e)
            TaskResult.fail("导航失败: ${e.message}")
        }
    }

    /**
     * 执行发送消息任务 - 使用工具链
     */
    suspend fun execute(task: Task, service: BridgeAccessibilityService): TaskResult {
        return try {
            Log.d(TAG, "开始执行任务: target=${task.target}, message=${task.message.take(20)}...")

            // 获取所有工具
            val allTools = ToolManager.getAllTools(service)

            // 查找发送按钮工具
            val sendBtnTool = allTools.find { it.name == "发送按钮" }
            if (sendBtnTool == null) {
                return TaskResult.fail("找不到「发送按钮」工具，请先在工具管理中配置")
            }

            // 获取发送按钮的完整执行链
            val executionChain = ToolManager.getExecutionChain(service, sendBtnTool.id)
            Log.d(TAG, "执行链: ${executionChain.map { it.name }}")

            // 查找所需工具
            val searchBtnTool = allTools.find { it.name == "搜索按钮" }
            val imeClipboardTool = allTools.find { it.name == "输入法剪贴板" }
            val contactTool = allTools.find { it.name == "联系人" }
            val msgInputTool = allTools.find { it.name == "消息输入框" }

            // 步骤1: 设置剪贴板内容为目标联系人名称（直接使用中文）
            // 注：剪贴板完全支持中文，某信搜索也支持中文直接搜索
            // 如需使用拼音首字母搜索，可改为：PinyinUtil.toPinyinInitials(task.target)
            val searchText = task.target
            Log.d(TAG, "搜索联系人: '$searchText'")

            setClipboard(service, searchText)
            delayAfterInput()

            // 步骤2: 执行前置工具链（打开某信等）
            for (tool in executionChain) {
                // 跳过发送按钮本身，我们后面单独处理
                if (tool.name == "发送按钮") continue

                Log.d(TAG, "执行工具: ${tool.name}")
                val result = executeTool(tool, service, task.target)
                if (!result.success) {
                    Log.w(TAG, "工具执行失败: ${tool.name}, ${result.message}")
                    // 继续尝试，不中断
                }
            }

            // 步骤3: 设置剪贴板为要发送的消息
            setClipboard(service, task.message)
            delayAfterInput()

            // 步骤4: 点击消息输入框
            if (msgInputTool != null && msgInputTool.x > 0 && msgInputTool.y > 0) {
                clickToolCoordinate(msgInputTool, service)
                delayKeyboardShow()
            }

            // 步骤5: 点击输入法剪贴板粘贴消息
            if (imeClipboardTool != null && imeClipboardTool.x > 0 && imeClipboardTool.y > 0) {
                clickToolCoordinate(imeClipboardTool, service)
                delayAfterIME()
            }

            // 步骤6: 点击发送按钮
            if (sendBtnTool.x > 0 && sendBtnTool.y > 0) {
                clickToolCoordinate(sendBtnTool, service)
                delayAfterClick()
            }

            Log.d(TAG, "任务执行成功")
            TaskResult.ok("消息已发送")

        } catch (e: Exception) {
            Log.e(TAG, "任务执行失败", e)
            TaskResult.fail("执行异常: ${e.message}")
        }
    }

    /**
     * 执行单个工具
     */
    private suspend fun executeTool(
        tool: Tool,
        service: BridgeAccessibilityService,
        searchText: String? = null
    ): TaskResult {
        return when (tool.id) {
            ToolManager.TOOL_OPEN_MOXIN -> {
                if (!service.openMoxin()) {
                    TaskResult.fail("无法打开某信")
                } else {
                    delayAfterOpenApp()
                    TaskResult.ok("已打开某信")
                }
            }
            ToolManager.TOOL_SET_CLIPBOARD -> {
                val content = searchText ?: "test"
                setClipboard(service, content)
                delayAfterInput()
                TaskResult.ok("已设置剪贴板")
            }
            ToolManager.TOOL_GO_BACK -> {
                service.goBack()
                delayAfterClick()
                TaskResult.ok("已返回")
            }
            else -> {
                // 用户定义的坐标工具
                if (tool.x > 0 && tool.y > 0) {
                    // 特殊处理：输入法剪贴板需要等待键盘
                    if (tool.name.contains("剪贴板") || tool.name.contains("输入法")) {
                        delayKeyboardShow()
                    }

                    clickToolCoordinate(tool, service)

                    // 根据工具名称设置不同的延迟
                    when {
                        tool.name.contains("搜索") -> delayAfterSearch()
                        tool.name.contains("剪贴板") || tool.name.contains("输入法") -> delayAfterIME()
                        tool.name.contains("联系人") -> delayAfterClick()
                        else -> delayAfterClick()
                    }

                    TaskResult.ok("已执行 ${tool.name}")
                } else {
                    TaskResult.fail("工具 ${tool.name} 坐标未配置")
                }
            }
        }
    }

    /**
     * 使用工具的坐标进行点击
     */
    private fun clickToolCoordinate(tool: Tool, service: BridgeAccessibilityService): Boolean {
        if (tool.x <= 0 || tool.y <= 0) {
            Log.w(TAG, "工具 ${tool.name} 坐标无效: (${tool.x}, ${tool.y})")
            return false
        }

        val screenBounds = service.getScreenBounds()
        val x = (screenBounds.width() * tool.x).toInt()
        val y = (screenBounds.height() * tool.y).toInt()

        Log.d(TAG, "点击 ${tool.name}: ($x, $y) 屏幕尺寸: ${screenBounds.width()}x${screenBounds.height()}")
        return service.clickAt(x, y)
    }

    /**
     * 设置剪贴板内容
     */
    private fun setClipboard(service: BridgeAccessibilityService, text: String) {
        try {
            val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText(null, text)
            clipboard.setPrimaryClip(clip)
            Log.d(TAG, "已设置剪贴板内容: ${text.take(30)}...")
        } catch (e: Exception) {
            Log.e(TAG, "设置剪贴板失败", e)
        }
    }
}
