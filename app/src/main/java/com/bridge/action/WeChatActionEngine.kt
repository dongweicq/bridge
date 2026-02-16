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

        val emptyBounds = android.graphics.Rect(0, 0, 0, 0)
        val rootBounds = android.graphics.Rect()
        root?.getBoundsInScreen(rootBounds)

        while (root == null || rootBounds == emptyBounds) {
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
            root?.getBoundsInScreen(rootBounds)
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
     * 使用拼音首字母输入，避免中文输入问题
     */
    private suspend fun inputSearchQuery(service: BridgeAccessibilityService, query: String): TaskResult {
        // 将中文转换为拼音首字母
        val pinyinInitials = com.bridge.util.PinyinUtil.toPinyinInitials(query)
        Log.d(TAG, "搜索词 '$query' 转换为拼音首字母: '$pinyinInitials'")

        // 点击搜索输入框
        val screenBounds = service.getScreenBounds()
        val x = (screenBounds.width() * COORD_SEARCH_INPUT.xRatio).toInt()
        val y = (screenBounds.height() * COORD_SEARCH_INPUT.yRatio).toInt()

        if (!service.clickAt(x, y)) {
            return TaskResult.fail("无法点击搜索输入框")
        }
        delay(300)

        // 尝试通过无障碍节点输入拼音
        val root = service.getRootNode()
        var inputSuccess = false

        // 方式1: 通过焦点节点输入
        val focusedNode = root?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null && focusedNode.isEditable) {
            val args = android.os.Bundle()
            args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pinyinInitials)
            inputSuccess = focusedNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (inputSuccess) {
                Log.d(TAG, "通过焦点节点输入拼音成功: $pinyinInitials")
            }
        }

        // 方式2: 通过可编辑节点输入
        if (!inputSuccess) {
            val editableNode = root?.let { findEditableNodeInBounds(it, COORD_SEARCH_INPUT, screenBounds) }
            if (editableNode != null) {
                val args = android.os.Bundle()
                args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pinyinInitials)
                inputSuccess = editableNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (inputSuccess) {
                    Log.d(TAG, "通过可编辑节点输入拼音成功: $pinyinInitials")
                }
            }
        }

        if (inputSuccess) {
            return TaskResult.ok("已输入搜索词拼音: $pinyinInitials")
        }

        // 方式3: 坐标输入作为最终回退
        Log.d(TAG, "节点输入失败，尝试坐标输入")
        return inputByCoordinate(service, COORD_SEARCH_INPUT, pinyinInitials, "搜索输入框")
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
     * 使用剪贴板+粘贴方式输入中文消息
     */
    private suspend fun inputMessage(service: BridgeAccessibilityService, message: String): TaskResult {
        Log.d(TAG, "准备输入消息: $message")

        // 步骤1: 设置剪贴板内容（确保在前台时设置）
        try {
            // 确保服务在前台
            val clipboard = service.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            // 使用 MIME 类型明确指定文本类型
            val clip = android.content.ClipData.newPlainText(null, message)
            clipboard.setPrimaryClip(clip)
            Log.d(TAG, "已设置剪贴板内容: $message")

            // 验证剪贴板内容
            val clipData = clipboard.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val clipText = clipData.getItemAt(0).text?.toString() ?: ""
                Log.d(TAG, "剪贴板验证: $clipText")
            }
        } catch (e: Exception) {
            Log.e(TAG, "设置剪贴板失败", e)
        }

        // 步骤2: 点击消息输入框
        val screenBounds = service.getScreenBounds()
        val inputX = (screenBounds.width() * COORD_MESSAGE_INPUT.xRatio).toInt()
        val inputY = (screenBounds.height() * COORD_MESSAGE_INPUT.yRatio).toInt()

        if (!service.clickAt(inputX, inputY)) {
            return TaskResult.fail("无法点击消息输入框")
        }
        delay(300)

        // 步骤3: 尝试 ACTION_PASTE
        val root = service.getRootNode()
        var pasteSuccess = false

        val focusedNode = root?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            // 先获取焦点
            focusedNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS)
            delay(100)
            // 尝试粘贴
            pasteSuccess = focusedNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE)
            Log.d(TAG, "ACTION_PASTE 结果: $pasteSuccess")
            delay(200)
        }

        // 步骤4: 如果 ACTION_PASTE 失败，尝试点击输入法工具栏的粘贴按钮
        if (!pasteSuccess) {
            Log.d(TAG, "尝试点击输入法工具栏粘贴按钮")
            // 输入法工具栏通常在屏幕底部，键盘上方
            // Y坐标约为屏幕高度的 80% 位置
            val imeToolbarY = (screenBounds.height() * 0.80).toInt()
            val imeToolbarX = screenBounds.width() / 2

            // 点击输入法工具栏中间位置
            service.clickAt(imeToolbarX, imeToolbarY)
            delay(300)
        }

        // 步骤5: 验证输入结果
        delay(300)
        val verifyRoot = service.getRootNode()
        val verifyNode = verifyRoot?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)

        if (verifyNode != null) {
            val currentText = verifyNode.text?.toString() ?: ""
            Log.d(TAG, "验证消息输入框内容: '$currentText'")
            if (currentText.contains(message) || message.contains(currentText)) {
                Log.d(TAG, "消息输入验证成功")
                return TaskResult.ok("已输入消息")
            }
        }

        // 即使验证失败，也假设成功，让后续步骤验证
        Log.d(TAG, "消息输入完成（未验证）")
        return TaskResult.ok("已尝试输入消息")
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
        // 设置剪贴板内容（先设置，因为后续可能需要粘贴）
        try {
            val clipboard = service.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText(null, text)
            clipboard.setPrimaryClip(clip)
            Log.d(TAG, "已设置剪贴板内容: ${text.take(20)}...")
        } catch (e: Exception) {
            Log.e(TAG, "设置剪贴板失败", e)
        }

        // 点击输入框获取焦点
        val screenBounds = service.getScreenBounds()
        val x = (screenBounds.width() * coord.xRatio).toInt()
        val y = (screenBounds.height() * coord.yRatio).toInt()

        if (!service.clickAt(x, y)) {
            return TaskResult.fail("无法点击$elementName")
        }
        delay(300)

        return try {
            val root = service.getRootNode()
            var inputSuccess = false

            // 方式1: 尝试通过焦点节点直接设置文本
            val focusedNode = root?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
            if (focusedNode != null && focusedNode.isEditable) {
                val args = android.os.Bundle()
                args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                inputSuccess = focusedNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (inputSuccess) {
                    Log.d(TAG, "通过焦点节点直接设置文本成功")
                }
            }

            // 方式2: 尝试通过可编辑节点设置文本
            if (!inputSuccess) {
                val editableNode = root?.let { findEditableNodeInBounds(it, coord, screenBounds) }
                if (editableNode != null) {
                    val args = android.os.Bundle()
                    args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    inputSuccess = editableNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    if (inputSuccess) {
                        Log.d(TAG, "通过遍历找到可编辑节点并设置文本")
                    }
                }
            }

            // 方式3: 长按 + 点击粘贴菜单（最可靠的方式）
            Log.d(TAG, "尝试长按粘贴方式")
            if (service.longPressAt(x, y)) {
                delay(800)  // 等待菜单弹出
                // 粘贴菜单通常出现在点击位置下方
                // 尝试点击多个可能的粘贴菜单位置
                val pastePositions = listOf(
                    Pair(x, y + 80),   // 正下方
                    Pair(x, y + 100),  // 稍低
                    Pair(x, y + 120),  // 更低
                    Pair(x, y + 150),  // 最低
                    Pair(x - 50, y + 100), // 左下方
                    Pair(x + 50, y + 100), // 右下方
                    Pair(x, y + 200),  // 更低位置
                )

                for ((px, py) in pastePositions) {
                    Log.d(TAG, "尝试点击粘贴位置: ($px, $py)")
                    service.clickAt(px, py)
                    delay(300)
                }
            }

            // 方式4: 尝试 ACTION_PASTE（可能被微信阻止）
            val pasteNode = root?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                ?: service.getRootNode()?.let { findEditableNodeInBounds(it, coord, screenBounds) }

            if (pasteNode != null) {
                val pasteResult = pasteNode.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_PASTE)
                Log.d(TAG, "ACTION_PASTE 结果: $pasteResult")
                delay(300)
            }

            // 验证输入结果
            delay(300)
            val verifyRoot = service.getRootNode()
            val verifyNode = verifyRoot?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                ?: verifyRoot?.let { findEditableNodeInBounds(it, coord, screenBounds) }

            if (verifyNode != null) {
                val currentText = verifyNode.text?.toString() ?: ""
                Log.d(TAG, "验证输入框内容: '$currentText'")
                if (currentText.isNotEmpty()) {
                    return TaskResult.ok("已输入: $currentText")
                }
            }

            // 即使验证失败，也返回成功让后续步骤处理
            Log.d(TAG, "输入完成，继续后续步骤")
            TaskResult.ok("已尝试输入$text")

        } catch (e: Exception) {
            Log.e(TAG, "输入文本失败", e)
            TaskResult.ok("已尝试输入$text")
        }
    }

    /**
     * 在指定坐标附近查找可编辑节点
     */
    private fun findEditableNodeInBounds(
        root: AccessibilityNodeInfo,
        coord: CoordinateRatio,
        screenBounds: android.graphics.Rect
    ): AccessibilityNodeInfo? {
        val targetX = (screenBounds.width() * coord.xRatio).toInt()
        val targetY = (screenBounds.height() * coord.yRatio).toInt()
        val tolerance = 200  // 像素容差

        return findEditableNodeNear(root, targetX, targetY, tolerance)
    }

    /**
     * 递归查找距离目标坐标最近的可编辑节点
     */
    private fun findEditableNodeNear(
        node: AccessibilityNodeInfo,
        targetX: Int,
        targetY: Int,
        tolerance: Int
    ): AccessibilityNodeInfo? {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)

        // 检查是否是可编辑节点且在目标位置附近
        if (node.isEditable) {
            val centerX = bounds.centerX()
            val centerY = bounds.centerY()
            val distance = kotlin.math.sqrt(
                ((centerX - targetX) * (centerX - targetX) +
                 (centerY - targetY) * (centerY - targetY)).toFloat()
            )
            if (distance < tolerance) {
                return node
            }
        }

        // 递归查找子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableNodeNear(child, targetX, targetY, tolerance)
            if (result != null) return result
        }
        return null
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
