package com.bridge

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bridge.util.Tool
import com.bridge.util.ToolManager
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREF_NAME = "bridge_settings"
        private const val KEY_CONTACT = "default_contact"
        private const val KEY_MESSAGE = "default_message"
    }

    private lateinit var statusText: TextView
    private lateinit var accessibilityBtn: Button
    private lateinit var contactInput: EditText
    private lateinit var messageInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        accessibilityBtn = findViewById(R.id.accessibilityBtn)
        contactInput = findViewById(R.id.contactInput)
        messageInput = findViewById(R.id.messageInput)

        // 初始化默认工具
        ToolManager.initDefaultTools(this)

        // 加载保存的设置
        loadSettings()

        // 确保 BridgeService 启动
        startBridgeService()

        accessibilityBtn.setOnClickListener {
            openAccessibilitySettings()
        }

        findViewById<Button>(R.id.testPingBtn).setOnClickListener {
            testPing()
        }

        // 测试搜索联系人
        findViewById<Button>(R.id.testSearchBtn).setOnClickListener {
            saveSettings()
            testSearch()
        }

        // 测试发送消息
        findViewById<Button>(R.id.testSendBtn).setOnClickListener {
            saveSettings()
            testSend()
        }

        // 工具管理按钮
        findViewById<Button>(R.id.toolManagerBtn).setOnClickListener {
            saveSettings()
            startActivity(Intent(this, ToolManagerActivity::class.java))
        }
    }

    // 加载保存的设置
    private fun loadSettings() {
        val prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        contactInput.setText(prefs.getString(KEY_CONTACT, "1810835"))
        messageInput.setText(prefs.getString(KEY_MESSAGE, "test"))
    }

    // 保存设置
    private fun saveSettings() {
        val contact = contactInput.text.toString().trim()
        val message = messageInput.text.toString().trim()
        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
            .edit()
            .putString(KEY_CONTACT, contact)
            .putString(KEY_MESSAGE, message)
            .apply()
    }

    // 获取默认联系人
    fun getDefaultContact(): String {
        return contactInput.text.toString().trim()
    }

    // 获取默认消息
    fun getDefaultMessage(): String {
        return messageInput.text.toString().trim()
    }

    private fun startBridgeService() {
        val intent = Intent(this, BridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        val hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val versionName = packageInfo.versionName
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

        val status = buildString {
            appendLine("Bridge 状态")
            appendLine()
            appendLine("HTTP: 127.0.0.1:${BridgeApp.HTTP_PORT}")
            appendLine("无障碍: ${if (isAccessibilityEnabled) "✓" else "✗"}")
            appendLine("悬浮窗: ${if (hasOverlayPermission) "✓" else "✗"}")
            appendLine("版本: $versionName ($versionCode)")
        }

        statusText.text = status

        accessibilityBtn.text = if (isAccessibilityEnabled) {
            "无障碍服务已启用"
        } else {
            "启用无障碍服务"
        }
        accessibilityBtn.isEnabled = !isAccessibilityEnabled
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = packageName + "/" + BridgeAccessibilityService::class.java.canonicalName
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.contains(service)
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun testPing() {
        Thread {
            try {
                val url = java.net.URL("http://127.0.0.1:${BridgeApp.HTTP_PORT}/ping")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 2000
                connection.readTimeout = 2000

                val response = connection.inputStream.bufferedReader().readText()
                connection.disconnect()

                runOnUiThread {
                    Toast.makeText(this, "Ping 成功: $response", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Ping 失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // 随机延迟函数（单位：毫秒）
    private fun randomDelay(minMs: Long, maxMs: Long) {
        Thread.sleep(minMs + Random.nextLong(maxMs - minMs))
    }

    // 测试搜索联系人 - 使用发送消息工具链（只执行到联系人）
    private fun testSearch() {
        val service = BridgeAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "请先启用无障碍服务", Toast.LENGTH_SHORT).show()
            return
        }

        val targetContact = contactInput.text.toString().trim()
        if (targetContact.isEmpty()) {
            Toast.makeText(this, "请输入联系人", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "开始搜索: $targetContact", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                // 使用发送按钮的工具链
                val allTools = ToolManager.getAllTools(this)
                val sendBtnTool = allTools.find { it.name == "发送按钮" }

                if (sendBtnTool == null) {
                    runOnUiThread {
                        Toast.makeText(this, "找不到「发送按钮」工具，请先在工具管理中配置", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                // 获取完整执行链
                val executionChain = ToolManager.getExecutionChain(this, sendBtnTool.id)
                android.util.Log.d("Bridge", "=== 搜索测试 ===")
                android.util.Log.d("Bridge", "执行链长度: ${executionChain.size}")
                android.util.Log.d("Bridge", "搜索执行链: ${executionChain.map { "${it.name}(x=${it.x},y=${it.y})" }}")

                // 设置剪贴板内容为目标联系人名称（直接使用中文）
                android.util.Log.d("Bridge", "搜索联系人: '$targetContact'")

                runOnUiThread {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(null, targetContact))
                }
                randomDelay(500, 1000)

                // 执行工具链（只执行到联系人，不包括消息输入框和发送按钮）
                for ((index, tool) in executionChain.withIndex()) {
                    // 停在联系人（执行完联系人点击后停止）
                    if (tool.name == "消息输入框") {
                        android.util.Log.d("Bridge", "到达消息输入框，停止执行")
                        break
                    }
                    android.util.Log.d("Bridge", "[$index] 执行工具: ${tool.name}, id=${tool.id}, x=${tool.x}, y=${tool.y}")
                    executeTool(tool, service)
                }

                runOnUiThread {
                    Toast.makeText(this, "搜索完成: $targetContact", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("Bridge", "搜索失败", e)
                runOnUiThread {
                    Toast.makeText(this, "搜索失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // 测试发送消息 - 使用工具链
    private fun testSend() {
        val service = BridgeAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "请先启用无障碍服务", Toast.LENGTH_SHORT).show()
            return
        }

        val targetContact = contactInput.text.toString().trim()
        val message = messageInput.text.toString().trim()

        if (targetContact.isEmpty()) {
            Toast.makeText(this, "请输入联系人", Toast.LENGTH_SHORT).show()
            return
        }
        if (message.isEmpty()) {
            Toast.makeText(this, "请输入消息内容", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "开始发送消息给 $targetContact", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                // 获取发送按钮工具的执行链
                val allTools = ToolManager.getAllTools(this)
                val sendBtnTool = allTools.find { it.name == "发送按钮" }

                if (sendBtnTool == null) {
                    runOnUiThread {
                        Toast.makeText(this, "找不到「发送按钮」工具，请先在工具管理中配置", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }

                // 获取完整执行链
                val executionChain = ToolManager.getExecutionChain(this, sendBtnTool.id)
                android.util.Log.d("Bridge", "发送执行链: ${executionChain.map { it.name }}")

                // 设置剪贴板内容为目标联系人名称（直接使用中文）
                android.util.Log.d("Bridge", "搜索联系人: '$targetContact'")

                runOnUiThread {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(null, targetContact))
                }
                randomDelay(500, 1000)

                // 执行工具链（不包括最后的发送按钮）
                for (tool in executionChain) {
                    if (tool.name == "发送按钮") continue
                    android.util.Log.d("Bridge", "执行工具: ${tool.name}")
                    executeTool(tool, service)
                }

                // 设置剪贴板为要发送的消息
                runOnUiThread {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(null, message))
                }
                randomDelay(1800, 4500)

                // 查找工具
                val msgInputTool = allTools.find { it.name == "消息输入框" }
                val imeClipboardTool = allTools.find { it.name == "输入法剪贴板" }

                // 点击消息输入框
                if (msgInputTool != null && msgInputTool.x > 0 && msgInputTool.y > 0) {
                    clickToolCoordinate(msgInputTool, service)
                    randomDelay(3000, 6000)  // 等待键盘弹出
                }

                // 点击输入法剪贴板粘贴消息
                if (imeClipboardTool != null && imeClipboardTool.x > 0 && imeClipboardTool.y > 0) {
                    clickToolCoordinate(imeClipboardTool, service)
                    randomDelay(4500, 9000)
                }

                // 点击发送按钮
                if (sendBtnTool.x > 0 && sendBtnTool.y > 0) {
                    clickToolCoordinate(sendBtnTool, service)
                    android.util.Log.d("Bridge", "点击发送按钮")
                }

                runOnUiThread {
                    Toast.makeText(this, "发送完成", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("Bridge", "发送失败", e)
                runOnUiThread {
                    Toast.makeText(this, "发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // 当前搜索词（用于工具链执行时保持剪贴板内容）
    private var currentSearchText: String = ""

    // 执行单个工具
    private fun executeTool(tool: Tool, service: BridgeAccessibilityService) {
        when (tool.id) {
            ToolManager.TOOL_OPEN_WECHAT -> {
                service.openWeChat()
                randomDelay(6000, 12000)
            }
            ToolManager.TOOL_SET_CLIPBOARD -> {
                // 保持当前剪贴板内容（已由调用方设置），不做覆盖
                randomDelay(1800, 4500)
            }
            ToolManager.TOOL_GO_BACK -> {
                service.goBack()
                randomDelay(1500, 3600)
            }
            else -> {
                // 用户定义的坐标工具
                if (tool.x > 0 && tool.y > 0) {
                    // 特殊处理：输入法剪贴板需要等待键盘
                    if (tool.name.contains("剪贴板") || tool.name.contains("输入法")) {
                        randomDelay(3000, 6000)
                    }

                    clickToolCoordinate(tool, service)
                    android.util.Log.d("Bridge", "点击 ${tool.name}")

                    // 根据工具名称设置不同的延迟
                    when {
                        tool.name.contains("搜索") -> randomDelay(4500, 9000)
                        tool.name.contains("剪贴板") || tool.name.contains("输入法") -> randomDelay(4500, 9000)
                        tool.name.contains("联系人") -> randomDelay(1500, 3600)
                        else -> randomDelay(1500, 3600)
                    }
                }
            }
        }
    }

    // 点击工具坐标
    private fun clickToolCoordinate(tool: Tool, service: BridgeAccessibilityService): Boolean {
        if (tool.x <= 0 || tool.y <= 0) {
            return false
        }

        val screenBounds = service.getScreenBounds()
        val x = (screenBounds.width() * tool.x).toInt()
        val y = (screenBounds.height() * tool.y).toInt()

        return service.clickAt(x, y)
    }
}
