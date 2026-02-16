package com.bridge

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bridge.util.ConfigManager

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var accessibilityBtn: Button
    private lateinit var inputX: EditText
    private lateinit var inputY: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        accessibilityBtn = findViewById(R.id.accessibilityBtn)
        inputX = findViewById(R.id.inputX)
        inputY = findViewById(R.id.inputY)

        // 确保 BridgeService 启动
        startBridgeService()

        accessibilityBtn.setOnClickListener {
            openAccessibilitySettings()
        }

        findViewById<Button>(R.id.testPingBtn).setOnClickListener {
            testPing()
        }

        // 保存配置按钮
        findViewById<Button>(R.id.saveConfigBtn).setOnClickListener {
            saveConfig()
        }

        // 测试点击按钮
        findViewById<Button>(R.id.testClickBtn).setOnClickListener {
            testClick()
        }

        // 重置配置按钮
        findViewById<Button>(R.id.resetConfigBtn).setOnClickListener {
            resetConfig()
        }

        // 加载已保存的配置
        loadConfig()
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

        val status = buildString {
            appendLine("Bridge 状态")
            appendLine()
            appendLine("HTTP 服务: 127.0.0.1:${BridgeApp.HTTP_PORT}")
            appendLine("无障碍服务: ${if (isAccessibilityEnabled) "已启用 ✓" else "未启用 ✗"}")
            appendLine("版本: ${packageManager.getPackageInfo(packageName, 0).versionName}")
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

    private fun loadConfig() {
        val x = ConfigManager.getImeClipboardX(this)
        val y = ConfigManager.getImeClipboardY(this)
        inputX.setText(String.format("%.2f", x))
        inputY.setText(String.format("%.2f", y))
    }

    private fun saveConfig() {
        try {
            val x = inputX.text.toString().toFloatOrNull()
            val y = inputY.text.toString().toFloatOrNull()

            if (x == null || y == null) {
                Toast.makeText(this, "请输入有效的数字", Toast.LENGTH_SHORT).show()
                return
            }

            if (x < 0 || x > 1 || y < 0 || y > 1) {
                Toast.makeText(this, "坐标值必须在 0.0 - 1.0 之间", Toast.LENGTH_SHORT).show()
                return
            }

            ConfigManager.setImeClipboardCoords(this, x, y)
            Toast.makeText(this, "配置已保存: X=$x, Y=$y", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun testClick() {
        val service = BridgeAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "无障碍服务未启用", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val x = inputX.text.toString().toFloatOrNull()
            val y = inputY.text.toString().toFloatOrNull()

            if (x == null || y == null) {
                Toast.makeText(this, "请输入有效的数字", Toast.LENGTH_SHORT).show()
                return
            }

            val screenBounds = service.getScreenBounds()
            val clickX = (screenBounds.width() * x).toInt()
            val clickY = (screenBounds.height() * y).toInt()

            Toast.makeText(this, "将点击: ($clickX, $clickY) - 3秒后执行", Toast.LENGTH_SHORT).show()

            // 3秒后执行点击，给用户时间切换到目标应用
            Thread {
                Thread.sleep(3000)
                val success = service.clickAt(clickX, clickY)
                runOnUiThread {
                    if (success) {
                        Toast.makeText(this, "点击已执行", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "点击失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        } catch (e: Exception) {
            Toast.makeText(this, "测试失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resetConfig() {
        ConfigManager.resetToDefaults(this)
        loadConfig()
        Toast.makeText(this, "已重置为默认值", Toast.LENGTH_SHORT).show()
    }
}
