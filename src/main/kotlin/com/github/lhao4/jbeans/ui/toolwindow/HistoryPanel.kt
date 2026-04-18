package com.github.lhao4.jbeans.ui.toolwindow

import com.github.lhao4.jbeans.history.HistoryRecord
import com.github.lhao4.jbeans.service.HistoryService
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.*

class HistoryPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val historyService get() = project.getService(HistoryService::class.java)

    var onReplay: ((HistoryRecord) -> Unit)? = null

    private val listModel = DefaultListModel<HistoryRecord>()
    private val list = JBList(listModel).apply {
        cellRenderer = HistoryCellRenderer()
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    init {
        val titleLabel = JLabel("History").apply {
            border = JBUI.Borders.empty(4, 6)
            font = font.deriveFont(Font.BOLD)
        }
        val header = JPanel(BorderLayout()).apply {
            add(titleLabel, BorderLayout.CENTER)
            border = JBUI.Borders.customLine(
                JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 0, 0, 1, 0
            )
        }
        add(header, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)

        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    list.selectedValue?.let { onReplay?.invoke(it) }
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    showContextMenu(e)
                }
            }
        })

        historyService.addListener { refresh() }
        refresh()
    }

    private fun refresh() {
        val all = historyService.getAll()
        SwingUtilities.invokeLater {
            listModel.clear()
            all.forEach { listModel.addElement(it) }
        }
    }

    private fun showContextMenu(e: MouseEvent) {
        val idx = list.locationToIndex(e.point)
        if (idx < 0) return
        list.selectedIndex = idx
        val record = listModel.getElementAt(idx) ?: return
        val menu = JPopupMenu()
        val starLabel = if (record.starred) "Unstar" else "Star"
        menu.add(JMenuItem(starLabel)).addActionListener { historyService.toggleStar(record.id) }
        menu.show(list, e.x, e.y)
    }

    private inner class HistoryCellRenderer : ListCellRenderer<HistoryRecord> {
        private val fmt = SimpleDateFormat("HH:mm:ss")

        override fun getListCellRendererComponent(
            list: JList<out HistoryRecord>,
            value: HistoryRecord,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val star = if (value.starred) "★ " else ""
            val status = if (value.success) "<font color='green'>✓</font>" else "<font color='red'>✗</font>"
            val time = fmt.format(Date(value.timestamp))
            val text = "<html>${star}<b>${value.signature}</b>&nbsp;&nbsp;$status ${value.durationMs}ms&nbsp;&nbsp;$time</html>"
            return JLabel(text).apply {
                isOpaque = true
                border = JBUI.Borders.empty(3, 6)
                background = if (isSelected) list.selectionBackground else list.background
                foreground = if (isSelected) list.selectionForeground else list.foreground
            }
        }
    }
}
