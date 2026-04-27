package com.jbeans.debug.plugin.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBSplitter
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import javax.swing.JPanel

class DebugToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val mainPanel = JPanel(BorderLayout())

        // Zone 1 - 顶部连接面板
        val connectionPanel = ConnectionPanel(project)
        mainPanel.add(connectionPanel, BorderLayout.NORTH)

        // Zone 2 - 左侧服务树 + 中间参数编辑器
        val treePanel = InterfaceTreePanel(project)
        val paramEditor = ParameterEditorPanel(project)

        // Zone 3 - 底部结果面板
        val resultPanel = ResultPanel(project)
        resultPanel.setParamEditorProvider { paramEditor.getEditorText() }
        paramEditor.setResultPanel(resultPanel)

        // 水平分割：服务树 | 参数编辑器
        val horizontalSplitter = JBSplitter(false, 0.22f).apply {
            firstComponent = treePanel
            secondComponent = paramEditor
        }

        // 垂直分割：(服务树|参数) 上方，结果 下方
        val verticalSplitter = JBSplitter(true, 0.65f).apply {
            firstComponent = horizontalSplitter
            secondComponent = resultPanel
        }

        mainPanel.add(verticalSplitter, BorderLayout.CENTER)

        val content = ContentFactory.getInstance().createContent(mainPanel, "", false)

        // 将面板注册到 content 的 Disposable 链，ToolWindow 关闭时自动释放 MessageBus 连接
        Disposer.register(content, connectionPanel)
        Disposer.register(content, treePanel)
        Disposer.register(content, paramEditor)
        Disposer.register(content, resultPanel)

        toolWindow.contentManager.addContent(content)
    }
}