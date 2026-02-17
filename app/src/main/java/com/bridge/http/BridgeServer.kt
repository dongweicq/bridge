package com.bridge.http

import android.util.Log
import com.bridge.BridgeAccessibilityService
import com.bridge.BridgeService
import com.bridge.action.ActionDispatcher
import com.bridge.action.WeChatActionEngine
import com.bridge.cache.WeChatDataCache
import com.bridge.model.Task
import com.bridge.model.TaskResult
import com.bridge.model.TaskStatus
import com.google.gson.Gson
import com.google.gson.JsonObject
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
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
     * 参数: refresh=true 强制刷新缓存
     */
    private fun handleChatList(session: IHTTPSession): Response {
        val params = session.parms
        val refresh = params["refresh"]?.toBoolean() ?: false

        val service = BridgeAccessibilityService.instance
        if (service == null) {
            return json(Response.Status.SERVICE_UNAVAILABLE, mapOf(
                "status" to "error",
                "error" to "Accessibility service not enabled"
            ))
        }

        // 先检查缓存（除非强制刷新）
        if (!refresh) {
            val cached = WeChatDataCache.getContacts()
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

        // 使用同步方式执行读取（在 ActionDispatcher 线程）
        return executeReadWithCache(
            service = service,
            cacheSetter = { WeChatDataCache.setContacts(it) },
            readOperation = { actionEngine, svc ->
                // 导航到首页
                val navResult = actionEngine.navigateToWeChatHome(svc)
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
            val cached = WeChatDataCache.getMessages(target)
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
            cacheSetter = { WeChatDataCache.setMessages(target, it) },
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
        readOperation: suspend (WeChatActionEngine, BridgeAccessibilityService) -> com.bridge.model.ReadResult
    ): Response {
        // 使用同步方式执行读取（在 ActionDispatcher 线程）
        var result: com.bridge.model.ReadResult? = null
        val latch = java.util.concurrent.CountDownLatch(1)

        CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                result = withContext(ActionDispatcher.dispatcher) {
                    val actionEngine = WeChatActionEngine()
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
        readOperation: suspend (WeChatActionEngine, BridgeAccessibilityService) -> com.bridge.model.ReadResult
    ): Response {
        // 使用同步方式执行读取（在 ActionDispatcher 线程）
        var result: com.bridge.model.ReadResult? = null
        val latch = java.util.concurrent.CountDownLatch(1)

        CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                result = withContext(ActionDispatcher.dispatcher) {
                    val actionEngine = WeChatActionEngine()
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
