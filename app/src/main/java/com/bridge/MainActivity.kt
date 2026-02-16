package com.bridge

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bridge.util.ConfigManager
import com.bridge.util.CoordinatePicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    // 随机延迟函数（单位：毫秒）
    private fun randomDelay(minMs: Long, maxMs: Long) {
        val delay = minMs + Random.nextLong(maxMs - minMs)
        Thread.sleep(delay)
    }

    // 常用延迟配置 - 模拟正常人操作频率 (x3)
    private fun delayAfterClick() = randomDelay(1500, 3600)      // 点击后
    private fun delayAfterOpenApp() = randomDelay(6000, 12000)   // 打开应用后
    private fun delayAfterInput() = randomDelay(1800, 4500)      // 输入后
    private fun delayAfterSearch() = randomDelay(4500, 9000)     // 搜索后等待结果
    private fun delayAfterIME() = randomDelay(4500, 9000)        // 输入法操作后
    private fun delayBetweenSteps() = randomDelay(2400, 4500)    // 步骤之间
    private fun delayKeyboardShow() = randomDelay(3000, 6000)    // 等待键盘弹出

    private lateinit var statusText: TextView
    private lateinit var accessibilityBtn: Button

    // 5个步骤的输入框
    private lateinit var step1X: EditText
    private lateinit var step1Y: EditText
    private lateinit var step2X: EditText
    private lateinit var step2Y: EditText
    private lateinit var step3X: EditText
    private lateinit var step3Y: EditText
    private lateinit var step4X: EditText
    private lateinit var step4Y: EditText
    private lateinit var step5X: EditText
    private lateinit var step5Y: EditText

    private var coordinatePicker: CoordinatePicker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        accessibilityBtn = findViewById(R.id.accessibilityBtn)

        // 初始化输入框
        step1X = findViewById(R.id.step1X)
        step1Y = findViewById(R.id.step1Y)
        step2X = findViewById(R.id.step2X)
        step2Y = findViewById(R.id.step2Y)
        step3X = findViewById(R.id.step3X)
        step3Y = findViewById(R.id.step3Y)
        step4X = findViewById(R.id.step4X)
        step4Y = findViewById(R.id.step4Y)
        step5X = findViewById(R.id.step5X)
        step5Y = findViewById(R.id.step5Y)

        // 确保 BridgeService 启动
        startBridgeService()

        accessibilityBtn.setOnClickListener {
            openAccessibilitySettings()
        }

        findViewById<Button>(R.id.testPingBtn).setOnClickListener {
            testPing()
        }

        // 5个步骤的获取按钮
        findViewById<Button>(R.id.step1PickBtn).setOnClickListener { pickCoordinate(1) }
        findViewById<Button>(R.id.step2PickBtn).setOnClickListener { pickCoordinate(2) }
        findViewById<Button>(R.id.step3PickBtn).setOnClickListener { pickCoordinate(3) }
        findViewById<Button>(R.id.step4PickBtn).setOnClickListener { pickCoordinate(4) }
        findViewById<Button>(R.id.step5PickBtn).setOnClickListener { pickCoordinate(5) }

        // 5个步骤的测试按钮
        findViewById<Button>(R.id.step1TestBtn).setOnClickListener { testCoordinate(1) }
        findViewById<Button>(R.id.step2TestBtn).setOnClickListener { testCoordinate(2) }
        findViewById<Button>(R.id.step3TestBtn).setOnClickListener { testCoordinate(3) }
        findViewById<Button>(R.id.step4TestBtn).setOnClickListener { testCoordinate(4) }
        findViewById<Button>(R.id.step5TestBtn).setOnClickListener { testCoordinate(5) }

        // 保存和重置按钮
        findViewById<Button>(R.id.saveConfigBtn).setOnClickListener {
            saveConfig()
        }

        findViewById<Button>(R.id.resetConfigBtn).setOnClickListener {
            resetConfig()
        }

        findViewById<Button>(R.id.testSendBtn).setOnClickListener {
            testSend()
        }

        // 工具管理按钮
        findViewById<Button>(R.id.toolManagerBtn).setOnClickListener {
            startActivity(Intent(this, ToolManagerActivity::class.java))
        }
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
        loadConfig()
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

    // 加载配置
    private fun loadConfig() {
        step1X.setText(String.format("%.3f", ConfigManager.getSearchBtnX(this)))
        step1Y.setText(String.format("%.3f", ConfigManager.getSearchBtnY(this)))
        step2X.setText(String.format("%.3f", ConfigManager.getImeClipboardX(this)))
        step2Y.setText(String.format("%.3f", ConfigManager.getImeClipboardY(this)))
        step3X.setText(String.format("%.3f", ConfigManager.getContactX(this)))
        step3Y.setText(String.format("%.3f", ConfigManager.getContactY(this)))
        step4X.setText(String.format("%.3f", ConfigManager.getMsgInputX(this)))
        step4Y.setText(String.format("%.3f", ConfigManager.getMsgInputY(this)))
        step5X.setText(String.format("%.3f", ConfigManager.getSendBtnX(this)))
        step5Y.setText(String.format("%.3f", ConfigManager.getSendBtnY(this)))
    }

    // 保存配置
    private fun saveConfig() {
        try {
            ConfigManager.setSearchBtnCoords(
                this,
                step1X.text.toString().toFloatOrNull() ?: 0.845f,
                step1Y.text.toString().toFloatOrNull() ?: 0.075f
            )
            ConfigManager.setImeClipboardCoords(
                this,
                step2X.text.toString().toFloatOrNull() ?: 0.50f,
                step2Y.text.toString().toFloatOrNull() ?: 0.65f
            )
            ConfigManager.setContactCoords(
                this,
                step3X.text.toString().toFloatOrNull() ?: 0.50f,
                step3Y.text.toString().toFloatOrNull() ?: 0.213f
            )
            ConfigManager.setMsgInputCoords(
                this,
                step4X.text.toString().toFloatOrNull() ?: 0.35f,
                step4Y.text.toString().toFloatOrNull() ?: 0.955f
            )
            ConfigManager.setSendBtnCoords(
                this,
                step5X.text.toString().toFloatOrNull() ?: 0.92f,
                step5Y.text.toString().toFloatOrNull() ?: 0.955f
            )
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // 重置配置
    private fun resetConfig() {
        ConfigManager.resetToDefaults(this)
        loadConfig()
        Toast.makeText(this, "已重置为默认值", Toast.LENGTH_SHORT).show()
    }

    // 获取坐标
    private fun pickCoordinate(step: Int) {
        // 检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }

        // 检查无障碍服务
        val service = BridgeAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "请先启用无障碍服务", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "正在准备界面...", Toast.LENGTH_SHORT).show()

        // 在协程中执行前置步骤
        CoroutineScope(Dispatchers.Main).launch {
            val success = executePreSteps(step, service)
            if (success) {
                // 显示蒙层
                showCoordinatePicker(step)
            }
        }
    }

    // 测试坐标（执行前置步骤并点击目标位置）
    private fun testCoordinate(step: Int) {
        // 检查无障碍服务
        val service = BridgeAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "请先启用无障碍服务", Toast.LENGTH_SHORT).show()
            return
        }

        // 先保存当前配置
        saveConfig()

        val stepNames = arrayOf("", "搜索按钮", "输入法剪贴板", "联系人", "消息输入框", "发送按钮")
        Toast.makeText(this, "开始测试步骤${step}: ${stepNames[step]}", Toast.LENGTH_SHORT).show()

        // 使用后台线程执行
        Thread {
            try {
                android.util.Log.d("Bridge", "=== 开始测试步骤 $step ===")

                // 获取屏幕尺寸
                val screenBounds = service.getScreenBounds()
                android.util.Log.d("Bridge", "屏幕尺寸: ${screenBounds.width()}x${screenBounds.height()}")

                // 步骤1：打开微信
                android.util.Log.d("Bridge", "打开微信...")
                val opened = service.openWeChat()
                android.util.Log.d("Bridge", "打开微信结果: $opened")
                delayAfterOpenApp()

                if (step == 1) {
                    // 直接点击目标位置
                    val x = (screenBounds.width() * ConfigManager.getSearchBtnX(this)).toInt()
                    val y = (screenBounds.height() * ConfigManager.getSearchBtnY(this)).toInt()
                    android.util.Log.d("Bridge", "步骤1点击: ($x, $y)")
                    service.clickAt(x, y)
                    runOnUiThread { Toast.makeText(this, "已点击步骤1: ($x, $y)", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }

                // 点击搜索按钮
                delayBetweenSteps()
                val x1 = (screenBounds.width() * ConfigManager.getSearchBtnX(this)).toInt()
                val y1 = (screenBounds.height() * ConfigManager.getSearchBtnY(this)).toInt()
                android.util.Log.d("Bridge", "点击搜索按钮: ($x1, $y1)")
                service.clickAt(x1, y1)
                delayAfterClick()

                if (step == 2) {
                    // 设置剪贴板并触发输入法
                    delayBetweenSteps()
                    val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(null, "test"))
                    delayAfterInput()

                    val inputX = (screenBounds.width() * 0.50f).toInt()
                    val inputY = (screenBounds.height() * 0.05f).toInt()
                    service.clickAt(inputX, inputY)
                    delayKeyboardShow()

                    val x = (screenBounds.width() * ConfigManager.getImeClipboardX(this)).toInt()
                    val y = (screenBounds.height() * ConfigManager.getImeClipboardY(this)).toInt()
                    android.util.Log.d("Bridge", "步骤2点击: ($x, $y)")
                    service.clickAt(x, y)
                    runOnUiThread { Toast.makeText(this, "已点击步骤2: ($x, $y)", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }

                // 后续步骤的准备工作
                delayBetweenSteps()
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(null, "test"))
                delayAfterInput()

                val inputX = (screenBounds.width() * 0.50f).toInt()
                val inputY = (screenBounds.height() * 0.05f).toInt()
                service.clickAt(inputX, inputY)
                delayKeyboardShow()

                // 点击输入法剪贴板
                delayBetweenSteps()
                val x2 = (screenBounds.width() * ConfigManager.getImeClipboardX(this)).toInt()
                val y2 = (screenBounds.height() * ConfigManager.getImeClipboardY(this)).toInt()
                service.clickAt(x2, y2)
                delayAfterIME()

                if (step == 3) {
                    // 等待搜索结果显示
                    delayAfterSearch()
                    val x = (screenBounds.width() * ConfigManager.getContactX(this)).toInt()
                    val y = (screenBounds.height() * ConfigManager.getContactY(this)).toInt()
                    android.util.Log.d("Bridge", "步骤3点击联系人: ($x, $y)")
                    val clicked = service.clickAt(x, y)
                    runOnUiThread { Toast.makeText(this, "步骤3点击联系人($x, $y): ${if(clicked) "成功" else "失败"}", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }

                // 点击联系人
                delayAfterSearch()
                val x3 = (screenBounds.width() * ConfigManager.getContactX(this)).toInt()
                val y3 = (screenBounds.height() * ConfigManager.getContactY(this)).toInt()
                service.clickAt(x3, y3)
                delayAfterClick()

                if (step == 4) {
                    val x = (screenBounds.width() * ConfigManager.getMsgInputX(this)).toInt()
                    val y = (screenBounds.height() * ConfigManager.getMsgInputY(this)).toInt()
                    android.util.Log.d("Bridge", "步骤4点击: ($x, $y)")
                    service.clickAt(x, y)
                    delayKeyboardShow()  // 等待键盘弹出
                    runOnUiThread { Toast.makeText(this, "已点击步骤4: ($x, $y)", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }

                // 点击消息输入框
                delayBetweenSteps()
                val x4 = (screenBounds.width() * ConfigManager.getMsgInputX(this)).toInt()
                val y4 = (screenBounds.height() * ConfigManager.getMsgInputY(this)).toInt()
                service.clickAt(x4, y4)
                delayAfterClick()

                if (step == 5) {
                    // 注意：不隐藏键盘，因为发送按钮坐标是在键盘弹出时获取的
                    delayKeyboardShow()
                    val x = (screenBounds.width() * ConfigManager.getSendBtnX(this)).toInt()
                    val y = (screenBounds.height() * ConfigManager.getSendBtnY(this)).toInt()
                    android.util.Log.d("Bridge", "步骤5点击: ($x, $y)")
                    service.clickAt(x, y)
                    runOnUiThread { Toast.makeText(this, "已点击步骤5: ($x, $y)", Toast.LENGTH_SHORT).show() }
                    return@Thread
                }

            } catch (e: Exception) {
                android.util.Log.e("Bridge", "测试坐标失败", e)
                runOnUiThread { Toast.makeText(this, "测试失败: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    // 执行前置步骤（用于获取坐标）
    private suspend fun executePreSteps(step: Int, service: BridgeAccessibilityService): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                // 步骤1：打开微信
                service.openWeChat()
                kotlinx.coroutines.delay(6000 + Random.nextLong(6000))

                if (step == 1) return@withContext true

                // 步骤1b：点击搜索按钮
                kotlinx.coroutines.delay(2400 + Random.nextLong(2100))
                val screenBounds = service.getScreenBounds()
                val x1 = (screenBounds.width() * ConfigManager.getSearchBtnX(this@MainActivity)).toInt()
                val y1 = (screenBounds.height() * ConfigManager.getSearchBtnY(this@MainActivity)).toInt()
                service.clickAt(x1, y1)
                kotlinx.coroutines.delay(1500 + Random.nextLong(2100))

                // 步骤2需要：设置剪贴板内容，点击输入框触发输入法
                if (step == 2) {
                    kotlinx.coroutines.delay(2400 + Random.nextLong(2100))
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(null, "test"))
                    kotlinx.coroutines.delay(1800 + Random.nextLong(2700))

                    // 点击搜索输入框触发输入法
                    val inputX = (screenBounds.width() * 0.50f).toInt()
                    val inputY = (screenBounds.height() * 0.05f).toInt()
                    service.clickAt(inputX, inputY)
                    kotlinx.coroutines.delay(3000 + Random.nextLong(3000))
                    return@withContext true
                }

                // 步骤2b：设置剪贴板并点击输入法剪贴板（用于后续步骤）
                kotlinx.coroutines.delay(2400 + Random.nextLong(2100))
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(null, "test"))
                kotlinx.coroutines.delay(1800 + Random.nextLong(2700))

                // 点击搜索输入框触发输入法
                val inputX = (screenBounds.width() * 0.50f).toInt()
                val inputY = (screenBounds.height() * 0.05f).toInt()
                service.clickAt(inputX, inputY)
                kotlinx.coroutines.delay(3000 + Random.nextLong(3000))

                // 点击输入法剪贴板
                kotlinx.coroutines.delay(2400 + Random.nextLong(2100))
                val x2 = (screenBounds.width() * ConfigManager.getImeClipboardX(this@MainActivity)).toInt()
                val y2 = (screenBounds.height() * ConfigManager.getImeClipboardY(this@MainActivity)).toInt()
                service.clickAt(x2, y2)
                kotlinx.coroutines.delay(4500 + Random.nextLong(4500))

                if (step == 3) return@withContext true

                // 步骤3：点击联系人（第一个搜索结果）- 需要等待搜索结果
                kotlinx.coroutines.delay(4500 + Random.nextLong(4500))
                val x3 = (screenBounds.width() * ConfigManager.getContactX(this@MainActivity)).toInt()
                val y3 = (screenBounds.height() * ConfigManager.getContactY(this@MainActivity)).toInt()
                service.clickAt(x3, y3)
                kotlinx.coroutines.delay(1500 + Random.nextLong(2100))

                if (step == 4) return@withContext true

                // 步骤4：点击消息输入框
                kotlinx.coroutines.delay(2400 + Random.nextLong(2100))
                val x4 = (screenBounds.width() * ConfigManager.getMsgInputX(this@MainActivity)).toInt()
                val y4 = (screenBounds.height() * ConfigManager.getMsgInputY(this@MainActivity)).toInt()
                service.clickAt(x4, y4)
                kotlinx.coroutines.delay(3000 + Random.nextLong(3000))

                if (step == 5) {
                    // 步骤5不需要隐藏键盘，因为发送按钮坐标是在键盘弹出时获取的
                    return@withContext true
                }

                true
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "执行前置步骤失败: ${e.message}", Toast.LENGTH_SHORT).show()
                false
            }
        }
    }

    // 执行前置步骤（用于测试坐标）
    private suspend fun executePreStepsForTest(step: Int, service: BridgeAccessibilityService): Boolean {
        return withContext(Dispatchers.Main) {
            try {
                // 步骤1：打开微信
                service.openWeChat()
                delay(2000)

                if (step == 1) return@withContext true

                // 步骤1b：点击搜索按钮
                val screenBounds = service.getScreenBounds()
                val x1 = (screenBounds.width() * ConfigManager.getSearchBtnX(this@MainActivity)).toInt()
                val y1 = (screenBounds.height() * ConfigManager.getSearchBtnY(this@MainActivity)).toInt()
                service.clickAt(x1, y1)
                delay(1000)

                // 步骤2需要：设置剪贴板内容，点击输入框触发输入法
                if (step == 2) {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(null, "test"))
                    delay(500)

                    // 点击搜索输入框触发输入法
                    val inputX = (screenBounds.width() * 0.50f).toInt()
                    val inputY = (screenBounds.height() * 0.05f).toInt()
                    service.clickAt(inputX, inputY)
                    delay(1500) // 等待输入法弹出
                    return@withContext true
                }

                // 后续步骤的准备工作
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(null, "test"))
                delay(500)

                // 点击搜索输入框触发输入法
                val inputX = (screenBounds.width() * 0.50f).toInt()
                val inputY = (screenBounds.height() * 0.05f).toInt()
                service.clickAt(inputX, inputY)
                delay(1000)

                if (step == 3) {
                    // 步骤3需要先点击剪贴板（输入搜索内容）
                    val x2 = (screenBounds.width() * ConfigManager.getImeClipboardX(this@MainActivity)).toInt()
                    val y2 = (screenBounds.height() * ConfigManager.getImeClipboardY(this@MainActivity)).toInt()
                    service.clickAt(x2, y2)
                    delay(500)
                    return@withContext true
                }

                // 点击输入法剪贴板
                val x2 = (screenBounds.width() * ConfigManager.getImeClipboardX(this@MainActivity)).toInt()
                val y2 = (screenBounds.height() * ConfigManager.getImeClipboardY(this@MainActivity)).toInt()
                service.clickAt(x2, y2)
                delay(500)

                if (step == 4) {
                    // 步骤4需要先进入聊天界面
                    val x3 = (screenBounds.width() * ConfigManager.getContactX(this@MainActivity)).toInt()
                    val y3 = (screenBounds.height() * ConfigManager.getContactY(this@MainActivity)).toInt()
                    service.clickAt(x3, y3)
                    delay(1000)
                    return@withContext true
                }

                // 步骤4：点击消息输入框
                val x4 = (screenBounds.width() * ConfigManager.getMsgInputX(this@MainActivity)).toInt()
                val y4 = (screenBounds.height() * ConfigManager.getMsgInputY(this@MainActivity)).toInt()
                service.clickAt(x4, y4)
                delay(500)

                if (step == 5) {
                    // 步骤5需要隐藏键盘
                    service.goBack()
                    delay(300)
                    return@withContext true
                }

                true
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "执行前置步骤失败: ${e.message}", Toast.LENGTH_SHORT).show()
                false
            }
        }
    }

    // 显示坐标选择器
    private fun showCoordinatePicker(step: Int) {
        // 获取已有坐标
        val currentX: Float?
        val currentY: Float?
        when (step) {
            1 -> {
                currentX = ConfigManager.getSearchBtnX(this)
                currentY = ConfigManager.getSearchBtnY(this)
            }
            2 -> {
                currentX = ConfigManager.getImeClipboardX(this)
                currentY = ConfigManager.getImeClipboardY(this)
            }
            3 -> {
                currentX = ConfigManager.getContactX(this)
                currentY = ConfigManager.getContactY(this)
            }
            4 -> {
                currentX = ConfigManager.getMsgInputX(this)
                currentY = ConfigManager.getMsgInputY(this)
            }
            5 -> {
                currentX = ConfigManager.getSendBtnX(this)
                currentY = ConfigManager.getSendBtnY(this)
            }
            else -> {
                currentX = null
                currentY = null
            }
        }

        coordinatePicker?.dismiss()
        coordinatePicker = CoordinatePicker(
            context = this,
            onCoordinatePicked = { x, y ->
                runOnUiThread {
                    // 保存到对应的输入框
                    when (step) {
                        1 -> {
                            step1X.setText(String.format("%.3f", x))
                            step1Y.setText(String.format("%.3f", y))
                        }
                        2 -> {
                            step2X.setText(String.format("%.3f", x))
                            step2Y.setText(String.format("%.3f", y))
                        }
                        3 -> {
                            step3X.setText(String.format("%.3f", x))
                            step3Y.setText(String.format("%.3f", y))
                        }
                        4 -> {
                            step4X.setText(String.format("%.3f", x))
                            step4Y.setText(String.format("%.3f", y))
                        }
                        5 -> {
                            step5X.setText(String.format("%.3f", x))
                            step5Y.setText(String.format("%.3f", y))
                        }
                    }
                    // 自动保存
                    saveConfig()
                }
            },
            onCancelled = {
                runOnUiThread {
                    Toast.makeText(this, "已取消", Toast.LENGTH_SHORT).show()
                }
            },
            initialX = if (currentX != null && currentX > 0) currentX else null,
            initialY = if (currentY != null && currentY > 0) currentY else null
        )
        coordinatePicker?.show()
    }

    // 测试发送
    private fun testSend() {
        // 先保存当前配置
        saveConfig()

        Thread {
            try {
                val url = java.net.URL("http://127.0.0.1:${BridgeApp.HTTP_PORT}/send_message")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val json = """{"target":"文件传输助手","message":"坐标配置测试"}"""
                connection.outputStream.write(json.toByteArray())

                val response = connection.inputStream.bufferedReader().readText()
                connection.disconnect()

                runOnUiThread {
                    Toast.makeText(this, "已提交: $response", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "测试失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // 协程辅助函数
    private suspend fun <T> withContext(context: kotlin.coroutines.CoroutineContext, block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T {
        return kotlinx.coroutines.withContext(context, block)
    }
}
