package com.daverobins.projectfilesbrowser

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.TransferHandler

/**
 * Drag-and-drop for folder view: drag selected file rows onto a virtual folder
 * to assign them, onto the `<Unassigned>` bucket to unassign. Uses plain Swing
 * DnD (`dragEnabled` + `DropMode.ON`) — drops elsewhere are rejected.
 */
class MegatronTreeTransferHandler(
    private val project: Project,
    private val rootDir: VirtualFile,
    private val store: FolderLayoutStore,
    private val tree: Tree,
    private val onChanged: () -> Unit,
) : TransferHandler() {

    override fun getSourceActions(c: JComponent): Int = MOVE

    override fun createTransferable(c: JComponent): Transferable? {
        if (MegatronFilterState.getInstance(project).getViewMode() != ViewMode.FOLDERS) return null
        val paths = selectedFilePaths(tree, rootDir)
        if (paths.isEmpty()) return null
        return PathListTransferable(paths)
    }

    override fun canImport(support: TransferSupport): Boolean =
        support.isDataFlavorSupported(PATHS_FLAVOR) && dropTarget(support) != null

    override fun importData(support: TransferSupport): Boolean {
        if (!canImport(support)) return false
        val target = dropTarget(support) ?: return false
        val data = support.transferable.getTransferData(PATHS_FLAVOR) as? List<*> ?: return false
        val paths = data.filterIsInstance<String>()
        if (paths.isEmpty()) return false
        store.mutate { layout ->
            paths.fold(layout) { acc, path ->
                if (target == UNASSIGNED_TARGET) acc.withUnassigned(path)
                else acc.withAssignment(path, target)
            }
        }
        onChanged()
        return true
    }

    /** Folder path for the drop row, [UNASSIGNED_TARGET] for the bucket, null = invalid target. */
    private fun dropTarget(support: TransferSupport): String? {
        val location = support.dropLocation as? JTree.DropLocation ?: return null
        val path = location.path ?: return null
        TreeUtil.getLastUserObject(VirtualFolderNode::class.java, path)?.let { return it.folderPath }
        val node = TreeUtil.getLastUserObject(FileNode::class.java, path) ?: return null
        return if (node.isUnassignedBucket) UNASSIGNED_TARGET else null
    }

    /** JVM-local transferable carrying the dragged files' relative paths. */
    private class PathListTransferable(private val paths: List<String>) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(PATHS_FLAVOR)
        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == PATHS_FLAVOR
        override fun getTransferData(flavor: DataFlavor): Any {
            if (flavor != PATHS_FLAVOR) throw UnsupportedFlavorException(flavor)
            return paths
        }
    }

    companion object {
        private const val UNASSIGNED_TARGET = ""
        val PATHS_FLAVOR = DataFlavor(List::class.java, "Megatron file paths")
    }
}
