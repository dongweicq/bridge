package com.bridge.model

/**
 * 任务类型
 */
enum class TaskType {
    SEND_MESSAGE,
    READ_HISTORY,
    GET_CONTACTS,    // 获取联系人列表
    GET_HISTORY      // 获取会话历史
}

/**
 * 任务状态
 */
enum class TaskStatus {
    QUEUED,
    RUNNING,
    DONE,
    FAILED
}

/**
 * 任务定义
 */
data class Task(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: TaskType = TaskType.SEND_MESSAGE,
    val target: String = "",      // 目标联系人
    val message: String = "",     // 消息内容
    val limit: Int = 20,          // 消息数量限制
    val refresh: Boolean = false, // 是否强制刷新
    var status: TaskStatus = TaskStatus.QUEUED,
    var error: String? = null,
    var result: ReadResult? = null,  // 读取结果
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 任务执行结果
 */
data class TaskResult(
    val success: Boolean,
    val message: String = "",
    val error: String? = null,
    val candidates: List<String>? = null
) {
    companion object {
        fun ok(message: String = "成功"): TaskResult {
            return TaskResult(success = true, message = message)
        }

        fun fail(error: String): TaskResult {
            return TaskResult(success = false, error = error)
        }

        fun candidates(list: List<String>): TaskResult {
            return TaskResult(
                success = false,
                error = "找到多个匹配的联系人",
                candidates = list
            )
        }
    }
}
