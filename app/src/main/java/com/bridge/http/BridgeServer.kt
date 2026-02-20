package com.bridge.http

import android.graphics.Bitmap
import android.util.Log
import com.bridge.BridgeAccessibilityService
import com.bridge.BridgeService
import com.bridge.action.ActionDispatcher
import com.bridge.action.MoxinActionEngine
import com.bridge.cache.MoxinDataCache
import com.bridge.model.Task
import com.bridge.model.TaskResult
import com.bridge.model.TaskStatus
import com.bridge.ocr.OcrService
import com.bridge.ocr.ScrollingOcrReader
import com.bridge.ocr.ScreenshotHelper
import com.google.gson.Gson
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridge HTTP Server
 * 提供 REST API 供 OpenClaw 调用
 */
class BridgeServer(port: Int) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "BridgeServer"
        private val gson = Gson()

        // 任务存储（内存，简单实现）
        private val tasks = ConcurrentHashMap<String, Task>()
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.d(TAG, "Request: $method $uri")

        return try {
            when {
                uri == "/ping" && method == Method.GET -> handlePing()
                uri == "/health" && method == Method.GET -> handleHealth()
                uri == "/send_message" && method == Method.POST -> handleSendMessage(session)
                uri == "/chat_list" && method == Method.GET -> handleChatList(session)
                uri == "/chat_history" && method == Method.GET -> handleChatHistory(session)
                uri.startsWith("/task/") && method == Method.GET -> handleTaskStatus(uri)
                uri.startsWith("/debug/ui_tree") && method == Method.GET -> handleDebugUITree(session)
                uri == "/debug/ocr" && method == Method.GET -> handleOcrTest(session)
                uri == "/debug/screenshot_init" && method == Method.GET -> handleScreenshotInit(session)
                else -> json(Response.Status.NOT_FOUND, mapOf("error" to "Not Found"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request error", e)
            json(Response.Status.INTERNAL_ERROR, mapOf("error" to (e.message ?: "Internal Error")))
        }
    }

    /**
     * GET /ping - 健康检查
     */
    private fun handlePing(): Response {
        return json(Response.Status.OK, mapOf(
            "status" to "ok",
            "version" to "1.0"
        ))
    }

    /**
     * GET /health - 状态检查
     */
    private fun handleHealth(): Response {
        val accessibilityEnabled = BridgeAccessibilityService.instance != null

        return json(Response.Status.OK, mapOf(
            "status" to if (accessibilityEnabled) "ok" else "degraded",
            "uptime" to formatUptime(),
            "queue_length" to tasks.values.count { it.status == TaskStatus.QUEUED },
            "accessibility_enabled" to accessibilityEnabled,
            "service_running" to BridgeService.isRunning
        ))
    }

    /**
     * POST /send_message - 发送消息
     */
    private fun handleSendMessage(session: IHTTPSession): Response {
        // 解析请求体
        val body = parseBody(session)
        val target = body["target"] as? String
        val message = body["message"] as? String

        if (target.isNullOrBlank() || message.isNullOrBlank()) {
            return json(Response.Status.BAD_REQUEST, mapOf(
                "status" to "error",
                "error" to "Missing 'target' or 'message'"
            ))
        }

        // 检查无障碍服务
        if (BridgeAccessibilityService.instance == null) {
            return json(Response.Status.SERVICE_UNAVAILABLE, mapOf(
                "status" to "error",
                "error" to "Accessibility service not enabled"
            ))
        }

        // 创建任务
        val task = Task(
            type = com.bridge.model.TaskType.SEND_MESSAGE,
            target = target,
            message = message
        )
        tasks[task.id] = task

        // 异步执行任务
        executeTaskAsync(task)

        // 立即返回
        return json(Response.Status.OK, mapOf(
            "status" to "queued",
            "task_id" to task.id
        ))
    }

    /**
     * GET /task/{id} - 查询任务状态
     */
    private fun handleTaskStatus(uri: String): Response {
        val taskId = uri.removePrefix("/task/")
        val task = tasks[taskId]

        if (task == null) {
            return json(Response.Status.NOT_FOUND, mapOf(
                "error" to "Task not found"
            ))
        }

        val result = mutableMapOf(
            "id" to task.id,
            "status" to task.status.name.lowercase(),
            "target" to task.target
        )

        if (task.status == TaskStatus.FAILED) {
            result["error"] = (task.error ?: "Unknown error")
        }

        return json(Response.Status.OK, result)
    }

    /**
     * GET /chat_list - 获取联系人列表
     * 参数:
     *   refresh=true 强制刷新缓存
     *   use_ocr=true 使用OCR方式读取（推荐，支持滚动）
     */
    private fun handleChatList(session: IHTTPSession): Response {
        val params = session.parms
        val refresh = params["refresh"]?.toBoolean() ?: false
        val useOcr = params["use_ocr"]?.toBoolean() ?: true  // 默认使用OCR

        val service = BridgeAccessibilityService.instance
        if (service == null) {
            return json(Response.Status.SERVICE_UNAVAILABLE, mapOf(
                "status" to "error",
                "error" to "Accessibility service not enabled"
            ))
        }

        // 先检查缓存（除非强制刷新）
        if (!refresh) {
            val cached = MoxinDataCache.getContacts()
            if (cached != null && cached.isNotEmpty()) {
                return json(Response.Status.OK, mapOf(
                    "status" to "ok",
                    "source" to "cache",
                    "total" to cached.size,
                    "contacts" to cached.map { contact ->
                        mapOf(
                            "name" to contact.name,
                            "display_name" to (contact.displayName ?: contact.name),
                            "last_message" to (contact.lastMessage ?: ""),
                            "last_time" to (contact.lastTime ?: 0),
                            "unread_count" to contact.unreadCount
                        )
                    }
                ))
            }
        }

        // OCR 方式（支持滚动读取完整列表）
        if (useOcr) {
            return executeOcrReadWithCache(
                service = service,
                cacheSetter = { MoxinDataCache.setContacts(it) }
            )
        }

        // 传统 AccessibilityService 方式（不支持滚动）
        return executeReadWithCache(
            service = service,
            cacheSetter = { MoxinDataCache.setContacts(it) },
            readOperation = { actionEngine, svc ->
                // 导航到首页
                val navResult = actionEngine.navigateToMoxinHome(svc)
                if (!navResult.success) {
                    return@executeReadWithCache com.bridge.model.ReadResult.error(navResult.error ?: "导航失败")
                }
                // 读取联系人列表
                svc.readChatList()
            }
        )
    }

    /**
     * GET /chat_history - 获取会话历史
     * 参数: target=联系人名称, refresh=true 强制刷新
     */
    private fun handleChatHistory(session: IHTTPSession): Response {
        val params = session.parms
        val target = params["target"] ?: ""
        val refresh = params["refresh"]?.toBoolean() ?: false

        if (target.isBlank()) {
            return json(Response.Status.BAD_REQUEST, mapOf(
                "status" to "error",
                "error" to "Missing required parameter: target"
            ))
        }

        val service = BridgeAccessibilityService.instance
        if (service == null) {
            return json(Response.Status.SERVICE_UNAVAILABLE, mapOf(
                "status" to "error",
                "error" to "Accessibility service not enabled"
            ))
        }

        // 先检查缓存（除非强制刷新）
        if (!refresh) {
            val cached = MoxinDataCache.getMessages(target)
            if (cached != null && cached.isNotEmpty()) {
                return json(Response.Status.OK, mapOf(
                    "status" to "ok",
                    "source" to "cache",
                    "contact" to target,
                    "total" to cached.size,
                    "messages" to cached.map { msg ->
                        mapOf(
                            "id" to msg.id,
                            "sender" to msg.sender,
                            "content" to msg.content,
                            "type" to msg.type,
                            "time" to msg.time,
                            "is_self" to msg.isSelf
                        )
                    }
                ))
            }
        }

        return executeReadWithCacheMessages(
            service = service,
            contactName = target,
            cacheSetter = { MoxinDataCache.setMessages(target, it) },
            readOperation = { actionEngine, svc ->
                // 导航到聊天界面
                val navResult = actionEngine.navigateToChat(svc, target)
                if (!navResult.success) {
                    return@executeReadWithCacheMessages com.bridge.model.ReadResult.error(navResult.error ?: "导航失败")
                }
                // 读取聊天记录
                svc.readChatHistory(target)
            }
        )
    }

    /**
     * 带缓存的读取执行器 - 联系人列表
     */
    private fun executeReadWithCache(
        service: BridgeAccessibilityService,
        cacheSetter: (List<com.bridge.model.ContactData>) -> Unit,
        readOperation: suspend (MoxinActionEngine, BridgeAccessibilityService) -> com.bridge.model.ReadResult
    ): Response {
        // 使用同步方式执行读取（在 ActionDispatcher 线程）
        var result: com.bridge.model.ReadResult? = null
        val latch = java.util.concurrent.CountDownLatch(1)

        CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                result = withContext(ActionDispatcher.dispatcher) {
                    val actionEngine = MoxinActionEngine()
                    readOperation(actionEngine, service)
                }
            } catch (e: Exception) {
                Log.e(TAG, "读取失败", e)
                result = com.bridge.model.ReadResult.error("读取异常: ${e.message}")
            } finally {
                latch.countDown()
            }
        }

        // 等待完成（最多30秒）
        if (!latch.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
            return json(Response.Status.INTERNAL_ERROR, mapOf(
                "status" to "error",
                "error" to "操作超时"
            ))
        }

        val readResult = result!!
        return if (readResult.success && readResult.contacts != null) {
            // 更新缓存
            cacheSetter(readResult.contacts!!)

            json(Response.Status.OK, mapOf(
                "status" to "ok",
                "source" to "fresh",
                "total" to readResult.totalCount,
                "contacts" to readResult.contacts!!.map { contact ->
                    mapOf(
                        "name" to contact.name,
                        "display_name" to (contact.displayName ?: contact.name),
                        "last_message" to (contact.lastMessage ?: ""),
                        "last_time" to (contact.lastTime ?: 0),
                        "unread_count" to contact.unreadCount
                    )
                }
            ))
        } else {
            json(Response.Status.INTERNAL_ERROR, mapOf(
                "status" to "error",
                "error" to (readResult.error ?: "读取失败")
            ))
        }
    }

    /**
     * 带缓存的读取执行器 - 聊天记录
     */
    private fun executeReadWithCacheMessages(
        service: BridgeAccessibilityService,
        contactName: String,
        cacheSetter: (List<com.bridge.model.MessageData>) -> Unit,
        readOperation: suspend (MoxinActionEngine, BridgeAccessibilityService) -> com.bridge.model.ReadResult
    ): Response {
        // 使用同步方式执行读取（在 ActionDispatcher 线程）
        var result: com.bridge.model.ReadResult? = null
        val latch = java.util.concurrent.CountDownLatch(1)

        CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                result = withContext(ActionDispatcher.dispatcher) {
                    val actionEngine = MoxinActionEngine()
                    readOperation(actionEngine, service)
                }
            } catch (e: Exception) {
                Log.e(TAG, "读取聊天记录失败", e)
                result = com.bridge.model.ReadResult.error("读取异常: ${e.message}")
            } finally {
                latch.countDown()
            }
        }

        // 等待完成（最多60秒，导航+读取可能较慢）
        if (!latch.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
            return json(Response.Status.INTERNAL_ERROR, mapOf(
                "status" to "error",
                "error" to "操作超时"
            ))
        }

        val readResult = result!!
        return if (readResult.success && readResult.messages != null) {
            // 更新缓存
            cacheSetter(readResult.messages!!)

            json(Response.Status.OK, mapOf(
                "status" to "ok",
                "source" to "fresh",
                "contact" to contactName,
                "total" to readResult.totalCount,
                "has_more" to readResult.hasMore,
                "messages" to readResult.messages!!.map { msg ->
                    mapOf(
                        "id" to msg.id,
                        "sender" to msg.sender,
                        "content" to msg.content,
                        "type" to msg.type,
                        "time" to msg.time,
                        "is_self" to msg.isSelf
                    )
                }
            ))
        } else {
            json(Response.Status.INTERNAL_ERROR, mapOf(
                "status" to "error",
                "contact" to contactName,
                "error" to (readResult.error ?: "读取失败")
            ))
        }
    }

    /**
     * 使用 OCR 方式读取联系人列表（支持滚动）
     * 包含导航重试逻辑
     */
    private fun executeOcrReadWithCache(
        service: BridgeAccessibilityService,
        cacheSetter: (List<com.bridge.model.ContactData>) -> Unit
    ): Response {
        // 检查截图权限
        if (screenshotHelper?.isInitialized() != true) {
            return json(Response.Status.SERVICE_UNAVAILABLE, mapOf(
                "status" to "error",
                "error" to "截图权限未初始化",
                "hint" to "请在 Bridge APP 中点击 OCR 测试按钮授权截图权限，或调用 /debug/screenshot_init"
            ))
        }

        // 使用同步方式执行
        var result: com.bridge.model.ReadResult? = null
        var navigationRetries = 0
        val maxNavigationRetries = 3
        val latch = java.util.concurrent.CountDownLatch(1)

        CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                // 1. 导航到通讯录（带重试）
                var navResult: com.bridge.model.TaskResult
                do {
                    navResult = withContext(ActionDispatcher.dispatcher) {
                        val actionEngine = MoxinActionEngine()
                        actionEngine.navigateToContacts(service)
                    }
                    
                    if (!navResult.success && navigationRetries < maxNavigationRetries - 1) {
                        navigationRetries++
                        Log.w(TAG, "导航失败，重试 ($navigationRetries/$maxNavigationRetries): ${navResult.error}")
                        kotlinx.coroutines.delay(2000L * navigationRetries)  // 指数退避
                    } else {
                        break
                    }
                } while (navigationRetries < maxNavigationRetries)

                if (!navResult.success) {
                    result = com.bridge.model.ReadResult.error(
                        "导航到通讯录失败 (重试${navigationRetries}次): ${navResult.error}"
                    )
                    latch.countDown()
                    return@launch
                }

                // 2. 使用滚动 OCR 读取联系人
                val reader = ScrollingOcrReader(
                    context = BridgeService.instance ?: service.applicationContext,
                    screenshotHelper = screenshotHelper!!
                )
                result = reader.readContactsScrolling(service)

            } catch (e: Exception) {
                Log.e(TAG, "OCR 读取失败", e)
                result = com.bridge.model.ReadResult.error("OCR 读取异常: ${e.message}")
            } finally {
                latch.countDown()
            }
        }

        // 等待完成（最多2分钟）
        if (!latch.await(120, java.util.concurrent.TimeUnit.SECONDS)) {
            return json(Response.Status.INTERNAL_ERROR, mapOf(
                "status" to "error",
                "error" to "OCR 读取超时"
            ))
        }

        val readResult = result!!
        return if (readResult.success && readResult.contacts != null) {
            // 更新缓存
            cacheSetter(readResult.contacts!!)

            val responseData = mutableMapOf(
                "status" to if (readResult.partial) "partial" else "ok",
                "source" to readResult.source,
                "method" to "scrolling_ocr",
                "total" to readResult.totalCount,
                "partial" to readResult.partial,
                "navigation_retries" to navigationRetries,
                "scroll_count" to readResult.scrollCount,
                "processing_time_ms" to readResult.processingTimeMs,
                "contacts" to readResult.contacts!!.map { contact ->
                    mapOf(
                        "name" to contact.name,
                        "display_name" to (contact.displayName ?: contact.name),
                        "last_message" to (contact.lastMessage ?: ""),
                        "last_time" to (contact.lastTime ?: 0),
                        "unread_count" to contact.unreadCount
                    )
                }
            )
            if (readResult.error != null) {
                responseData["warning"] = readResult.error
            }
            json(Response.Status.OK, responseData)
        } else {
            json(Response.Status.INTERNAL_ERROR, mapOf(
                "status" to "error",
                "error" to (readResult.error ?: "读取失败"),
                "error_type" to (readResult.errorType?.name ?: "UNKNOWN"),
                "navigation_retries" to navigationRetries,
                "partial" to readResult.partial,
                "partial_count" to readResult.totalCount
            ))
        }
    }

    /**
     * 异步执行任务
     */
    private fun executeTaskAsync(task: Task) {
        CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                // 更新状态为运行中
                task.status = TaskStatus.RUNNING

                // 在 Action 线程执行 UI 操作
                val result = withContext(ActionDispatcher.dispatcher) {
                    val service = BridgeAccessibilityService.instance
                    if (service == null) {
                        TaskResult.fail("Accessibility service not available")
                    } else {
                        service.executeSendMessage(task)
                    }
                }

                // 更新任务状态
                if (result.success) {
                    task.status = TaskStatus.DONE
                    Log.d(TAG, "Task ${task.id} completed: ${result.message}")
                } else {
                    task.status = TaskStatus.FAILED
                    task.error = result.error ?: "Unknown error"
                    Log.w(TAG, "Task ${task.id} failed: ${task.error}")
                }

            } catch (e: Exception) {
                task.status = TaskStatus.FAILED
                task.error = e.message ?: "Exception"
                Log.e(TAG, "Task ${task.id} exception", e)
            }
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 解析 JSON 请求体
     * 直接从输入流读取，确保 UTF-8 编码正确解析中文
     */
    private fun parseBody(session: IHTTPSession): Map<String, Any> {
        return try {
            // 获取 Content-Length
            val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength == 0) {
                return emptyMap()
            }

            // 直接从输入流读取字节
            val buffer = ByteArray(contentLength)
            var bytesRead = 0
            while (bytesRead < contentLength) {
                val read = session.inputStream.read(buffer, bytesRead, contentLength - bytesRead)
                if (read == -1) break
                bytesRead += read
            }

            // 使用 UTF-8 解码
            val body = String(buffer, 0, bytesRead, Charsets.UTF_8)
            Log.d(TAG, "收到请求体: $body")

            gson.fromJson(body, JsonObject::class.java).let { json ->
                json.entrySet().associate { it.key to (it.value.asString) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析请求体失败: ${e.message}")
            emptyMap()
        }
    }

    /**
     * 返回 JSON 响应
     */
    private fun json(status: Response.Status, data: Any): Response {
        return newFixedLengthResponse(
            status,
            "application/json",
            gson.toJson(data)
        )
    }

    /**
     * GET /debug/ui_tree - 调试接口，返回UI树结构
     * 参数:
     *   - package: 可选，过滤指定包名的节点（如 com.tencent.mm）
     *   - depth: 可选，最大递归深度，默认10
     */
    private fun handleDebugUITree(session: IHTTPSession): Response {
        val params = session.parms
        val packageFilter = params["package"]
        val maxDepth = params["depth"]?.toIntOrNull() ?: 10

        val service = BridgeAccessibilityService.instance
        if (service == null) {
            return json(Response.Status.SERVICE_UNAVAILABLE, mapOf(
                "status" to "error",
                "error" to "Accessibility service not enabled"
            ))
        }

        return try {
            val uiTree = service.dumpUITreeToJson(packageFilter, maxDepth)
            newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                uiTree.toString()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dump UI tree", e)
            json(Response.Status.INTERNAL_ERROR, mapOf(
                "status" to "error",
                "error" to (e.message ?: "Failed to dump UI tree")
            ))
        }
    }

    // 截图权限相关
    private var screenshotHelper: ScreenshotHelper? = null
    private var pendingScreenshotResult: ((Boolean) -> Unit)? = null
    private var screenshotResultCode: Int = 0
    private var screenshotData: android.content.Intent? = null

    /**
     * 设置截图权限结果（由 MainActivity 调用）
     */
    fun setScreenshotResult(resultCode: Int, data: android.content.Intent?) {
        Log.d(TAG, "setScreenshotResult: resultCode=$resultCode")
        screenshotResultCode = resultCode
        screenshotData = data

        // 初始化 ScreenshotHelper
        if (screenshotHelper == null) {
            screenshotHelper = ScreenshotHelper(BridgeService.instance ?: return)
        }

        val intent = data ?: return
        
        // initMediaProjection is now suspend, launch in coroutine
        CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val success = screenshotHelper?.initMediaProjection(resultCode, intent) ?: false
                pendingScreenshotResult?.invoke(success)
                pendingScreenshotResult = null
            } catch (e: Exception) {
                Log.e(TAG, "initMediaProjection failed", e)
                pendingScreenshotResult?.invoke(false)
                pendingScreenshotResult = null
            }
        }
    }

    /**
     * GET /debug/screenshot_init - 初始化截图权限
     * 这个端点需要配合前端使用，实际授权需要通过 UI
     * 返回当前截图权限状态
     */
    private fun handleScreenshotInit(session: IHTTPSession): Response {
        val initialized = screenshotHelper?.isInitialized() ?: false
        return json(Response.Status.OK, mapOf(
            "status" to "ok",
            "screenshot_initialized" to initialized,
            "message" to if (initialized) "截图权限已初始化" else "截图权限未初始化，请在 Bridge APP 中点击 OCR 测试按钮授权"
        ))
    }

    /**
     * GET /debug/ocr - OCR 测试
     * 参数:
     *   - save_image: 可选，保存截图到文件 (true/false)
     *
     * 需要:
     *   1. 先在 Bridge APP 中授权截图权限
     *   2. 或者通过 /debug/screenshot_init 初始化
     */
    private fun handleOcrTest(session: IHTTPSession): Response {
        // 检查截图权限
        if (screenshotHelper?.isInitialized() != true) {
            return json(Response.Status.SERVICE_UNAVAILABLE, mapOf(
                "status" to "error",
                "error" to "截图权限未初始化",
                "hint" to "请在 Bridge APP 中点击 OCR 测试按钮授权截图权限"
            ))
        }

        // 同步执行截图和 OCR
        var resultBitmap: Bitmap? = null
        var ocrResult: OcrService.OcrResult? = null
        var error: String? = null

        val latch = java.util.concurrent.CountDownLatch(1)

        CoroutineScope(Dispatchers.Main).launch {
            try {
                // 截图
                val screenshotResult = withContext(Dispatchers.IO) {
                    screenshotHelper?.capture()
                }

                if (screenshotResult == null || !screenshotResult.success) {
                    error = screenshotResult?.error ?: "截图失败"
                    latch.countDown()
                    return@launch
                }

                resultBitmap = screenshotResult.bitmap

                // OCR 识别
                if (resultBitmap != null) {
                    ocrResult = withContext(Dispatchers.IO) {
                        OcrService.recognize(resultBitmap!!)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "OCR test failed", e)
                error = e.message ?: "OCR 测试异常"
            } finally {
                latch.countDown()
            }
        }

        // 等待完成（最多 10 秒）
        if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
            return json(Response.Status.INTERNAL_ERROR, mapOf(
                "status" to "error",
                "error" to "OCR 测试超时"
            ))
        }

        if (error != null) {
            return json(Response.Status.INTERNAL_ERROR, mapOf(
                "status" to "error",
                "error" to error
            ))
        }

        if (ocrResult == null) {
            return json(Response.Status.INTERNAL_ERROR, mapOf(
                "status" to "error",
                "error" to "OCR 结果为空"
            ))
        }

        // 构建返回结果
        val textBlocks = ocrResult!!.textBlocks.map { block ->
            mapOf(
                "text" to block.text,
                "bounds" to mapOf(
                    "left" to block.bounds.left,
                    "top" to block.bounds.top,
                    "right" to block.bounds.right,
                    "bottom" to block.bounds.bottom
                ),
                "lines" to block.lines.map { line ->
                    mapOf(
                        "text" to line.text,
                        "bounds" to mapOf(
                            "left" to line.bounds.left,
                            "top" to line.bounds.top,
                            "right" to line.bounds.right,
                            "bottom" to line.bounds.bottom
                        )
                    )
                }
            )
        }

        return json(Response.Status.OK, mapOf(
            "status" to "ok",
            "success" to ocrResult!!.success,
            "processing_time_ms" to ocrResult!!.processingTimeMs,
            "block_count" to ocrResult!!.textBlocks.size,
            "full_text" to ocrResult!!.fullText,
            "text_blocks" to textBlocks
        ))
    }

    /**
     * 格式化运行时间
     */
    private fun formatUptime(): String {
        val uptimeMs = android.os.SystemClock.elapsedRealtime()
        val hours = uptimeMs / 3600000
        val minutes = (uptimeMs % 3600000) / 60000
        return "${hours}h${minutes}m"
    }
}
