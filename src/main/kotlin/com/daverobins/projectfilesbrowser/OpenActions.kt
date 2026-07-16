package com.daverobins.projectfilesbrowser

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.treeStructure.SimpleNode
import java.io.File

internal const val OPEN_TABS_CONFIRM_THRESHOLD = 20

private val HEADER_EXTENSIONS = setOf("h", "hh", "hpp", "hxx")
private val SOURCE_EXTENSIONS = setOf("c", "cc", "cpp", "cxx")

private fun counterpartExtensions(extension: String): Set<String>? {
    val lower = extension.lowercase()
    return when {
        lower in HEADER_EXTENSIONS -> SOURCE_EXTENSIONS
        lower in SOURCE_EXTENSIONS -> HEADER_EXTENSIONS
        else -> null
    }
}

/**
 * The header/source counterpart of [relativePath] among [candidates] (relative
 * paths): opposite extension family, same base name (case-insensitive). Same
 * directory wins; else the candidate sharing the most leading path segments
 * with the file's directory; ties broken by case-insensitive path order.
 */
internal fun findCounterpart(relativePath: String, candidates: Collection<String>): String? {
    val targets = counterpartExtensions(relativePath.substringAfterLast('.', "")) ?: return null
    val base = relativePath.substringAfterLast('/').substringBeforeLast('.').lowercase()
    val dir = relativePath.substringBeforeLast('/', "")
    val matches = candidates.filter { candidate ->
        candidate.substringAfterLast('.', "").lowercase() in targets &&
            candidate.substringAfterLast('/').substringBeforeLast('.').lowercase() == base
    }
    if (matches.isEmpty()) return null
    matches.firstOrNull { it.substringBeforeLast('/', "").equals(dir, ignoreCase = true) }
        ?.let { return it }
    return matches.sortedWith(
        compareByDescending<String> { sharedLeadingSegments(dir, it.substringBeforeLast('/', "")) }
            .thenBy { it.lowercase() }
    ).first()
}

private fun sharedLeadingSegments(a: String, b: String): Int {
    if (a.isEmpty() || b.isEmpty()) return 0
    val left = a.lowercase().split('/')
    val right = b.lowercase().split('/')
    var i = 0
    while (i < left.size && i < right.size && left[i] == right[i]) i++
    return i
}

/**
 * Resolves the counterpart of [file] among ALL project files (noise directories
 * skipped) — deliberately ignoring filter groups and the CMake gate: headers are
 * often absent from CMake targets or hidden by source-only filters, and "open my
 * header" should find them anyway.
 */
internal fun findCounterpartFile(file: VirtualFile, rootDir: VirtualFile): VirtualFile? {
    val prefix = rootDir.path + "/"
    if (!file.path.startsWith(prefix)) return null
    val byRelativePath = allFilesUnder(rootDir).associateBy { it.path.removePrefix(prefix) }
    val match = findCounterpart(file.path.removePrefix(prefix), byRelativePath.keys) ?: return null
    return byRelativePath[match]
}

/**
 * Every non-directory file the tree would show under [nodes], recursively —
 * walking the nodes' own children so visibility, exclusions, and folder
 * resolution all apply. Deduplicated, in tree order.
 */
internal fun collectFilesUnder(nodes: List<SimpleNode>): List<VirtualFile> {
    val out = LinkedHashSet<VirtualFile>()
    fun walk(node: SimpleNode) {
        if (node is FileNode && !node.file.isDirectory) {
            out.add(node.file)
            return
        }
        for (child in node.children) walk(child)
    }
    nodes.forEach(::walk)
    return out.toList()
}

/** Every file under the root, skipping excluded (noise) directories — no filter or gate applied. */
internal fun allFilesUnder(rootDir: VirtualFile): List<VirtualFile> {
    val out = ArrayList<VirtualFile>()
    fun walk(dir: VirtualFile) {
        for (child in dir.children ?: return) {
            if (!child.isValid) continue
            if (child.isDirectory) {
                if (FileFilter.includeDirectory(child.name)) walk(child)
            } else {
                out.add(child)
            }
        }
    }
    walk(rootDir)
    return out
}

/** Opens every file the tree shows under the given folder-like nodes, optionally pinning each tab. */
internal class OpenFolderInTabsAction(
    private val project: Project,
    private val nodes: List<SimpleNode>,
    private val pinned: Boolean,
) : AnAction(if (pinned) "Open in Pinned Tabs" else "Open in Tabs") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val files = collectFilesUnder(nodes).filter { it.isValid }
        if (files.isEmpty()) return
        if (files.size > OPEN_TABS_CONFIRM_THRESHOLD) {
            val answer = Messages.showYesNoDialog(
                project,
                "Open ${files.size} editor tabs?",
                "Open in Tabs",
                "Open",
                "Cancel",
                null,
            )
            if (answer != Messages.YES) return
        }
        val editors = FileEditorManager.getInstance(project)
        for (file in files) {
            editors.openFile(file, false)
            if (pinned) {
                (editors as? FileEditorManagerEx)?.currentWindow
                    ?.takeIf { it.isFileOpen(file) }
                    ?.setFilePinned(file, true)
            }
        }
    }
}

/** Opens the selected file and its header/source counterpart; focus lands on the selected file. */
internal class OpenPairAction(
    private val project: Project,
    private val file: VirtualFile,
    private val counterpart: VirtualFile,
) : AnAction("Open Pair") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val editors = FileEditorManager.getInstance(project)
        if (counterpart.isValid) editors.openFile(counterpart, false)
        if (file.isValid) editors.openFile(file, true)
    }
}

/** Reveals the file or directory in the OS file manager (platform-appropriate name). */
internal class RevealInFileManagerAction(
    private val file: VirtualFile,
) : AnAction(RevealFileAction.getActionName()) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val ioFile = File(file.path)
        if (ioFile.isDirectory) RevealFileAction.openDirectory(ioFile) else RevealFileAction.openFile(ioFile)
    }
}
