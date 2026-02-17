package com.bridge.model

/**
 * 任务类型
 */
enum class TaskType {
    SEND_MESSAGE,
    READ_HISTORY
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
    val target: String,      // 目标联系人
    val message: String,     // 消息内容
    var status: TaskStatus = TaskStatus.QUEUED,
    var error: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 任务执行结果
 */
data class TaskResult(
    val success: Boolean,
    val message: String = "",
    val error: String? = null,
    val candidates: List<String>? = null  // 多个匹配时的候选列表
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
