package com.github.lhao4.jbeans.ui.toolwindow

import com.github.lhao4.jbeans.history.HistoryRecord
import com.github.lhao4.jbeans.psi.MethodMeta
import com.github.lhao4.jbeans.psi.ParamGenerator
import com.github.lhao4.jbeans.service.HistoryService
import com.github.lhao4.jbeans.service.InvokeOrchestrator
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
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
    private val invokeButton = JButton("Invoke").apply { isEnabled = false }
    private val cancelButton = JButton("Cancel").apply { isVisible = false }
    private val jsonErrorLabel = JLabel("").apply {
        foreground = JBColor(Color(198, 40, 40), Color(244, 67, 54))
        border = JBUI.Borders.empty(2, 6)
        isVisible = false
    }

    var onResult: ((String) -> Unit)? = null
    var onStatus: ((success: Boolean, durationMs: Long, cancelled: Boolean) -> Unit)? = null

    private var currentMeta: MethodMeta? = null
    @Volatile private var cancelled = false

    init {
        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(invokeButton)
            add(Box.createHorizontalStrut(4))
            add(cancelButton)
        }
        val header = JPanel(BorderLayout()).apply {
            add(methodLabel, BorderLayout.CENTER)
            add(buttonPanel, BorderLayout.EAST)
            border = JBUI.Borders.customLine(
                JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 0, 0, 1, 0
            )
        }
        add(header, BorderLayout.NORTH)
        add(JBScrollPane(paramsEditor), BorderLayout.CENTER)
        add(jsonErrorLabel, BorderLayout.SOUTH)

        invokeButton.addActionListener { doInvoke() }
        cancelButton.addActionListener { doCancel() }
    }

    fun setMethod(meta: MethodMeta) {
        currentMeta = meta
        methodLabel.text = meta.signature
        invokeButton.isEnabled = true
        val params = meta.paramNames.zip(meta.paramTypes)
        paramsEditor.text = ParamGenerator.generate(params)
        paramsEditor.caretPosition = 0
        clearJsonError()
    }

    fun setFromHistory(record: HistoryRecord) {
        currentMeta = record.toMethodMeta()
        methodLabel.text = record.signature
        invokeButton.isEnabled = true
        paramsEditor.text = record.argsJson
        paramsEditor.caretPosition = 0
        clearJsonError()
    }

    private fun doInvoke() {
        val meta = currentMeta ?: return
        val json = paramsEditor.text.trim()

        if (!validateJson(json)) return

        cancelled = false
        setInvoking(true)
        onResult?.invoke("Invoking ${meta.signature}…")

        ApplicationManager.getApplication().executeOnPooledThread {
            val start = System.currentTimeMillis()
            val result = orchestrator.invoke(meta, json)
            val durationMs = System.currentTimeMillis() - start
            val wasCancelled = cancelled

            val resultText = when {
                wasCancelled -> "Cancelled."
                result.isSuccess -> result.getOrThrow()
                else -> classifyError(result.exceptionOrNull())
            }

            if (!wasCancelled) {
                historyService.add(
                    HistoryRecord.from(meta, json, resultText, result.isSuccess, durationMs)
                )
            }

            SwingUtilities.invokeLater {
                setInvoking(false)
                onResult?.invoke(resultText)
                onStatus?.invoke(result.isSuccess && !wasCancelled, durationMs, wasCancelled)
            }
        }
    }

    private fun doCancel() {
        cancelled = true
        orchestrator.cancelInvoke()
        setInvoking(false)
        onResult?.invoke("Cancelled.")
        onStatus?.invoke(false, 0L, true)
    }

    private fun setInvoking(invoking: Boolean) {
        invokeButton.isVisible = !invoking
        cancelButton.isVisible = invoking
    }

    private fun validateJson(json: String): Boolean {
        return try {
            JsonParser.parseString(json)
            clearJsonError()
            true
        } catch (e: JsonSyntaxException) {
            showJsonError("Invalid JSON: ${e.message?.substringBefore(" at ")?.take(80)}")
            false
        }
    }

    private fun showJsonError(msg: String) {
        jsonErrorLabel.text = msg
        jsonErrorLabel.isVisible = true
    }

    private fun clearJsonError() {
        jsonErrorLabel.isVisible = false
    }

    private fun classifyError(ex: Throwable?): String {
        val msg = ex?.message ?: "Unknown error"
        return when {
            msg.contains("NoSuchBeanDefinitionException") ->
                "Error: Bean not found — this class is not managed by the Spring container.\n\nDetails: $msg"
            msg.contains("ClassNotFoundException") ->
                "Error: Class not found on the target JVM classpath.\n\nDetails: $msg"
            msg.contains("NoSuchMethodException") || msg.contains("Method not found") ->
                "Error: Method not found — check parameter types.\n\nDetails: $msg"
            msg.contains("JsonParseException") || msg.contains("JsonMappingException") ||
            msg.contains("MismatchedInputException") ->
                "Error: Argument type mismatch — check your JSON parameter types.\n\nDetails: $msg"
            msg.contains("No active process") || msg.contains("Session lost") ->
                "Error: Process disconnected — please reconnect.\n\nDetails: $msg"
            else -> "Error: $msg"
        }
    }
}
