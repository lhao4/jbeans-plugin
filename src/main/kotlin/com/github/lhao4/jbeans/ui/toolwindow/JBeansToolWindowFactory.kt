package com.github.lhao4.jbeans.ui.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.JPanel

class JBeansToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val root = JPanel(BorderLayout())
        root.add(ProcessSelectorPanel(project), BorderLayout.NORTH)

        val content = ContentFactory.getInstance().createContent(root, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
