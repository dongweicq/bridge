package com.bridge

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build

class BridgeApp : Application() {

    companion object {
        const val CHANNEL_ID = "bridge_channel"
        const val CHANNEL_NAME = "Bridge Service"
        const val HTTP_PORT = 7788

        lateinit var instance: BridgeApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        createNotificationChannel()

        // 启动前台服务
        val intent = Intent(this, BridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Bridge 自动化服务运行中"
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
