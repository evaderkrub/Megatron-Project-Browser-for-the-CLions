package com.daverobins.projectfilesbrowser

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.treeStructure.SimpleNode

/** A user-defined virtual folder in folder view; children are subfolders plus assigned files. */
class VirtualFolderNode(
    private val project: Project,
    parent: SimpleNode,
    val folderPath: String,
    private val store: FolderLayoutStore,
    private val engine: FilterEngine,
    private val rootDir: VirtualFile,
    private val rootPath: String,
) : SimpleNode(project, parent) {

    override fun getChildren(): Array<SimpleNode> {
        val layout = store.layout()
        val subFolders: List<SimpleNode> = layout.childFolders(folderPath).map {
            VirtualFolderNode(project, this, it, store, engine, rootDir, rootPath)
        }
        val files: List<SimpleNode> = layout.filesIn(folderPath)
            .mapNotNull { resolveRelativePath(rootDir, it) }
            .filter { it.isValid && !it.isDirectory && engine.isFileVisible(it) }
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

        /** [VirtualFile.findFileByRelativePath] with a case-insensitive fallback per segment. */
        fun resolveRelativePath(root: VirtualFile, relativePath: String): VirtualFile? {
            root.findFileByRelativePath(relativePath)?.let { return it }
            var current: VirtualFile = root
            for (segment in relativePath.split('/')) {
                if (segment.isEmpty()) continue
                current = current.children?.firstOrNull { it.name.equals(segment, ignoreCase = true) }
                    ?: return null
            }
            return current
        }
    }
}
