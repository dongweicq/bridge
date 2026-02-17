package com.bridge.model

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
    val error: String? = null
) {
    companion object {
        fun successContacts(contacts: List<ContactData>, source: String = "fresh"): ReadResult {
            return ReadResult(
                success = true,
                contacts = contacts,
                source = source,
                totalCount = contacts.size
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

        fun error(error: String): ReadResult {
            return ReadResult(success = false, error = error)
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
