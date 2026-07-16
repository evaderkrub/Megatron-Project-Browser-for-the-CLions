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
    store: FolderLayoutStore? = null,
    scanner: BookmarkScanner? = null,
    sets: ConfigSetManager? = null,
) : SimpleTreeStructure() {
    private val root =
        FileNode(project, null, rootDir, engine, rootDir.path, store = store, scanner = scanner, sets = sets)
    override fun getRootElement(): Any = root
}

class FileNode(
    private val project: Project,
    parent: SimpleNode?,
    val file: VirtualFile,
    private val engine: FilterEngine,
    private val rootPath: String,
    private val flatLeaf: Boolean = false,
    private val store: FolderLayoutStore? = null,
    private val displayName: String? = null,
    private val excludedFiles: Set<String> = emptySet(),
    private val scanner: BookmarkScanner? = null,
    private val sets: ConfigSetManager? = null,
) : SimpleNode(project, parent) {

    private val isRootNode = parent == null

    /** True for the pinned `<Unassigned>` bucket shown in folder view. */
    val isUnassignedBucket: Boolean get() = displayName == UNASSIGNED_LABEL

    override fun getChildren(): Array<SimpleNode> {
        if (!file.isDirectory) return NO_CHILDREN
        if (isRootNode) {
            val main = when (MegatronFilterState.getInstance(project).getViewMode()) {
                ViewMode.FLAT -> flatChildren()
                ViewMode.FOLDERS -> folderChildren()
                ViewMode.TREE -> directoryChildren()
            }
            return main + bookmarksNode()
        }
        return directoryChildren()
    }

    /** The plain filtered directory listing (tree view, and every non-root directory). */
    private fun directoryChildren(): Array<SimpleNode> {
        val visible = (file.children ?: return NO_CHILDREN)
            .filter { it.isValid && isVisible(it) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        if (visible.isEmpty()) return NO_CHILDREN
        return visible
            .map { FileNode(project, this, it, engine, rootPath, excludedFiles = excludedFiles) }
            .toTypedArray()
    }

    /** Root only: the pinned Bookmarks group, absent when nothing survives. */
    private fun bookmarksNode(): Array<SimpleNode> {
        val activeScanner = scanner ?: return NO_CHILDREN
        val activeSet = sets?.effectiveSet() ?: return NO_CHILDREN
        val visible = ArrayList<VirtualFile>()
        collectVisibleFiles(file, visible)
        val found = visible.flatMap { candidate ->
            activeScanner.bookmarksIn(candidate)
                .filter { it.visibleInSet(activeSet) }
                .map { candidate to it }
        }
        if (found.isEmpty()) return NO_CHILDREN
        return arrayOf<SimpleNode>(BookmarksRootNode(project, this, found, rootPath))
    }

    override fun update(presentation: PresentationData) {
        presentation.presentableText = displayName ?: file.name
        if (flatLeaf) {
            val parentRel = relativePath(file).substringBeforeLast('/', "")
            if (parentRel.isNotEmpty()) presentation.locationString = parentRel
        }
        presentation.setIcon(
            if (file.isDirectory) AllIcons.Nodes.Folder
            else file.fileType.icon ?: AllIcons.FileTypes.Any_type
        )
    }

    override fun getEqualityObjects(): Array<Any> =
        if (displayName != null) arrayOf(file, displayName) else arrayOf(file)

    /** Folder view root: resolve rules over the visible-file walk, then group. */
    private fun folderChildren(): Array<SimpleNode> {
        val activeStore = store
        val layout = activeStore?.layout() ?: FolderLayout()
        val visible = ArrayList<VirtualFile>()
        collectVisibleFiles(file, visible)
        val filesByFolder = HashMap<String, MutableList<VirtualFile>>() // folder.lowercase() -> files
        val assigned = HashSet<String>() // lowercase relative paths
        for (candidate in visible) {
            val rel = relativePath(candidate)
            val folder = layout.folderFor(rel) ?: continue
            filesByFolder.getOrPut(folder.lowercase()) { ArrayList() }.add(candidate)
            assigned.add(rel.lowercase())
        }
        val folderNodes: List<SimpleNode> =
            if (activeStore == null) emptyList()
            else layout.childFolders("").map {
                VirtualFolderNode(project, this, it, layout, filesByFolder, engine, rootPath)
            }
        val unassigned = FileNode(
            project, this, file, engine, rootPath,
            displayName = UNASSIGNED_LABEL,
            excludedFiles = assigned,
        )
        return (folderNodes + unassigned).toTypedArray()
    }

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
            } else if (engine.isFileVisible(child)) {
                out.add(child)
            }
        }
    }

    private fun isVisible(candidate: VirtualFile): Boolean =
        if (candidate.isDirectory) {
            FileFilter.includeDirectory(candidate.name) && hasVisibleContent(candidate)
        } else {
            relativePath(candidate).lowercase() !in excludedFiles && engine.isFileVisible(candidate)
        }

    /** A directory is shown only if filtering leaves something inside it. */
    private fun hasVisibleContent(dir: VirtualFile): Boolean =
        (dir.children ?: return false).any { it.isValid && isVisible(it) }

    private fun relativePath(candidate: VirtualFile): String =
        candidate.path.removePrefix("$rootPath/")

    companion object {
        const val UNASSIGNED_LABEL = "<Unassigned>"
    }
}
