package com.bridge.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.bridge.BridgeAccessibilityService
import com.bridge.model.ContactData
import com.bridge.model.ReadErrorType
import com.bridge.model.ReadResult
import com.bridge.util.ConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 滚动 OCR 读取器
 * 用于滚动屏幕并读取完整列表
 */
class ScrollingOcrReader(
    private val context: Context,
    private val screenshotHelper: ScreenshotHelper
) {
    companion object {
        private const val TAG = "ScrollingOcrReader"
        private const val SCROLL_RATIO = 0.7f  // 滚动屏幕高度的70%
        private const val SCROLL_DURATION = 300L  // 滚动持续时间(ms)
        private const val STABILIZE_DELAY = 800L  // 等待屏幕稳定时间(ms)
        private const val MAX_CONSECUTIVE_SAME = 3  // 连续3次相同则停止
        private const val MAX_OCR_RETRIES = 3  // OCR最大重试次数
        private const val INITIAL_RETRY_DELAY = 1000L  // 初始重试延迟(ms)
    }

    /**
     * 获取联系人黑名单（从配置管理器加载）
     */
    private fun getContactBlacklist(): Set<String> {
        return ConfigManager.getContactBlacklist(context)
    }

    /**
     * 带重试的OCR识别
     * 使用指数退避策略：1s, 2s, 4s
     */
    private suspend fun recognizeWithRetry(bitmap: Bitmap): OcrService.OcrResult {
        var lastError: String? = null
        
        repeat(MAX_OCR_RETRIES) { attempt ->
            val result = OcrService.recognize(bitmap)
            if (result.success) {
                if (attempt > 0) {
                    Log.d(TAG, "OCR重试第$attempt次成功")
                }
                return result
            }
            
            lastError = result.error
            Log.w(TAG, "OCR识别失败 (尝试 ${attempt + 1}/$MAX_OCR_RETRIES): ${result.error}")
            
            if (attempt < MAX_OCR_RETRIES - 1) {
                val retryDelay = INITIAL_RETRY_DELAY * (1 shl attempt)  // 指数退避: 1s, 2s, 4s
                Log.d(TAG, "等待 ${retryDelay}ms 后重试...")
                delay(retryDelay)
            }
        }
        
        return OcrService.OcrResult(
            success = false,
            error = "OCR重试${MAX_OCR_RETRIES}次后仍失败: $lastError"
        )
    }

    /**
     * 带重试的截图捕获
     */
    private suspend fun captureWithRetry(): ScreenshotHelper.ScreenshotResult {
        var lastError: String? = null
        
        repeat(MAX_OCR_RETRIES) { attempt ->
            val result = screenshotHelper.capture()
            if (result.success && result.bitmap != null) {
                if (attempt > 0) {
                    Log.d(TAG, "截图重试第$attempt次成功")
                }
                return result
            }
            
            lastError = result.error
            Log.w(TAG, "截图失败 (尝试 ${attempt + 1}/$MAX_OCR_RETRIES): ${result.error}")
            
            if (attempt < MAX_OCR_RETRIES - 1) {
                val retryDelay = INITIAL_RETRY_DELAY * (1 shl attempt)
                Log.d(TAG, "等待 ${retryDelay}ms 后重试截图...")
                delay(retryDelay)
            }
        }
        
        return ScreenshotHelper.ScreenshotResult(
            success = false,
            error = "截图重试${MAX_OCR_RETRIES}次后仍失败: $lastError"
        )
    }

    /**
     * 滚动读取联系人列表
     */
    suspend fun readContactsScrolling(service: BridgeAccessibilityService): ReadResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        return@withContext withTimeoutOrNull(120_000L) {  // 最多2分钟
            try {
                val allContacts = mutableListOf<ContactData>()
                val seenNames = mutableSetOf<String>()
                var consecutiveSameCount = 0
                var lastScreenText = ""
                var scrollCount = 0
                val processingStart = System.currentTimeMillis()

                Log.d(TAG, "开始滚动读取联系人列表")

                while (consecutiveSameCount < MAX_CONSECUTIVE_SAME) {
                    // 1. 截图（带重试）
                    val screenshot = captureWithRetry()
                    if (!screenshot.success || screenshot.bitmap == null) {
                        Log.e(TAG, "截图失败: ${screenshot.error}")
                        // 如果已有部分联系人，返回部分结果
                        if (allContacts.isNotEmpty()) {
                            Log.w(TAG, "返回部分结果: ${allContacts.size} 个联系人")
                            val processingTime = System.currentTimeMillis() - processingStart
                            return@withTimeoutOrNull ReadResult.successContacts(
                                allContacts, 
                                partial = true, 
                                error = "截图失败但已有部分结果: ${screenshot.error}",
                                scrollCount = scrollCount,
                                processingTimeMs = processingTime
                            )
                        }
                        return@withTimeoutOrNull ReadResult.error(
                            "截图失败: ${screenshot.error}",
                            ReadErrorType.SCREENSHOT_FAILED
                        )
                    }

                    // 2. OCR识别（带重试）
                    val bitmap = screenshot.bitmap!!
                    val ocrResult = recognizeWithRetry(bitmap)

                    if (!ocrResult.success) {
                        Log.e(TAG, "OCR识别失败: ${ocrResult.error}")
                        bitmap.recycle()
                        // 如果已有部分联系人，返回部分结果
                        if (allContacts.isNotEmpty()) {
                            Log.w(TAG, "返回部分结果: ${allContacts.size} 个联系人")
                            val processingTime = System.currentTimeMillis() - processingStart
                            return@withTimeoutOrNull ReadResult.successContacts(
                                allContacts,
                                partial = true,
                                error = "OCR失败但已有部分结果: ${ocrResult.error}",
                                scrollCount = scrollCount,
                                processingTimeMs = processingTime
                            )
                        }
                        return@withTimeoutOrNull ReadResult.error(
                            "OCR识别失败: ${ocrResult.error}",
                            ReadErrorType.OCR_FAILED
                        )
                    }

                    // 3. 检测是否滚动到底部（连续相同内容）
                    val currentScreenText = ocrResult.fullText
                    if (currentScreenText == lastScreenText) {
                        consecutiveSameCount++
                        Log.d(TAG, "屏幕内容相同 ($consecutiveSameCount/$MAX_CONSECUTIVE_SAME)")
                    } else {
                        consecutiveSameCount = 0
                    }
                    lastScreenText = currentScreenText

                    // 4. 提取联系人
                    val screenContacts = extractContacts(ocrResult.textBlocks, bitmap)
                    
                    // 4a. 回收bitmap避免内存泄漏
                    bitmap.recycle()
                    
                    val newContacts = screenContacts.filter { it.name !in seenNames }

                    newContacts.forEach { contact ->
                        seenNames.add(contact.name)
                        allContacts.add(contact)
                        Log.d(TAG, "发现联系人: ${contact.name}")
                    }

                    Log.d(TAG, "本次屏幕识别: ${screenContacts.size} 个, 新增: ${newContacts.size} 个, 累计: ${allContacts.size} 个")

                    // 5. 如果连续相同次数达到阈值，停止滚动
                    if (consecutiveSameCount >= MAX_CONSECUTIVE_SAME) {
                        Log.d(TAG, "连续 $MAX_CONSECUTIVE_SAME 次内容相同，停止滚动")
                        break
                    }

                    // 6. 滚动屏幕
                    val screenBounds = service.getScreenBounds()
                    val centerX = screenBounds.width() / 2
                    val startY = (screenBounds.height() * 0.7).toInt()
                    val endY = (screenBounds.height() * (1 - SCROLL_RATIO)).toInt()

                    service.swipe(centerX, startY, centerX, endY, SCROLL_DURATION)
                    scrollCount++

                    Log.d(TAG, "滚动 #$scrollCount: ($centerX, $startY) -> ($centerX, $endY)")

                    // 7. 等待屏幕稳定
                    delay(STABILIZE_DELAY)
                }

                Log.d(TAG, "滚动读取完成: 共 ${allContacts.size} 个联系人, 滚动 $scrollCount 次")
                
                val processingTime = System.currentTimeMillis() - processingStart

                if (allContacts.isEmpty()) {
                    ReadResult.error(
                        "未识别到联系人",
                        ReadErrorType.NO_CONTACTS_FOUND
                    )
                } else {
                    ReadResult.successContacts(
                        allContacts,
                        scrollCount = scrollCount,
                        processingTimeMs = processingTime
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "滚动读取失败", e)
                ReadResult.error(
                    "读取失败: ${e.message}",
                    ReadErrorType.UNKNOWN
                )
            }
        } ?: ReadResult.error(
            "读取超时（120秒）",
            ReadErrorType.TIMEOUT
        )
    }

    /**
     * 从 OCR 结果中提取联系人
     */
    private fun extractContacts(
        textBlocks: List<OcrService.TextBlock>,
        bitmap: Bitmap
    ): List<ContactData> {
        val contacts = mutableListOf<ContactData>()
        val screenHeight = bitmap.height
        val screenWidth = bitmap.width

        for (block in textBlocks) {
            val text = block.text.trim()

            // 过滤条件
            if (!isValidContactName(text, block.bounds, screenWidth, screenHeight)) {
                continue
            }

            contacts.add(ContactData(
                name = text,
                displayName = text,
                lastMessage = null,
                lastTime = null,
                unreadCount = 0
            ))
        }

        return contacts
    }

    /**
     * 判断是否是有效的联系人名称
     * 改进版本：更宽松的过滤条件，支持更多昵称格式
     */
    private fun isValidContactName(text: String, bounds: Rect, screenWidth: Int, screenHeight: Int): Boolean {
        // 1. 长度检查（1-25字符，支持中文、英文、表情）
        // 放宽限制以支持单字昵称和长昵称
        if (text.isEmpty() || text.length > 25) {
            return false
        }

        // 2. 黑名单检查（包含部分匹配）
        val blacklist = getContactBlacklist()
        val lowerText = text.lowercase()
        for (blacklistItem in blacklist) {
            if (text == blacklistItem || lowerText.contains(blacklistItem.lowercase())) {
                return false
            }
        }

        // 3. 纯数字检查（排除电话号码等，但允许数字+字母混合）
        if (text.matches(Regex("^\\d+$"))) {
            return false
        }

        // 4. 时间格式检查
        if (text.matches(Regex("^\\d{1,2}:\\d{2}$"))) {
            return false
        }
        if (text.matches(Regex("^昨天.*\\d{1,2}:\\d{2}$"))) {
            return false
        }
        if (text.matches(Regex("^\\d{4}年\\d{1,2}月\\d{1,2}日.*"))) {
            return false
        }

        // 5. 位置检查（联系人名称通常在屏幕上半部分，排除底部导航）
        if (bounds.top > screenHeight * 0.85) {
            return false
        }

        // 6. 高度检查（排除过大的标题等）
        if (bounds.height() > screenHeight * 0.1) {
            return false
        }

        // 7. 位置分析：联系人名称通常左对齐（x < 40% 屏幕宽度）
        // 这有助于过滤掉右侧的消息预览等内容
        val relativeX = bounds.left.toFloat() / screenWidth
        if (relativeX > 0.4f) {
            return false
        }

        // 8. 必须包含至少一个字母或中文或有效表情符号
        val hasLetterOrChinese = text.any { it.isLetter() }
        if (!hasLetterOrChinese) {
            // 检查是否是有效表情符号（使用更全面的Unicode范围）
            val hasEmoji = text.any { char ->
                char.code in 0x1F600..0x1F64F ||  // 表情符号
                char.code in 0x1F300..0x1F5FF ||  // 符号和象形文字
                char.code in 0x1F680..0x1F6FF ||  // 交通和地图符号
                char.code in 0x1F700..0x1F77F ||  // 炼金术符号
                char.code in 0x1F780..0x1F7FF ||  // 几何图形扩展
                char.code in 0x1F800..0x1F8FF ||  // 补充箭头-C
                char.code in 0x1F900..0x1F9FF ||  // 补充符号和象形文字
                char.code in 0x1FA00..0x1FA6F ||  // 棋类符号
                char.code in 0x1FA70..0x1FAFF ||  // 符号和象形文字扩展-A
                char.code in 0x2600..0x26FF   ||  // 杂项符号
                char.code in 0x2700..0x27BF   ||  // 装饰符号
                char.code > 0x1F000              // 其他补充符号
            }
            if (!hasEmoji) {
                return false
            }
        }

        return true
    }
}
