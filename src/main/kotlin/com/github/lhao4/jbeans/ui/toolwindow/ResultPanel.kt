package com.github.lhao4.jbeans.ui.toolwindow

import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea

class ResultPanel : JPanel(BorderLayout()) {

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
            border = JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 0, 0, 1, 0)
        }
        add(header, BorderLayout.NORTH)
        add(JBScrollPane(resultArea), BorderLayout.CENTER)
    }

    fun showResult(text: String) {
        resultArea.text = text
        resultArea.caretPosition = 0
    }
}
