package com.bridge.action

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.bridge.BridgeAccessibilityService
import com.bridge.model.Task
import com.bridge.model.TaskResult
import kotlinx.coroutines.delay

/**
 * 微信动作引擎
 * 负责执行微信自动化操作
 */
class WeChatActionEngine {

    companion object {
        private const val TAG = "WeChatActionEngine"

        // 微信 UI 元素的 resource-id（可能随版本变化）
        // 这些是常见版本的 ID，如果失效需要更新
        private const val ID_SEARCH_BTN = "com.tencent.mm:id/f8y"          // 搜索按钮
        private const val ID_SEARCH_INPUT = "com.tencent.mm:id/b4u"        // 搜索输入框
        private const val ID_CHAT_ITEM = "com.tencent.mm:id/auo"           // 聊天列表项
        private const val ID_MESSAGE_INPUT = "com.tencent.mm:id/aqe"       // 消息输入框
        private const val ID_SEND_BTN = "com.tencent.mm:id/aq_"            // 发送按钮
        private const val ID_CHAT_NAME = "com.tencent.mm:id/k6"            // 聊天标题

        // 延迟配置
        private const val DELAY_AFTER_OPEN = 1500L      // 打开微信后等待
        private const val DELAY_AFTER_CLICK = 500L      // 点击后等待
        private const val DELAY_AFTER_INPUT = 300L      // 输入后等待
        private const val DELAY_SEARCH_RESULT = 1000L   // 等待搜索结果
    }

    /**
     * 执行发送消息任务
     */
    suspend fun execute(task: Task, service: BridgeAccessibilityService): TaskResult {
        return try {
            Log.d(TAG, "开始执行任务: target=${task.target}, message=${task.message.take(20)}...")

            // 1. 打开微信
            if (!service.openWeChat()) {
                return TaskResult.fail("无法打开微信")
            }
            delay(DELAY_AFTER_OPEN)

            // 2. 打开搜索
            val searchResult = openSearch(service)
            if (!searchResult.success) {
                return searchResult
            }
            delay(DELAY_AFTER_CLICK)

            // 3. 输入联系人名称搜索
            val inputResult = inputSearchQuery(service, task.target)
            if (!inputResult.success) {
                return inputResult
            }
            delay(DELAY_SEARCH_RESULT)

            // 4. 查找并点击联系人
            val clickResult = clickContact(service, task.target)
            if (!clickResult.success) {
                return clickResult
            }
            delay(DELAY_AFTER_CLICK)

            // 5. 输入消息
            val msgResult = inputMessage(service, task.message)
            if (!msgResult.success) {
                return msgResult
            }
            delay(DELAY_AFTER_INPUT)

            // 6. 点击发送
            val sendResult = clickSend(service)
            if (!sendResult.success) {
                return sendResult
            }
            delay(DELAY_AFTER_CLICK)

            // 7. 返回微信主界面
            service.goBack()
            delay(DELAY_AFTER_CLICK)

            Log.d(TAG, "任务执行成功")
            TaskResult.ok("消息已发送")

        } catch (e: Exception) {
            Log.e(TAG, "任务执行失败", e)
            TaskResult.fail("执行异常: ${e.message}")
        }
    }

    /**
     * 打开搜索
     */
    private suspend fun openSearch(service: BridgeAccessibilityService): TaskResult {
        // 方式1: 尝试点击搜索按钮（按ID）
        var searchBtn = service.findNodeById(ID_SEARCH_BTN)

        // 方式2: 按文本查找"搜索"
        if (searchBtn == null) {
            searchBtn = service.findNodeByText("搜索")
        }

        if (searchBtn == null) {
            // 方式3: 查找带放大镜图标的按钮
            val root = service.getRootNode()
            searchBtn = root?.let { findNodeByDesc(it, "搜索") }
        }

        if (searchBtn == null) {
            return TaskResult.fail("找不到搜索按钮")
        }

        return if (service.clickNode(searchBtn)) {
            TaskResult.ok("已打开搜索")
        } else {
            TaskResult.fail("无法点击搜索按钮")
        }
    }

    /**
     * 输入搜索词
     */
    private suspend fun inputSearchQuery(service: BridgeAccessibilityService, query: String): TaskResult {
        // 查找搜索输入框
        var inputNode = service.findNodeById(ID_SEARCH_INPUT)

        // 备选: 查找可编辑的输入框
        if (inputNode == null) {
            val root = service.getRootNode()
            inputNode = root?.let { findEditableNode(it) }
        }

        if (inputNode == null) {
            return TaskResult.fail("找不到搜索输入框")
        }

        // 点击输入框获取焦点
        service.clickNode(inputNode)
        delay(200)

        // 输入文本
        return if (service.inputText(inputNode, query)) {
            TaskResult.ok("已输入搜索词")
        } else {
            TaskResult.fail("无法输入搜索词")
        }
    }

    /**
     * 点击联系人
     */
    private suspend fun clickContact(service: BridgeAccessibilityService, name: String): TaskResult {
        // 等待搜索结果出现
        val root = service.getRootNode() ?: return TaskResult.fail("无法获取界面")

        // 查找包含联系人名称的节点
        val nodes = root.findAccessibilityNodeInfosByText(name)

        if (nodes.isEmpty()) {
            return TaskResult.fail("找不到联系人: $name")
        }

        // 过滤出聊天项（排除搜索框本身）
        val contactNodes = nodes.filter { node ->
            node.text?.toString() == name ||
            node.contentDescription?.toString()?.contains(name) == true
        }

        if (contactNodes.isEmpty()) {
            return TaskResult.fail("找不到联系人: $name")
        }

        if (contactNodes.size > 1) {
            // 多个匹配，返回候选列表
            val candidates = contactNodes.mapNotNull { it.text?.toString() }.distinct()
            return TaskResult.candidates(candidates)
        }

        // 点击联系人
        val targetNode = contactNodes[0]
        val clickableNode = service.findClickableParent(targetNode) ?: targetNode

        return if (service.clickNode(clickableNode)) {
            TaskResult.ok("已打开聊天")
        } else {
            TaskResult.fail("无法点击联系人")
        }
    }

    /**
     * 输入消息
     */
    private suspend fun inputMessage(service: BridgeAccessibilityService, message: String): TaskResult {
        // 查找消息输入框
        var inputNode = service.findNodeById(ID_MESSAGE_INPUT)

        // 备选: 查找 hint 为"发送"或包含"输入"的可编辑节点
        if (inputNode == null) {
            val root = service.getRootNode()
            inputNode = root?.let { findMessageInputNode(it) }
        }

        if (inputNode == null) {
            return TaskResult.fail("找不到消息输入框")
        }

        // 点击输入框获取焦点
        service.clickNode(inputNode)
        delay(200)

        // 输入消息
        return if (service.inputText(inputNode, message)) {
            TaskResult.ok("已输入消息")
        } else {
            TaskResult.fail("无法输入消息")
        }
    }

    /**
     * 点击发送按钮
     */
    private suspend fun clickSend(service: BridgeAccessibilityService): TaskResult {
        // 查找发送按钮
        var sendBtn = service.findNodeById(ID_SEND_BTN)

        // 备选: 按文本查找"发送"
        if (sendBtn == null) {
            sendBtn = service.findNodeByText("发送", exact = true)
        }

        // 备选: 按描述查找
        if (sendBtn == null) {
            val root = service.getRootNode()
            sendBtn = root?.let { findNodeByDesc(it, "发送") }
        }

        if (sendBtn == null) {
            return TaskResult.fail("找不到发送按钮")
        }

        // 确保按钮可见且可点击
        val clickableBtn = if (sendBtn.isClickable) sendBtn else service.findClickableParent(sendBtn)

        if (clickableBtn == null) {
            return TaskResult.fail("发送按钮不可点击")
        }

        return if (service.clickNode(clickableBtn)) {
            TaskResult.ok("消息已发送")
        } else {
            TaskResult.fail("无法点击发送按钮")
        }
    }

    // ==================== 辅助查找方法 ====================

    /**
     * 按 contentDescription 查找节点
     */
    private fun findNodeByDesc(root: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        if (root.contentDescription?.toString()?.contains(desc) == true) {
            return root
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findNodeByDesc(child, desc)
            if (result != null) return result
        }
        return null
    }

    /**
     * 查找可编辑节点
     */
    private fun findEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isEditable && root.className?.contains("EditText") == true) {
            return root
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findEditableNode(child)
            if (result != null) return result
        }
        return null
    }

    /**
     * 查找消息输入框（带 hint）
     */
    private fun findMessageInputNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isEditable) {
            val hint = root.hintText?.toString() ?: ""
            if (hint.contains("发送") || hint.contains("输入") || hint.contains("消息")) {
                return root
            }
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findMessageInputNode(child)
            if (result != null) return result
        }
        return null
    }
}
