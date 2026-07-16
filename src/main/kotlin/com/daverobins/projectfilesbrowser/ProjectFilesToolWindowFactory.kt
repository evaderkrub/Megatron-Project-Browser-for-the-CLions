package com.daverobins.projectfilesbrowser

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.content.ContentFactory

class ProjectFilesToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val rootDir = projectRootDir(project)
        val component =
            if (rootDir == null) JBPanelWithEmptyText().withEmptyText("Project directory not found")
            else ProjectFilesPanel(project, rootDir, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(component, "", false)
        toolWindow.contentManager.addContent(content)
    }

    /**
     * The directory the project was opened from. `guessProjectDir()` is only a
     * heuristic — with multiple CMake content roots it can return an included
     * library's root instead of the project itself — so prefer `basePath`.
     */
    private fun projectRootDir(project: Project): VirtualFile? {
        val fromBasePath = project.basePath
            ?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            ?.takeIf { it.isValid && it.isDirectory }
        return fromBasePath ?: project.guessProjectDir()
    }
}
