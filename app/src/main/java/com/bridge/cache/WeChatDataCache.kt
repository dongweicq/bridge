package com.bridge.cache

import android.util.Log
import com.bridge.model.ContactData
import com.bridge.model.MessageData

/**
 * 某信数据内存缓存
 * 简单实现：带时间戳的内存缓存，支持TTL过期
 */
object MoxinDataCache {

    private const val TAG = "MoxinDataCache"

    // 缓存TTL配置（毫秒）
    private const val CONTACTS_TTL_MS = 5 * 60 * 1000L    // 联系人列表: 5分钟
    private const val MESSAGES_TTL_MS = 2 * 60 * 1000L    // 聊天记录: 2分钟

    // 联系人列表缓存
    private var contactsCache: List<ContactData>? = null
    private var contactsCacheTime: Long = 0

    // 聊天记录缓存 (key: contact name)
    private val messagesCache = mutableMapOf<String, List<MessageData>>()
    private val messagesCacheTime = mutableMapOf<String, Long>()

    /**
     * 获取缓存的联系人列表
     * @return 缓存数据，如果不存在或已过期返回null
     */
    @Synchronized
    fun getContacts(): List<ContactData>? {
        if (contactsCache == null) {
            return null
        }
        if (System.currentTimeMillis() - contactsCacheTime > CONTACTS_TTL_MS) {
            Log.d(TAG, "联系人缓存已过期")
            contactsCache = null
            return null
        }
        Log.d(TAG, "命中联系人缓存: ${contactsCache?.size} 个")
        return contactsCache
    }

    /**
     * 设置联系人列表缓存
     */
    @Synchronized
    fun setContacts(contacts: List<ContactData>) {
        contactsCache = contacts
        contactsCacheTime = System.currentTimeMillis()
        Log.d(TAG, "缓存联系人列表: ${contacts.size} 个")
    }

    /**
     * 获取缓存的聊天记录
     * @param contactName 联系人名称
     * @return 缓存数据，如果不存在或已过期返回null
     */
    @Synchronized
    fun getMessages(contactName: String): List<MessageData>? {
        val time = messagesCacheTime[contactName]
        if (time == null || messagesCache[contactName] == null) {
            return null
        }
        if (System.currentTimeMillis() - time > MESSAGES_TTL_MS) {
            Log.d(TAG, "聊天记录缓存已过期: $contactName")
            messagesCache.remove(contactName)
            messagesCacheTime.remove(contactName)
            return null
        }
        val messages = messagesCache[contactName]
        Log.d(TAG, "命中聊天记录缓存: $contactName, ${messages?.size} 条")
        return messages
    }

    /**
     * 设置聊天记录缓存
     */
    @Synchronized
    fun setMessages(contactName: String, messages: List<MessageData>) {
        messagesCache[contactName] = messages
        messagesCacheTime[contactName] = System.currentTimeMillis()
        Log.d(TAG, "缓存聊天记录: $contactName, ${messages.size} 条")
    }

    /**
     * 清除所有缓存
     */
    @Synchronized
    fun clear() {
        contactsCache = null
        contactsCacheTime = 0
        messagesCache.clear()
        messagesCacheTime.clear()
        Log.d(TAG, "已清除所有缓存")
    }

    /**
     * 清除指定联系人的聊天记录缓存
     */
    @Synchronized
    fun clearMessages(contactName: String) {
        messagesCache.remove(contactName)
        messagesCacheTime.remove(contactName)
        Log.d(TAG, "已清除聊天记录缓存: $contactName")
    }
}
