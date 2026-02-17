package com.bridge.action

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.bridge.BridgeAccessibilityService
import com.bridge.data.DataStore
import com.bridge.model.ContactData
import com.bridge.model.MessageData
import com.bridge.model.ReadResult
import kotlinx.coroutines.delay

/**
 * 微信数据读取引擎
 * 负责通过 AccessibilityService 采集联系人和消息数据
 *
 * 必须在 ActionDispatcher.dispatcher 线程中执行
 */
class WeChatDataReader {

    companion object {
        private const val TAG = "WeChatDataReader"

        // 延迟配置
        private const val DELAY_AFTER_OPEN_APP = 3000L
        private const val DELAY_AFTER_SCROLL = 1000L
    }

    /**
     * 读取联系人列表
     * @param service 无障碍服务实例
     * @param limit 最大获取数量
     * @param forceRefresh 是否强制刷新
     * @return 读取结果
     */
    suspend fun readContacts(
        service: BridgeAccessibilityService,
        limit: Int = 50,
        forceRefresh: Boolean = false
    ): ReadResult {
        return try {
            Log.d(TAG, "开始读取联系人列表, limit=$limit, forceRefresh=$forceRefresh")

            // 如果不强制刷新，先尝试从缓存读取
            if (!forceRefresh && DataStore.hasContactCache()) {
                val cached = DataStore.getContacts(limit)
                if (cached.isNotEmpty()) {
                    Log.d(TAG, "返回缓存数据: ${cached.size} 个联系人")
                    return ReadResult.successContacts(cached, "cache")
                }
            }

            // 打开微信
            if (!service.openWeChat()) {
                return ReadResult.error("无法打开微信")
            }
            delay(DELAY_AFTER_OPEN_APP)

            // 读取联系人
            val contacts = mutableListOf<ContactData>()

            // 尝试从当前屏幕读取联系人
            val screenContacts = extractContactsFromScreen(service)
            contacts.addAll(screenContacts)

            // 如果需要更多联系人，滚动加载
            var scrollCount = 0
            val maxScrolls = 10
            while (contacts.size < limit && scrollCount < maxScrolls) {
                val screenBounds = service.getScreenBounds()
                val scrolled = service.swipe(
                    screenBounds.width() / 2,
                    (screenBounds.height() * 0.7).toInt(),
                    screenBounds.width() / 2,
                    (screenBounds.height() * 0.3).toInt(),
                    300
                )

                if (!scrolled) break

                delay(DELAY_AFTER_SCROLL)
                scrollCount++

                val newContacts = extractContactsFromScreen(service)
                // 去重添加
                val existingNames = contacts.map { it.name }.toSet()
                contacts.addAll(newContacts.filter { it.name !in existingNames })

                if (contacts.size >= limit) break
            }

            // 限制数量
            val finalContacts = contacts.take(limit)

            // 保存到缓存
            if (finalContacts.isNotEmpty()) {
                DataStore.replaceContacts(finalContacts)
                Log.d(TAG, "已保存 ${finalContacts.size} 个联系人到缓存")
            }

            ReadResult.successContacts(finalContacts, "fresh")

        } catch (e: Exception) {
            Log.e(TAG, "读取联系人失败", e)
            ReadResult.error("读取失败: ${e.message}")
        }
    }

    /**
     * 读取会话历史
     * @param service 无障碍服务实例
     * @param contactName 联系人名称
     * @param limit 消息数量
     * @return 读取结果
     */
    suspend fun readHistory(
        service: BridgeAccessibilityService,
        contactName: String,
        limit: Int = 20
    ): ReadResult {
        return try {
            Log.d(TAG, "开始读取与 $contactName 的聊天记录, limit=$limit")

            // 先尝试返回缓存数据
            val cached = DataStore.getMessages(contactName, limit)
            if (cached.isNotEmpty()) {
                Log.d(TAG, "返回缓存数据: ${cached.size} 条消息")
                return ReadResult.successMessages(cached)
            }

            // TODO: 实现完整的会话历史读取
            // 当前返回空列表作为占位实现

            val messages = emptyList<MessageData>()

            ReadResult.successMessages(messages)

        } catch (e: Exception) {
            Log.e(TAG, "读取会话历史失败", e)
            ReadResult.error("读取失败: ${e.message}")
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 从当前屏幕提取联系人列表
     */
    private fun extractContactsFromScreen(
        service: BridgeAccessibilityService
    ): List<ContactData> {
        val contacts = mutableListOf<ContactData>()
        val root = service.getRootNode() ?: return contacts

        try {
            // 遍历节点树，查找聊天列表项
            findContactNodes(root, contacts)
        } catch (e: Exception) {
            Log.e(TAG, "提取联系人失败", e)
        } finally {
            root.recycle()
        }

        return contacts
    }

    /**
     * 递归查找联系人节点
     */
    private fun findContactNodes(
        node: AccessibilityNodeInfo,
        contacts: MutableList<ContactData>
    ) {
        // 尝试从节点文本提取联系人
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank() && text.length in 1..20) {
            // 检查是否是时间格式（如 "12:30", "昨天"）- 跳过
            if (!isTimeText(text)) {
                // 创建联系人数据
                val contact = ContactData(
                    name = text
                )

                // 避免重复
                if (contacts.none { it.name == text }) {
                    contacts.add(contact)
                }
            }
        }

        // 递归遍历子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findContactNodes(child, contacts)
            child.recycle()
        }
    }

    /**
     * 判断文本是否是时间格式
     */
    private fun isTimeText(text: String): Boolean {
        return text.matches(Regex("\\d{1,2}:\\d{2}")) ||
                text in listOf("昨天", "前天", "刚刚", "今天") ||
                text.matches(Regex("星期[一二三四五六日]"))
    }
}
