package com.github.lhao4.jbeans.ui.toolwindow

import com.github.lhao4.jbeans.psi.MethodMeta
import com.github.lhao4.jbeans.service.MethodSearchService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import java.awt.BorderLayout
import javax.swing.*
import javax.swing.event.DocumentEvent

class MethodSearchPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val searchService get() = project.getService(MethodSearchService::class.java)

    private val searchField = SearchTextField(true)
    private val resultModel = DefaultListModel<MethodMeta>()
    private val resultList = JBList(resultModel)
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    var onMethodSelected: ((MethodMeta) -> Unit)? = null

    init {
        searchField.textEditor.emptyText.text = "搜索类名 / 方法名..."
        resultList.cellRenderer = MethodCellRenderer()
        resultList.selectionMode = ListSelectionModel.SINGLE_SELECTION

        add(searchField, BorderLayout.NORTH)
        add(JBScrollPane(resultList), BorderLayout.CENTER)

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = scheduleSearch(searchField.text)
        })

        resultList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                resultList.selectedValue?.let { onMethodSelected?.invoke(it) }
            }
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            searchService.refreshIndex()
        }
    }

    private fun scheduleSearch(query: String) {
        alarm.cancelAllRequests()
        alarm.addRequest({
            val results = searchService.search(query)
            SwingUtilities.invokeLater {
                resultModel.clear()
                results.forEach { resultModel.addElement(it) }
            }
        }, 300)
    }

    override fun dispose() {}

    private class MethodCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int, isSelected: Boolean, hasFocus: Boolean
        ): java.awt.Component {
            val label = super.getListCellRendererComponent(list, value, index, isSelected, hasFocus) as JLabel
            if (value is MethodMeta) {
                val ann = if (value.beanAnnotations.isNotEmpty()) " [${value.beanAnnotations.joinToString(",")}]" else ""
                label.text = "<html><b>${value.methodName}</b>&nbsp;<font color='gray'>${value.className}$ann</font></html>"
                label.toolTipText = value.signature
            }
            return label
        }
    }
}
