package com.daverobins.projectfilesbrowser

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.content.ContentFactory

class ProjectFilesToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val rootDir = project.guessProjectDir()
        val component =
            if (rootDir == null) JBPanelWithEmptyText().withEmptyText("Project directory not found")
            else ProjectFilesPanel(project, rootDir, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(component, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
