package com.github.lhao4.jbeans.ui.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.JPanel

class JBeansToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val invokePanel = InvokePanel(project)
        val resultPanel = ResultPanel()
        invokePanel.onResult = { resultPanel.showResult(it) }

        val methodSearch = MethodSearchPanel(project)
        methodSearch.onMethodSelected = { invokePanel.setMethod(it) }

        val rightSplit = OnePixelSplitter(true, 0.55f).apply {
            firstComponent = invokePanel
            secondComponent = resultPanel
        }

        val mainSplit = OnePixelSplitter(false, 0.35f).apply {
            firstComponent = methodSearch
            secondComponent = rightSplit
        }

        val root = JPanel(BorderLayout())
        root.add(ProcessSelectorPanel(project), BorderLayout.NORTH)
        root.add(mainSplit, BorderLayout.CENTER)

        val content = ContentFactory.getInstance().createContent(root, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
