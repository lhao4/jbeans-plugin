package com.jbeans.debug.plugin.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.messages.MessageBusConnection
import com.jbeans.debug.plugin.client.JbeansDebugClient
import com.jbeans.debug.plugin.service.JbeansDataListener
import com.jbeans.debug.plugin.service.JbeansProjectService
import com.jbeans.debug.plugin.service.MethodInfo
import com.jbeans.debug.plugin.service.ServiceInfo
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent

/**
 * 左侧面板：Bean 方法输入框 + 搜索栏 + 刷新按钮 + 服务/方法树。
 *
 * 两种选择方法的方式：
 * 1. 从服务树中点选（自动发现的接口）
 * 2. 手动输入 "类全限定名#方法名" 加载任意 Spring Bean 方法
 */
class InterfaceTreePanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val root = DefaultMutableTreeNode("JBeans Services")
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel)
    private val searchField = SearchTextField()
    private val beanInputField = JTextField()
    private val loadBeanBtn = JButton("加载")
    private val emptyLabel = JBLabel(
        "<html><center>未发现可调用服务<br/>请先连接目标应用</center></html>",
        SwingConstants.CENTER
    )

    private var allServices: List<ServiceInfo> = emptyList()
    private val busConnection: MessageBusConnection = project.messageBus.connect(this)

    init {
        border = BorderFactory.createEmptyBorder(4, 4, 4, 0)

        val topArea = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        // —— Bean 方法输入栏：[指定方法] [输入框] [加载] ——
        val beanBar = JPanel(BorderLayout(4, 0)).apply {
            border = BorderFactory.createEmptyBorder(4, 4, 2, 4)
            maximumSize = Dimension(Int.MAX_VALUE, 32)
        }
        val beanLabel = JBLabel("指定方法")
        beanLabel.font = beanLabel.font.deriveFont(Font.PLAIN, 11f)
        beanLabel.border = BorderFactory.createEmptyBorder(0, 2, 0, 0)

        beanInputField.toolTipText = "类名#方法名，如：com.example.UserService#getUser"
        beanInputField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) onLoadBeanClicked()
            }
        })
        loadBeanBtn.preferredSize = Dimension(48, 26)
        loadBeanBtn.addActionListener { onLoadBeanClicked() }

        beanBar.add(beanLabel, BorderLayout.WEST)
        beanBar.add(beanInputField, BorderLayout.CENTER)
        beanBar.add(loadBeanBtn, BorderLayout.EAST)
        topArea.add(beanBar)

        // —— 搜索 + 刷新按钮 ——
        val toolbar = JPanel(BorderLayout(6, 0)).apply {
            border = BorderFactory.createEmptyBorder(2, 4, 4, 4)
            maximumSize = Dimension(Int.MAX_VALUE, 32)
        }
        searchField.addKeyboardListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent?) {
                filterTree(searchField.text.trim())
            }
        })
        val refreshBtn = JButton("🔄")
        refreshBtn.toolTipText = "刷新服务列表"
        refreshBtn.preferredSize = Dimension(40, 26)
        refreshBtn.addActionListener {
            ApplicationManager.getApplication().executeOnPooledThread {
                JbeansProjectService.getInstance(project).refreshServices()
            }
        }
        toolbar.add(searchField, BorderLayout.CENTER)
        toolbar.add(refreshBtn, BorderLayout.EAST)
        topArea.add(toolbar)

        add(topArea, BorderLayout.NORTH)

        // —— 服务树 ——
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.addTreeSelectionListener {
            val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            val userObj = node.userObject
            if (userObj is MethodNodeData) {
                val service = JbeansProjectService.getInstance(project)
                service.selectMethod(userObj.method, userObj.interfaceName, beanMethod = false)
            }
        }

        add(JScrollPane(tree), BorderLayout.CENTER)

        updateEmptyState()

        // —— MessageBus ——
        busConnection.subscribe(JbeansDataListener.TOPIC, object : JbeansDataListener {
            override fun onConnectionChanged(connected: Boolean, message: String) {
                ApplicationManager.getApplication().invokeLater {
                    loadBeanBtn.isEnabled = connected
                    if (!connected) {
                        allServices = emptyList()
                        rebuildTree(emptyList())
                    }
                }
            }

            override fun onServicesLoaded(services: List<ServiceInfo>) {
                ApplicationManager.getApplication().invokeLater {
                    allServices = services
                    rebuildTree(services)
                }
            }

            override fun onMethodSelected(method: MethodInfo?, interfaceName: String?) {}
            override fun onInvokeResult(result: Map<String, Any?>?, error: String?) {}
        })

        loadBeanBtn.isEnabled = JbeansProjectService.getInstance(project).isConnected
    }

    override fun dispose() {
        // busConnection 通过 connext(this) 自动随 Disposable 释放
    }

    @Suppress("UNCHECKED_CAST")
    private fun onLoadBeanClicked() {
        val text = beanInputField.text.trim()
        if (text.isEmpty()) return

        val parts = text.split("#", limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            showError("格式错误，请输入：类全限定名#方法名")
            return
        }
        val className = parts[0].trim()
        val methodName = parts[1].trim()

        loadBeanBtn.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val svc = JbeansProjectService.getInstance(project)
                val result = JbeansDebugClient.resolveBeanMethod(
                    svc.host, svc.port, className, methodName
                )
                if (result["error"] != null) {
                    val msg = result["error"].toString()
                    ApplicationManager.getApplication().invokeLater {
                        loadBeanBtn.isEnabled = true
                        showError(msg)
                    }
                    return@executeOnPooledThread
                }
                val overloads = result["overloads"] as? List<Map<String, Any?>> ?: emptyList()
                if (overloads.isEmpty()) {
                    ApplicationManager.getApplication().invokeLater {
                        loadBeanBtn.isEnabled = true
                        showError("未找到方法：$className#$methodName")
                    }
                    return@executeOnPooledThread
                }
                val overload = overloads[0]
                val paramNames = overload["parameterNames"] as? List<String> ?: emptyList()
                val paramTypes = overload["parameterTypes"] as? List<String> ?: emptyList()
                val returnType = overload["returnType"] as? String ?: "void"
                val paramSchemas = overload["parameterSchemas"] as? List<Any?> ?: emptyList()

                val methodInfo = MethodInfo(
                    name = methodName,
                    parameterNames = paramNames,
                    parameterTypes = paramTypes,
                    returnType = returnType
                )
                ApplicationManager.getApplication().invokeLater {
                    loadBeanBtn.isEnabled = true
                    tree.clearSelection()
                    svc.lastBeanParamSchemas = paramSchemas
                    svc.selectMethod(methodInfo, className, beanMethod = true)
                }
            } catch (e: Exception) {
                val msg = e.message ?: "连接失败"
                ApplicationManager.getApplication().invokeLater {
                    loadBeanBtn.isEnabled = true
                    showError(msg)
                }
            }
        }
    }

    private fun showError(message: String) {
        // 这里可改为 IDEA MessagesDialog 或 status bar
        javax.swing.JOptionPane.showMessageDialog(
            this,
            message,
            "Bean 方法加载失败",
            javax.swing.JOptionPane.WARNING_MESSAGE
        )
    }

    // —— 搜索过滤 ——
    private fun filterTree(query: String) {
        if (query.isEmpty()) {
            rebuildTree(allServices)
            return
        }
        val lower = query.lowercase()
        val filtered = allServices.mapNotNull { svc ->
            val matchingMethods = svc.methods.filter {
                it.name.lowercase().contains(lower) ||
                        svc.interfaceName.lowercase().contains(lower)
            }
            if (matchingMethods.isNotEmpty()) ServiceInfo(svc.interfaceName, matchingMethods) else null
        }
        rebuildTree(filtered)
    }

    private fun rebuildTree(services: List<ServiceInfo>) {
        root.removeAllChildren()
        for (svc in services) {
            val simpleName = svc.interfaceName.substringAfterLast('.')
            val ifaceNode = DefaultMutableTreeNode(simpleName)
            for (method in svc.methods) {
                ifaceNode.add(DefaultMutableTreeNode(MethodNodeData(method, svc.interfaceName)))
            }
            root.add(ifaceNode)
        }
        treeModel.reload()
        expandAll()
        updateEmptyState()
    }

    private fun expandAll() {
        var row = 0
        while (row < tree.rowCount) {
            tree.expandRow(row)
            row++
        }
    }

    private fun updateEmptyState() {
        val hasContent = root.childCount > 0
        tree.isVisible = hasContent
        emptyLabel.isVisible = !hasContent
        if (!hasContent && emptyLabel.parent == null) {
            add(emptyLabel, BorderLayout.SOUTH)
        } else if (hasContent && emptyLabel.parent != null) {
            remove(emptyLabel)
        }
        revalidate()
        repaint()
    }

    private data class MethodNodeData(val method: MethodInfo, val interfaceName: String) {
        override fun toString(): String = method.displaySignature
    }

}
