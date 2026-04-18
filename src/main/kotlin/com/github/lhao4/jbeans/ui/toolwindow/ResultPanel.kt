package com.github.lhao4.jbeans.ui.toolwindow

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea

class ResultPanel : JPanel(BorderLayout()) {

    private val statusLabel = JLabel("").apply {
        border = JBUI.Borders.empty(2, 6)
        font = font.deriveFont(Font.PLAIN, 11f)
        isVisible = false
    }
    private val titleLabel = JLabel("Result").apply {
        border = JBUI.Borders.empty(4, 6)
        font = font.deriveFont(Font.BOLD)
    }
    private val resultArea = JTextArea().apply {
        font = Font("Monospaced", Font.PLAIN, 12)
        isEditable = false
        lineWrap = false
        text = ""
    }

    init {
        val header = JPanel(BorderLayout()).apply {
            add(titleLabel, BorderLayout.WEST)
            add(statusLabel, BorderLayout.EAST)
            border = JBUI.Borders.customLine(
                JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 0, 0, 1, 0
            )
        }
        add(header, BorderLayout.NORTH)
        add(JBScrollPane(resultArea), BorderLayout.CENTER)
    }

    fun showResult(text: String) {
        resultArea.text = text
        resultArea.caretPosition = 0
    }

    fun showStatus(success: Boolean, durationMs: Long, cancelled: Boolean) {
        val (text, color) = when {
            cancelled -> "◼ Cancelled" to JBColor(Color(117, 117, 117), Color(158, 158, 158))
            success   -> "✓ ${durationMs}ms" to JBColor(Color(46, 125, 50), Color(76, 175, 80))
            else      -> "✗ ${durationMs}ms" to JBColor(Color(198, 40, 40), Color(244, 67, 54))
        }
        statusLabel.text = text
        statusLabel.foreground = color
        statusLabel.isVisible = true
    }
}
