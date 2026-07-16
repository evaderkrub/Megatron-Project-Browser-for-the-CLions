# Bookmarks (Phase 10) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Marker comments (`// megatron[/set]: "title"`) inserted by a toolbar button become navigable bookmarks under a Bookmarks node at the bottom of the Megatron tree.

**Architecture:** A pure parser/insertion module (`Bookmark.kt`), a stamp-keyed cached scanner (`BookmarkScanner`), two `SimpleNode` classes appended to the root's children in every view mode, and a toolbar action that replaces Refresh (Refresh moves to the right-click menu). A debounced editor-document listener keeps the tree live while typing.

**Tech Stack:** Kotlin 2.3.0, IntelliJ Platform Gradle Plugin 2.18.1, CLion 2026.1.1, JUnit 4 plain unit tests (no IDE fixtures).

**Spec:** `docs/superpowers/specs/2026-07-16-bookmarks-design.md`

## Global Constraints

- Build with the wrapper: `./gradlew.bat` (Gradle 9.6.1). If Gradle fails with a Java-version error, set `JAVA_HOME` to `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot` first.
- Tests are plain JUnit 4 unit tests in `src/test/kotlin/com/daverobins/projectfilesbrowser/` — no IDE fixtures. `tasks.test` already sets `idea.load.plugins=false`; don't touch it.
- Commit via the Bash tool (PowerShell mangles embedded double quotes in `git commit -m`).
- Package for all code: `com.daverobins.projectfilesbrowser`.
- KDoc style: single-purpose one-liners like the existing files; comments state constraints, not narration.
- Version bump to `0.10.0` happens ONLY in Task 5.

---

### Task 1: Bookmark model, parser, visibility predicate, insertion helper

Pure functions only — no IntelliJ imports. This is the foundation every other task consumes.

**Files:**
- Create: `src/main/kotlin/com/daverobins/projectfilesbrowser/Bookmark.kt`
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/BookmarkTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `data class Bookmark(val line: Int, val setName: String?, val title: String)` — `line` is 0-based
  - `fun Bookmark.visibleInSet(activeSet: String): Boolean`
  - `fun parseBookmarks(text: String): List<Bookmark>`
  - `data class BookmarkInsertion(val lineText: String, val caretColumn: Int)`
  - `fun bookmarkInsertion(caretLineText: String, fileName: String, activeSet: String): BookmarkInsertion`
  - `const val BOOKMARK_MARKER_WORD = "megatron"` (top-level)

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/daverobins/projectfilesbrowser/BookmarkTest.kt`:

```kotlin
package com.daverobins.projectfilesbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkTest {

    @Test
    fun `parses cpp marker with set name and title`() {
        val result = parseBookmarks("""// megatron/default: "do something"""")
        assertEquals(listOf(Bookmark(0, "default", "do something")), result)
    }

    @Test
    fun `parses marker without set name`() {
        val result = parseBookmarks("""// megatron: "fix later"""")
        assertEquals(listOf(Bookmark(0, null, "fix later")), result)
    }

    @Test
    fun `parses cmake hash marker`() {
        val result = parseBookmarks("""# megatron/build: "check flags"""")
        assertEquals(listOf(Bookmark(0, "build", "check flags")), result)
    }

    @Test
    fun `marker keyword is case-insensitive and set name casing is preserved`() {
        val result = parseBookmarks("""// MEGATRON/Default: "x"""")
        assertEquals(listOf(Bookmark(0, "Default", "x")), result)
    }

    @Test
    fun `indented markers are parsed`() {
        val result = parseBookmarks("\t   // megatron: \"deep\"")
        assertEquals(listOf(Bookmark(0, null, "deep")), result)
    }

    @Test
    fun `empty title parses to empty string`() {
        val result = parseBookmarks("// megatron: \"\"")
        assertEquals(listOf(Bookmark(0, null, "")), result)
    }

    @Test
    fun `lines without a quoted title are ignored`() {
        assertTrue(parseBookmarks("// megatron: no quotes here").isEmpty())
        assertTrue(parseBookmarks("// megatron/default:").isEmpty())
        assertTrue(parseBookmarks("""// megatron: "unterminated""").isEmpty())
    }

    @Test
    fun `marker after code is not a bookmark`() {
        assertTrue(parseBookmarks("""int x; // megatron: "hi"""").isEmpty())
    }

    @Test
    fun `blank set name means no set`() {
        val result = parseBookmarks("""// megatron/: "x"""")
        assertEquals(listOf(Bookmark(0, null, "x")), result)
    }

    @Test
    fun `line numbers are zero-based over multiple lines`() {
        val text = "int a;\n// megatron: \"first\"\nint b;\n  # megatron/s: \"second\""
        val result = parseBookmarks(text)
        assertEquals(listOf(Bookmark(1, null, "first"), Bookmark(3, "s", "second")), result)
    }

    @Test
    fun `visibleInSet matches null set always and named set case-insensitively`() {
        assertTrue(Bookmark(0, null, "t").visibleInSet("anything"))
        assertTrue(Bookmark(0, "Default", "t").visibleInSet("default"))
        assertFalse(Bookmark(0, "other", "t").visibleInSet("default"))
    }

    @Test
    fun `insertion uses hash prefix for cmake files`() {
        val cmake = bookmarkInsertion("add_library(x)", "CMakeLists.txt", "default")
        assertEquals("# megatron/default: \"\"", cmake.lineText)
        assertEquals(cmake.lineText.length - 1, cmake.caretColumn)
        assertTrue(bookmarkInsertion("", "helpers.cmake", "s").lineText.startsWith("#"))
        assertTrue(bookmarkInsertion("", "main.CMAKE", "s").lineText.startsWith("#"))
        assertTrue(bookmarkInsertion("", "main.cpp", "s").lineText.startsWith("//"))
    }

    @Test
    fun `insertion copies indentation and puts caret between quotes`() {
        val insertion = bookmarkInsertion("    int x = 0;", "main.cpp", "default")
        assertEquals("    // megatron/default: \"\"", insertion.lineText)
        assertEquals(insertion.lineText.length - 1, insertion.caretColumn)
        assertEquals('"', insertion.lineText[insertion.caretColumn])
        assertEquals('"', insertion.lineText[insertion.caretColumn - 1])
    }
}
```

Raw-string note for the executor: Kotlin raw strings like `"""// megatron: "x""""` end greedily (the extra quote before the closing `"""` is content), so the marker lines above compile as written; the empty-title and insertion tests use escaped strings because a raw string cannot comfortably end in two quotes.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew.bat test --tests "com.daverobins.projectfilesbrowser.BookmarkTest"`
Expected: FAIL — compilation error, `parseBookmarks`/`Bookmark` unresolved.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/com/daverobins/projectfilesbrowser/Bookmark.kt`:

```kotlin
package com.daverobins.projectfilesbrowser

/** One bookmark comment: 0-based [line], optional [setName], quoted [title]. */
data class Bookmark(val line: Int, val setName: String?, val title: String)

/** Shown when the marker names no set, or names the active set (case-insensitive). */
fun Bookmark.visibleInSet(activeSet: String): Boolean =
    setName == null || setName.equals(activeSet, ignoreCase = true)

/** The word that identifies bookmark comments; used for cheap change detection. */
const val BOOKMARK_MARKER_WORD = "megatron"

// Comment prefix ('//' or '#'), 'megatron', optional '/set', ':', quoted title.
// Anchored at line start (after indentation) so markers behind code don't count.
private val MARKER = Regex(
    """^\s*(?://|#)\s*megatron(?:/([^:"]*))?\s*:\s*"([^"]*)\"""",
    RegexOption.IGNORE_CASE,
)

/**
 * Extracts bookmark comments from file text. Lines without a parsable quoted
 * title are ignored; a blank set name is treated as no set.
 */
fun parseBookmarks(text: String): List<Bookmark> {
    val result = ArrayList<Bookmark>()
    text.lineSequence().forEachIndexed { index, line ->
        val match = MARKER.find(line) ?: return@forEachIndexed
        val set = match.groupValues[1].trim().takeIf { it.isNotEmpty() }
        result.add(Bookmark(index, set, match.groupValues[2]))
    }
    return result
}

/** Text and caret column for a new bookmark line inserted above the caret line. */
data class BookmarkInsertion(val lineText: String, val caretColumn: Int)

/** Builds the inserted line: caret line's indentation, file-appropriate prefix, empty title. */
fun bookmarkInsertion(caretLineText: String, fileName: String, activeSet: String): BookmarkInsertion {
    val indent = caretLineText.takeWhile { it == ' ' || it == '\t' }
    val prefix = if (isCMakeFile(fileName)) "#" else "//"
    val line = "$indent$prefix megatron/$activeSet: \"\""
    return BookmarkInsertion(line, line.length - 1)
}

private fun isCMakeFile(fileName: String): Boolean =
    fileName.equals("CMakeLists.txt", ignoreCase = true) ||
        fileName.endsWith(".cmake", ignoreCase = true)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew.bat test --tests "com.daverobins.projectfilesbrowser.BookmarkTest"`
Expected: PASS (all tests green).

- [ ] **Step 5: Run the full suite**

Run: `./gradlew.bat test`
Expected: PASS — nothing else touched.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/daverobins/projectfilesbrowser/Bookmark.kt src/test/kotlin/com/daverobins/projectfilesbrowser/BookmarkTest.kt
git commit -m "feat: bookmark comment parser, visibility rule, insertion builder"
```

---

### Task 2: BookmarkScanner — cached per-file discovery

Thin VFS/Document wrapper over `parseBookmarks`; the logic worth testing lives in Task 1, so this task's gate is compilation + the existing suite.

**Files:**
- Create: `src/main/kotlin/com/daverobins/projectfilesbrowser/BookmarkScanner.kt`

**Interfaces:**
- Consumes: `parseBookmarks(text): List<Bookmark>` (Task 1).
- Produces:
  - `class BookmarkScanner` (no constructor args)
  - `fun bookmarksIn(file: VirtualFile): List<Bookmark>`
  - `fun hadBookmarks(path: String): Boolean`

- [ ] **Step 1: Write the implementation**

Create `src/main/kotlin/com/daverobins/projectfilesbrowser/BookmarkScanner.kt`:

```kotlin
package com.daverobins.projectfilesbrowser

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException

/**
 * Caches parsed bookmarks per file, keyed on modification stamp. Prefers the
 * in-memory document (unsaved edits included) over disk bytes. Called during
 * tree builds (background thread, inside a read action).
 */
class BookmarkScanner {

    private data class Entry(val stamp: Long, val fromDocument: Boolean, val bookmarks: List<Bookmark>)

    private val cache = HashMap<String, Entry>()

    @Synchronized
    fun bookmarksIn(file: VirtualFile): List<Bookmark> {
        if (file.length > MAX_SIZE_BYTES) return emptyList()
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        val stamp = document?.modificationStamp ?: file.modificationStamp
        val fromDocument = document != null
        val cached = cache[file.path]
        if (cached != null && cached.stamp == stamp && cached.fromDocument == fromDocument) {
            return cached.bookmarks
        }
        val bookmarks = parseBookmarks(document?.text ?: loadText(file))
        cache[file.path] = Entry(stamp, fromDocument, bookmarks)
        return bookmarks
    }

    /** True when the last scan of [path] found bookmarks — detects marker deletion cheaply. */
    @Synchronized
    fun hadBookmarks(path: String): Boolean = cache[path]?.bookmarks?.isNotEmpty() == true

    private fun loadText(file: VirtualFile): String =
        try {
            String(file.contentsToByteArray(), file.charset)
        } catch (e: IOException) {
            logger<BookmarkScanner>().warn("Failed to read ${file.path}", e)
            ""
        }

    companion object {
        private const val MAX_SIZE_BYTES = 1L shl 20 // skip files over 1 MB
    }
}
```

- [ ] **Step 2: Compile and run the suite**

Run: `./gradlew.bat test`
Expected: PASS (compiles; no behavior change yet — nothing constructs the scanner).

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/daverobins/projectfilesbrowser/BookmarkScanner.kt
git commit -m "feat: BookmarkScanner with stamp-keyed per-file cache"
```

---

### Task 3: Tree nodes + root integration (Bookmarks node at the bottom)

**Files:**
- Create: `src/main/kotlin/com/daverobins/projectfilesbrowser/BookmarkNodes.kt`
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/FilteredTreeStructure.kt`

**Interfaces:**
- Consumes: `BookmarkScanner.bookmarksIn(file)` (Task 2), `Bookmark.visibleInSet(activeSet)` (Task 1), `ConfigSetManager.effectiveSet()` (existing).
- Produces:
  - `class BookmarksRootNode(project, parent, bookmarks: List<Pair<VirtualFile, Bookmark>>, rootPath: String)`
  - `class BookmarkNode(project, parent, val file: VirtualFile, val bookmark: Bookmark, rootPath: String)` — Task 4's navigation reads `.file` and `.bookmark.line`
  - `FilteredTreeStructure` gains optional `scanner: BookmarkScanner? = null, sets: ConfigSetManager? = null` params; `FileNode` likewise (root-only use)

- [ ] **Step 1: Create the node classes**

Create `src/main/kotlin/com/daverobins/projectfilesbrowser/BookmarkNodes.kt`:

```kotlin
package com.daverobins.projectfilesbrowser

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.treeStructure.SimpleNode

/** The pinned "Bookmarks" group shown after all other root children. */
class BookmarksRootNode(
    private val project: Project,
    parent: SimpleNode,
    private val bookmarks: List<Pair<VirtualFile, Bookmark>>,
    private val rootPath: String,
) : SimpleNode(project, parent) {

    override fun getChildren(): Array<SimpleNode> =
        bookmarks
            .sortedWith(
                compareBy(
                    { it.second.title.lowercase() },
                    { it.first.path.lowercase() },
                    { it.second.line },
                ),
            )
            .map { (file, bookmark) -> BookmarkNode(project, this, file, bookmark, rootPath) }
            .toTypedArray()

    override fun update(presentation: PresentationData) {
        presentation.presentableText = "Bookmarks"
        presentation.setIcon(AllIcons.Nodes.BookmarkGroup)
    }

    override fun getEqualityObjects(): Array<Any> = arrayOf(EQUALITY_KEY)

    companion object {
        private const val EQUALITY_KEY = "megatron.bookmarksRoot"
    }
}

/** One bookmark leaf: title, grey `path:line` location, navigates on activation. */
class BookmarkNode(
    project: Project,
    parent: SimpleNode,
    val file: VirtualFile,
    val bookmark: Bookmark,
    private val rootPath: String,
) : SimpleNode(project, parent) {

    override fun getChildren(): Array<SimpleNode> = NO_CHILDREN

    override fun update(presentation: PresentationData) {
        presentation.presentableText = bookmark.title.ifBlank { UNTITLED_LABEL }
        presentation.locationString =
            "${file.path.removePrefix("$rootPath/")}:${bookmark.line + 1}"
        presentation.setIcon(AllIcons.Nodes.Bookmark)
    }

    override fun getEqualityObjects(): Array<Any> =
        arrayOf(EQUALITY_KEY, file, bookmark.line, bookmark.title)

    companion object {
        const val UNTITLED_LABEL = "(untitled)"
        private const val EQUALITY_KEY = "megatron.bookmark"
    }
}
```

- [ ] **Step 2: Wire into the root node**

Modify `src/main/kotlin/com/daverobins/projectfilesbrowser/FilteredTreeStructure.kt`:

Replace the `FilteredTreeStructure` class (lines 11-19) with:

```kotlin
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
```

Add two params to the END of the `FileNode` constructor parameter list (after `excludedFiles`):

```kotlin
    private val scanner: BookmarkScanner? = null,
    private val sets: ConfigSetManager? = null,
```

Replace `FileNode.getChildren()` (currently lines 38-54) with:

```kotlin
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
```

Everything else in the file stays as is (`flatChildren`, `folderChildren`, `collectVisibleFiles`, `isVisible`, `hasVisibleContent`, `relativePath`, `update`, `getEqualityObjects`).

- [ ] **Step 3: Compile and run the suite**

Run: `./gradlew.bat test`
Expected: PASS — `FilteredTreeStructureTest` still passes because the new constructor params default to null, and null scanner/sets short-circuit `bookmarksNode()` to empty.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/daverobins/projectfilesbrowser/BookmarkNodes.kt src/main/kotlin/com/daverobins/projectfilesbrowser/FilteredTreeStructure.kt
git commit -m "feat: Bookmarks tree node pinned after root children in all view modes"
```

---

### Task 4: Toolbar action, Refresh relocation, live updates, navigation

**Files:**
- Create: `src/main/kotlin/com/daverobins/projectfilesbrowser/BookmarkAction.kt`
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/ProjectFilesPanel.kt`
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/FolderActions.kt`

**Interfaces:**
- Consumes: `bookmarkInsertion(caretLineText, fileName, activeSet): BookmarkInsertion` and `BOOKMARK_MARKER_WORD` (Task 1), `BookmarkScanner` + `hadBookmarks(path)` (Task 2), `BookmarkNode.file`/`.bookmark` (Task 3), `ConfigSetManager.effectiveSet()` (existing).
- Produces: `class BookmarkAction(project, sets)`; `MegatronTreePopupGroup` gains a trailing constructor param `onRefresh: () -> Unit`.

- [ ] **Step 1: Create BookmarkAction**

Create `src/main/kotlin/com/daverobins/projectfilesbrowser/BookmarkAction.kt`:

```kotlin
package com.daverobins.projectfilesbrowser

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.wm.IdeFocusManager

/**
 * Inserts a bookmark comment above the caret line of the active editor, stamps
 * the active set, and leaves the caret between the quotes so the title is
 * typed in place. Disabled when no text editor is open.
 */
class BookmarkAction(
    private val project: Project,
    private val sets: ConfigSetManager,
) : AnAction("Add Bookmark", "Insert a bookmark comment at the caret in the active editor", AllIcons.Nodes.Bookmark) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = FileEditorManager.getInstance(project).selectedTextEditor != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val document = editor.document
        val fileName = FileDocumentManager.getInstance().getFile(document)?.name ?: ""
        val line = editor.caretModel.logicalPosition.line
        val lineStart: Int
        val lineText: String
        if (line < document.lineCount) {
            lineStart = document.getLineStartOffset(line)
            lineText = document.getText(TextRange(lineStart, document.getLineEndOffset(line)))
        } else {
            // Caret on the virtual line past the end (empty document or trailing caret).
            lineStart = document.textLength
            lineText = ""
        }
        val insertion = bookmarkInsertion(lineText, fileName, sets.effectiveSet())
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(lineStart, insertion.lineText + "\n")
            editor.caretModel.moveToOffset(lineStart + insertion.caretColumn)
        }
        IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
    }
}
```

- [ ] **Step 2: Move Refresh into the right-click menu**

Modify `src/main/kotlin/com/daverobins/projectfilesbrowser/FolderActions.kt`:

Add import (the file has no `AllIcons` import yet):

```kotlin
import com.intellij.icons.AllIcons
```

Add a trailing constructor parameter to `MegatronTreePopupGroup` (after `onChanged`):

```kotlin
    private val onRefresh: () -> Unit,
```

In `getChildren`, just before `return actions.toTypedArray()`, append:

```kotlin
        if (actions.isNotEmpty()) actions.add(Separator.getInstance())
        actions.add(RefreshAction())
```

Add this inner class alongside the other inner actions (e.g. after `DeleteFolderAction`):

```kotlin
    private inner class RefreshAction :
        AnAction("Refresh", "Rebuild the file tree", AllIcons.Actions.Refresh) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) = onRefresh()
    }
```

- [ ] **Step 3: Rewire ProjectFilesPanel**

Modify `src/main/kotlin/com/daverobins/projectfilesbrowser/ProjectFilesPanel.kt`:

3a. Imports — add:

```kotlin
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent as EditorDocumentEvent
import com.intellij.openapi.editor.event.DocumentListener as EditorDocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
```

(The aliases avoid clashing with the already-imported `javax.swing.event.DocumentEvent`/`DocumentListener` used by the quick-filter field. Keep the existing `AllIcons` import — it is still used by nothing after the refresh action moves, so REMOVE `import com.intellij.icons.AllIcons` only if the compiler flags it as unused.)

3b. Fields — add a scanner and pass it (plus `sets`) to the structure. Replace:

```kotlin
    private val structureModel =
        StructureTreeModel(FilteredTreeStructure(project, rootDir, engine, folderStore), parentDisposable)
```

with:

```kotlin
    private val scanner = BookmarkScanner()
    private val structureModel =
        StructureTreeModel(
            FilteredTreeStructure(project, rootDir, engine, folderStore, scanner, sets),
            parentDisposable,
        )
```

(`sets` is declared before `folderStore`, so initialization order is safe.)

3c. Toolbar — replace the `refresh` action (lines 70-74) and the group. Delete:

```kotlin
        val refresh = object : AnAction("Refresh", "Rebuild the file tree", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                rootDir.refresh(true, true) { structureModel.invalidateAsync() }
            }
        }
```

and change the group to start with the bookmark action:

```kotlin
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "ProjectFilesBrowser",
            DefaultActionGroup(
                BookmarkAction(project, sets),
                SetSwitcherAction(project, sets) { configChanged() },
                FilterDropdownAction(project, engine) { structureModel.invalidateAsync() },
                FlatViewToggleAction(project) { structureModel.invalidateAsync() },
                FolderViewToggleAction(project) { structureModel.invalidateAsync() },
            ),
            true,
        )
```

If `AnAction`/`AnActionEvent`/`AllIcons` imports become unused after this, remove them.

3d. Popup — pass the refresh callback (new last argument):

```kotlin
        PopupHandler.installFollowingSelectionTreePopup(
            tree,
            MegatronTreePopupGroup(project, rootDir, folderStore, tree, { structureModel.invalidateAsync() }) {
                rootDir.refresh(true, true) { structureModel.invalidateAsync() }
            },
            "MegatronTreePopup",
        )
```

3e. Live updates — in `init`, after the `projectModelGate.subscribe` block, add:

```kotlin
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
                        event.document.charsSequence.contains(BOOKMARK_MARKER_WORD, ignoreCase = true)
                    ) {
                        bookmarkAlarm.cancelAndRequest()
                    }
                }
            },
            parentDisposable,
        )
```

3f. Navigation — replace `openSelection` and the top of `openPairOrSelection`:

```kotlin
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
```

- [ ] **Step 4: Compile and run the suite**

Run: `./gradlew.bat test`
Expected: PASS. If `MegatronTreePopupGroup` is constructed anywhere else (it isn't, but verify with grep), those sites need the new argument:

```bash
grep -rn "MegatronTreePopupGroup(" src/
```

Expected: only `FolderActions.kt` (declaration) and `ProjectFilesPanel.kt` (the one call).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/daverobins/projectfilesbrowser/BookmarkAction.kt src/main/kotlin/com/daverobins/projectfilesbrowser/ProjectFilesPanel.kt src/main/kotlin/com/daverobins/projectfilesbrowser/FolderActions.kt
git commit -m "feat: bookmark toolbar action, live tree updates, Refresh to context menu"
```

---

### Task 5: Docs, version 0.10.0, plugin zip

**Files:**
- Modify: `README.md` (toolbar table row, right-click tables, new Bookmarks section)
- Modify: `build.gradle.kts:10` (version)

**Interfaces:**
- Consumes: everything prior (documents shipped behavior).
- Produces: `build/distributions/clionprojectview-0.10.0.zip` for sandbox install.

- [ ] **Step 1: Update README.md**

In the toolbar table ("## The toolbar, left to right"), replace the Refresh row:

```markdown
| **🔖 Add Bookmark** | Inserts a bookmark comment above the caret line of the active editor — `// megatron/<active set>: ""` (`#` in CMake files) — and puts the caret between the quotes so you type the title in place. Disabled when no editor is open. |
```

In the "## Right-click menu" section, add to BOTH context notes — simplest: after the folders table, add:

```markdown
On **anything** (always shown, last entry):

| Entry | What it does |
|---|---|
| **Refresh** | Forces a VFS refresh and rebuilds the tree. Rarely needed — the tree auto-refreshes when relevant files change. |
```

Add a new section after "## Opening files" (before "## Right-click menu"):

```markdown
## Bookmarks

A bookmark is a plain comment in your code:

```cpp
// megatron: "fix this overflow"          ← shown in every config set
// megatron/default: "wire up the panel"  ← shown only when the 'default' set is active
```

(`# megatron: "..."` in CMake files.) The **🔖 Add Bookmark** toolbar button inserts one above the caret line, pre-stamped with the active set, caret between the quotes.

All bookmarks in filter-visible files appear under a **Bookmarks** node pinned at the bottom of the tree (hidden when there are none), titled by the quoted text with a grey `path:line`. Double-click or Enter jumps to the line. To delete or edit a bookmark, edit the comment. Bookmarks in files hidden by the current filters (or quick filter, or CMake gate) don't appear.
```

- [ ] **Step 2: Bump the version**

In `build.gradle.kts` line 10, change:

```kotlin
version = "0.9.2"
```

to:

```kotlin
version = "0.10.0"
```

- [ ] **Step 3: Full suite + build the plugin zip**

Run: `./gradlew.bat test buildPlugin`
Expected: PASS; `build/distributions/clionprojectview-0.10.0.zip` exists.

- [ ] **Step 4: Commit**

```bash
git add README.md build.gradle.kts
git commit -m "docs: bookmarks user manual; version 0.10.0"
```

---

## Manual sandbox checklist (user-run, after install)

1. Click 🔖 with `main.cpp` open → `// megatron/default: ""` appears above the caret line, caret between quotes; type a title → Bookmarks node appears at the bottom within ~a second (file still unsaved).
2. Same in `CMakeLists.txt` → `# megatron/default: ""`.
3. Double-click the bookmark → caret lands on the comment line.
4. Switch to another set → set-stamped bookmark disappears; a `// megatron: "..."` (no set) bookmark stays.
5. Quick-filter to exclude the file → bookmark disappears.
6. Delete the comment line → bookmark disappears (debounced).
7. Flat and Folder views → Bookmarks node still last.
8. Right-click anywhere in the tree → Refresh present and working; toolbar has no Refresh.
9. 🔖 greyed out when all editors are closed.
