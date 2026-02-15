package com.bridge

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var accessibilityBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        accessibilityBtn = findViewById(R.id.accessibilityBtn)

        // 确保 BridgeService 启动
        startBridgeService()

        accessibilityBtn.setOnClickListener {
            openAccessibilitySettings()
        }

        findViewById<Button>(R.id.testPingBtn).setOnClickListener {
            testPing()
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
}
