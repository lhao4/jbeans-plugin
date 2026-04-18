package com.github.lhao4.jbeans.ui.toolwindow

import com.github.lhao4.jbeans.http.HttpTestService
import com.github.lhao4.jbeans.http.RouteInfo
import com.intellij.openapi.project.Project
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

class HttpRouteSearchPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val httpService get() = project.getService(HttpTestService::class.java)

    var onRouteSelected: ((RouteInfo) -> Unit)? = null

    private val searchField = SearchTextField(false)
    private val listModel = DefaultListModel<RouteInfo>()
    private val resultList = JBList(listModel).apply {
        cellRenderer = RouteCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }
    private val statusLabel = JLabel("").apply {
        border = JBUI.Borders.empty(2, 6)
        foreground = JBUI.CurrentTheme.Label.disabledForeground()
    }

    init {
        val refreshButton = JButton("↻").apply {
            toolTipText = "Scan routes"
            isBorderPainted = false
            isContentAreaFilled = false
        }
        val topPanel = JPanel(BorderLayout(4, 0)).apply {
            border = JBUI.Borders.empty(4, 6)
            add(searchField, BorderLayout.CENTER)
            add(refreshButton, BorderLayout.EAST)
        }
        add(topPanel, BorderLayout.NORTH)
        add(JBScrollPane(resultList), BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)

        searchField.addKeyboardListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent) = updateList()
        })
        resultList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) resultList.selectedValue?.let { onRouteSelected?.invoke(it) }
            }
        })
        resultList.addListSelectionListener {
            if (!it.valueIsAdjusting) resultList.selectedValue?.let { r -> onRouteSelected?.invoke(r) }
        }
        refreshButton.addActionListener { doRefresh() }

        httpService.addListener { updateList() }
        doRefresh()
    }

    private fun doRefresh() {
        statusLabel.text = "Scanning…"
        httpService.refresh()
    }

    private fun updateList() {
        val results = httpService.search(searchField.text.trim())
        listModel.clear()
        results.forEach { listModel.addElement(it) }
        val total = results.size
        statusLabel.text = if (httpService.isScanning()) "Scanning…" else "$total route${if (total != 1) "s" else ""}"
    }

    private inner class RouteCellRenderer : ListCellRenderer<RouteInfo> {
        override fun getListCellRendererComponent(
            list: JList<out RouteInfo>, value: RouteInfo,
            index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val methodColor = when (value.httpMethod) {
                "GET" -> "#4CAF50"
                "POST" -> "#2196F3"
                "PUT" -> "#FF9800"
                "DELETE" -> "#F44336"
                "PATCH" -> "#9C27B0"
                else -> "#607D8B"
            }
            val text = "<html><b><font color='$methodColor'>${value.httpMethod}</font></b>&nbsp;" +
                    "${value.path}&nbsp;&nbsp;<font color='gray'>${value.className}</font></html>"
            return JLabel(text).apply {
                isOpaque = true
                border = JBUI.Borders.empty(3, 6)
                background = if (isSelected) list.selectionBackground else list.background
                foreground = if (isSelected) list.selectionForeground else list.foreground
            }
        }
    }
}
