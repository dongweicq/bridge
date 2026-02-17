package com.bridge

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bridge.util.CoordinatePicker
import com.bridge.util.Tool
import com.bridge.util.ToolManager
import kotlin.math.abs
import kotlin.random.Random

class ToolManagerActivity : AppCompatActivity() {

    private lateinit var toolsRecyclerView: RecyclerView
    private lateinit var toolAdapter: ToolAdapter
    private var tools: MutableList<Tool> = mutableListOf()
    private var coordinatePicker: CoordinatePicker? = null
    private var currentPickingToolId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tool_manager)

        supportActionBar?.title = "工具管理"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // 初始化默认工具
        ToolManager.initDefaultTools(this)

        toolsRecyclerView = findViewById(R.id.toolsRecyclerView)
        toolsRecyclerView.layoutManager = LinearLayoutManager(this)

        toolAdapter = ToolAdapter(
            tools = tools,
            onGetCoordClick = { tool -> pickCoordinate(tool) },
            onTestClick = { tool -> testTool(tool) },
            onEditClick = { tool -> showEditDialog(tool) },
            onDeleteClick = { tool -> deleteTool(tool) }
        )
        toolsRecyclerView.adapter = toolAdapter

        findViewById<Button>(R.id.addToolBtn).setOnClickListener {
            showEditDialog(null)
        }

        findViewById<Button>(R.id.exportBtn).setOnClickListener {
            exportConfig()
        }

        findViewById<Button>(R.id.importBtn).setOnClickListener {
            importConfig()
        }

        loadTools()
    }

    override fun onResume() {
        super.onResume()
        loadTools()
    }

    private fun loadTools() {
        tools.clear()
        tools.addAll(ToolManager.getAllTools(this))
        toolAdapter.notifyDataSetChanged()
    }

    private fun pickCoordinate(tool: Tool) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
            startActivity(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            ))
            return
        }

        val service = BridgeAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "请先启用无障碍服务", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "正在执行前置步骤...", Toast.LENGTH_SHORT).show()

        // 获取默认联系人设置剪贴板（直接使用中文）
        val defaultContact = getDefaultContact()
        android.util.Log.d("ToolManager", "设置剪贴板: $defaultContact")
        runOnUiThread {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(null, defaultContact))
        }

        // 在后台线程执行前置工具链
        Thread {
            try {
                // 获取完整执行链（不包含当前工具本身）
                val executionChain = ToolManager.getExecutionChain(this, tool.id)
                // 排除当前工具
                val preTools = executionChain.filter { it.id != tool.id }

                android.util.Log.d("ToolManager", "前置工具链: ${preTools.map { it.name }}")

                for (t in preTools) {
                    android.util.Log.d("ToolManager", "执行前置工具: ${t.name}")
                    executeTool(t, service)
                }

                // 等待目标应用完全显示
                randomDelay(1000, 2000)

                runOnUiThread {
                    Toast.makeText(this, "请在屏幕上点击选择位置", Toast.LENGTH_SHORT).show()
                    showCoordinatePicker(tool)
                }
            } catch (e: Exception) {
                android.util.Log.e("ToolManager", "执行前置工具失败", e)
                runOnUiThread {
                    Toast.makeText(this, "前置步骤失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // 获取默认联系人
    private fun getDefaultContact(): String {
        val prefs = getSharedPreferences("bridge_settings", MODE_PRIVATE)
        return prefs.getString("default_contact", "1810835") ?: "1810835"
    }

    // 获取默认消息
    private fun getDefaultMessage(): String {
        val prefs = getSharedPreferences("bridge_settings", MODE_PRIVATE)
        return prefs.getString("default_message", "test") ?: "test"
    }

    private fun showCoordinatePicker(tool: Tool) {
        currentPickingToolId = tool.id
        coordinatePicker?.dismiss()
        coordinatePicker = CoordinatePicker(
            context = this,
            onCoordinatePicked = { x, y ->
                runOnUiThread {
                    // 更新工具坐标
                    val updatedTool = tool.copy(x = x, y = y)
                    if (!tool.isBuiltIn) {
                        ToolManager.saveTool(this, updatedTool)
                    }
                    loadTools()
                    Toast.makeText(this, "坐标已更新: (${String.format("%.3f", x)}, ${String.format("%.3f", y)})", Toast.LENGTH_SHORT).show()
                }
            },
            onCancelled = {
                runOnUiThread {
                    Toast.makeText(this, "已取消", Toast.LENGTH_SHORT).show()
                }
            },
            initialX = if (tool.x > 0) tool.x else null,
            initialY = if (tool.y > 0) tool.y else null
        )
        coordinatePicker?.show()
    }

    private fun executeTool(tool: Tool, service: BridgeAccessibilityService) {
        when (tool.id) {
            ToolManager.TOOL_OPEN_WECHAT -> {
                service.openWeChat()
                randomDelay(6000, 12000)
            }
            ToolManager.TOOL_SET_CLIPBOARD -> {
                // 剪贴板内容由调用方设置，这里不覆盖
                randomDelay(1800, 4500)
            }
            ToolManager.TOOL_GO_BACK -> {
                service.goBack()
                randomDelay(1500, 3600)
            }
            else -> {
                // 用户定义的坐标工具
                if (tool.x > 0 && tool.y > 0) {
                    val screenBounds = service.getScreenBounds()
                    val x = (screenBounds.width() * tool.x).toInt()
                    val y = (screenBounds.height() * tool.y).toInt()

                    // 特殊处理：输入法剪贴板需要等待键盘
                    if (tool.name.contains("剪贴板") || tool.name.contains("输入法")) {
                        randomDelay(3000, 6000)
                    }

                    service.clickAt(x, y)
                    android.util.Log.d("ToolManager", "点击 ${tool.name}: ($x, $y)")
                    randomDelay(1500, 3600)
                }
            }
        }
    }

    private fun testTool(tool: Tool) {
        val service = BridgeAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, "请先启用无障碍服务", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "开始测试: ${tool.name}", Toast.LENGTH_SHORT).show()

        // 获取默认设置
        val defaultContact = getDefaultContact()
        val defaultMessage = getDefaultMessage()

        Thread {
            try {
                // 设置剪贴板为默认联系人（直接使用中文）
                android.util.Log.d("ToolManager", "设置剪贴板: $defaultContact")
                runOnUiThread {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(null, defaultContact))
                }
                randomDelay(500, 1000)

                // 获取执行链
                val executionChain = ToolManager.getExecutionChain(this, tool.id)
                android.util.Log.d("ToolManager", "执行链: ${executionChain.map { it.name }}")

                for (t in executionChain) {
                    android.util.Log.d("ToolManager", "执行工具: ${t.name}")

                    when (t.id) {
                        ToolManager.TOOL_OPEN_WECHAT -> {
                            service.openWeChat()
                            randomDelay(6000, 12000)
                        }
                        ToolManager.TOOL_SET_CLIPBOARD -> {
                            // 剪贴板内容由调用方设置，这里不覆盖
                            randomDelay(1800, 4500)
                        }
                        ToolManager.TOOL_GO_BACK -> {
                            service.goBack()
                            randomDelay(1500, 3600)
                        }
                        else -> {
                            // 用户定义的坐标工具
                            if (t.x > 0 && t.y > 0) {
                                val screenBounds = service.getScreenBounds()
                                val x = (screenBounds.width() * t.x).toInt()
                                val y = (screenBounds.height() * t.y).toInt()

                                // 特殊处理：输入法剪贴板需要等待键盘
                                if (t.name.contains("剪贴板") || t.name.contains("输入法")) {
                                    randomDelay(3000, 6000)
                                }

                                service.clickAt(x, y)
                                android.util.Log.d("ToolManager", "点击 ${t.name}: ($x, $y)")

                                // 根据工具名称设置不同的延迟
                                when {
                                    t.name.contains("搜索") -> randomDelay(4500, 9000)
                                    t.name.contains("剪贴板") || t.name.contains("输入法") -> randomDelay(4500, 9000)
                                    t.name.contains("联系人") -> randomDelay(1500, 3600)
                                    else -> randomDelay(1500, 3600)
                                }
                            }
                        }
                    }
                }

                runOnUiThread {
                    Toast.makeText(this, "测试完成: ${tool.name}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("ToolManager", "测试失败", e)
                runOnUiThread {
                    Toast.makeText(this, "测试失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun randomDelay(minMs: Long, maxMs: Long) {
        Thread.sleep(minMs + Random.nextLong(maxMs - minMs))
    }

    private fun showEditDialog(tool: Tool?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_tool_editor, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        val nameEdit = dialogView.findViewById<EditText>(R.id.toolNameEdit)
        val descEdit = dialogView.findViewById<EditText>(R.id.toolDescEdit)
        val xEdit = dialogView.findViewById<EditText>(R.id.toolXEdit)
        val yEdit = dialogView.findViewById<EditText>(R.id.toolYEdit)
        val pickCoordBtn = dialogView.findViewById<Button>(R.id.pickCoordBtn)
        val preToolsList = dialogView.findViewById<RecyclerView>(R.id.preToolsList)
        val addPreToolBtn = dialogView.findViewById<Button>(R.id.addPreToolBtn)
        val cancelBtn = dialogView.findViewById<Button>(R.id.cancelBtn)
        val saveBtn = dialogView.findViewById<Button>(R.id.saveBtn)

        val isNewTool = tool == null
        var editingTool = tool
        val selectedPreTools = mutableListOf<Tool>()

        // 如果是编辑，填充现有数据
        if (tool != null) {
            nameEdit.setText(tool.name)
            descEdit.setText(tool.description)
            xEdit.setText(String.format("%.3f", tool.x))
            yEdit.setText(String.format("%.3f", tool.y))

            // 加载前置工具
            val allTools = ToolManager.getAllTools(this)
            for (preId in tool.preToolIds) {
                allTools.find { it.id == preId }?.let { selectedPreTools.add(it) }
            }
        }

        // 前置工具列表适配器
        lateinit var preToolAdapter: PreToolAdapter
        preToolAdapter = PreToolAdapter(
            tools = selectedPreTools,
            onRemove = { t ->
                selectedPreTools.remove(t)
                preToolAdapter.notifyDataSetChanged()
            },
            onMove = { fromPosition, toPosition ->
                // 顺序已经在 adapter 中更新了，这里只需要刷新显示
            }
        )
        preToolsList.layoutManager = LinearLayoutManager(this)
        preToolsList.adapter = preToolAdapter

        // 添加拖拽排序支持
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                preToolAdapter.onItemMove(viewHolder.adapterPosition, target.adapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // 不支持滑动删除
            }
        })
        itemTouchHelper.attachToRecyclerView(preToolsList)

        // 获取坐标按钮 - 需要执行前置工具
        pickCoordBtn.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show()
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                ))
                return@setOnClickListener
            }

            val service = BridgeAccessibilityService.instance
            if (service == null) {
                Toast.makeText(this, "请先启用无障碍服务", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 临时保存当前编辑的工具（用于获取前置工具链）
            val tempTool = Tool(
                id = editingTool?.id ?: "temp",
                name = nameEdit.text.toString(),
                description = descEdit.text.toString(),
                x = xEdit.text.toString().toFloatOrNull() ?: 0f,
                y = yEdit.text.toString().toFloatOrNull() ?: 0f,
                preToolIds = selectedPreTools.map { it.id },
                isBuiltIn = editingTool?.isBuiltIn ?: false
            )

            Toast.makeText(this, "正在执行前置步骤...", Toast.LENGTH_SHORT).show()

            // 获取默认联系人设置剪贴板（直接使用中文）
            val defaultContact = getDefaultContact()
            android.util.Log.d("ToolManager", "设置剪贴板: $defaultContact")
            runOnUiThread {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(null, defaultContact))
            }

            // 在后台线程执行前置工具链
            Thread {
                try {
                    // 获取前置工具链
                    android.util.Log.d("ToolManager", "编辑对话框前置工具链: ${selectedPreTools.map { it.name }}")

                    for (t in selectedPreTools) {
                        android.util.Log.d("ToolManager", "执行前置工具: ${t.name}")
                        executeTool(t, service)
                    }

                    // 等待目标应用完全显示
                    randomDelay(1000, 2000)

                    runOnUiThread {
                        Toast.makeText(this, "请在屏幕上点击选择位置", Toast.LENGTH_SHORT).show()

                        val currentX = xEdit.text.toString().toFloatOrNull() ?: 0f
                        val currentY = yEdit.text.toString().toFloatOrNull() ?: 0f

                        coordinatePicker?.dismiss()
                        coordinatePicker = CoordinatePicker(
                            context = this@ToolManagerActivity,
                            onCoordinatePicked = { x, y ->
                                runOnUiThread {
                                    xEdit.setText(String.format("%.3f", x))
                                    yEdit.setText(String.format("%.3f", y))
                                }
                            },
                            onCancelled = {
                                runOnUiThread {
                                    Toast.makeText(this@ToolManagerActivity, "已取消", Toast.LENGTH_SHORT).show()
                                }
                            },
                            initialX = if (currentX > 0) currentX else null,
                            initialY = if (currentY > 0) currentY else null
                        )
                        coordinatePicker?.show()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ToolManager", "执行前置工具失败", e)
                    runOnUiThread {
                        Toast.makeText(this, "前置步骤失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }

        // 添加前置工具按钮
        addPreToolBtn.setOnClickListener {
            showPreToolSelector(selectedPreTools) { newSelected ->
                selectedPreTools.clear()
                selectedPreTools.addAll(newSelected)
                preToolAdapter.notifyDataSetChanged()
            }
        }

        // 取消按钮
        cancelBtn.setOnClickListener {
            dialog.dismiss()
        }

        // 保存按钮
        saveBtn.setOnClickListener {
            val name = nameEdit.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "请输入工具名称", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val desc = descEdit.text.toString().trim()
            val x = xEdit.text.toString().toFloatOrNull() ?: 0f
            val y = yEdit.text.toString().toFloatOrNull() ?: 0f
            val preToolIds = selectedPreTools.map { it.id }

            val savedTool = Tool(
                id = editingTool?.id ?: ToolManager.generateId(),
                name = name,
                description = desc,
                x = x,
                y = y,
                preToolIds = preToolIds,
                isBuiltIn = editingTool?.isBuiltIn ?: false
            )

            ToolManager.saveTool(this, savedTool)
            loadTools()
            dialog.dismiss()
            Toast.makeText(this, "工具已保存", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun showPreToolSelector(currentSelected: List<Tool>, onSelected: (List<Tool>) -> Unit) {
        val allTools = ToolManager.getAllTools(this)
        val selectedIds = currentSelected.map { it.id }.toMutableList()

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pre_tool_selector, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.toolsRecyclerView)
        val cancelBtn = dialogView.findViewById<Button>(R.id.cancelBtn)
        val confirmBtn = dialogView.findViewById<Button>(R.id.confirmBtn)

        val adapter = ToolSelectAdapter(
            tools = allTools,
            selectedIds = selectedIds,
            onSelectionChanged = { id, isSelected ->
                if (isSelected) {
                    if (id !in selectedIds) selectedIds.add(id)
                } else {
                    selectedIds.remove(id)
                }
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        cancelBtn.setOnClickListener { dialog.dismiss() }

        confirmBtn.setOnClickListener {
            val selectedTools = selectedIds.mapNotNull { id -> allTools.find { it.id == id } }
            onSelected(selectedTools)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun deleteTool(tool: Tool) {
        if (tool.isBuiltIn) {
            Toast.makeText(this, "内置工具不能删除", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("删除工具")
            .setMessage("确定要删除「${tool.name}」吗？")
            .setPositiveButton("删除") { _, _ ->
                ToolManager.deleteTool(this, tool.id)
                loadTools()
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 导出配置
     */
    private fun exportConfig() {
        // 检查存储权限（Android 10+ 不需要）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
                Toast.makeText(this, "请授予存储权限", Toast.LENGTH_SHORT).show()
                return
            }
        }

        Thread {
            val filePath = ToolManager.exportTools(this)
            runOnUiThread {
                if (filePath != null) {
                    Toast.makeText(this, "配置已导出到:\n$filePath", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "导出失败", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /**
     * 导入配置
     */
    private fun importConfig() {
        // 检查存储权限（Android 10+ 不需要）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 101)
                Toast.makeText(this, "请授予存储权限", Toast.LENGTH_SHORT).show()
                return
            }
        }

        // 检查导入文件是否存在
        if (!ToolManager.hasImportFile()) {
            Toast.makeText(this, "导入文件不存在:\n${ToolManager.getImportFilePath()}", Toast.LENGTH_LONG).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("导入配置")
            .setMessage("确定要导入配置吗？\n当前配置将被覆盖。")
            .setPositiveButton("导入") { _, _ ->
                Thread {
                    val success = ToolManager.importTools(this)
                    runOnUiThread {
                        if (success) {
                            loadTools()
                            Toast.makeText(this, "配置导入成功", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "导入失败，请检查文件格式", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // 工具列表适配器
    inner class ToolAdapter(
        private val tools: List<Tool>,
        private val onGetCoordClick: (Tool) -> Unit,
        private val onTestClick: (Tool) -> Unit,
        private val onEditClick: (Tool) -> Unit,
        private val onDeleteClick: (Tool) -> Unit
    ) : RecyclerView.Adapter<ToolAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val toolName: TextView = view.findViewById(R.id.toolName)
            val toolCoord: TextView = view.findViewById(R.id.toolCoord)
            val toolDesc: TextView = view.findViewById(R.id.toolDesc)
            val preToolsInfo: TextView = view.findViewById(R.id.preToolsInfo)
            val getCoordBtn: Button = view.findViewById(R.id.getCoordBtn)
            val testBtn: Button = view.findViewById(R.id.testBtn)
            val editBtn: Button = view.findViewById(R.id.editBtn)
            val deleteBtn: Button = view.findViewById(R.id.deleteBtn)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_tool, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val tool = tools[position]

            holder.toolName.text = tool.name
            holder.toolCoord.text = String.format("(%.3f, %.3f)", tool.x, tool.y)
            holder.toolDesc.text = tool.description

            // 显示前置工具信息
            if (tool.preToolIds.isNotEmpty()) {
                val allTools = ToolManager.getAllTools(this@ToolManagerActivity)
                val preToolNames = tool.preToolIds.mapNotNull { id ->
                    allTools.find { it.id == id }?.name
                }
                holder.preToolsInfo.text = "前置: ${preToolNames.joinToString(" → ")}"
                holder.preToolsInfo.visibility = View.VISIBLE
            } else {
                holder.preToolsInfo.visibility = View.GONE
            }

            // 内置工具特殊处理
            if (tool.isBuiltIn) {
                holder.toolCoord.visibility = View.GONE
                holder.getCoordBtn.visibility = View.GONE
                holder.deleteBtn.visibility = View.GONE
            } else {
                holder.toolCoord.visibility = View.VISIBLE
                holder.getCoordBtn.visibility = View.VISIBLE
                holder.deleteBtn.visibility = View.VISIBLE
            }

            holder.getCoordBtn.setOnClickListener { onGetCoordClick(tool) }
            holder.testBtn.setOnClickListener { onTestClick(tool) }
            holder.editBtn.setOnClickListener { onEditClick(tool) }
            holder.deleteBtn.setOnClickListener { onDeleteClick(tool) }
        }

        override fun getItemCount() = tools.size
    }

    // 已选前置工具适配器（支持拖拽排序）
    inner class PreToolAdapter(
        private val tools: MutableList<Tool>,
        private val onRemove: (Tool) -> Unit,
        private val onMove: (fromPosition: Int, toPosition: Int) -> Unit
    ) : RecyclerView.Adapter<PreToolAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val orderNumber: TextView = view.findViewById(R.id.orderNumber)
            val toolName: TextView = view.findViewById(R.id.toolName)
            val removeBtn: ImageButton = view.findViewById(R.id.removeBtn)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_pre_tool, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val tool = tools[position]
            holder.orderNumber.text = (position + 1).toString()
            holder.toolName.text = tool.name
            holder.removeBtn.setOnClickListener { onRemove(tool) }
        }

        override fun getItemCount() = tools.size

        fun onItemMove(fromPosition: Int, toPosition: Int) {
            val item = tools.removeAt(fromPosition)
            tools.add(toPosition, item)
            notifyItemMoved(fromPosition, toPosition)
            // 更新序号
            notifyItemRangeChanged(minOf(fromPosition, toPosition), abs(toPosition - fromPosition) + 1)
            onMove(fromPosition, toPosition)
        }
    }

    // 工具选择适配器
    inner class ToolSelectAdapter(
        private val tools: List<Tool>,
        private val selectedIds: MutableList<String>,
        private val onSelectionChanged: (String, Boolean) -> Unit
    ) : RecyclerView.Adapter<ToolSelectAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val checkbox: CheckBox = view.findViewById(R.id.toolCheckbox)
            val toolName: TextView = view.findViewById(R.id.toolName)
            val toolDesc: TextView = view.findViewById(R.id.toolDesc)
            val toolType: TextView = view.findViewById(R.id.toolType)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_tool_selectable, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val tool = tools[position]
            holder.toolName.text = tool.name
            holder.toolDesc.text = tool.description
            holder.checkbox.isChecked = tool.id in selectedIds

            if (tool.isBuiltIn) {
                holder.toolType.visibility = View.VISIBLE
                holder.toolType.text = "内置"
            } else {
                holder.toolType.visibility = View.GONE
            }

            holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
                onSelectionChanged(tool.id, isChecked)
            }

            holder.itemView.setOnClickListener {
                holder.checkbox.isChecked = !holder.checkbox.isChecked
            }
        }

        override fun getItemCount() = tools.size
    }
}
