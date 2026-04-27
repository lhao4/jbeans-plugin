package com.jbeans.debug.plugin.ui

import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import com.intellij.lang.Language
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.components.JBLabel
import com.intellij.ui.LanguageTextField
import com.intellij.json.JsonLanguage
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.JavaPsiFacade
import com.intellij.ui.JBColor
import com.jbeans.debug.plugin.client.JbeansDebugClient
import com.jbeans.debug.plugin.param.DefaultValueGenerator
import com.jbeans.debug.plugin.service.JbeansDataListener
import com.jbeans.debug.plugin.service.JbeansProjectService
import com.jbeans.debug.plugin.service.MethodInfo
import com.jbeans.debug.plugin.service.ServiceInfo
import java.awt.*
import javax.swing.*

class ParameterEditorPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val methodInfoLabel = JBLabel("当前方法: -")
    private val invokeBtn = JButton("🚀 测试")
    private val editor: LanguageTextField
    private var currentMethod: MethodInfo? = null
    private var currentInterface: String? = null
    private var resultPanel: ResultPanel? = null
    private val busConnection = project.messageBus.connect(this)

    private val prettyGson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .create()

    init {
        val topArea = JPanel(BorderLayout())
        val infoBar = JPanel(BorderLayout()).apply {
            background = JBColor(Color(0x25,0x26,0x27), Color(0x25,0x26,0x27))
            border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
        }
        methodInfoLabel.foreground = JBColor(java.awt.Color(0xCC, 0xCC, 0xCC), java.awt.Color(0xCC, 0xCC, 0xCC))
        methodInfoLabel.font = methodInfoLabel.font.deriveFont(Font.PLAIN, 12f)
        infoBar.add(methodInfoLabel, BorderLayout.CENTER)

        invokeBtn.isFocusPainted = false
        invokeBtn.isEnabled = false // 初始禁用，连接后启用
        invokeBtn.preferredSize = Dimension(80, 28)
        invokeBtn.font = invokeBtn.font.deriveFont(Font.PLAIN, 13f)
        invokeBtn.addActionListener { onInvokeClicked() }

        val btnWrapper = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { isOpaque = false }
        btnWrapper.add(invokeBtn)
        infoBar.add(btnWrapper, BorderLayout.EAST)

        topArea.add(infoBar, BorderLayout.CENTER)
        add(topArea, BorderLayout.NORTH)

        editor = try {
            LanguageTextField(JsonLanguage.INSTANCE, project, "", false)
        } catch (e: Exception) {
            LanguageTextField(Language.ANY, project, "", false)
        }
        editor.minimumSize = Dimension(0, 0)
        val editorScroll = JScrollPane(editor).apply { minimumSize = Dimension(0, 0) }
        add(editorScroll, BorderLayout.CENTER)

        busConnection.subscribe(JbeansDataListener.TOPIC, object : JbeansDataListener {
            override fun onConnectionChanged(connected: Boolean, message: String) {
                ApplicationManager.getApplication().invokeLater {
                    currentMethod = null
                    currentInterface = null
                    methodInfoLabel.text = "当前方法: -"
                    invokeBtn.isEnabled = false
                    editor.text = ""
                }
            }

            override fun onServicesLoaded(services: List<ServiceInfo>) {}
            override fun onMethodSelected(method: MethodInfo?, interfaceName: String?) {
                ApplicationManager.getApplication().invokeLater {
                    currentMethod = method
                    currentInterface = interfaceName
                    val connected = JbeansProjectService.getInstance(project).isConnected
                    invokeBtn.isEnabled = connected && method != null
                    if (method != null) {
                        methodInfoLabel.text = "当前方法: ${method.displaySignature}"
                        generateDefaultParamsAsync(method)
                    } else {
                        methodInfoLabel.text = "当前方法: -"
                        editor.text = ""
                    }
                }
            }

            override fun onInvokeResult(result: Map<String, Any?>?, error: String?) {}
        })
    }

    fun setResultPanel(panel: ResultPanel) {
        this.resultPanel = panel
        panel.onInvokeStateChanged = { invoking ->
            ApplicationManager.getApplication().invokeLater {
                invokeBtn.isEnabled = !invoking
                invokeBtn.text = if (invoking) "测试中..." else "🚀 测试"
            }
        }
    }

    fun getEditorText(): String = editor.text

    private fun onInvokeClicked() {
        resultPanel?.triggerInvoke()
    }

    // ------------------------------
    // 三级参数默认值生成
    // ------------------------------
    private fun generateDefaultParamsAsync(method: MethodInfo) {
        if (method.parameterNames.isEmpty()) {
            editor.text = "{}"
            return
        }

        // Bean 方法: resolve 已返回 schema，直接使用
        val service = JbeansProjectService.getInstance(project)
        if (service.isBeanMethod && service.lastBeanParamSchemas.isNotEmpty()) {
            editor.text = buildParamsFromSchemas(method, service.lastBeanParamSchemas)
            return
        }

        // 策略 1: Agent /schema - 运行时反射 DTO 字段
        val fromAgent = generateWithAgentSchema(method)
        if (fromAgent != null) {
            editor.text = fromAgent
            return
        }

        // 策略 2: PSI - IDE 源码分析
        val fromPsi = generateWithPsi(method)
        if (fromPsi != null) {
            editor.text = fromPsi
            return
        }

        // 策略 3: 简易类型默认值（兜底）
        editor.text = generateSimpleDefaults(method)
    }

    private fun generateWithAgentSchema(method: MethodInfo): String? {
        val service = JbeansProjectService.getInstance(project)
        if (!service.isConnected) return null
        return try {
            val params = LinkedHashMap<String, Any?>()
            for (i in method.parameterTypes.indices) {
                val typeName = method.parameterTypes[i]
                val paramName = friendlyParamName(method.parameterNames[i], typeName)
                val schema = JbeansDebugClient.getSchema(service.host, service.port, typeName)
                params[paramName] = schema
            }
            // 单参数复杂对象：直接展开
            if (params.size == 1) {
                val value = params.values.first()
                if (value is Map<*, *>) {
                    return prettyGson.toJson(value)
                }
            }
            prettyGson.toJson(params)
        } catch (_: Exception) {
            null
        }
    }

    private fun generateWithPsi(method: MethodInfo): String? {
        val ifaceName = currentInterface ?: return null
        return ReadAction.compute<String?, Throwable> {
            val facade = JavaPsiFacade.getInstance(project)
            val scope = GlobalSearchScope.allScope(project)
            val psiClass = facade.findClass(ifaceName, scope) ?: return@compute null
            val psiMethod = psiClass.findMethodsByName(method.name, true)
                .firstOrNull { it.parameterList.parametersCount == method.parameterTypes.size }
            DefaultValueGenerator.generate(psiMethod)
        }
    }

    private fun generateSimpleDefaults(method: MethodInfo): String {
        val sb = StringBuilder("{\n")
        for (i in method.parameterNames.indices) {
            val name = friendlyParamName(method.parameterNames[i], method.parameterTypes[i])
            val type = method.parameterTypes[i]
            if (i > 0) sb.append(",\n")
            sb.append("  \"$name\": ${defaultForType(type)}")
        }
        sb.append("\n}")
        return sb.toString()
    }

    /**
     * 从 Bean resolve 返回的 paramSchemas 构建参数 JSON，与 generateWithAgentSchema 的输出格式保持一致。
     */
    private fun buildParamsFromSchemas(method: MethodInfo, schemas: List<Any?>): String {
        // 单参数 DTO（Map）：直接展开字段
        if (schemas.size == 1 && schemas[0] is Map<*, *>) {
            return prettyGson.toJson(schemas[0])
        }
        // 其他：包裹为 { paramName: schema, ... }
        val params = LinkedHashMap<String, Any?>()
        for (i in schemas.indices) {
            val name = if (i < method.parameterNames.size) {
                friendlyParamName(method.parameterNames[i], method.parameterTypes.getOrElse(i) { "" })
            } else "arg$i"
            params[name] = schemas[i]
        }
        return prettyGson.toJson(params)
    }

    private fun friendlyParamName(rawName: String, typeFqcn: String): String {
        if (!rawName.matches(Regex("arg\\d+"))) return rawName
        return if (typeFqcn.contains(".")) {
            typeFqcn.substringAfterLast('.').replaceFirstChar { it.lowercase() }
        } else {
            rawName
        }
    }

    private fun defaultForType(fqcn: String): String = when {
        fqcn == "java.lang.String" || fqcn == "String" -> "\"\""
        fqcn in listOf("int", "java.lang.Integer", "long", "java.lang.Long",
            "short", "java.lang.Short", "byte", "java.lang.Byte") -> "0"
        fqcn in listOf("double", "java.lang.Double", "float", "java.lang.Float",
            "java.math.BigDecimal") -> "0.0"
        fqcn in listOf("boolean", "java.lang.Boolean") -> "false"
        fqcn.startsWith("java.util.List") || fqcn.startsWith("java.util.Collection") -> "[]"
        fqcn.startsWith("java.util.Set") -> "[]"
        fqcn.startsWith("java.util.Map") -> "{}"
        else -> "{}"
    }

    override fun dispose() {
        // busConnection 通过 connect(this) 自动随 Disposable 释放
    }
}
