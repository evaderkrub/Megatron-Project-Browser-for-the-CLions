package com.daverobins.projectfilesbrowser

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.tree.AsyncTreeModel
import com.intellij.ui.tree.StructureTreeModel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.KeyStroke

class ProjectFilesPanel(
    private val project: Project,
    rootDir: VirtualFile,
    parentDisposable: Disposable,
) : SimpleToolWindowPanel(true, true) {

    private val structureModel =
        StructureTreeModel(FilteredTreeStructure(project, rootDir), parentDisposable)
    private val tree = Tree(AsyncTreeModel(structureModel, parentDisposable))

    init {
        tree.isRootVisible = true
        tree.emptyText.text = "No matching files"

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                openSelection()
                return true
            }
        }.installOn(tree)

        tree.registerKeyboardAction(
            { openSelection() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            JComponent.WHEN_FOCUSED,
        )

        val refresh = object : AnAction("Refresh", "Rebuild the file tree", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                structureModel.invalidateAsync()
            }
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("ProjectFilesBrowser", DefaultActionGroup(refresh), true)
        toolbar.targetComponent = tree
        setToolbar(toolbar.component)
        setContent(ScrollPaneFactory.createScrollPane(tree))
    }

    private fun openSelection() {
        val path = tree.selectionPath ?: return
        val node = TreeUtil.getLastUserObject(FileNode::class.java, path) ?: return
        val file = node.file
        if (!file.isDirectory && file.isValid) {
            OpenFileDescriptor(project, file).navigate(true)
        }
    }
}
