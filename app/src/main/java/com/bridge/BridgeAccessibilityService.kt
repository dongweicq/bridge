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
import com.bridge.action.WeChatActionEngine
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

        // 微信包名
        const val WECHAT_PACKAGE = "com.tencent.mm"
    }

    private val actionEngine = WeChatActionEngine()
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

    /**
     * 打开微信
     */
    fun openWeChat(): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(WECHAT_PACKAGE)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
                Log.d(TAG, "WeChat launched")
                true
            } else {
                Log.e(TAG, "WeChat not found")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch WeChat", e)
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
     */
    fun getScreenBounds(): Rect {
        val rect = Rect()
        rootInActiveWindow?.getBoundsInScreen(rect)
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
