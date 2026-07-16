package com.daverobins.projectfilesbrowser

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.treeStructure.SimpleNode
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil

/** Files currently selected in the tree, as project-relative paths (directories excluded). */
internal fun selectedFilePaths(tree: Tree, rootDir: VirtualFile): List<String> {
    val prefix = rootDir.path + "/"
    return (tree.selectionPaths ?: return emptyList())
        .mapNotNull { TreeUtil.getLastUserObject(FileNode::class.java, it) }
        .filter { !it.file.isDirectory && it.file.isValid && it.file.path.startsWith(prefix) }
        .map { it.file.path.removePrefix(prefix) }
}

/** The single selected virtual folder's path, or null when the selection is anything else. */
internal fun selectedVirtualFolder(tree: Tree): String? {
    val paths = tree.selectionPaths ?: return null
    if (paths.size != 1) return null
    return TreeUtil.getLastUserObject(VirtualFolderNode::class.java, paths[0])?.folderPath
}

/** Selected folder-like nodes: virtual folders, disk directories, and the <Unassigned> bucket. */
internal fun selectedFolderLikeNodes(tree: Tree): List<SimpleNode> =
    (tree.selectionPaths ?: return emptyList()).mapNotNull { path ->
        TreeUtil.getLastUserObject(VirtualFolderNode::class.java, path)
            ?: TreeUtil.getLastUserObject(FileNode::class.java, path)?.takeIf { it.file.isDirectory }
    }

/** The single selected FileNode (file or directory), or null. */
internal fun singleSelectedFileNode(tree: Tree): FileNode? {
    val paths = tree.selectionPaths ?: return null
    if (paths.size != 1) return null
    return TreeUtil.getLastUserObject(FileNode::class.java, paths[0])
}

/**
 * Right-click menu for the Megatron tree. File assignment works in every view
 * mode; folder management appears only in folder view (folder nodes only exist there).
 */
class MegatronTreePopupGroup(
    private val project: Project,
    private val rootDir: VirtualFile,
    private val store: FolderLayoutStore,
    private val tree: Tree,
    private val onChanged: () -> Unit,
) : ActionGroup() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val actions = ArrayList<AnAction>()
        val files = selectedFilePaths(tree, rootDir)
        if (files.isNotEmpty()) {
            actions.add(AddToFolderGroup(files))
            if (files.any { store.layout().folderFor(it) != null }) {
                actions.add(RemoveFromFolderAction(files))
            }
        }
        if (MegatronFilterState.getInstance(project).getViewMode() == ViewMode.FOLDERS) {
            if (actions.isNotEmpty()) actions.add(Separator.getInstance())
            actions.add(NewFolderAction())
            selectedVirtualFolder(tree)?.let { folder ->
                actions.add(NewSubfolderAction(folder))
                actions.add(RenameFolderAction(folder))
                actions.add(DeleteFolderAction(folder))
            }
        }
        val extras = ArrayList<AnAction>()
        val folderLike = selectedFolderLikeNodes(tree)
        if (folderLike.isNotEmpty()) {
            extras.add(OpenFolderInTabsAction(project, folderLike, pinned = false))
            extras.add(OpenFolderInTabsAction(project, folderLike, pinned = true))
        }
        val single = singleSelectedFileNode(tree)
        if (single != null && !single.file.isDirectory) {
            findCounterpartFile(single.file, rootDir)?.let { counterpart ->
                extras.add(OpenPairAction(project, single.file, counterpart))
            }
        }
        if (single != null && !single.isUnassignedBucket) {
            extras.add(RevealInFileManagerAction(single.file))
        }
        if (extras.isNotEmpty()) {
            if (actions.isNotEmpty()) actions.add(Separator.getInstance())
            actions.addAll(extras)
        }
        return actions.toTypedArray()
    }

    private fun mutateAndRefresh(change: (FolderLayout) -> FolderLayout) {
        store.mutate(change)
        onChanged()
    }

    /** Prompts for a folder name; returns the trimmed name or null when cancelled. */
    private fun promptFolderName(title: String, initial: String?, siblingNames: Collection<String>): String? {
        val validator = object : InputValidator {
            override fun checkInput(input: String): Boolean =
                validateFolderName(input, siblingNames) == null
            override fun canClose(input: String): Boolean = checkInput(input)
        }
        val name = Messages.showInputDialog(project, "Folder name:", title, null, initial, validator)
            ?: return null
        return name.trim().takeIf { it.isNotEmpty() }
    }

    private fun siblingNames(parent: String): List<String> =
        store.layout().childFolders(parent).map { it.substringAfterLast('/') }

    private inner class AddToFolderGroup(private val files: List<String>) :
        ActionGroup("Add to Folder", true) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun getChildren(e: AnActionEvent?): Array<AnAction> {
            val existing: List<AnAction> = store.layout().allFolders().map { AssignAction(it, files) }
            val tail = listOf(Separator.getInstance(), NewFolderAssignAction(files))
            return (existing + tail).toTypedArray()
        }
    }

    private inner class AssignAction(private val folder: String, private val files: List<String>) :
        AnAction(folder) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            mutateAndRefresh { layout ->
                files.fold(layout) { acc, path -> acc.withAssignment(path, folder) }
            }
        }
    }

    private inner class NewFolderAssignAction(private val files: List<String>) :
        AnAction("New Folder…") {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            val name = promptFolderName("New Folder", null, siblingNames("")) ?: return
            mutateAndRefresh { layout ->
                files.fold(layout.withFolder(name)) { acc, path -> acc.withAssignment(path, name) }
            }
        }
    }

    private inner class RemoveFromFolderAction(private val files: List<String>) :
        AnAction("Remove from Folder") {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            mutateAndRefresh { layout ->
                files.fold(layout) { acc, path -> acc.withUnassigned(path) }
            }
        }
    }

    private inner class NewFolderAction : AnAction("New Folder…") {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            val name = promptFolderName("New Folder", null, siblingNames("")) ?: return
            mutateAndRefresh { it.withFolder(name) }
        }
    }

    private inner class NewSubfolderAction(private val parent: String) :
        AnAction("New Subfolder…") {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            val name = promptFolderName("New Subfolder", null, siblingNames(parent)) ?: return
            mutateAndRefresh { it.withFolder("$parent/$name") }
        }
    }

    private inner class RenameFolderAction(private val folder: String) :
        AnAction("Rename…") {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            val currentName = folder.substringAfterLast('/')
            val siblings = siblingNames(folder.substringBeforeLast('/', ""))
                .filterNot { it.equals(currentName, ignoreCase = true) }
            val name = promptFolderName("Rename Folder", currentName, siblings) ?: return
            mutateAndRefresh { it.withFolderRenamed(folder, name) }
        }
    }

    private inner class DeleteFolderAction(private val folder: String) :
        AnAction("Delete") {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) {
            val name = folder.substringAfterLast('/')
            val answer = Messages.showYesNoDialog(
                project,
                "Delete folder '$name'? Its files return to ${FileNode.UNASSIGNED_LABEL}.",
                "Delete Folder",
                "Delete",
                "Cancel",
                null,
            )
            if (answer != Messages.YES) return
            mutateAndRefresh { it.withFolderDeleted(folder) }
        }
    }
}
