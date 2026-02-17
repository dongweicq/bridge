package com.bridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bridge.action.ActionDispatcher
import com.bridge.action.MoxinActionEngine
import com.bridge.model.ContactData
import com.bridge.model.MessageData
import com.bridge.model.ReadResult
import com.bridge.model.Task
import com.bridge.model.TaskResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class BridgeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BridgeAccessibility"

        var instance: BridgeAccessibilityService? = null
            private set

        // 某信包名
        const val MOXIN_PACKAGE = "com.tencent.mm"
    }

    private val actionEngine = MoxinActionEngine()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要处理事件，仅用于执行操作
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "Accessibility Service Destroyed")
    }

    /**
     * 执行发送消息任务
     * 必须在 ActionDispatcher.dispatcher 线程中调用
     */
    suspend fun executeSendMessage(task: Task): TaskResult {
        return actionEngine.execute(task, this)
    }

    // ==================== 数据读取方法 ====================

    /**
     * 读取某信首页聊天列表
     * 前提：当前已在某信首页
     * @return ReadResult 包含联系人列表
     */
    fun readChatList(): ReadResult {
        return try {
            val root = rootInActiveWindow
            if (root == null) {
                Log.w(TAG, "readChatList: 无活动窗口")
                return ReadResult.error("无活动窗口")
            }

            // 检查是否在某信
            val packageName = root.packageName?.toString()
            if (packageName != MOXIN_PACKAGE) {
                Log.w(TAG, "readChatList: 不在某信中, package=$packageName")
                return ReadResult.error("不在某信应用中")
            }

            // 获取屏幕尺寸
            val screenBounds = getScreenBounds()
            Log.d(TAG, "readChatList: 屏幕尺寸 ${screenBounds.width()}x${screenBounds.height()}, root包名=$packageName")

            val contacts = mutableListOf<ContactData>()
            val seenNames = mutableSetOf<String>()

            // 遍历UI树查找所有文本节点
            collectAllTextNodes(root, contacts, seenNames, 0, 20, screenBounds)

            Log.d(TAG, "读取到 ${contacts.size} 个联系人")
            if (contacts.isEmpty()) {
                ReadResult.error("未读取到联系人，请确保在某信首页")
            } else {
                ReadResult.successContacts(contacts)
            }

        } catch (e: Exception) {
            Log.e(TAG, "读取聊天列表失败", e)
            ReadResult.error("读取失败: ${e.message}")
        }
    }

    /**
     * 收集所有可能的联系人文本节点
     */
    private fun collectAllTextNodes(
        node: AccessibilityNodeInfo,
        contacts: MutableList<ContactData>,
        seenNames: MutableSet<String>,
        depth: Int,
        maxDepth: Int,
        screenBounds: Rect
    ) {
        if (depth > maxDepth) return

        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty() && text.length >= 2 && text.length <= 20) {
            // 检查是否可能是联系人名称
            if (isValidContactName(text) && text !in seenNames) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                // 检查节点是否在屏幕上半部分（联系人名称通常在上方）
                if (bounds.top < screenBounds.height() * 0.8 && bounds.height() > 10 && bounds.height() < 150) {
                    seenNames.add(text)
                    contacts.add(ContactData(
                        name = text,
                        displayName = text,
                        lastMessage = null,
                        lastTime = null,
                        unreadCount = 0
                    ))
                    Log.d(TAG, "找到联系人: $text, bounds=$bounds")
                }
            }
        }

        // 递归处理子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllTextNodes(child, contacts, seenNames, depth + 1, maxDepth, screenBounds)
        }
    }

    /**
     * 读取当前聊天界面的消息记录
     * 前提：当前已在聊天界面
     * @param contactName 联系人名称（用于标识发送者）
     * @return ReadResult 包含消息列表
     */
    fun readChatHistory(contactName: String): ReadResult {
        return try {
            val root = rootInActiveWindow
            if (root == null) {
                Log.w(TAG, "readChatHistory: 无活动窗口")
                return ReadResult.error("无活动窗口")
            }

            // 检查是否在某信
            val packageName = root.packageName?.toString()
            if (packageName != MOXIN_PACKAGE) {
                Log.w(TAG, "readChatHistory: 不在某信中")
                return ReadResult.error("不在某信应用中")
            }

            val messages = mutableListOf<MessageData>()
            val screenBounds = getScreenBounds()
            val centerX = screenBounds.width() / 2

            // 遍历UI树收集消息
            collectMessages(root, messages, contactName, centerX, 0, 20)

            Log.d(TAG, "读取到 ${messages.size} 条消息")
            if (messages.isEmpty()) {
                ReadResult.error("未读取到消息，请确保在聊天界面")
            } else {
                ReadResult.successMessages(messages)
            }

        } catch (e: Exception) {
            Log.e(TAG, "读取聊天记录失败", e)
            ReadResult.error("读取失败: ${e.message}")
        }
    }

    /**
     * 递归收集消息
     */
    private fun collectMessages(
        node: AccessibilityNodeInfo,
        messages: MutableList<MessageData>,
        contactName: String,
        screenCenterX: Int,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > maxDepth) return

        val text = node.text?.toString()?.trim()

        // 检查是否是消息文本节点
        if (!text.isNullOrEmpty() && text.length >= 1) {
            // 排除时间分隔符
            if (!isTimeSeparator(text)) {
                // 判断是否为自己发送的消息（根据位置）
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                // 右侧消息是自己发送的
                val isSelf = bounds.left > screenCenterX

                // 生成消息ID
                val msgId = "${text.hashCode()}_${System.nanoTime()}".hashCode().toLong() and 0x7FFFFFFFFFFFFFFF

                messages.add(MessageData(
                    id = msgId,
                    sender = if (isSelf) "我" else contactName,
                    content = text,
                    type = "text",
                    time = System.currentTimeMillis(),
                    isSelf = isSelf
                ))
            }
        }

        // 递归处理子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectMessages(child, messages, contactName, screenCenterX, depth + 1, maxDepth)
        }
    }

    /**
     * 判断是否是时间分隔符文本
     */
    private fun isTimeSeparator(text: String): Boolean {
        // 时间格式: "12:30", "昨天 12:30", "2024年1月15日"
        if (text.matches(Regex("\\d{1,2}:\\d{2}"))) return true
        if (text.matches(Regex("昨天.*\\d{1,2}:\\d{2}"))) return true
        if (text.matches(Regex("\\d{4}年\\d{1,2}月\\d{1,2}日.*"))) return true
        if (text in setOf("以下为新消息", "查看更多消息")) return true
        return false
    }

    /**
     * 打开某信
     */
    fun openMoxin(): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(MOXIN_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
                Log.d(TAG, "Moxin launched")
                true
            } else {
                Log.e(TAG, "Moxin not found")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch Moxin", e)
            false
        }
    }

    /**
     * 等待窗口稳定
     */
    fun waitForWindow(timeoutMs: Long = 1500) {
        Thread.sleep(timeoutMs)
    }

    /**
     * 查找节点 - 按文本
     */
    fun findNodeByText(text: String, exact: Boolean = false): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val nodes = root.findAccessibilityNodeInfosByText(text)

        return if (exact) {
            nodes.find { it.text?.toString() == text }
        } else {
            nodes.firstOrNull()
        }
    }

    /**
     * 查找节点 - 按 resource-id
     */
    fun findNodeById(id: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findAccessibilityNodeInfosByViewId(id).firstOrNull()
    }

    /**
     * 查找可点击的父节点
     */
    fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                return current
            }
            current = current.parent
        }
        return null
    }

    /**
     * 点击节点
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        return try {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                val clickable = findClickableParent(node)
                clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to click node", e)
            false
        }
    }

    /**
     * 输入文本
     */
    fun inputText(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to input text", e)
            false
        }
    }

    /**
     * 执行返回操作
     */
    fun goBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * 执行点击坐标（通过手势）
     */
    fun clickAt(x: Int, y: Int): Boolean {
        return try {
            val gestureBuilder = GestureDescription.Builder()
            val clickPath = android.graphics.Path()
            clickPath.moveTo(x.toFloat(), y.toFloat())
            gestureBuilder.addStroke(GestureDescription.StrokeDescription(clickPath, 0, 100))

            val gesture = gestureBuilder.build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to click at ($x, $y)", e)
            false
        }
    }

    /**
     * 执行长按手势
     */
    fun longPressAt(x: Int, y: Int, durationMs: Long = 800): Boolean {
        return try {
            val gestureBuilder = GestureDescription.Builder()
            val path = android.graphics.Path()
            path.moveTo(x.toFloat(), y.toFloat())
            gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))

            val gesture = gestureBuilder.build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to long press at ($x, $y)", e)
            false
        }
    }

    /**
     * 执行滑动手势
     */
    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long = 300): Boolean {
        return try {
            val gestureBuilder = GestureDescription.Builder()
            val path = android.graphics.Path()
            path.moveTo(startX.toFloat(), startY.toFloat())
            path.lineTo(endX.toFloat(), endY.toFloat())
            gestureBuilder.addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))

            val gesture = gestureBuilder.build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to swipe", e)
            false
        }
    }

    /**
     * 获取屏幕尺寸
     * 优先使用 rootInActiveWindow，失败时使用 WindowManager 作为备用
     */
    fun getScreenBounds(): Rect {
        val rect = Rect()

        // 方式1: 从 rootInActiveWindow 获取
        rootInActiveWindow?.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            return rect
        }

        // 方式2: 从 WindowManager 获取（备用）
        try {
            val windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            val displayMetrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(displayMetrics)
            rect.set(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
            Log.d(TAG, "getScreenBounds from WindowManager: ${rect.width()}x${rect.height()}")
            return rect
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get screen bounds from WindowManager", e)
        }

        return rect
    }

    /**
     * 获取根节点
     */
    fun getRootNode(): AccessibilityNodeInfo? {
        return rootInActiveWindow
    }

    /**
     * 将 UI 树转换为 JSON（用于调试）
     * @param packageFilter 可选的包名过滤
     * @param maxDepth 最大递归深度
     */
    fun dumpUITreeToJson(packageFilter: String? = null, maxDepth: Int = 10): JSONObject {
        val result = JSONObject()
        result.put("timestamp", System.currentTimeMillis())
        result.put("package_filter", packageFilter)

        val root = rootInActiveWindow
        if (root == null) {
            result.put("error", "No active window")
            result.put("node_count", 0)
            return result
        }

        val screenBounds = Rect()
        root.getBoundsInScreen(screenBounds)
        result.put("screen_bounds", JSONObject().apply {
            put("left", screenBounds.left)
            put("top", screenBounds.top)
            put("right", screenBounds.right)
            put("bottom", screenBounds.bottom)
            put("width", screenBounds.width())
            put("height", screenBounds.height())
        })

        val nodeCount = intArrayOf(0)
        val nodesArray = JSONArray()
        dumpNodeToJson(root, nodesArray, 0, maxDepth, nodeCount, packageFilter)
        result.put("nodes", nodesArray)
        result.put("node_count", nodeCount[0])

        return result
    }

    /**
     * 递归转换单个节点为 JSON
     */
    private fun dumpNodeToJson(
        node: AccessibilityNodeInfo,
        nodesArray: JSONArray,
        depth: Int,
        maxDepth: Int,
        nodeCount: IntArray,
        packageFilter: String?
    ) {
        if (depth > maxDepth) return

        nodeCount[0]++

        val nodeJson = JSONObject()
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        nodeJson.put("depth", depth)
        nodeJson.put("class_name", node.className?.toString() ?: "")
        nodeJson.put("text", node.text?.toString() ?: "")
        nodeJson.put("content_desc", node.contentDescription?.toString() ?: "")
        nodeJson.put("view_id", node.viewIdResourceName ?: "")
        nodeJson.put("hint", node.hintText?.toString() ?: "")
        nodeJson.put("package", node.packageName?.toString() ?: "")
        nodeJson.put("clickable", node.isClickable)
        nodeJson.put("editable", node.isEditable)
        nodeJson.put("enabled", node.isEnabled)
        nodeJson.put("scrollable", node.isScrollable)
        nodeJson.put("focusable", node.isFocusable)
        nodeJson.put("bounds", JSONObject().apply {
            put("left", bounds.left)
            put("top", bounds.top)
            put("right", bounds.right)
            put("bottom", bounds.bottom)
        })

        nodesArray.put(nodeJson)

        // 递归处理子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue

            // 如果有包名过滤，检查是否匹配
            if (packageFilter != null) {
                val childPackage = child.packageName?.toString() ?: ""
                if (childPackage.isNotEmpty() && !childPackage.contains(packageFilter)) {
                    continue
                }
            }

            dumpNodeToJson(child, nodesArray, depth + 1, maxDepth, nodeCount, packageFilter)
        }
    }
}
