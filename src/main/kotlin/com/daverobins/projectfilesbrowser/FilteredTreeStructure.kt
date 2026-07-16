package com.daverobins.projectfilesbrowser

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.treeStructure.SimpleNode
import com.intellij.ui.treeStructure.SimpleTreeStructure

/** Tree of project files filtered through [FilterEngine], rooted at [rootDir]. */
class FilteredTreeStructure(
    project: Project,
    rootDir: VirtualFile,
    engine: FilterEngine,
) : SimpleTreeStructure() {
    private val root = FileNode(project, null, rootDir, engine, rootDir.path)
    override fun getRootElement(): Any = root
}

class FileNode(
    private val project: Project,
    parent: FileNode?,
    val file: VirtualFile,
    private val engine: FilterEngine,
    private val rootPath: String,
    private val flatLeaf: Boolean = false,
) : SimpleNode(project, parent) {

    private val isRootNode = parent == null

    override fun getChildren(): Array<SimpleNode> {
        if (!file.isDirectory) return NO_CHILDREN
        if (isRootNode && MegatronFilterState.getInstance(project).isFlatMode()) {
            return flatChildren()
        }
        val visible = (file.children ?: return NO_CHILDREN)
            .filter { it.isValid && isVisible(it) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        if (visible.isEmpty()) return NO_CHILDREN
        return visible.map { FileNode(project, this, it, engine, rootPath) }.toTypedArray()
    }

    override fun update(presentation: PresentationData) {
        presentation.presentableText = file.name
        if (flatLeaf) {
            val parentRel = relativePath(file).substringBeforeLast('/', "")
            if (parentRel.isNotEmpty()) presentation.locationString = parentRel
        }
        presentation.setIcon(
            if (file.isDirectory) AllIcons.Nodes.Folder
            else file.fileType.icon ?: AllIcons.FileTypes.Any_type
        )
    }

    override fun getEqualityObjects(): Array<Any> = arrayOf(file)

    private fun flatChildren(): Array<SimpleNode> {
        val files = ArrayList<VirtualFile>()
        collectVisibleFiles(file, files)
        files.sortWith(compareBy({ it.name.lowercase() }, { relativePath(it).lowercase() }))
        return files.map { FileNode(project, this, it, engine, rootPath, flatLeaf = true) }.toTypedArray()
    }

    /** Depth-first collection of visible files; excluded directories are not entered at all. */
    private fun collectVisibleFiles(dir: VirtualFile, out: MutableList<VirtualFile>) {
        for (child in dir.children ?: return) {
            if (!child.isValid) continue
            if (child.isDirectory) {
                if (FileFilter.includeDirectory(child.name)) collectVisibleFiles(child, out)
            } else if (engine.isFileVisible(relativePath(child), child.name)) {
                out.add(child)
            }
        }
    }

    private fun isVisible(candidate: VirtualFile): Boolean =
        if (candidate.isDirectory) {
            FileFilter.includeDirectory(candidate.name) && hasVisibleContent(candidate)
        } else {
            engine.isFileVisible(relativePath(candidate), candidate.name)
        }

    /** A directory is shown only if filtering leaves something inside it. */
    private fun hasVisibleContent(dir: VirtualFile): Boolean =
        (dir.children ?: return false).any { it.isValid && isVisible(it) }

    private fun relativePath(candidate: VirtualFile): String =
        candidate.path.removePrefix("$rootPath/")
}
