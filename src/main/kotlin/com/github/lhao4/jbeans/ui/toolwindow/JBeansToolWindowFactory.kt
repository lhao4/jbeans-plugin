package com.github.lhao4.jbeans.ui.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JTabbedPane

class JBeansToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val root = JPanel(BorderLayout())
        root.add(ProcessSelectorPanel(project), BorderLayout.NORTH)
        root.add(buildTabs(project), BorderLayout.CENTER)

        val content = ContentFactory.getInstance().createContent(root, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun buildTabs(project: Project): JTabbedPane {
        val tabs = JTabbedPane(JTabbedPane.TOP)
        tabs.addTab("Beans", buildBeansTab(project))
        tabs.addTab("HTTP", buildHttpTab(project))
        return tabs
    }

    private fun buildBeansTab(project: Project): JPanel {
        val invokePanel = InvokePanel(project)
        val resultPanel = ResultPanel()
        invokePanel.onResult = { resultPanel.showResult(it) }
        invokePanel.onStatus = { success, durationMs, cancelled ->
            resultPanel.showStatus(success, durationMs, cancelled)
        }

        val methodSearch = MethodSearchPanel(project)
        methodSearch.onMethodSelected = { invokePanel.setMethod(it) }

        val historyPanel = HistoryPanel(project)
        historyPanel.onReplay = { invokePanel.setFromHistory(it) }

        val leftSplit = OnePixelSplitter(true, 0.55f).apply {
            firstComponent = methodSearch
            secondComponent = historyPanel
        }
        val rightSplit = OnePixelSplitter(true, 0.55f).apply {
            firstComponent = invokePanel
            secondComponent = resultPanel
        }
        return JPanel(BorderLayout()).apply {
            add(OnePixelSplitter(false, 0.35f).apply {
                firstComponent = leftSplit
                secondComponent = rightSplit
            })
        }
    }

    private fun buildHttpTab(project: Project): JPanel {
        val requestPanel = HttpRequestPanel(project)
        val routeSearch = HttpRouteSearchPanel(project)
        routeSearch.onRouteSelected = { requestPanel.setRoute(it) }

        return JPanel(BorderLayout()).apply {
            add(OnePixelSplitter(false, 0.35f).apply {
                firstComponent = routeSearch
                secondComponent = requestPanel
            })
        }
    }
}
