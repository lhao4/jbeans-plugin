package com.github.lhao4.jbeans.ui.toolwindow

import com.github.lhao4.jbeans.process.JvmProcessInfo
import com.github.lhao4.jbeans.process.JvmScanner
import com.github.lhao4.jbeans.process.ProcessSession
import com.github.lhao4.jbeans.process.ProcessSession.State
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel

class ProcessSelectorPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val scanner = JvmScanner()
    private val processCombo = JComboBox<JvmProcessInfo>()
    private val statusLabel = JBLabel("● 未连接")
    private val connectButton = JButton("连接")

    private var session: ProcessSession? = null

    var onSessionChanged: ((ProcessSession?) -> Unit)? = null

    init {
        border = JBUI.Borders.empty(4, 8)

        val baseRenderer = javax.swing.DefaultListCellRenderer()
        processCombo.setRenderer { list, value, index, selected, focused ->
            baseRenderer.getListCellRendererComponent(list, value?.displayName ?: "", index, selected, focused)
        }

        connectButton.addActionListener { onConnectClick() }

        val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(JBLabel("进程"))
            add(processCombo)
        }
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            add(statusLabel)
            add(connectButton)
        }

        add(left, BorderLayout.CENTER)
        add(right, BorderLayout.EAST)

        refresh()
    }

    fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val processes = scanner.scanAll().filter { scanner.isSpringBootProcess(it) }
            ApplicationManager.getApplication().invokeLater {
                val selected = processCombo.selectedItem as? JvmProcessInfo
                processCombo.removeAllItems()
                processes.forEach { processCombo.addItem(it) }
                if (selected != null) {
                    processes.find { it.pid == selected.pid }?.let { processCombo.selectedItem = it }
                }
            }
        }
    }

    private fun onConnectClick() {
        val current = session
        if (current != null && current.state == State.CONNECTED) {
            current.disconnect()
            session = null
            onSessionChanged?.invoke(null)
            updateStatus(State.DISCONNECTED)
            connectButton.text = "连接"
            return
        }

        val info = processCombo.selectedItem as? JvmProcessInfo ?: return
        updateStatus(State.CONNECTING)
        connectButton.isEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            val s = ProcessSession(info.pid)
            val result = s.connect()
            ApplicationManager.getApplication().invokeLater {
                connectButton.isEnabled = true
                if (result.isSuccess) {
                    session = s
                    onSessionChanged?.invoke(s)
                    updateStatus(State.CONNECTED)
                    connectButton.text = "断开"
                } else {
                    updateStatus(State.FAILED)
                    connectButton.text = "连接"
                }
            }
        }
    }

    private fun updateStatus(state: State) {
        val (color, text) = when (state) {
            State.DISCONNECTED -> JBColor(Color(158, 158, 158), Color(117, 117, 117)) to "● 未连接"
            State.CONNECTING   -> JBColor(Color(21, 101, 192), Color(66, 165, 245))  to "● 连接中..."
            State.CONNECTED    -> JBColor(Color(46, 125, 50),  Color(76, 175, 80))   to "● 已连接"
            State.FAILED       -> JBColor(Color(198, 40, 40),  Color(244, 67, 54))   to "● 连接失败"
        }
        statusLabel.foreground = color
        statusLabel.text = text
    }
}
