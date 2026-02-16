package com.bridge.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 配置管理器
 * 用于保存和读取运行时配置
 */
object ConfigManager {

    private const val PREFS_NAME = "bridge_config"

    // IME 剪贴板坐标配置
    private const val KEY_IME_CLIPBOARD_X = "ime_clipboard_x"
    private const val KEY_IME_CLIPBOARD_Y = "ime_clipboard_y"

    // 默认值
    private const val DEFAULT_IME_X = 0.50f
    private const val DEFAULT_IME_Y = 0.65f

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * 获取 IME 剪贴板 X 坐标比例
     */
    fun getImeClipboardX(context: Context): Float {
        return getPrefs(context).getFloat(KEY_IME_CLIPBOARD_X, DEFAULT_IME_X)
    }

    /**
     * 获取 IME 剪贴板 Y 坐标比例
     */
    fun getImeClipboardY(context: Context): Float {
        return getPrefs(context).getFloat(KEY_IME_CLIPBOARD_Y, DEFAULT_IME_Y)
    }

    /**
     * 设置 IME 剪贴板坐标
     */
    fun setImeClipboardCoords(context: Context, x: Float, y: Float) {
        getPrefs(context).edit()
            .putFloat(KEY_IME_CLIPBOARD_X, x)
            .putFloat(KEY_IME_CLIPBOARD_Y, y)
            .apply()
    }

    /**
     * 重置为默认值
     */
    fun resetToDefaults(context: Context) {
        getPrefs(context).edit()
            .putFloat(KEY_IME_CLIPBOARD_X, DEFAULT_IME_X)
            .putFloat(KEY_IME_CLIPBOARD_Y, DEFAULT_IME_Y)
            .apply()
    }
}
