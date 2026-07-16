package com.daverobins.projectfilesbrowser

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.treeStructure.SimpleNode
import com.intellij.ui.treeStructure.SimpleTreeStructure

/** Tree of project files filtered through [FileFilter], rooted at [rootDir]. */
class FilteredTreeStructure(project: Project, rootDir: VirtualFile) : SimpleTreeStructure() {
    private val root = FileNode(project, null, rootDir)
    override fun getRootElement(): Any = root
}

class FileNode(
    private val project: Project,
    parent: FileNode?,
    val file: VirtualFile,
) : SimpleNode(project, parent) {

    override fun getChildren(): Array<SimpleNode> {
        if (!file.isDirectory) return NO_CHILDREN
        val visible = (file.children ?: return NO_CHILDREN)
            .filter { it.isValid && isVisible(it) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        if (visible.isEmpty()) return NO_CHILDREN
        return visible.map { FileNode(project, this, it) }.toTypedArray()
    }

    override fun update(presentation: PresentationData) {
        presentation.presentableText = file.name
        presentation.setIcon(
            if (file.isDirectory) AllIcons.Nodes.Folder
            else file.fileType.icon ?: AllIcons.FileTypes.Any_type
        )
    }

    override fun getEqualityObjects(): Array<Any> = arrayOf(file)

    companion object {
        private fun isVisible(file: VirtualFile): Boolean =
            if (file.isDirectory) FileFilter.includeDirectory(file.name) && hasVisibleContent(file)
            else FileFilter.includeFile(file.name)

        /** A directory is shown only if filtering leaves something inside it. */
        private fun hasVisibleContent(dir: VirtualFile): Boolean =
            (dir.children ?: return false).any { it.isValid && isVisible(it) }
    }
}
