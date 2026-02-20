package com.bridge.ocr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 截图服务
 * 使用 MediaProjection API 截取屏幕
 */
class ScreenshotHelper(private val context: Context) {

    companion object {
        private const val TAG = "ScreenshotHelper"
        const val REQUEST_CODE_SCREENSHOT = 1001

        private var mediaProjection: MediaProjection? = null
        private var virtualDisplay: VirtualDisplay? = null
        private var imageReader: ImageReader? = null
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    /**
     * 截图结果
     */
    data class ScreenshotResult(
        val success: Boolean,
        val bitmap: Bitmap? = null,
        val error: String? = null
    )

    /**
     * 获取屏幕尺寸
     */
    fun getScreenSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            return Pair(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            return Pair(metrics.widthPixels, metrics.heightPixels)
        }
    }

    /**
     * 初始化 MediaProjection
     * @param resultCode 从 onActivityResult 获取的 resultCode
     * @param data 从 onActivityResult 获取的 Intent
     */
    suspend fun initMediaProjection(resultCode: Int, data: Intent): Boolean {
        return try {
            Log.d(TAG, "=== initMediaProjection 开始 ===")
            Log.d(TAG, "resultCode=$resultCode (RESULT_OK=${Activity.RESULT_OK})")
            Log.d(TAG, "data=$data")
            Log.d(TAG, "data.extras=${data.extras}")

            if (resultCode != Activity.RESULT_OK) {
                Log.e(TAG, "Result code is not OK: $resultCode")
                return false
            }

            if (data == null) {
                Log.e(TAG, "Intent data is null")
                return false
            }

            // Android 10+ 需要先启动 mediaProjection 类型的前台服务
            Log.d(TAG, "启动 MediaProjectionService...")
            if (!MediaProjectionService.isRunning) {
                MediaProjectionService.start(context)
                // 等待服务启动（最多 3 秒）
                var waitCount = 0
                while (!MediaProjectionService.isRunning && waitCount < 30) {
                    delay(100)
                    waitCount++
                }
                if (!MediaProjectionService.isRunning) {
                    Log.e(TAG, "MediaProjectionService 启动超时")
                    return false
                }
                // 额外等待确保 startForeground 完成
                delay(500)
            }
            Log.d(TAG, "MediaProjectionService 已就绪: ${MediaProjectionService.isRunning}")

            // 释放旧的 MediaProjection
            mediaProjection?.stop()
            mediaProjection = null

            Log.d(TAG, "调用 projectionManager.getMediaProjection...")
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            if (mediaProjection == null) {
                Log.e(TAG, "getMediaProjection returned null")
                MediaProjectionService.stop(context)
                return false
            }

            // 注册回调以检测异常
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped")
                    mediaProjection = null
                    // 停止截图服务
                    MediaProjectionService.stop(context)
                }
            }, Handler(Looper.getMainLooper()))

            Log.d(TAG, "=== MediaProjection 初始化成功 ===")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init MediaProjection: ${e.message}", e)
            MediaProjectionService.stop(context)
            false
        }
    }

    /**
     * 检查是否已初始化
     */
    fun isInitialized(): Boolean = mediaProjection != null

    /**
     * 截取当前屏幕
     */
    suspend fun capture(): ScreenshotResult {
        return suspendCancellableCoroutine { continuation ->
            val projection = mediaProjection
            if (projection == null) {
                Log.e(TAG, "capture: MediaProjection not initialized")
                continuation.resume(ScreenshotResult(
                    success = false,
                    error = "MediaProjection not initialized"
                ))
                return@suspendCancellableCoroutine
            }

            try {
                val (width, height) = getScreenSize()
                val density = context.resources.displayMetrics.densityDpi

                Log.d(TAG, "Capturing screen: ${width}x${height}, density=$density")

                // 创建 ImageReader
                imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                val reader = imageReader!!

                // 使用 ImageReader 的 OnImageAvailableListener 来获取图像
                reader.setOnImageAvailableListener({ imgReader ->
                    try {
                        val image = imgReader.acquireLatestImage()
                        if (image != null) {
                            val bitmap = imageToBitmap(image, width, height)
                            image.close()

                            // 清理
                            virtualDisplay?.release()
                            virtualDisplay = null
                            imgReader.setOnImageAvailableListener(null, null)

                            Log.d(TAG, "Screenshot captured successfully")

                            continuation.resume(ScreenshotResult(
                                success = true,
                                bitmap = bitmap
                            ))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing image", e)
                        continuation.resume(ScreenshotResult(
                            success = false,
                            error = "Error processing image: ${e.message}"
                        ))
                    }
                }, Handler(Looper.getMainLooper()))

                // 创建 VirtualDisplay
                virtualDisplay = projection.createVirtualDisplay(
                    "Screenshot",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface,
                    object : VirtualDisplay.Callback() {
                        override fun onPaused() {
                            Log.d(TAG, "VirtualDisplay paused")
                        }

                        override fun onResumed() {
                            Log.d(TAG, "VirtualDisplay resumed")
                        }

                        override fun onStopped() {
                            Log.d(TAG, "VirtualDisplay stopped")
                        }
                    },
                    Handler(Looper.getMainLooper())
                )

                // 设置超时
                Handler(Looper.getMainLooper()).postDelayed({
                    if (continuation.isActive) {
                        Log.w(TAG, "Screenshot timeout, trying to acquire image directly")

                        // 尝试直接获取图像
                        try {
                            val image = reader.acquireLatestImage()
                            if (image != null) {
                                val bitmap = imageToBitmap(image, width, height)
                                image.close()

                                virtualDisplay?.release()
                                virtualDisplay = null

                                continuation.resume(ScreenshotResult(
                                    success = true,
                                    bitmap = bitmap
                                ))
                            } else {
                                continuation.resume(ScreenshotResult(
                                    success = false,
                                    error = "Timeout: No image available"
                                ))
                            }
                        } catch (e: Exception) {
                            continuation.resume(ScreenshotResult(
                                success = false,
                                error = "Timeout: ${e.message}"
                            ))
                        }
                    }
                }, 3000) // 3秒超时

            } catch (e: Exception) {
                Log.e(TAG, "Screenshot failed", e)
                continuation.resume(ScreenshotResult(
                    success = false,
                    error = e.message ?: "Screenshot failed"
                ))
            }
        }
    }

    /**
     * 将 Image 转换为 Bitmap
     */
    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)

        // 裁剪掉多余的 padding
        return if (rowPadding > 0) {
            Bitmap.createBitmap(bitmap, 0, 0, width, height)
        } else {
            bitmap
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        // 停止截图服务
        MediaProjectionService.stop(context)
        Log.d(TAG, "ScreenshotHelper released")
    }
}
