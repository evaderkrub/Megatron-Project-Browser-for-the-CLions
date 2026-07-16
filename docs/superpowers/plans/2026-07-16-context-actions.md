# Context Actions (Phase 8) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Four tree context actions — Open in Tabs, Open in Pinned Tabs (folders, recursive, >20 confirm), Open Pair (header/source counterpart, also Ctrl+double-click), Reveal in Explorer.

**Architecture:** `OpenActions.kt` holds pure helpers (counterpart matching on relative paths; recursive file collection that simply walks the tree nodes' own `getChildren()`, inheriting all visibility/exclusion rules) plus the four `AnAction`s. `MegatronTreePopupGroup` registers them; the panel adds a Ctrl+double-click branch. Platform side effects use javap-verified APIs only.

**Tech Stack:** Kotlin 2.3.0, IntelliJ Platform Gradle Plugin 2.18.1, CLion 2026.1.1, JUnit 4.

## Global Constraints

- Before any Gradle call in a fresh PowerShell: `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"`. Test command: `.\gradlew.bat test`.
- Open in Tabs / Open in Pinned Tabs: on virtual folders, disk directory nodes, and the `<Unassigned>` bucket; multi-select unions and dedupes; recursive over what the tree itself would show (walk `getChildren()`); confirm dialog when total > 20 files (constant `OPEN_TABS_CONFIRM_THRESHOLD = 20`).
- Pinning route (javap-verified — trust it): `FileEditorManager.getInstance(project).openFile(vf, false)` then `(fem as FileEditorManagerEx).currentWindow?.setFilePinned(vf, true)`.
- Open Pair: single selected non-directory file with a counterpart; headers `h, hh, hpp, hxx` ↔ sources `c, cc, cpp, cxx`, same base name case-insensitively; search VISIBLE files (current filters apply); same directory wins, else most shared leading path segments with the file's directory, ties by case-insensitive path order. Opens counterpart first (unfocused), selected file last (focused). Ctrl+double-click = same behavior, falling back to plain open when no counterpart.
- Reveal in Explorer: single selected FileNode that is not the `<Unassigned>` bucket; action text from `RevealFileAction.getActionName()`; `RevealFileAction.openFile(java.io.File)` for files / `openDirectory` for directories (both javap-verified).
- Menu order: existing entries, then a separator, then Open in Tabs, Open in Pinned Tabs, Open Pair, Reveal — each only when its selection rule matches.
- Popup actions use `ActionUpdateThread.EDT` (they read Swing tree selection) — same as the existing group.
- No new automated tests for the tab/pin/reveal side effects (sandbox-verified); pure helpers ARE unit-tested.
- Plugin version becomes exactly `0.8.0` (Task 2).
- Commit messages: conventional commits.

---

### Task 1: Pure helpers — counterpart matching + recursive collection

**Files:**
- Create: `src/main/kotlin/com/daverobins/projectfilesbrowser/OpenActions.kt` (helpers only this task)
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/CounterpartTest.kt` (new, pure JUnit4)
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/FilteredTreeStructureTest.kt` (one collection test)

**Interfaces:**
- Consumes: `FileNode` (`file`, `isUnassignedBucket`), `VirtualFolderNode`, `FileFilter.includeDirectory`, `FilterEngine.isFileVisible`.
- Produces (Task 2 relies on):
  - `internal fun findCounterpart(relativePath: String, candidates: Collection<String>): String?`
  - `internal fun findCounterpartFile(file: VirtualFile, rootDir: VirtualFile, engine: FilterEngine): VirtualFile?`
  - `internal fun collectFilesUnder(nodes: List<SimpleNode>): List<VirtualFile>`
  - `internal fun visibleFilesUnder(rootDir: VirtualFile, engine: FilterEngine): List<VirtualFile>`
  - `internal const val OPEN_TABS_CONFIRM_THRESHOLD = 20`

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/daverobins/projectfilesbrowser/CounterpartTest.kt`:

```kotlin
package com.daverobins.projectfilesbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CounterpartTest {

    @Test
    fun `matches opposite extension family with same base name`() {
        assertEquals("src/engine.h", findCounterpart("src/engine.cpp", listOf("src/engine.h", "src/other.h")))
        assertEquals("src/engine.cpp", findCounterpart("src/engine.h", listOf("src/engine.cpp")))
        assertEquals("a.cc", findCounterpart("a.hh", listOf("a.cc")))
        assertEquals("a.cxx", findCounterpart("a.hxx", listOf("a.cxx")))
    }

    @Test
    fun `non-pairable extensions and missing matches return null`() {
        assertNull(findCounterpart("notes.md", listOf("notes.h", "notes.cpp")))
        assertNull(findCounterpart("src/engine.cpp", listOf("src/other.h")))
        assertNull(findCounterpart("src/engine.cpp", emptyList()))
        assertNull(findCounterpart("src/engine.cpp", listOf("src/engine.cc"))) // same family, not a pair
    }

    @Test
    fun `same directory beats closer name elsewhere`() {
        assertEquals(
            "src/deep/engine.h",
            findCounterpart("src/deep/engine.cpp", listOf("include/engine.h", "src/deep/engine.h")),
        )
    }

    @Test
    fun `most shared leading segments wins then path order breaks ties`() {
        assertEquals(
            "src/deep/inc/engine.h",
            findCounterpart(
                "src/deep/engine.cpp",
                listOf("include/engine.h", "src/deep/inc/engine.h", "src/other/engine.h"),
            ),
        )
        assertEquals(
            "include/a/engine.h",
            findCounterpart("src/engine.cpp", listOf("include/b/engine.h", "include/a/engine.h")),
        )
    }

    @Test
    fun `matching is case-insensitive on base name extension and directory`() {
        assertEquals("SRC/Engine.H", findCounterpart("src/ENGINE.CPP", listOf("SRC/Engine.H")))
    }
}
```

Append to `FilteredTreeStructureTest.kt`:

```kotlin
    fun testCollectFilesUnderWalksFoldersAndDirectoriesWithDedup() {
        myFixture.addFileToProject("cu/megatron/default.folders", "Core/\n  src/**\nCore/Sub/\n  top.cpp\n")
        myFixture.addFileToProject("cu/src/a.cpp", "")
        myFixture.addFileToProject("cu/src/deep/b.h", "")
        myFixture.addFileToProject("cu/top.cpp", "")
        myFixture.addFileToProject("cu/other.cpp", "")

        val state = MegatronFilterState.getInstance(project)
        state.setViewMode(ViewMode.FOLDERS)
        try {
            val rootDir = requireNotNull(myFixture.findFileInTempDir("cu"))
            val store = FolderLayoutStore(project, rootDir)
            val structure = FilteredTreeStructure(project, rootDir, FilterEngine(project, rootDir), store)
            val root = structure.rootElement as FileNode
            val children = root.children

            val core = children.first { it is VirtualFolderNode && it.folderPath == "Core" }
            val coreFiles = collectFilesUnder(listOf(core)).map { it.name }.sorted()
            assertEquals(listOf("a.cpp", "b.h", "top.cpp"), coreFiles) // recursive: Core + Core/Sub

            val unassigned = children.filterIsInstance<FileNode>().first { it.isUnassignedBucket }
            assertEquals(listOf("other.cpp"), collectFilesUnder(listOf(unassigned)).map { it.name })

            val union = collectFilesUnder(listOf(core, core, unassigned))
            assertEquals(4, union.size) // dedup across repeated selection
        } finally {
            state.setViewMode(ViewMode.TREE)
        }
    }
```

- [ ] **Step 2: Run to verify failure**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.CounterpartTest"
```
Expected: COMPILE FAILURE (`findCounterpart` unresolved).

- [ ] **Step 3: Implement OpenActions.kt (helpers only)**

```kotlin
package com.daverobins.projectfilesbrowser

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.treeStructure.SimpleNode

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

/** Resolves the counterpart of [file] among the currently visible project files. */
internal fun findCounterpartFile(file: VirtualFile, rootDir: VirtualFile, engine: FilterEngine): VirtualFile? {
    val prefix = rootDir.path + "/"
    if (!file.path.startsWith(prefix)) return null
    val byRelativePath = visibleFilesUnder(rootDir, engine).associateBy { it.path.removePrefix(prefix) }
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

/** The filtered file walk (same rules as flat mode's collector). */
internal fun visibleFilesUnder(rootDir: VirtualFile, engine: FilterEngine): List<VirtualFile> {
    val out = ArrayList<VirtualFile>()
    fun walk(dir: VirtualFile) {
        for (child in dir.children ?: return) {
            if (!child.isValid) continue
            if (child.isDirectory) {
                if (FileFilter.includeDirectory(child.name)) walk(child)
            } else if (engine.isFileVisible(child)) {
                out.add(child)
            }
        }
    }
    walk(rootDir)
    return out
}
```

- [ ] **Step 4: Run CounterpartTest + FilteredTreeStructureTest, then the full suite** — all BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```powershell
git add src/main/kotlin/com/daverobins/projectfilesbrowser/OpenActions.kt src/test/kotlin/com/daverobins/projectfilesbrowser/CounterpartTest.kt src/test/kotlin/com/daverobins/projectfilesbrowser/FilteredTreeStructureTest.kt
git commit -m "feat: counterpart matching and recursive file collection helpers"
```

---

### Task 2: The four actions, menu registration, Ctrl+double-click, version 0.8.0

**Files:**
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/OpenActions.kt` (append the actions)
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/FolderActions.kt` (register in MegatronTreePopupGroup; ctor gains engine)
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/ProjectFilesPanel.kt` (ctor arg + Ctrl+double-click)
- Modify: `build.gradle.kts` (version)

**Interfaces:**
- Consumes: Task 1 helpers; verified APIs: `FileEditorManagerEx.getInstanceEx(project)` / `(FileEditorManager as FileEditorManagerEx).currentWindow` + `EditorWindow.setFilePinned(VirtualFile, Boolean)`; `RevealFileAction.getActionName()`, `RevealFileAction.openFile(java.io.File)`, `RevealFileAction.openDirectory(java.io.File)`; `Messages.showYesNoDialog(Project, String, String, String, String, Icon)`.
- Produces: `MegatronTreePopupGroup(project, rootDir, store, engine, tree, onChanged)` — note the NEW `engine: FilterEngine` 4th parameter.

- [ ] **Step 1: Append the actions to OpenActions.kt**

Add imports: `com.intellij.openapi.actionSystem.ActionUpdateThread`, `com.intellij.openapi.actionSystem.AnAction`, `com.intellij.openapi.actionSystem.AnActionEvent`, `com.intellij.openapi.fileEditor.FileEditorManager`, `com.intellij.openapi.fileEditor.ex.FileEditorManagerEx`, `com.intellij.ide.actions.RevealFileAction`, `com.intellij.openapi.project.Project`, `com.intellij.openapi.ui.Messages`, `java.io.File`.

```kotlin
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
        val window = (editors as? FileEditorManagerEx)?.currentWindow
        for (file in files) {
            editors.openFile(file, false)
            if (pinned) window?.setFilePinned(file, true)
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
```

- [ ] **Step 2: Register in FolderActions.kt**

`MegatronTreePopupGroup` constructor gains `private val engine: FilterEngine` as the 4th parameter (after `store`, before `tree`).

Add two selection helpers next to the existing ones (top level, same file):

```kotlin
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
```

Add import `com.intellij.ui.treeStructure.SimpleNode`.

At the END of `getChildren` (before `return actions.toTypedArray()`):

```kotlin
        val extras = ArrayList<AnAction>()
        val folderLike = selectedFolderLikeNodes(tree)
        if (folderLike.isNotEmpty()) {
            extras.add(OpenFolderInTabsAction(project, folderLike, pinned = false))
            extras.add(OpenFolderInTabsAction(project, folderLike, pinned = true))
        }
        val single = singleSelectedFileNode(tree)
        if (single != null && !single.file.isDirectory) {
            findCounterpartFile(single.file, rootDir, engine)?.let { counterpart ->
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
```

- [ ] **Step 3: Wire the panel**

In `ProjectFilesPanel.kt`:
- The `MegatronTreePopupGroup(...)` construction gains `engine` as the 4th argument: `MegatronTreePopupGroup(project, rootDir, folderStore, engine, tree) { ... }`.
- The DoubleClickListener body becomes:

```kotlin
            override fun onDoubleClick(event: MouseEvent): Boolean {
                if (event.isControlDown) openPairOrSelection() else openSelection()
                return true
            }
```

- Add next to `openSelection()`:

```kotlin
    /** Ctrl+double-click: open the header/source pair when one exists, else plain open. */
    private fun openPairOrSelection() {
        val path = tree.selectionPath ?: return
        val node = TreeUtil.getLastUserObject(FileNode::class.java, path) ?: return
        val file = node.file
        if (file.isDirectory || !file.isValid) return
        val counterpart = findCounterpartFile(file, rootDir, engine)
        val editors = FileEditorManager.getInstance(project)
        if (counterpart != null && counterpart.isValid) editors.openFile(counterpart, false)
        editors.openFile(file, true)
    }
```

Add import `com.intellij.openapi.fileEditor.FileEditorManager`.

- [ ] **Step 4: Bump the version** — `build.gradle.kts`: `version = "0.7.0"` → `version = "0.8.0"`.

- [ ] **Step 5: Run the full suite** — BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```powershell
git add src/main/kotlin/com/daverobins/projectfilesbrowser/OpenActions.kt src/main/kotlin/com/daverobins/projectfilesbrowser/FolderActions.kt src/main/kotlin/com/daverobins/projectfilesbrowser/ProjectFilesPanel.kt build.gradle.kts
git commit -m "feat: open-in-tabs, open pair, reveal-in-explorer context actions; version 0.8.0"
```

---

## Sandbox Checklist (post-implementation, human verification)

- Right-click a ~5-file folder → Open in Tabs: all open. Pinned variant: tabs show pins.
- Folder with >20 files: confirm dialog appears; Cancel opens nothing.
- Open Pair from a .cpp and from its .h; entry hidden for files without counterparts.
- Ctrl+double-click a paired file (both open, focus on clicked) and an unpaired one (plain open).
- Reveal in Explorer on a file and a directory; entry hidden on <Unassigned> and virtual folders.
- Multi-select two folders → Open in Tabs opens the union once.
