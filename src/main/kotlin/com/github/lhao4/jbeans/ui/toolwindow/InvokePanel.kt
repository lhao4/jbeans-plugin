package com.github.lhao4.jbeans.ui.toolwindow

import com.github.lhao4.jbeans.history.HistoryRecord
import com.github.lhao4.jbeans.psi.MethodMeta
import com.github.lhao4.jbeans.psi.ParamGenerator
import com.github.lhao4.jbeans.service.HistoryService
import com.github.lhao4.jbeans.service.InvokeOrchestrator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.*

class InvokePanel(private val project: Project) : JPanel(BorderLayout()) {

    private val orchestrator get() = project.getService(InvokeOrchestrator::class.java)
    private val historyService get() = project.getService(HistoryService::class.java)

    private val methodLabel = JLabel("← Select a method").apply {
        border = JBUI.Borders.empty(4, 6)
        font = font.deriveFont(Font.BOLD)
    }
    private val paramsEditor = JTextArea(8, 40).apply {
        font = Font("Monospaced", Font.PLAIN, 12)
        lineWrap = false
        tabSize = 2
        text = "{}"
    }
    private val invokeButton = JButton("Invoke").apply {
        isEnabled = false
    }

    var onResult: ((String) -> Unit)? = null

    private var currentMeta: MethodMeta? = null

    init {
        val header = JPanel(BorderLayout()).apply {
            add(methodLabel, BorderLayout.CENTER)
            add(invokeButton, BorderLayout.EAST)
            border = JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 0, 0, 1, 0)
        }
        add(header, BorderLayout.NORTH)
        add(JBScrollPane(paramsEditor), BorderLayout.CENTER)

        invokeButton.addActionListener { doInvoke() }
    }

    fun setMethod(meta: MethodMeta) {
        currentMeta = meta
        methodLabel.text = meta.signature
        invokeButton.isEnabled = true
        val params = meta.paramNames.zip(meta.paramTypes)
        paramsEditor.text = ParamGenerator.generate(params)
        paramsEditor.caretPosition = 0
    }

    fun setFromHistory(record: HistoryRecord) {
        currentMeta = record.toMethodMeta()
        methodLabel.text = record.signature
        invokeButton.isEnabled = true
        paramsEditor.text = record.argsJson
        paramsEditor.caretPosition = 0
    }

    private fun doInvoke() {
        val meta = currentMeta ?: return
        val json = paramsEditor.text.trim()
        invokeButton.isEnabled = false
        invokeButton.text = "Invoking…"
        onResult?.invoke("Invoking ${meta.signature}…")

        ApplicationManager.getApplication().executeOnPooledThread {
            val start = System.currentTimeMillis()
            val result = orchestrator.invoke(meta, json)
            val durationMs = System.currentTimeMillis() - start
            val resultText = result.getOrElse { "Error: ${it.message}" }
            historyService.add(
                HistoryRecord.from(meta, json, resultText, result.isSuccess, durationMs)
            )
            SwingUtilities.invokeLater {
                invokeButton.isEnabled = true
                invokeButton.text = "Invoke"
                onResult?.invoke(resultText)
            }
        }
    }
}
