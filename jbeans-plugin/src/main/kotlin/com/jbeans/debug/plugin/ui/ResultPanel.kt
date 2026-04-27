package com.jbeans.debug.plugin.ui

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.google.gson.ToNumberPolicy
import com.intellij.lang.Language
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.LanguageTextField
import com.intellij.json.JsonLanguage
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.ui.JBColor
import com.intellij.util.messages.MessageBusConnection
import com.jbeans.debug.plugin.service.JbeansDataListener
import com.jbeans.debug.plugin.service.JbeansProjectService
import com.jbeans.debug.plugin.service.MethodInfo
import com.jbeans.debug.plugin.service.ServiceInfo
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import javax.swing.*

/**
 * 调用结果展示面板：带语法高亮的只读 JSON 编辑器，复制/清空按钮，状态标签。
 */
class ResultPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val resultEditor: LanguageTextField
    private val statusLabel = JBLabel("")
    private val copyBtn = JButton("📋 复制")
    private val clearBtn = JButton("🗑 清空")
    private var paramEditorProvider: (() -> String)? = null
    private val busConnection: MessageBusConnection = project.messageBus.connect(this)

    var onInvokeStateChanged: ((invoking: Boolean) -> Unit)? = null

    init {
        preferredSize = java.awt.Dimension(0, 220)
        border = BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border())

        // 结果区域：JSON 语法高亮编辑器（只读）
        resultEditor = try {
            object : LanguageTextField(JsonLanguage.INSTANCE, project, "", false) {
                override fun createEditor(): EditorEx {
                    return super.createEditor().apply {
                        settings.isLineNumbersShown = false
                        settings.isFoldingOutlineShown = false
                        settings.isAdditionalPageAtBottom = false
                        isViewer = true // 只读
                    }
                }
            }
        } catch (e: Exception) {
            LanguageTextField(Language.ANY, project, "", false)
        }

        // 顶部工具栏
        val toolbar = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
        }
        statusLabel.font = statusLabel.font.deriveFont(Font.PLAIN, 12f)
        toolbar.add(statusLabel, BorderLayout.WEST)

        val btnRow = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0))
        copyBtn.addActionListener {
            val toolkit = java.awt.Toolkit.getDefaultToolkit()
            val clipboard = toolkit.systemClipboard
            clipboard.setContents(StringSelection(resultEditor.text), null)
        }
        clearBtn.addActionListener {
            resultEditor.text = ""
            statusLabel.text = ""
        }
        btnRow.add(copyBtn)
        btnRow.add(clearBtn)
        toolbar.add(btnRow, BorderLayout.EAST)
        add(toolbar, BorderLayout.NORTH)

        // 结果编辑器放到中间（带滚动支持）
        resultEditor.minimumSize = java.awt.Dimension(0, 0)
        val scrollPane = com.intellij.ui.components.JBScrollPane(resultEditor).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            minimumSize = java.awt.Dimension(0, 0)
        }
        add(scrollPane, BorderLayout.CENTER)

        busConnection.subscribe(JbeansDataListener.TOPIC, object : JbeansDataListener {
            override fun onConnectionChanged(connected: Boolean, message: String) {}
            override fun onServicesLoaded(services: List<ServiceInfo>) {}
            override fun onMethodSelected(method: MethodInfo?, interfaceName: String?) {}
            override fun onInvokeResult(result: Map<String, Any?>?, error: String?) {
                ApplicationManager.getApplication().invokeLater {
                    onInvokeStateChanged?.invoke(false)
                    if (error != null) {
                        setResultText("// Error: $error", isError = true)
                        statusLabel.text = "❌ 调用失败"
                    } else if (result != null) {
                        handleResult(result)
                    }
                }
            }
        })
    }

    fun setParamEditorProvider(provider: () -> String) {
        this.paramEditorProvider = provider
    }

    fun triggerInvoke() {
        val service = JbeansProjectService.getInstance(project)
        if (!service.isConnected) {
            setResultText("// 当前未连接到目标应用", isError = true)
            return
        }
        val method = service.getSelectedMethod()
        val interfaceName = service.getSelectedInterface()
        if (method == null || interfaceName == null) {
            setResultText("// 请先从左侧选择一个方法", isError = true)
            return
        }

        val paramsJson = paramEditorProvider?.invoke() ?: "{}"
        try {
            JsonParser.parseString(paramsJson)
        } catch (e: Exception) {
            setResultText("// JSON 格式错误: ${e.message}", isError = true)
            return
        }

        onInvokeStateChanged?.invoke(true)
        statusLabel.text = "⏳ 调用中..."
        setResultText("")

        // 构建 args 数组：智能匹配参数
        val gson = GsonBuilder()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .disableHtmlEscaping()
            .create()
        val argsMap: Map<String, Any?> = try {
            gson.fromJson(paramsJson, object : com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type)
        } catch (e: Exception) {
            setResultText("// 参数解析失败: ${e.message}", isError = true)
            onInvokeStateChanged?.invoke(false)
            return
        }

        val args: List<Any> = if (method.parameterNames.size == 1) {
            // 单参数：先看是否有精确匹配的 key（包裹模式），否则整个 map 就是该参数值（展开模式）
            val name = method.parameterNames[0]
            if (argsMap.containsKey(name)) {
                listOf(argsMap[name] ?: emptyMap<String, Any>())
            } else {
                listOf(argsMap) // 整个 JSON 对象就是这个参数
            }
        } else {
            // 多参数：按参数名匹配，fallback 按位置
            method.parameterNames.mapIndexed { idx, name ->
                argsMap[name] ?: argsMap.values.toList().getOrNull(idx) ?: emptyMap<String, Any>()
            }
        }

        // 去掉泛型: "java.util.List<X>" → "java.util.List"
        val paramTypes = method.parameterTypes.map { t ->
            val idx = t.indexOf('<')
            if (idx > 0) t.substring(0, idx) else t
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "调用 ${method.name}...") {
            override fun run(indicator: ProgressIndicator) {
                try {
                    service.invokeMethod(interfaceName, method.name, paramTypes, args)
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        onInvokeStateChanged?.invoke(false)
                        setResultText("// Error: ${e.message}", isError = true)
                        statusLabel.text = "❌ 调用失败"
                    }
                }
            }
        })
    }

    private fun setResultText(text: String, isError: Boolean = false) {
        resultEditor.text = text
    }

    private fun handleResult(result: Map<String, Any?>) {
        val success = result["success"] as? Boolean ?: false
        if (success) {
            val resultType = result["resultType"]?.toString() ?: ""
            if (resultType == "void") {
                setResultText("// 调用成功 (void)")
            } else {
                val gson = GsonBuilder()
                    .setPrettyPrinting()
                    .disableHtmlEscaping()
                    .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
                    .create()
                val resultValue = result["result"]
                val jsonElement = gson.toJsonTree(resultValue)
                val jsonStr = gson.toJson(jsonElement)
                setResultText(if (jsonStr.length > 100_000) {
                    jsonStr.substring(0, 100_000) + "\n\n// [结果已截断，共 ${jsonStr.length} 字符]"
                } else {
                    jsonStr
                })
            }
            val elapsed = result["elapsedMs"]
            statusLabel.text = "✅ 调用成功（耗时 ${elapsed}ms）"
        } else {
            val errorCode = result["error"]?.toString() ?: "UNKNOWN"
            val msg = result["message"]?.toString() ?: ""
            val stack = result["stackTrace"]?.toString() ?: ""
            setResultText("// $errorCode: $msg\n\n// Stack Trace:\n// $stack", isError = true)
            statusLabel.text = "❌ 调用失败"
        }
    }

    override fun dispose() {
        // busConnection 通过 connect(this) 自动随 Disposable 释放
    }
}
