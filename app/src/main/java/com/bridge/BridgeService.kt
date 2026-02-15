package com.bridge

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bridge.http.BridgeServer

class BridgeService : Service() {

    companion object {
        private const val TAG = "BridgeService"
        private const val NOTIFICATION_ID = 1001

        var isRunning = false
            private set
    }

    private var server: BridgeServer? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BridgeService onCreate")
        startForeground(NOTIFICATION_ID, createNotification())
        startHttpServer()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "BridgeService onStartCommand")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "BridgeService onDestroy")
        stopHttpServer()
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
            .setContentTitle("Bridge 运行中")
            .setContentText("HTTP: 127.0.0.1:${BridgeApp.HTTP_PORT}")
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startHttpServer() {
        try {
            server = BridgeServer(BridgeApp.HTTP_PORT)
            server?.start()
            Log.d(TAG, "HTTP Server started on port ${BridgeApp.HTTP_PORT}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP Server", e)
        }
    }

    private fun stopHttpServer() {
        server?.stop()
        server = null
        Log.d(TAG, "HTTP Server stopped")
    }
}
