package com.jbeans.debug.plugin.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.messages.MessageBusConnection
import com.jbeans.debug.plugin.attach.ProcessDiscovery
import com.jbeans.debug.plugin.service.JbeansDataListener
import com.jbeans.debug.plugin.service.JbeansProjectService
import com.jbeans.debug.plugin.service.MethodInfo
import com.jbeans.debug.plugin.service.ServiceInfo
import java.awt.*
import javax.swing.*

/**
 * 连接面板：进程选择器 + 刷新 + Attach/断开 + 状态
 * 布局：[🔄] [进程下拉框（自适应宽度）] [🔗 连接 / ❌ 断开] [状态文本]
 */
class ConnectionPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val processCombo = JComboBox<ProcessDiscovery.JvmProcessInfo>()
    private val refreshBtn = JButton("🔄")
    private val connectBtn = JButton("🔗 连接")
    private val statusLabel = JBLabel("❌ 未连接")
    private val busConnection: MessageBusConnection = project.messageBus.connect(this)

    init {
        preferredSize = Dimension(0, 38)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
            BorderFactory.createEmptyBorder(4, 8, 4, 8)
        )

        // 左侧：刷新按钮
        refreshBtn.toolTipText = "刷新进程列表"
        refreshBtn.preferredSize = Dimension(40, 26)
        refreshBtn.addActionListener { refreshProcessList() }

        // 中间：进程下拉框（自适应拉伸）
        processCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int,
                isSelected: Boolean, cellHasFocus: Boolean
            ) = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).also {
                if (value is ProcessDiscovery.JvmProcessInfo) {
                    text = "${value.pid} - ${value.displayName}"
                }
            }
        }

        // 右侧：按钮 + 状态
        connectBtn.font = connectBtn.font.deriveFont(Font.PLAIN, 11f)
        connectBtn.addActionListener { onConnectClicked() }

        statusLabel.foreground = JBColor.RED
        statusLabel.font = statusLabel.font.deriveFont(Font.PLAIN, 11f)

        val rightPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
        rightPanel.add(connectBtn)
        rightPanel.add(statusLabel)

        val leftPanel = JPanel(BorderLayout(6, 0)).apply { isOpaque = false }
        leftPanel.add(refreshBtn, BorderLayout.WEST)

        // 使用 BorderLayout 让下拉框占据中间自适应空间
        add(leftPanel, BorderLayout.WEST)
        add(processCombo, BorderLayout.CENTER)
        add(rightPanel, BorderLayout.EAST)

        // 初始加载进程列表
        refreshProcessList()

        // 监听连接状态变化
        busConnection.subscribe(JbeansDataListener.TOPIC, object : JbeansDataListener {
            override fun onConnectionChanged(connected: Boolean, message: String) {
                ApplicationManager.getApplication().invokeLater { updateConnectionUI(connected, message) }
            }

            override fun onServicesLoaded(services: List<ServiceInfo>) {}
            override fun onMethodSelected(method: MethodInfo?, interfaceName: String?) {}
            override fun onInvokeResult(result: Map<String, Any?>?, error: String?) {}
        })
    }

    private fun refreshProcessList() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val processes = ProcessDiscovery.listJavaProcesses()
            ApplicationManager.getApplication().invokeLater {
                processCombo.removeAllItems()
                processes.forEach { processCombo.addItem(it) }
                if (processes.isEmpty()) {
                    processCombo.addItem(ProcessDiscovery.JvmProcessInfo("", "未发现 Java 进程", ""))
                }
            }
        }
    }

    private fun onConnectClicked() {
        val service = JbeansProjectService.getInstance(project)
        if (service.isConnected) {
            ApplicationManager.getApplication().executeOnPooledThread {
                service.disconnect()
            }
            return
        }

        val selected = processCombo.selectedItem as? ProcessDiscovery.JvmProcessInfo ?: return
        if (selected.pid.isEmpty()) return

        connectBtn.isEnabled = false
        statusLabel.text = "⏳ 注入中..."
        statusLabel.foreground = JBColor(Color(0x99, 0x99, 0x99), Color(0x99, 0x99, 0x99))

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                service.attachToProcess(selected)
                service.refreshServices()
                ApplicationManager.getApplication().invokeLater {
                    connectBtn.isEnabled = true
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    connectBtn.isEnabled = true
                    statusLabel.text = "❌ ${e.message}"
                    statusLabel.foreground = JBColor.RED
                }
            }
        }
    }

    private fun updateConnectionUI(connected: Boolean, message: String) {
        if (connected) {
            connectBtn.text = "❌ 断开"
            statusLabel.text = "✅ $message"
            statusLabel.foreground = JBColor(Color(0,180,0),Color(0x6A, 0xC4, 0x6A))
            processCombo.isEnabled = false
            refreshBtn.isEnabled = false
        } else {
            connectBtn.text = "🔗 连接"
            statusLabel.text = "❌ $message"
            statusLabel.foreground = JBColor.RED
            processCombo.isEnabled = true
            refreshBtn.isEnabled = true
        }
    }

    override fun dispose() {
        // busConnection 通过 connect(this) 自动随 Disposable 释放
    }
}
