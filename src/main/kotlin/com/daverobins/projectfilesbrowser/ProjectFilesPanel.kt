package com.daverobins.projectfilesbrowser

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent as EditorDocumentEvent
import com.intellij.openapi.editor.event.DocumentListener as EditorDocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.tree.AsyncTreeModel
import com.intellij.ui.tree.StructureTreeModel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.SingleAlarm
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.DropMode
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
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
    private val scanner = BookmarkScanner()
    private val structureModel =
        StructureTreeModel(
            FilteredTreeStructure(project, rootDir, engine, folderStore, scanner, sets),
            parentDisposable,
        )
    private val tree = Tree(AsyncTreeModel(structureModel, parentDisposable))
    private val banner: JPanel = buildBanner()
    private val quickFilterField = SearchTextField(false)

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

        val toolbar = ActionManager.getInstance().createActionToolbar(
            "ProjectFilesBrowser",
            DefaultActionGroup(
                BookmarkAction(project, sets),
                SetSwitcherAction(project, sets) { configChanged() },
                FilterDropdownAction(project, engine, sets) { structureModel.invalidateAsync() },
                FlatViewToggleAction(project) { structureModel.invalidateAsync() },
                FolderViewToggleAction(project) { structureModel.invalidateAsync() },
            ),
            true,
        )
        toolbar.targetComponent = tree
        quickFilterField.textEditor.emptyText.text = "Filter results…"
        val quickFilterAlarm = SingleAlarm(
            Runnable {
                engine.setQuickFilter(quickFilterField.text)
                structureModel.invalidateAsync()
            },
            QUICK_FILTER_DEBOUNCE_MS,
            parentDisposable,
        )
        quickFilterField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = quickFilterAlarm.cancelAndRequest()
            override fun removeUpdate(e: DocumentEvent) = quickFilterAlarm.cancelAndRequest()
            override fun changedUpdate(e: DocumentEvent) = quickFilterAlarm.cancelAndRequest()
        })
        val header = JPanel(BorderLayout())
        header.add(toolbar.component, BorderLayout.WEST)
        header.add(quickFilterField, BorderLayout.CENTER)
        setToolbar(header)
        val content = JPanel(BorderLayout())
        content.add(banner, BorderLayout.NORTH)
        content.add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)
        setContent(content)

        PopupHandler.installFollowingSelectionTreePopup(
            tree,
            MegatronTreePopupGroup(project, rootDir, folderStore, tree, { structureModel.invalidateAsync() }) {
                rootDir.refresh(true, true) { structureModel.invalidateAsync() }
            },
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

        val bookmarkAlarm = SingleAlarm(
            Runnable { structureModel.invalidateAsync() },
            QUICK_FILTER_DEBOUNCE_MS,
            parentDisposable,
        )
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : EditorDocumentListener {
                override fun documentChanged(event: EditorDocumentEvent) {
                    val changed = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    if (!changed.path.startsWith(rootDir.path + "/")) return
                    if (scanner.hadBookmarks(changed.path) ||
                        changeTouchesMarker(event.document.charsSequence, event.offset, event.newFragment.length)
                    ) {
                        bookmarkAlarm.cancelAndRequest()
                    }
                }
            },
            parentDisposable,
        )
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
        TreeUtil.getLastUserObject(BookmarkNode::class.java, path)?.let {
            openBookmark(it)
            return
        }
        val node = TreeUtil.getLastUserObject(FileNode::class.java, path) ?: return
        val file = node.file
        if (!file.isDirectory && file.isValid) {
            OpenFileDescriptor(project, file).navigate(true)
        }
    }

    /** Opens the bookmark's file with the caret on its line. */
    private fun openBookmark(node: BookmarkNode) {
        if (!node.file.isValid) return
        OpenFileDescriptor(project, node.file, node.bookmark.line, 0).navigate(true)
    }

    /** Ctrl+double-click: open the header/source pair when one exists, else plain open. */
    private fun openPairOrSelection(event: MouseEvent) {
        val path = tree.getPathForLocation(event.x, event.y) ?: return
        TreeUtil.getLastUserObject(BookmarkNode::class.java, path)?.let {
            openBookmark(it)
            return
        }
        val node = TreeUtil.getLastUserObject(FileNode::class.java, path) ?: return
        val file = node.file
        if (file.isDirectory || !file.isValid) return
        val counterpart = findCounterpartFile(file, rootDir)
        val editors = FileEditorManager.getInstance(project)
        if (counterpart != null && counterpart.isValid) editors.openFile(counterpart, false)
        editors.openFile(file, true)
    }

    companion object {
        private const val QUICK_FILTER_DEBOUNCE_MS = 300
    }
}
