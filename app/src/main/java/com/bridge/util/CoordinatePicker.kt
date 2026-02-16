package com.bridge.util

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.bridge.R

/**
 * 坐标选择器
 * 显示全屏蒙层，让用户点击选择坐标位置
 */
class CoordinatePicker(
    private val context: Context,
    private val onCoordinatePicked: (xRatio: Float, yRatio: Float) -> Unit,
    private val onCancelled: () -> Unit = {}
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var isShowing = false

    private var selectedX: Float? = null
    private var selectedY: Float? = null
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

    /**
     * 显示蒙层
     */
    fun show() {
        if (isShowing) return

        // 获取屏幕尺寸
        val screenBounds = getScreenBounds()
        screenWidth = screenBounds.width()
        screenHeight = screenBounds.height()

        // 创建蒙层视图
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        overlayView = LayoutInflater.from(context).inflate(R.layout.overlay_coordinate_picker, null)

        // 设置触摸监听
        setupTouchListeners()

        // 添加到窗口
        try {
            windowManager.addView(overlayView, layoutParams)
            isShowing = true
        } catch (e: Exception) {
            Toast.makeText(context, "无法显示蒙层: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupTouchListeners() {
        val view = overlayView ?: return

        val crosshairImage = view.findViewById<ImageView>(R.id.crosshairImage)
        val coordinateText = view.findViewById<TextView>(R.id.coordinateText)
        val confirmButton = view.findViewById<Button>(R.id.confirmButton)
        val cancelButton = view.findViewById<Button>(R.id.cancelButton)
        val bottomPanel = view.findViewById<View>(R.id.coordinateText).parent as View

        // 蒙层触摸事件
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val x = event.x
                val y = event.y

                // 检查是否点击在底部面板上
                val panelLocation = IntArray(2)
                bottomPanel.getLocationOnScreen(panelLocation)
                if (y >= panelLocation[1]) {
                    return@setOnTouchListener false
                }

                // 记录坐标
                selectedX = x / screenWidth
                selectedY = y / screenHeight

                // 更新显示
                coordinateText.text = String.format("X: %.3f  Y: %.3f", selectedX!!, selectedY!!)

                // 更新十字标记位置
                crosshairImage.visibility = View.VISIBLE
                crosshairImage.x = x - crosshairImage.width / 2
                crosshairImage.y = y - crosshairImage.height / 2

                // 启用确定按钮
                confirmButton.isEnabled = true

                true
            } else {
                false
            }
        }

        // 确定按钮
        confirmButton.setOnClickListener {
            val x = selectedX
            val y = selectedY
            if (x != null && y != null) {
                onCoordinatePicked(x, y)
            }
            dismiss()
        }

        // 取消按钮
        cancelButton.setOnClickListener {
            onCancelled()
            dismiss()
        }
    }

    /**
     * 隐藏蒙层
     */
    fun dismiss() {
        if (!isShowing) return

        try {
            overlayView?.let {
                windowManager.removeViewImmediate(it)
            }
        } catch (e: Exception) {
            // 忽略
        }

        overlayView = null
        isShowing = false
    }

    /**
     * 获取屏幕尺寸
     */
    private fun getScreenBounds(): Rect {
        val rect = Rect()
        try {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            display.getRealRect(rect)
        } catch (e: Exception) {
            // 备用方案
            val metrics = context.resources.displayMetrics
            rect.set(0, 0, metrics.widthPixels, metrics.heightPixels)
        }
        return rect
    }

    @Suppress("DEPRECATION")
    private fun android.view.Display.getRealRect(outRect: Rect) {
        val metrics = android.util.DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            getRealMetrics(metrics)
        } else {
            getMetrics(metrics)
        }
        outRect.set(0, 0, metrics.widthPixels, metrics.heightPixels)
    }
}
