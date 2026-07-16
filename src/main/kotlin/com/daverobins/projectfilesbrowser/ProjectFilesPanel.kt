package com.daverobins.projectfilesbrowser

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.tree.AsyncTreeModel
import com.intellij.ui.tree.StructureTreeModel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.DropMode
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.event.HyperlinkEvent

class ProjectFilesPanel(
    private val project: Project,
    private val rootDir: VirtualFile,
    parentDisposable: Disposable,
) : SimpleToolWindowPanel(true, true) {

    private val projectModelGate = OcWorkspaceGate(project)
    private val engine = FilterEngine(project, rootDir, projectModelGate)
    private val sets = ConfigSetManager(project, rootDir)
    private val folderStore = FolderLayoutStore(project, rootDir)
    private val structureModel =
        StructureTreeModel(FilteredTreeStructure(project, rootDir, engine, folderStore), parentDisposable)
    private val tree = Tree(AsyncTreeModel(structureModel, parentDisposable))
    private val banner: JPanel = buildBanner()

    init {
        tree.isRootVisible = true
        tree.emptyText.text = "No matching files"

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                if (event.isControlDown) openPairOrSelection(event) else openSelection()
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
                rootDir.refresh(true, true) { structureModel.invalidateAsync() }
            }
        }
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "ProjectFilesBrowser",
            DefaultActionGroup(
                refresh,
                SetSwitcherAction(project, sets) { configChanged() },
                FilterDropdownAction(project, engine) { structureModel.invalidateAsync() },
                FlatViewToggleAction(project) { structureModel.invalidateAsync() },
                FolderViewToggleAction(project) { structureModel.invalidateAsync() },
            ),
            true,
        )
        toolbar.targetComponent = tree
        setToolbar(toolbar.component)
        val content = JPanel(BorderLayout())
        content.add(banner, BorderLayout.NORTH)
        content.add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)
        setContent(content)

        PopupHandler.installFollowingSelectionTreePopup(
            tree,
            MegatronTreePopupGroup(project, rootDir, folderStore, engine, tree) { structureModel.invalidateAsync() },
            "MegatronTreePopup",
        )

        tree.dragEnabled = true
        tree.dropMode = DropMode.ON
        tree.transferHandler =
            MegatronTreeTransferHandler(project, rootDir, folderStore, tree) { structureModel.invalidateAsync() }

        VfsChangeWatcher(
            project,
            rootDir,
            parentDisposable,
            { relativePath, fileName -> engine.isGroupVisible(relativePath, fileName) },
        ) {
            configChanged()
        }

        projectModelGate.subscribe(parentDisposable) {
            structureModel.invalidateAsync()
        }
    }

    private fun buildBanner(): JPanel {
        val link = HyperlinkLabel()
        link.setHyperlinkText("Create default set")
        link.addHyperlinkListener { event ->
            if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                sets.createDefaultSet()
                configChanged()
            }
        }
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        panel.add(JBLabel("No Megatron config sets."))
        panel.add(link)
        panel.isVisible = sets.setNames().isEmpty()
        return panel
    }

    /** EDT. Re-evaluates the empty-state banner and rebuilds the tree. */
    private fun configChanged() {
        banner.isVisible = sets.setNames().isEmpty()
        structureModel.invalidateAsync()
    }

    private fun openSelection() {
        val path = tree.selectionPath ?: return
        val node = TreeUtil.getLastUserObject(FileNode::class.java, path) ?: return
        val file = node.file
        if (!file.isDirectory && file.isValid) {
            OpenFileDescriptor(project, file).navigate(true)
        }
    }

    /** Ctrl+double-click: open the header/source pair when one exists, else plain open. */
    private fun openPairOrSelection(event: MouseEvent) {
        val path = tree.getPathForLocation(event.x, event.y) ?: return
        val node = TreeUtil.getLastUserObject(FileNode::class.java, path) ?: return
        val file = node.file
        if (file.isDirectory || !file.isValid) return
        val counterpart = findCounterpartFile(file, rootDir, engine)
        val editors = FileEditorManager.getInstance(project)
        if (counterpart != null && counterpart.isValid) editors.openFile(counterpart, false)
        editors.openFile(file, true)
    }
}
