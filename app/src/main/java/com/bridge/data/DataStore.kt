package com.bridge.data

import com.bridge.model.ContactData
import com.bridge.model.MessageData

/**
 * 简单内存数据存储
 * 用于缓存联系人和消息数据
 */
object DataStore {

    // 联系人缓存
    private val contacts = mutableListOf<ContactData>()

    // 消息缓存 (联系人名称 -> 消息列表)
    private val messages = mutableMapOf<String, MutableList<MessageData>>()

    // 最后获取时间
    var lastContactFetchTime: Long = 0

    /**
     * 获取联系人列表
     */
    fun getContacts(limit: Int = 50): List<ContactData> {
        return contacts.take(limit)
    }

    /**
     * 替换所有联系人
     */
    fun replaceContacts(newContacts: List<ContactData>) {
        contacts.clear()
        contacts.addAll(newContacts)
        lastContactFetchTime = System.currentTimeMillis()
    }

    /**
     * 获取联系人的消息
     */
    fun getMessages(contactName: String, limit: Int = 20): List<MessageData> {
        return messages[contactName]?.take(limit) ?: emptyList()
    }

    /**
     * 替换联系人的消息
     */
    fun replaceMessages(contactName: String, newMessages: List<MessageData>) {
        messages[contactName] = newMessages.toMutableList()
    }

    /**
     * 清空所有数据
     */
    fun clear() {
        contacts.clear()
        messages.clear()
        lastContactFetchTime = 0
    }

    /**
     * 检查是否有缓存数据
     */
    fun hasContactCache(): Boolean {
        return contacts.isNotEmpty()
    }
}
