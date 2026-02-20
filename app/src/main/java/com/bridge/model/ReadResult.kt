package com.bridge.model

/**
 * 错误类型枚举
 */
enum class ReadErrorType {
    UNKNOWN,
    NAVIGATION_FAILED,
    SCREENSHOT_FAILED,
    OCR_FAILED,
    TIMEOUT,
    PERMISSION_DENIED,
    NO_CONTACTS_FOUND
}

/**
 * 数据读取结果
 */
data class ReadResult(
    val success: Boolean,
    val contacts: List<ContactData>? = null,
    val messages: List<MessageData>? = null,
    val source: String = "fresh",
    val hasMore: Boolean = false,
    val totalCount: Int = 0,
    val partial: Boolean = false,  // 是否为部分结果
    val error: String? = null,
    val errorType: ReadErrorType? = null,
    val scrollCount: Int = 0,      // 滚动次数
    val processingTimeMs: Long = 0  // 处理时间(毫秒)
) {
    companion object {
        fun successContacts(
            contacts: List<ContactData>, 
            source: String = "fresh",
            partial: Boolean = false,
            error: String? = null,
            scrollCount: Int = 0,
            processingTimeMs: Long = 0
        ): ReadResult {
            return ReadResult(
                success = true,
                contacts = contacts,
                source = source,
                totalCount = contacts.size,
                partial = partial,
                error = error,
                scrollCount = scrollCount,
                processingTimeMs = processingTimeMs
            )
        }

        fun successMessages(messages: List<MessageData>, hasMore: Boolean = false): ReadResult {
            return ReadResult(
                success = true,
                messages = messages,
                hasMore = hasMore,
                totalCount = messages.size
            )
        }

        fun error(
            error: String, 
            errorType: ReadErrorType = ReadErrorType.UNKNOWN,
            partial: Boolean = false,
            contacts: List<ContactData>? = null
        ): ReadResult {
            return ReadResult(
                success = false, 
                error = error, 
                errorType = errorType,
                partial = partial,
                contacts = contacts,
                totalCount = contacts?.size ?: 0
            )
        }
    }
}

/**
 * 联系人数据
 */
data class ContactData(
    val name: String,
    val displayName: String? = null,
    val lastMessage: String? = null,
    val lastTime: Long? = null,
    val unreadCount: Int = 0
)

/**
 * 消息数据
 */
data class MessageData(
    val id: Long,
    val sender: String,
    val content: String,
    val type: String = "text",
    val time: Long,
    val isSelf: Boolean
)
