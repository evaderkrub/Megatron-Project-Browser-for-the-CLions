package com.daverobins.projectfilesbrowser

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.treeStructure.SimpleNode

/**
 * A user-defined virtual folder in folder view. Children are subfolders plus the
 * files that resolved to this folder during the root's visible-file walk.
 */
class VirtualFolderNode(
    private val project: Project,
    parent: SimpleNode,
    val folderPath: String,
    private val layout: FolderLayout,
    private val filesByFolder: Map<String, List<VirtualFile>>,
    private val engine: FilterEngine,
    private val rootPath: String,
) : SimpleNode(project, parent) {

    override fun getChildren(): Array<SimpleNode> {
        val subFolders: List<SimpleNode> = layout.childFolders(folderPath).map {
            VirtualFolderNode(project, this, it, layout, filesByFolder, engine, rootPath)
        }
        val files: List<SimpleNode> = (filesByFolder[folderPath.lowercase()] ?: emptyList())
            .sortedWith(compareBy({ it.name.lowercase() }, { it.path.lowercase() }))
            .map { FileNode(project, this, it, engine, rootPath, flatLeaf = true) }
        return (subFolders + files).toTypedArray()
    }

    override fun update(presentation: PresentationData) {
        presentation.presentableText = folderPath.substringAfterLast('/')
        presentation.setIcon(AllIcons.Nodes.Folder)
    }

    override fun getEqualityObjects(): Array<Any> = arrayOf(EQUALITY_KEY, folderPath.lowercase())

    companion object {
        private const val EQUALITY_KEY = "megatron.virtualFolder"
    }
}
