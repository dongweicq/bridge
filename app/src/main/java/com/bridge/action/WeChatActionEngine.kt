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
        private const val DELAY_AFTER_OPEN = 3000L      // 打开微信后等待（增加时间）
        private const val DELAY_AFTER_CLICK = 800L      // 点击后等待
        private const val DELAY_AFTER_INPUT = 500L      // 输入后等待
        private const val DELAY_SEARCH_RESULT = 1500L   // 等待搜索结果

        // 预设坐标（屏幕比例 0.0-1.0）
        // 这些坐标基于微信8.0.x版本截图分析
        private data class CoordinateRatio(val xRatio: Float, val yRatio: Float)

        // 微信首页元素坐标
        private val COORD_SEARCH_BTN = CoordinateRatio(0.845f, 0.075f)     // 右上角搜索按钮
        private val COORD_SEARCH_INPUT = CoordinateRatio(0.50f, 0.05f)    // 搜索输入框（顶部中间）
        private val COORD_FIRST_CONTACT = CoordinateRatio(0.50f, 0.213f)  // 搜索结果第一个联系人
        private val COORD_MESSAGE_INPUT = CoordinateRatio(0.35f, 0.955f)  // 消息输入框（底部偏左）
        private val COORD_SEND_BTN = CoordinateRatio(0.92f, 0.955f)       // 发送按钮（底部右侧）
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
        // 等待并重试获取有效的根节点
        var root = service.getRootNode()
        var retryCount = 0
        val maxRetries = 5

        while (root == null || root.bounds == android.graphics.Rect(0, 0, 0, 0)) {
            retryCount++
            if (retryCount > maxRetries) {
                Log.e(TAG, "无法获取有效的根节点")
                // 无法获取节点时，使用坐标点击作为回退
                Log.d(TAG, "使用预设坐标点击搜索按钮")
                return clickByCoordinate(service, COORD_SEARCH_BTN, "搜索按钮")
            }
            Log.d(TAG, "等待微信界面加载... ($retryCount/$maxRetries)")
            delay(500)
            root = service.getRootNode()
        }

        // 打印节点信息用于调试
        dumpNodeInfo(root, 0)

        // 获取屏幕尺寸
        val screenBounds = service.getScreenBounds()
        Log.d(TAG, "屏幕尺寸: ${screenBounds.width()} x ${screenBounds.height()}")

        // 方式1: 尝试点击搜索按钮（按ID）
        var searchBtn = service.findNodeById(ID_SEARCH_BTN)
        if (searchBtn != null) {
            Log.d(TAG, "通过ID找到搜索按钮")
        }

        // 方式2: 按文本查找"搜索"
        if (searchBtn == null) {
            searchBtn = service.findNodeByText("搜索")
            if (searchBtn != null) Log.d(TAG, "通过文本找到搜索按钮")
        }

        if (searchBtn == null) {
            // 方式3: 查找带放大镜图标的按钮
            searchBtn = findNodeByDesc(root, "搜索")
            if (searchBtn != null) Log.d(TAG, "通过描述找到搜索按钮")
        }

        // 方式4: 查找 ImageView 或 ImageButton（搜索图标）
        if (searchBtn == null) {
            searchBtn = findImageButton(root, screenBounds)
            if (searchBtn != null) Log.d(TAG, "通过位置找到搜索按钮图标")
        }

        // 方式5: 使用预设坐标回退
        if (searchBtn == null) {
            Log.d(TAG, "所有节点查找方式失败，使用预设坐标")
            return clickByCoordinate(service, COORD_SEARCH_BTN, "搜索按钮")
        }

        return if (service.clickNode(searchBtn)) {
            Log.d(TAG, "成功点击搜索按钮")
            TaskResult.ok("已打开搜索")
        } else {
            // 节点点击失败，尝试坐标点击
            Log.w(TAG, "节点点击失败，尝试坐标点击")
            clickByCoordinate(service, COORD_SEARCH_BTN, "搜索按钮")
        }
    }

    /**
     * 打印节点信息（调试用）
     */
    private fun dumpNodeInfo(node: AccessibilityNodeInfo, depth: Int) {
        val indent = "  ".repeat(depth)
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""
        val className = node.className?.toString()?.substringAfterLast(".") ?: ""
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)

        // 打印所有节点（深度限制为 3 层）
        if (depth <= 3) {
            Log.d(TAG, "$indent[$className] text='$text' desc='$desc' id='$id' clickable=${node.isClickable} bounds=$bounds")
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { dumpNodeInfo(it, depth + 1) }
        }
    }

    /**
     * 查找 ImageButton 或 ImageView（可能是搜索按钮）
     * 使用屏幕相对位置判断，而非硬编码像素
     */
    private fun findImageButton(root: AccessibilityNodeInfo, screenBounds: android.graphics.Rect): AccessibilityNodeInfo? {
        val className = root.className?.toString() ?: ""
        if ((className.contains("ImageButton") || className.contains("ImageView")) && root.isClickable) {
            // 检查是否在右上角区域（搜索按钮通常在右上角）
            val bounds = android.graphics.Rect()
            root.getBoundsInScreen(bounds)

            // 使用屏幕比例判断：右边30%区域，顶部25%区域
            val screenWidth = screenBounds.width()
            val screenHeight = screenBounds.height()
            val isInRightArea = bounds.right > screenWidth * 0.7
            val isInTopArea = bounds.top < screenHeight * 0.25

            if (isInRightArea && isInTopArea) {
                Log.d(TAG, "找到右上角图标按钮: bounds=$bounds")
                return root
            }
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findImageButton(child, screenBounds)
            if (result != null) return result
        }
        return null
    }

    /**
     * 输入搜索词
     */
    private suspend fun inputSearchQuery(service: BridgeAccessibilityService, query: String): TaskResult {
        // 查找搜索输入框
        var inputNode = service.findNodeById(ID_SEARCH_INPUT)
        if (inputNode != null) {
            Log.d(TAG, "通过ID找到搜索输入框")
        }

        // 备选: 查找可编辑的输入框
        if (inputNode == null) {
            val root = service.getRootNode()
            inputNode = root?.let { findEditableNode(it) }
            if (inputNode != null) Log.d(TAG, "通过遍历找到可编辑输入框")
        }

        // 使用坐标输入作为回退
        if (inputNode == null) {
            Log.d(TAG, "找不到搜索输入框节点，使用坐标输入")
            return inputByCoordinate(service, COORD_SEARCH_INPUT, query, "搜索输入框")
        }

        // 点击输入框获取焦点
        service.clickNode(inputNode)
        delay(200)

        // 输入文本
        return if (service.inputText(inputNode, query)) {
            Log.d(TAG, "成功输入搜索词")
            TaskResult.ok("已输入搜索词")
        } else {
            // 节点输入失败，尝试坐标方式
            Log.w(TAG, "节点输入失败，尝试坐标输入")
            inputByCoordinate(service, COORD_SEARCH_INPUT, query, "搜索输入框")
        }
    }

    /**
     * 点击联系人
     */
    private suspend fun clickContact(service: BridgeAccessibilityService, name: String): TaskResult {
        // 等待搜索结果出现
        val root = service.getRootNode()

        if (root == null) {
            Log.w(TAG, "无法获取界面，尝试坐标点击第一个联系人")
            return clickByCoordinate(service, COORD_FIRST_CONTACT, "第一个联系人")
        }

        // 查找包含联系人名称的节点
        val nodes = root.findAccessibilityNodeInfosByText(name)

        if (nodes.isEmpty()) {
            Log.w(TAG, "找不到联系人节点: $name，尝试坐标点击")
            // 使用坐标点击第一个搜索结果
            return clickByCoordinate(service, COORD_FIRST_CONTACT, "第一个联系人")
        }

        // 过滤出聊天项（排除搜索框本身）
        val contactNodes = nodes.filter { node ->
            node.text?.toString() == name ||
            node.contentDescription?.toString()?.contains(name) == true
        }

        if (contactNodes.isEmpty()) {
            Log.w(TAG, "过滤后联系人节点为空，尝试坐标点击")
            return clickByCoordinate(service, COORD_FIRST_CONTACT, "第一个联系人")
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
            Log.d(TAG, "成功点击联系人: $name")
            TaskResult.ok("已打开聊天")
        } else {
            Log.w(TAG, "节点点击联系人失败，尝试坐标点击")
            clickByCoordinate(service, COORD_FIRST_CONTACT, "第一个联系人")
        }
    }

    /**
     * 输入消息
     */
    private suspend fun inputMessage(service: BridgeAccessibilityService, message: String): TaskResult {
        // 查找消息输入框
        var inputNode = service.findNodeById(ID_MESSAGE_INPUT)
        if (inputNode != null) {
            Log.d(TAG, "通过ID找到消息输入框")
        }

        // 备选: 查找 hint 为"发送"或包含"输入"的可编辑节点
        if (inputNode == null) {
            val root = service.getRootNode()
            inputNode = root?.let { findMessageInputNode(it) }
            if (inputNode != null) Log.d(TAG, "通过hint找到消息输入框")
        }

        // 使用坐标输入作为回退
        if (inputNode == null) {
            Log.d(TAG, "找不到消息输入框节点，使用坐标输入")
            return inputByCoordinate(service, COORD_MESSAGE_INPUT, message, "消息输入框")
        }

        // 点击输入框获取焦点
        service.clickNode(inputNode)
        delay(200)

        // 输入消息
        return if (service.inputText(inputNode, message)) {
            Log.d(TAG, "成功输入消息")
            TaskResult.ok("已输入消息")
        } else {
            // 节点输入失败，尝试坐标方式
            Log.w(TAG, "节点输入消息失败，尝试坐标输入")
            inputByCoordinate(service, COORD_MESSAGE_INPUT, message, "消息输入框")
        }
    }

    /**
     * 点击发送按钮
     */
    private suspend fun clickSend(service: BridgeAccessibilityService): TaskResult {
        // 查找发送按钮
        var sendBtn = service.findNodeById(ID_SEND_BTN)
        if (sendBtn != null) {
            Log.d(TAG, "通过ID找到发送按钮")
        }

        // 备选: 按文本查找"发送"
        if (sendBtn == null) {
            sendBtn = service.findNodeByText("发送", exact = true)
            if (sendBtn != null) Log.d(TAG, "通过文本找到发送按钮")
        }

        // 备选: 按描述查找
        if (sendBtn == null) {
            val root = service.getRootNode()
            sendBtn = root?.let { findNodeByDesc(it, "发送") }
            if (sendBtn != null) Log.d(TAG, "通过描述找到发送按钮")
        }

        // 使用坐标点击作为回退
        if (sendBtn == null) {
            Log.d(TAG, "找不到发送按钮节点，使用坐标点击")
            return clickByCoordinate(service, COORD_SEND_BTN, "发送按钮")
        }

        // 确保按钮可见且可点击
        val clickableBtn = if (sendBtn.isClickable) sendBtn else service.findClickableParent(sendBtn)

        if (clickableBtn == null) {
            Log.w(TAG, "发送按钮不可点击，尝试坐标点击")
            return clickByCoordinate(service, COORD_SEND_BTN, "发送按钮")
        }

        return if (service.clickNode(clickableBtn)) {
            Log.d(TAG, "成功点击发送按钮")
            TaskResult.ok("消息已发送")
        } else {
            Log.w(TAG, "节点点击发送按钮失败，尝试坐标点击")
            clickByCoordinate(service, COORD_SEND_BTN, "发送按钮")
        }
    }

    // ==================== 辅助查找方法 ====================

    /**
     * 使用预设坐标比例点击
     * @param service 无障碍服务
     * @param coord 坐标比例 (0.0-1.0)
     * @param elementName 元素名称（用于日志）
     */
    private fun clickByCoordinate(
        service: BridgeAccessibilityService,
        coord: CoordinateRatio,
        elementName: String
    ): TaskResult {
        val screenBounds = service.getScreenBounds()
        val x = (screenBounds.width() * coord.xRatio).toInt()
        val y = (screenBounds.height() * coord.yRatio).toInt()

        Log.d(TAG, "坐标点击 $elementName: ($x, $y) 屏幕=${screenBounds.width()}x${screenBounds.height()}")

        return if (service.clickAt(x, y)) {
            Log.d(TAG, "成功点击 $elementName")
            TaskResult.ok("已通过坐标点击$elementName")
        } else {
            Log.e(TAG, "坐标点击 $elementName 失败")
            TaskResult.fail("无法通过坐标点击$elementName")
        }
    }

    /**
     * 使用预设坐标比例输入文本
     * 先点击获取焦点，再输入文本
     */
    private suspend fun inputByCoordinate(
        service: BridgeAccessibilityService,
        coord: CoordinateRatio,
        text: String,
        elementName: String
    ): TaskResult {
        // 先点击获取焦点
        val clickResult = clickByCoordinate(service, coord, elementName)
        if (!clickResult.success) {
            return clickResult
        }
        delay(200)

        // 使用剪贴板粘贴（更可靠）
        return try {
            // 设置剪贴板内容
            val clipboard = service.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("text", text)
            clipboard.setPrimaryClip(clip)

            // 模拟粘贴操作 (Ctrl+V 在移动端不可用，使用长按粘贴)
            // 先尝试直接通过无障碍服务输入
            val root = service.getRootNode()
            val focusedNode = root?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)

            if (focusedNode != null && focusedNode.isEditable) {
                val args = android.os.Bundle()
                args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                focusedNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                Log.d(TAG, "通过无障碍输入文本到 $elementName")
                TaskResult.ok("已输入$text")
            } else {
                Log.w(TAG, "无法获取焦点节点，文本输入可能失败")
                TaskResult.fail("无法输入文本")
            }
        } catch (e: Exception) {
            Log.e(TAG, "输入文本失败", e)
            TaskResult.fail("输入文本失败: ${e.message}")
        }
    }

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
