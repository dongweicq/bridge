package com.bridge.ocr

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bridge.BridgeApp
import com.bridge.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * MediaProjection 前台服务
 * 用于 MediaProjection API 要求的前台服务
 */
class MediaProjectionService : Service() {

    companion object {
        private const val TAG = "MediaProjectionService"
        private const val NOTIFICATION_ID = 2001

        var isRunning = false
            private set

        // 用于等待服务就绪的锁
        private val readyLatch = CountDownLatch(1)

        /**
         * 启动服务并等待就绪
         * @return 服务是否成功启动
         */
        fun startAndWait(context: Context): Boolean {
            // 重置锁
            val latch = CountDownLatch(1)
            val oldLatch = readyLatch

            val intent = Intent(context, MediaProjectionService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            // 等待服务就绪（最多 5 秒）
            return try {
                latch.await(5, TimeUnit.SECONDS) || isRunning
            } catch (e: Exception) {
                Log.e(TAG, "等待服务启动超时", e)
                false
            }
        }

        /**
         * 启动服务
         */
        fun start(context: Context) {
            val intent = Intent(context, MediaProjectionService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 停止服务
         */
        fun stop(context: Context) {
            val intent = Intent(context, MediaProjectionService::class.java)
            context.stopService(intent)
        }

        /**
         * 通知服务已就绪
         */
        private fun notifyReady() {
            try {
                // 倒计数所有等待的锁
                while (readyLatch.count > 0) {
                    readyLatch.countDown()
                }
            } catch (e: Exception) {
                Log.e(TAG, "notifyReady error", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MediaProjectionService onCreate")
        startForeground(NOTIFICATION_ID, createNotification())
        isRunning = true
        // 通知服务已就绪
        notifyReady()
        Log.d(TAG, "MediaProjectionService 已就绪")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "MediaProjectionService onStartCommand")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MediaProjectionService onDestroy")
        isRunning = false
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, BridgeApp.CHANNEL_ID)
            .setContentTitle("Bridge 投屏服务")
            .setContentText("正在运行")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
