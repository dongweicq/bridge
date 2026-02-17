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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.bridge.R

/**
 * 坐标选择器
 * 显示全屏蒙层，让用户点击选择坐标位置
 */
class CoordinatePicker(
    context: Context,
    private val onCoordinatePicked: (xRatio: Float, yRatio: Float) -> Unit,
    private val onCancelled: () -> Unit = {},
    private val initialX: Float? = null,
    private val initialY: Float? = null
) {
    // 使用 applicationContext 确保能在其他应用之上显示 overlay
    private val context = context.applicationContext
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var isShowing = false

    private var selectedX: Float? = initialX
    private var selectedY: Float? = initialY
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

            // 如果有初始坐标，显示标记
            if (initialX != null && initialY != null) {
                showInitialCoordinate()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "无法显示蒙层: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 显示初始坐标位置
     */
    private fun showInitialCoordinate() {
        val view = overlayView ?: return
        val crosshairImage = view.findViewById<ImageView>(R.id.crosshairImage)
        val coordinateText = view.findViewById<TextView>(R.id.coordinateText)
        val confirmButton = view.findViewById<Button>(R.id.confirmButton)

        // 计算屏幕绝对坐标
        val screenX = initialX!! * screenWidth
        val screenY = initialY!! * screenHeight

        // 更新显示
        coordinateText.text = String.format("当前: X: %.3f  Y: %.3f", initialX!!, initialY!!)

        // 显示十字标记（需要转换为视图相对坐标）
        crosshairImage.visibility = View.VISIBLE
        crosshairImage.post {
            // 获取视图在屏幕上的位置
            val viewLocation = IntArray(2)
            view.getLocationOnScreen(viewLocation)

            // 转换为视图相对坐标
            val viewX = screenX - viewLocation[0]
            val viewY = screenY - viewLocation[1]

            crosshairImage.x = viewX - crosshairImage.width / 2
            crosshairImage.y = viewY - crosshairImage.height / 2

            android.util.Log.d("CoordinatePicker", "初始坐标: 屏幕($screenX, $screenY), 视图偏移(${viewLocation[0]}, ${viewLocation[1]}), 相对($viewX, $viewY)")
        }

        // 启用确定按钮
        confirmButton.isEnabled = true
    }

    private fun setupTouchListeners() {
        val view = overlayView ?: return

        val crosshairImage = view.findViewById<ImageView>(R.id.crosshairImage)
        val coordinateText = view.findViewById<TextView>(R.id.coordinateText)
        val confirmButton = view.findViewById<Button>(R.id.confirmButton)
        val cancelButton = view.findViewById<Button>(R.id.cancelButton)
        val hidePanelButton = view.findViewById<Button>(R.id.hidePanelButton)
        val showPanelButton = view.findViewById<Button>(R.id.showPanelButton)
        val bottomPanel = view.findViewById<LinearLayout>(R.id.bottomPanel)

        // 隐藏面板按钮
        hidePanelButton.setOnClickListener {
            bottomPanel.visibility = View.GONE
            showPanelButton.visibility = View.VISIBLE
        }

        // 显示面板按钮
        showPanelButton.setOnClickListener {
            bottomPanel.visibility = View.VISIBLE
            showPanelButton.visibility = View.GONE
        }

        // 蒙层触摸事件
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                // 使用 rawX/rawY 获取屏幕绝对坐标
                val rawX = event.rawX
                val rawY = event.rawY

                // 如果面板可见，检查是否点击在底部面板上
                if (bottomPanel.visibility == View.VISIBLE) {
                    val panelLocation = IntArray(2)
                    bottomPanel.getLocationOnScreen(panelLocation)
                    if (rawY >= panelLocation[1]) {
                        return@setOnTouchListener false
                    }
                }

                // 如果显示面板按钮可见，检查是否点击在按钮上
                if (showPanelButton.visibility == View.VISIBLE) {
                    val btnLocation = IntArray(2)
                    showPanelButton.getLocationOnScreen(btnLocation)
                    if (rawX >= btnLocation[0] && rawX <= btnLocation[0] + showPanelButton.width &&
                        rawY >= btnLocation[1] && rawY <= btnLocation[1] + showPanelButton.height) {
                        return@setOnTouchListener false
                    }
                }

                // 记录坐标（相对于屏幕尺寸的比例）
                selectedX = rawX / screenWidth
                selectedY = rawY / screenHeight

                android.util.Log.d("CoordinatePicker", "点击坐标: raw=($rawX, $rawY), 屏幕=(${screenWidth}x${screenHeight}), 比例=($selectedX, $selectedY)")

                // 更新显示
                coordinateText.text = String.format("X: %.3f  Y: %.3f", selectedX!!, selectedY!!)

                // 更新十字标记位置（使用raw坐标需要转换为视图坐标）
                val viewLocation = IntArray(2)
                view.getLocationOnScreen(viewLocation)
                val viewX = rawX - viewLocation[0]
                val viewY = rawY - viewLocation[1]

                crosshairImage.visibility = View.VISIBLE
                crosshairImage.x = viewX - crosshairImage.width / 2
                crosshairImage.y = viewY - crosshairImage.height / 2

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
