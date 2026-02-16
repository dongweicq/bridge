package com.bridge.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置管理器
 * 用于保存和读取运行时配置
 */
object ConfigManager {

    private const val PREFS_NAME = "bridge_config"

    // 5个步骤的坐标配置键
    private const val KEY_SEARCH_BTN_X = "search_btn_x"
    private const val KEY_SEARCH_BTN_Y = "search_btn_y"
    private const val KEY_IME_CLIPBOARD_X = "ime_clipboard_x"
    private const val KEY_IME_CLIPBOARD_Y = "ime_clipboard_y"
    private const val KEY_CONTACT_X = "contact_x"
    private const val KEY_CONTACT_Y = "contact_y"
    private const val KEY_MSG_INPUT_X = "msg_input_x"
    private const val KEY_MSG_INPUT_Y = "msg_input_y"
    private const val KEY_SEND_BTN_X = "send_btn_x"
    private const val KEY_SEND_BTN_Y = "send_btn_y"

    // 默认值
    private val DEFAULTS = mapOf(
        KEY_SEARCH_BTN_X to 0.845f,
        KEY_SEARCH_BTN_Y to 0.075f,
        KEY_IME_CLIPBOARD_X to 0.50f,
        KEY_IME_CLIPBOARD_Y to 0.65f,
        KEY_CONTACT_X to 0.50f,
        KEY_CONTACT_Y to 0.213f,
        KEY_MSG_INPUT_X to 0.35f,
        KEY_MSG_INPUT_Y to 0.955f,
        KEY_SEND_BTN_X to 0.92f,
        KEY_SEND_BTN_Y to 0.955f
    )

    // 步骤配置信息
    data class CoordinateConfig(
        val name: String,
        val description: String,
        val keyX: String,
        val keyY: String
    )

    val COORDINATE_CONFIGS = listOf(
        CoordinateConfig("搜索按钮", "微信首页右上角放大镜", KEY_SEARCH_BTN_X, KEY_SEARCH_BTN_Y),
        CoordinateConfig("输入法剪贴板", "键盘上的剪贴板内容位置", KEY_IME_CLIPBOARD_X, KEY_IME_CLIPBOARD_Y),
        CoordinateConfig("联系人", "搜索结果中的联系人", KEY_CONTACT_X, KEY_CONTACT_Y),
        CoordinateConfig("消息输入框", "聊天界面底部输入框", KEY_MSG_INPUT_X, KEY_MSG_INPUT_Y),
        CoordinateConfig("发送按钮", "聊天界面发送按钮", KEY_SEND_BTN_X, KEY_SEND_BTN_Y)
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // 通用获取方法
    private fun getX(context: Context, key: String): Float {
        return getPrefs(context).getFloat(key, DEFAULTS[key] as Float)
    }

    private fun getY(context: Context, key: String): Float {
        return getPrefs(context).getFloat(key, DEFAULTS[key] as Float)
    }

    // 通用设置方法
    private fun setCoords(context: Context, keyX: String, keyY: String, x: Float, y: Float) {
        getPrefs(context).edit()
            .putFloat(keyX, x)
            .putFloat(keyY, y)
            .apply()
    }

    // 步骤1：搜索按钮
    fun getSearchBtnX(context: Context) = getX(context, KEY_SEARCH_BTN_X)
    fun getSearchBtnY(context: Context) = getY(context, KEY_SEARCH_BTN_Y)
    fun setSearchBtnCoords(context: Context, x: Float, y: Float) =
        setCoords(context, KEY_SEARCH_BTN_X, KEY_SEARCH_BTN_Y, x, y)

    // 步骤2：输入法剪贴板
    fun getImeClipboardX(context: Context) = getX(context, KEY_IME_CLIPBOARD_X)
    fun getImeClipboardY(context: Context) = getY(context, KEY_IME_CLIPBOARD_Y)
    fun setImeClipboardCoords(context: Context, x: Float, y: Float) =
        setCoords(context, KEY_IME_CLIPBOARD_X, KEY_IME_CLIPBOARD_Y, x, y)

    // 步骤3：联系人
    fun getContactX(context: Context) = getX(context, KEY_CONTACT_X)
    fun getContactY(context: Context) = getY(context, KEY_CONTACT_Y)
    fun setContactCoords(context: Context, x: Float, y: Float) =
        setCoords(context, KEY_CONTACT_X, KEY_CONTACT_Y, x, y)

    // 步骤4：消息输入框
    fun getMsgInputX(context: Context) = getX(context, KEY_MSG_INPUT_X)
    fun getMsgInputY(context: Context) = getY(context, KEY_MSG_INPUT_Y)
    fun setMsgInputCoords(context: Context, x: Float, y: Float) =
        setCoords(context, KEY_MSG_INPUT_X, KEY_MSG_INPUT_Y, x, y)

    // 步骤5：发送按钮
    fun getSendBtnX(context: Context) = getX(context, KEY_SEND_BTN_X)
    fun getSendBtnY(context: Context) = getY(context, KEY_SEND_BTN_Y)
    fun setSendBtnCoords(context: Context, x: Float, y: Float) =
        setCoords(context, KEY_SEND_BTN_X, KEY_SEND_BTN_Y, x, y)

    /**
     * 重置为默认值
     */
    fun resetToDefaults(context: Context) {
        val editor = getPrefs(context).edit()
        DEFAULTS.forEach { (key, value) ->
            editor.putFloat(key, value as Float)
        }
        editor.apply()
    }
}
