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
}
