package com.github.lhao4.jbeans.ui.toolwindow

import com.github.lhao4.jbeans.http.HttpClient
import com.github.lhao4.jbeans.http.RouteInfo
import com.github.lhao4.jbeans.psi.ParamGenerator
import com.github.lhao4.jbeans.service.ProcessManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*

class HttpRequestPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val processManager get() = project.getService(ProcessManager::class.java)

    // URL bar
    private val methodLabel = JLabel("GET").apply {
        font = font.deriveFont(Font.BOLD)
        border = JBUI.Borders.empty(0, 6)
        preferredSize = Dimension(60, preferredSize.height)
        horizontalAlignment = SwingConstants.CENTER
    }
    private val urlField = JBTextField("http://localhost:8080").apply { columns = 30 }
    private val sendButton = JButton("Send")
    private val cancelButton = JButton("Cancel").apply { isVisible = false }

    // Path variables
    private val pathVarPanel = JPanel(GridBagLayout())
    private val pathVarFields = mutableMapOf<String, JTextField>()

    // Query params
    private val queryParamPanel = JPanel(GridBagLayout())
    private val queryParamFields = mutableMapOf<String, JTextField>()

    // Request body
    private val bodyEditor = JTextArea(6, 40).apply {
        font = Font("Monospaced", Font.PLAIN, 12)
        lineWrap = false
        tabSize = 2
        text = ""
    }

    // Response
    private val statusLabel = JLabel("").apply {
        border = JBUI.Borders.empty(2, 6)
        font = font.deriveFont(Font.BOLD, 11f)
        isVisible = false
    }
    private val responseArea = JTextArea().apply {
        font = Font("Monospaced", Font.PLAIN, 12)
        isEditable = false
        lineWrap = false
        text = ""
    }

    private var currentRoute: RouteInfo? = null
    @Volatile private var cancelled = false

    init {
        // URL bar
        val urlBar = JPanel(BorderLayout(4, 0)).apply {
            border = JBUI.Borders.empty(6)
            add(methodLabel, BorderLayout.WEST)
            add(urlField, BorderLayout.CENTER)
            val btns = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(sendButton)
                add(cancelButton)
            }
            add(btns, BorderLayout.EAST)
        }

        // Params panel (path vars + query params + body)
        val paramsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(0, 6, 6, 6)
        }

        val pathVarSection = labeledSection("Path Variables", pathVarPanel)
        val querySection = labeledSection("Query Params", queryParamPanel)
        val bodySection = labeledSection("Body (JSON)", JBScrollPane(bodyEditor).apply {
            preferredSize = Dimension(Int.MAX_VALUE, 120)
            maximumSize = Dimension(Int.MAX_VALUE, 120)
        })

        paramsPanel.add(pathVarSection)
        paramsPanel.add(querySection)
        paramsPanel.add(bodySection)

        // Response panel
        val responseHeader = JPanel(BorderLayout()).apply {
            add(JLabel("Response").apply {
                border = JBUI.Borders.empty(4, 6)
                font = font.deriveFont(Font.BOLD)
            }, BorderLayout.WEST)
            add(statusLabel, BorderLayout.EAST)
            border = JBUI.Borders.customLine(
                JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 0, 0, 1, 0
            )
        }
        val responsePanel = JPanel(BorderLayout()).apply {
            add(responseHeader, BorderLayout.NORTH)
            add(JBScrollPane(responseArea), BorderLayout.CENTER)
        }

        val topPanel = JPanel(BorderLayout()).apply {
            add(urlBar, BorderLayout.NORTH)
            add(JBScrollPane(paramsPanel).apply {
                border = null
                preferredSize = Dimension(Int.MAX_VALUE, 240)
            }, BorderLayout.CENTER)
        }

        val mainSplit = com.intellij.ui.OnePixelSplitter(true, 0.5f).apply {
            firstComponent = topPanel
            secondComponent = responsePanel
        }
        add(mainSplit, BorderLayout.CENTER)

        sendButton.addActionListener { doSend() }
        cancelButton.addActionListener { doCancel() }
    }

    fun setRoute(route: RouteInfo) {
        currentRoute = route
        methodLabel.text = route.httpMethod

        // Update URL with detected port
        val port = processManager.currentSession()?.getProperty("server.port")?.toIntOrNull() ?: 8080
        urlField.text = "http://localhost:$port${route.path}"

        // Path variable fields
        pathVarPanel.removeAll()
        pathVarFields.clear()
        route.pathVariables.forEachIndexed { i, name ->
            val field = JTextField(15)
            pathVarFields[name] = field
            addKvRow(pathVarPanel, name, field, i)
        }

        // Query param fields
        queryParamPanel.removeAll()
        queryParamFields.clear()
        route.queryParams.forEachIndexed { i, param ->
            val field = JTextField(15)
            queryParamFields[param.name] = field
            val label = if (param.required) "${param.name} *" else param.name
            addKvRow(queryParamPanel, label, field, i)
        }

        // Body editor
        bodyEditor.text = route.requestBody
            ?.let { ParamGenerator.generate(listOf("body" to it)).let { json ->
                // unwrap the {"body": ...} wrapper for single body param
                if (json.startsWith("{\"body\":")) {
                    val inner = json.removePrefix("{\"body\":").trimEnd().dropLast(1).trim()
                    if (inner == "{}") "{}" else inner
                } else json
            }}
            ?: ""

        // Reset response
        responseArea.text = ""
        statusLabel.isVisible = false

        revalidate()
        repaint()
    }

    private fun doSend() {
        val route = currentRoute ?: return
        cancelled = false

        val pathVarValues = pathVarFields.mapValues { it.value.text.trim() }
        val queryValues = queryParamFields.entries
            .filter { it.value.text.isNotBlank() }
            .associate { it.key to it.value.text.trim() }
        val body = bodyEditor.text.trim().takeIf { it.isNotBlank() && it != "{}" }
        val url = HttpClient.buildUrl(
            urlField.text.trimEnd('/'),
            route.path,
            pathVarValues,
            queryValues
        )

        setSending(true)
        responseArea.text = "Sending…"

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = HttpClient.send(route.httpMethod, url, body)
            SwingUtilities.invokeLater {
                setSending(false)
                if (cancelled) {
                    responseArea.text = "Cancelled."
                    showStatus(false, 0, true)
                    return@invokeLater
                }
                result.onSuccess { resp ->
                    responseArea.text = resp.body
                    responseArea.caretPosition = 0
                    showStatus(resp.statusCode in 200..299, resp.durationMs, false, resp.statusCode)
                }.onFailure { ex ->
                    responseArea.text = "Error: ${ex.message}"
                    showStatus(false, 0, false)
                }
            }
        }
    }

    private fun doCancel() {
        cancelled = true
        HttpClient.cancel()
        setSending(false)
        responseArea.text = "Cancelled."
        showStatus(false, 0, true)
    }

    private fun setSending(sending: Boolean) {
        sendButton.isVisible = !sending
        cancelButton.isVisible = sending
    }

    private fun showStatus(success: Boolean, durationMs: Long, cancelled: Boolean, code: Int? = null) {
        val (text, color) = when {
            cancelled -> "◼ Cancelled" to JBColor(Color(117, 117, 117), Color(158, 158, 158))
            success   -> "✓ ${code ?: ""} ${durationMs}ms" to JBColor(Color(46, 125, 50), Color(76, 175, 80))
            else      -> "✗ ${code ?: "Error"} ${if (durationMs > 0) "${durationMs}ms" else ""}" to
                         JBColor(Color(198, 40, 40), Color(244, 67, 54))
        }
        statusLabel.text = text
        statusLabel.foreground = color
        statusLabel.isVisible = true
    }

    private fun addKvRow(panel: JPanel, label: String, field: JTextField, row: Int) {
        val gbc = GridBagConstraints()
        gbc.gridy = row
        gbc.insets = Insets(2, 0, 2, 6)
        gbc.anchor = GridBagConstraints.WEST

        gbc.gridx = 0
        gbc.fill = GridBagConstraints.NONE
        gbc.weightx = 0.0
        panel.add(JLabel("$label:"), gbc)

        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.weightx = 1.0
        panel.add(field, gbc)
    }

    private fun labeledSection(title: String, content: JComponent): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            val header = JLabel(title).apply {
                font = font.deriveFont(Font.BOLD, 11f)
                foreground = JBUI.CurrentTheme.Label.disabledForeground()
                border = JBUI.Borders.empty(6, 0, 2, 0)
            }
            add(header, BorderLayout.NORTH)
            add(content, BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, maximumSize.height)
        }
    }
}
