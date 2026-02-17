package com.bridge.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 工具数据类
 */
data class Tool(
    val id: String,
    val name: String,
    val description: String,
    val x: Float,           // 相对坐标 0.0-1.0
    val y: Float,           // 相对坐标 0.0-1.0
    val preToolIds: List<String>,  // 前置工具ID列表，按顺序执行
    val isBuiltIn: Boolean = false  // 是否内置工具（不可删除）
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("description", description)
            put("x", x)
            put("y", y)
            put("preToolIds", JSONArray(preToolIds))
            put("isBuiltIn", isBuiltIn)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): Tool {
            val preToolIds = mutableListOf<String>()
            val preArray = json.optJSONArray("preToolIds") ?: JSONArray()
            for (i in 0 until preArray.length()) {
                preToolIds.add(preArray.getString(i))
            }
            return Tool(
                id = json.getString("id"),
                name = json.getString("name"),
                description = json.optString("description", ""),
                x = json.getDouble("x").toFloat(),
                y = json.getDouble("y").toFloat(),
                preToolIds = preToolIds,
                isBuiltIn = json.optBoolean("isBuiltIn", false)
            )
        }
    }
}

/**
 * 工具管理器
 */
object ToolManager {
    private const val PREF_NAME = "bridge_tools"
    private const val KEY_TOOLS = "tools"
    private const val KEY_VERSION = "tools_version"
    private const val CURRENT_VERSION = 3  // 更新版本号以强制重置工具配置

    // 内置工具ID
    const val TOOL_OPEN_WECHAT = "builtin_open_wechat"
    const val TOOL_SET_CLIPBOARD = "builtin_set_clipboard"
    const val TOOL_GO_BACK = "builtin_go_back"

    // 获取默认内置工具
    private fun getBuiltInTools(): List<Tool> {
        return listOf(
            Tool(
                id = TOOL_OPEN_WECHAT,
                name = "打开微信",
                description = "启动微信应用",
                x = 0f,
                y = 0f,
                preToolIds = emptyList(),
                isBuiltIn = true
            ),
            Tool(
                id = TOOL_SET_CLIPBOARD,
                name = "设置剪贴板",
                description = "将内容复制到剪贴板（执行时设置）",
                x = 0f,
                y = 0f,
                preToolIds = emptyList(),
                isBuiltIn = true
            ),
            Tool(
                id = TOOL_GO_BACK,
                name = "返回",
                description = "模拟返回键，可隐藏键盘",
                x = 0f,
                y = 0f,
                preToolIds = emptyList(),
                isBuiltIn = true
            )
        )
    }

    /**
     * 获取所有工具（内置 + 用户自定义）
     */
    fun getAllTools(context: Context): List<Tool> {
        val builtInTools = getBuiltInTools()
        val userTools = getUserTools(context)
        return builtInTools + userTools
    }

    /**
     * 获取用户自定义工具
     */
    private fun getUserTools(context: Context): List<Tool> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val toolsJson = prefs.getString(KEY_TOOLS, "[]") ?: "[]"
        val tools = mutableListOf<Tool>()
        try {
            val array = JSONArray(toolsJson)
            for (i in 0 until array.length()) {
                tools.add(Tool.fromJson(array.getJSONObject(i)))
            }
        } catch (e: Exception) {
            android.util.Log.e("ToolManager", "Failed to load tools", e)
        }
        return tools
    }

    /**
     * 保存用户工具列表
     */
    private fun saveUserTools(context: Context, tools: List<Tool>) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        tools.filter { !it.isBuiltIn }.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_TOOLS, array.toString()).apply()
    }

    /**
     * 添加或更新工具
     */
    fun saveTool(context: Context, tool: Tool) {
        val tools = getUserTools(context).toMutableList()
        val existingIndex = tools.indexOfFirst { it.id == tool.id }
        if (existingIndex >= 0) {
            tools[existingIndex] = tool
        } else {
            tools.add(tool)
        }
        saveUserTools(context, tools)
    }

    /**
     * 删除工具
     */
    fun deleteTool(context: Context, toolId: String) {
        val tools = getUserTools(context).filter { it.id != toolId }
        saveUserTools(context, tools)
    }

    /**
     * 获取单个工具
     */
    fun getTool(context: Context, toolId: String): Tool? {
        return getAllTools(context).find { it.id == toolId }
    }

    /**
     * 获取工具执行链（递归获取所有前置工具）
     */
    fun getExecutionChain(context: Context, toolId: String): List<Tool> {
        val allTools = getAllTools(context)
        val result = mutableListOf<Tool>()
        val visited = mutableSetOf<String>()

        // 打印所有工具配置用于调试
        android.util.Log.d("ToolManager", "=== 所有工具配置 ===")
        for (tool in allTools) {
            android.util.Log.d("ToolManager", "工具: ${tool.name}, id=${tool.id}, preToolIds=${tool.preToolIds}, x=${tool.x}, y=${tool.y}")
        }

        fun collectChain(id: String) {
            if (id in visited) return
            visited.add(id)

            val tool = allTools.find { it.id == id } ?: return

            // 先递归收集前置工具
            for (preId in tool.preToolIds) {
                collectChain(preId)
            }

            // 再添加当前工具
            result.add(tool)
        }

        collectChain(toolId)

        android.util.Log.d("ToolManager", "=== 执行链 for $toolId ===")
        android.util.Log.d("ToolManager", "执行链: ${result.map { it.name }}")

        return result
    }

    /**
     * 创建新工具ID
     */
    fun generateId(): String {
        return "tool_${UUID.randomUUID().toString().substring(0, 8)}"
    }

    /**
     * 初始化默认用户工具（如果是首次使用或版本更新）
     */
    fun initDefaultTools(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val savedVersion = prefs.getInt(KEY_VERSION, 0)

        android.util.Log.d("ToolManager", "initDefaultTools: savedVersion=$savedVersion, CURRENT_VERSION=$CURRENT_VERSION")

        // 版本更新时强制重置工具配置
        if (!prefs.contains(KEY_TOOLS) || savedVersion < CURRENT_VERSION) {
            android.util.Log.d("ToolManager", "重置工具配置...")

            // 首次使用，创建默认工具
            // 注意：剪贴板内容由调用方在执行前设置，工具链中不包含 SET_CLIPBOARD
            val defaultTools = listOf(
                Tool(
                    id = "tool_search_btn",
                    name = "搜索按钮",
                    description = "微信首页右上角放大镜",
                    x = 0.845f,
                    y = 0.075f,
                    preToolIds = listOf(TOOL_OPEN_WECHAT)
                ),
                Tool(
                    id = "tool_ime_clipboard",
                    name = "输入法剪贴板",
                    description = "键盘上的剪贴板内容位置",
                    x = 0.50f,
                    y = 0.65f,
                    preToolIds = listOf(TOOL_OPEN_WECHAT, "tool_search_btn")
                ),
                Tool(
                    id = "tool_contact",
                    name = "联系人",
                    description = "搜索结果中的联系人",
                    x = 0.50f,
                    y = 0.213f,
                    preToolIds = listOf(TOOL_OPEN_WECHAT, "tool_search_btn", "tool_ime_clipboard")
                ),
                Tool(
                    id = "tool_msg_input",
                    name = "消息输入框",
                    description = "聊天界面底部输入框",
                    x = 0.35f,
                    y = 0.955f,
                    preToolIds = listOf(TOOL_OPEN_WECHAT, "tool_search_btn", "tool_ime_clipboard", "tool_contact")
                ),
                Tool(
                    id = "tool_send_btn",
                    name = "发送按钮",
                    description = "聊天界面发送按钮",
                    x = 0.92f,
                    y = 0.955f,
                    preToolIds = listOf(TOOL_OPEN_WECHAT, "tool_search_btn", "tool_ime_clipboard", "tool_contact", "tool_msg_input", "tool_ime_clipboard")
                )
            )
            saveUserTools(context, defaultTools)
            // 保存版本号
            prefs.edit().putInt(KEY_VERSION, CURRENT_VERSION).apply()
        }
    }
}
