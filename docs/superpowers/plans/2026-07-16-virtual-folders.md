# Virtual Folders (Phase 5) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** VS-style virtual folders in the Megatron tool window — a third view mode where files are grouped into manually created named folders, driven by a plain-text `megatron.folders` project file, edited via context menu and drag-and-drop.

**Architecture:** A pure immutable `FolderLayout` model (parser + serializer + mutations) is the single source of truth, cached behind `FolderLayoutStore` keyed on the file's VFS modification stamp (same pattern as `FilterEngine`). The tree gains a `FOLDERS` root branch producing `VirtualFolderNode`s plus a pinned `<Unassigned>` `FileNode` that excludes assigned files. All UI mutations rewrite `megatron.folders`; hand edits reload through the existing VFS watcher.

**Tech Stack:** Kotlin 2.3.0, IntelliJ Platform Gradle Plugin 2.18.1, CLion 2026.1.1, JUnit 4 `BasePlatformTestCase`, Swing `TransferHandler` for DnD (javap-verified decision: IJ `DnDSupport` has unverifiable behavior contracts; Swing DnD is fully specified).

## Global Constraints

- Before any Gradle call in a fresh PowerShell: `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"`. Test command: `.\gradlew.bat test` (from `C:\~prj\Dropbox\vibeProjects\clionprojectview`).
- The folders file is named exactly `megatron.folders`, at the project root (sibling of `megatron.filters`).
- The unassigned bucket label is exactly `<Unassigned>`.
- All path and folder-name comparisons are case-insensitive; first-declared casing wins for display.
- Serializer output: folders sorted by lowercase full path, each followed by its files sorted by lowercase path, file lines indented two spaces, every line `\n`-terminated.
- One folder per file; later assignment replaces earlier (last wins).
- `MegatronFilterState.State.flatMode` is REPLACED by `viewMode: ViewMode = ViewMode.TREE` (enum `TREE | FLAT | FOLDERS`). The one-time loss of users' persisted flat toggle is accepted by the spec.
- Toolbar actions use `ActionUpdateThread.BGT`; the tree popup actions use `ActionUpdateThread.EDT` (they read Swing tree selection).
- `BasePlatformTestCase` reuses the project between test methods: any test that mutates `MegatronFilterState` MUST restore it in a `finally` block (`setViewMode(ViewMode.TREE)`, `setCmakeGateEnabled(false)`).
- Plugin version becomes `0.5.0` (Task 8).
- Commit messages: conventional commits (`feat:`, `test:`, `refactor:`, `chore:`).
- Verified API facts (do not re-derive): `PopupHandler.installFollowingSelectionTreePopup(JTree, ActionGroup, String)` exists; `Messages.showInputDialog(Project, String, String, Icon, String, InputValidator)` exists; `Messages.showYesNoDialog(Project, String, String, String, String, Icon)` exists and compares against `Messages.YES`; `AllIcons.Nodes.Folder` exists (already used).

---

### Task 1: FolderLayout — pure model, parser, serializer, mutations, validation

**Files:**
- Create: `src/main/kotlin/com/daverobins/projectfilesbrowser/FolderLayout.kt`
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/FolderLayoutTest.kt`

**Interfaces:**
- Consumes: nothing (pure Kotlin, no platform imports).
- Produces (later tasks depend on these EXACT signatures):
  - `data class FileAssignment(val path: String, val folder: String)`
  - `class FolderLayout(folders: List<String> = emptyList(), assignments: List<FileAssignment> = emptyList())` with members:
    `val folders: List<String>`, `fun folderFor(relativePath: String): String?`,
    `fun assignedFilesLowercase(): Set<String>`, `fun filesIn(folder: String): List<String>`,
    `fun childFolders(parent: String): List<String>`, `fun allFolders(): List<String>`,
    `fun hasFolder(path: String): Boolean`, `fun withFolder(path: String): FolderLayout`,
    `fun withAssignment(relativePath: String, folder: String): FolderLayout`,
    `fun withUnassigned(relativePath: String): FolderLayout`,
    `fun withFolderRenamed(path: String, newName: String): FolderLayout`,
    `fun withFolderDeleted(path: String): FolderLayout`, `fun serialize(): String`
  - Top-level: `fun parseFoldersFile(text: String): FolderLayout`,
    `fun validateFolderName(name: String, siblingNames: Collection<String>): String?`

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/daverobins/projectfilesbrowser/FolderLayoutTest.kt`:

```kotlin
package com.daverobins.projectfilesbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderLayoutTest {

    @Test
    fun `parses folders and assignments`() {
        val layout = parseFoldersFile(
            """
            # comment
            Core/
              src/engine.cpp
              src/engine.h
            Core/Math/
              src/vec.h
            Platform/
            """.trimIndent()
        )
        assertEquals(listOf("Core", "Core/Math", "Platform"), layout.folders)
        assertEquals("Core", layout.folderFor("src/engine.cpp"))
        assertEquals("Core/Math", layout.folderFor("src/vec.h"))
        assertNull(layout.folderFor("src/other.cpp"))
    }

    @Test
    fun `file line before any folder is ignored`() {
        val layout = parseFoldersFile("orphan.cpp\nCore/\n  a.cpp\n")
        assertNull(layout.folderFor("orphan.cpp"))
        assertEquals("Core", layout.folderFor("a.cpp"))
    }

    @Test
    fun `blank lines comments and indentation are cosmetic`() {
        val layout = parseFoldersFile("\n  # note\n  Core/  \n\n    src/a.cpp   \n")
        assertEquals(listOf("Core"), layout.folders)
        assertEquals("Core", layout.folderFor("src/a.cpp"))
    }

    @Test
    fun `backslashes normalize and lookups are case-insensitive`() {
        val layout = parseFoldersFile("Core/\n  src\\Engine.CPP\n")
        assertEquals("Core", layout.folderFor("SRC/engine.cpp"))
        assertTrue("src/engine.cpp" in layout.assignedFilesLowercase())
    }

    @Test
    fun `duplicate assignment last wins`() {
        val layout = parseFoldersFile("A/\n  x.cpp\nB/\n  x.cpp\n")
        assertEquals("B", layout.folderFor("x.cpp"))
        assertEquals(emptyList<String>(), layout.filesIn("A"))
        assertEquals(listOf("x.cpp"), layout.filesIn("B"))
    }

    @Test
    fun `nested folder declaration auto-creates parents`() {
        val layout = parseFoldersFile("Core/Math/Linear/\n")
        assertEquals(listOf("Core", "Core/Math", "Core/Math/Linear"), layout.folders)
    }

    @Test
    fun `folder casing merges case-insensitively with first declaration winning`() {
        val layout = parseFoldersFile("Core/\ncore/\n  a.cpp\nCORE/Math/\n")
        assertEquals(listOf("Core", "Core/Math"), layout.folders)
        assertEquals("Core", layout.folderFor("a.cpp"))
    }

    @Test
    fun `childFolders returns direct children sorted by name`() {
        val layout = parseFoldersFile("B/\nA/\nA/zz/\nA/mm/\n")
        assertEquals(listOf("A", "B"), layout.childFolders(""))
        assertEquals(listOf("A/mm", "A/zz"), layout.childFolders("A"))
        assertEquals(listOf("A/mm", "A/zz"), layout.childFolders("a"))
    }

    @Test
    fun `serialize writes sorted folders then sorted files with two-space indent`() {
        val layout = parseFoldersFile("Platform/\n  win.cpp\nCore/\n  src/b.cpp\n  src/A.cpp\nEmpty/\n")
        assertEquals(
            "Core/\n  src/A.cpp\n  src/b.cpp\nEmpty/\nPlatform/\n  win.cpp\n",
            layout.serialize(),
        )
    }

    @Test
    fun `serialize then parse round-trips`() {
        val original = parseFoldersFile("Core/\n  src/a.cpp\nCore/Math/\n  v.h\nPlatform/\n")
        val reparsed = parseFoldersFile(original.serialize())
        assertEquals(original.serialize(), reparsed.serialize())
        assertEquals(original.folders.sorted(), reparsed.folders.sorted())
    }

    @Test
    fun `withFolder adds folder and keeps assignments`() {
        val layout = parseFoldersFile("Core/\n  a.cpp\n").withFolder("New/Sub")
        assertTrue(layout.hasFolder("New"))
        assertTrue(layout.hasFolder("new/sub"))
        assertEquals("Core", layout.folderFor("a.cpp"))
    }

    @Test
    fun `withAssignment moves file between folders`() {
        val layout = parseFoldersFile("A/\n  x.cpp\nB/\n").withAssignment("x.cpp", "B")
        assertEquals("B", layout.folderFor("x.cpp"))
        assertEquals(emptyList<String>(), layout.filesIn("A"))
    }

    @Test
    fun `withAssignment to unknown folder creates it`() {
        val layout = FolderLayout().withAssignment("src/a.cpp", "Fresh")
        assertEquals(listOf("Fresh"), layout.folders)
        assertEquals("Fresh", layout.folderFor("src/a.cpp"))
    }

    @Test
    fun `withUnassigned removes the assignment`() {
        val layout = parseFoldersFile("A/\n  x.cpp\n").withUnassigned("X.CPP")
        assertNull(layout.folderFor("x.cpp"))
        assertTrue(layout.hasFolder("A"))
    }

    @Test
    fun `withFolderRenamed cascades to descendants and assignments`() {
        val layout = parseFoldersFile("Core/\n  a.cpp\nCore/Math/\n  v.h\nOther/\n")
            .withFolderRenamed("Core", "Base")
        assertEquals(listOf("Base", "Base/Math", "Other"), layout.folders)
        assertEquals("Base", layout.folderFor("a.cpp"))
        assertEquals("Base/Math", layout.folderFor("v.h"))
    }

    @Test
    fun `withFolderDeleted removes subtree and unassigns its files`() {
        val layout = parseFoldersFile("Core/\n  a.cpp\nCore/Math/\n  v.h\nOther/\n  o.cpp\n")
            .withFolderDeleted("Core")
        assertEquals(listOf("Other"), layout.folders)
        assertNull(layout.folderFor("a.cpp"))
        assertNull(layout.folderFor("v.h"))
        assertEquals("Other", layout.folderFor("o.cpp"))
    }

    @Test
    fun `validateFolderName rejects empty slashes and duplicates`() {
        assertNotNull(validateFolderName("", emptyList()))
        assertNotNull(validateFolderName("   ", emptyList()))
        assertNotNull(validateFolderName("a/b", emptyList()))
        assertNotNull(validateFolderName("a\\b", emptyList()))
        assertNotNull(validateFolderName("core", listOf("Core", "Other")))
        assertNull(validateFolderName("Fresh", listOf("Core", "Other")))
        assertNull(validateFolderName("  Fresh  ", listOf("Core")))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.FolderLayoutTest"
```
Expected: COMPILE FAILURE (`FolderLayout` unresolved).

- [ ] **Step 3: Implement FolderLayout.kt**

Create `src/main/kotlin/com/daverobins/projectfilesbrowser/FolderLayout.kt`:

```kotlin
package com.daverobins.projectfilesbrowser

/** A file assigned to a virtual folder: original-case relative path plus owning folder path. */
data class FileAssignment(val path: String, val folder: String)

/**
 * Immutable model of megatron.folders: the user's virtual-folder tree plus file
 * assignments. Folder paths use '/' separators ("Core/Math"); all comparisons are
 * case-insensitive with the first-declared casing winning for display. One folder
 * per file: a later assignment replaces an earlier one.
 */
class FolderLayout(
    folders: List<String> = emptyList(),
    assignments: List<FileAssignment> = emptyList(),
) {

    /** Canonical folder paths (parents auto-created), first-declared casing, declaration order. */
    val folders: List<String>

    private val byFile: Map<String, FileAssignment> // key: lowercase normalized relative path

    init {
        val canonical = LinkedHashMap<String, String>() // lowercase path -> display path
        fun canonicalize(rawPath: String): String? {
            val segments = rawPath.replace('\\', '/').split('/')
                .map { it.trim() }.filter { it.isNotEmpty() }
            if (segments.isEmpty()) return null
            var lower = ""
            var display = ""
            for (segment in segments) {
                lower = if (lower.isEmpty()) segment.lowercase() else "$lower/${segment.lowercase()}"
                val existing = canonical[lower]
                if (existing == null) {
                    display = if (display.isEmpty()) segment else "$display/$segment"
                    canonical[lower] = display
                } else {
                    display = existing
                }
            }
            return display
        }
        folders.forEach { canonicalize(it) }
        val files = LinkedHashMap<String, FileAssignment>()
        for (assignment in assignments) {
            val path = normalizeFilePath(assignment.path)
            if (path.isEmpty()) continue
            val folder = canonicalize(assignment.folder) ?: continue
            files[path.lowercase()] = FileAssignment(path, folder)
        }
        this.folders = canonical.values.toList()
        this.byFile = files
    }

    fun folderFor(relativePath: String): String? =
        byFile[normalizeFilePath(relativePath).lowercase()]?.folder

    /** Lowercase normalized relative paths of every assigned file (for tree exclusion). */
    fun assignedFilesLowercase(): Set<String> = byFile.keys

    /** Original-case paths of files assigned directly to [folder], sorted. */
    fun filesIn(folder: String): List<String> =
        byFile.values.filter { it.folder.equals(folder, ignoreCase = true) }
            .map { it.path }
            .sortedBy { it.lowercase() }

    /** Direct child folders of [parent] ("" = top level), sorted by display name. */
    fun childFolders(parent: String): List<String> =
        folders.filter { it.substringBeforeLast('/', "").equals(parent, ignoreCase = true) }
            .sortedBy { it.substringAfterLast('/').lowercase() }

    /** Every folder path, sorted — stable order for serialization and menus. */
    fun allFolders(): List<String> = folders.sortedBy { it.lowercase() }

    fun hasFolder(path: String): Boolean = folders.any { it.equals(path, ignoreCase = true) }

    fun withFolder(path: String): FolderLayout =
        FolderLayout(folders + path, byFile.values.toList())

    fun withAssignment(relativePath: String, folder: String): FolderLayout =
        FolderLayout(folders, byFile.values.toList() + FileAssignment(relativePath, folder))

    fun withUnassigned(relativePath: String): FolderLayout {
        val key = normalizeFilePath(relativePath).lowercase()
        return FolderLayout(folders, byFile.values.filterNot { it.path.lowercase() == key })
    }

    fun withFolderRenamed(path: String, newName: String): FolderLayout {
        val parent = path.substringBeforeLast('/', "")
        val newPath = if (parent.isEmpty()) newName else "$parent/$newName"
        return FolderLayout(
            folders.map { remapped(it, path, newPath) },
            byFile.values.map { it.copy(folder = remapped(it.folder, path, newPath)) },
        )
    }

    fun withFolderDeleted(path: String): FolderLayout =
        FolderLayout(
            folders.filterNot { inSubtree(it, path) },
            byFile.values.filterNot { inSubtree(it.folder, path) },
        )

    fun serialize(): String {
        val sb = StringBuilder()
        for (folder in allFolders()) {
            sb.append(folder).append("/\n")
            for (file in filesIn(folder)) sb.append("  ").append(file).append('\n')
        }
        return sb.toString()
    }

    companion object {
        private fun normalizeFilePath(path: String): String =
            path.replace('\\', '/').trim().trimStart('/').trimEnd('/')

        private fun inSubtree(candidate: String, root: String): Boolean =
            candidate.equals(root, ignoreCase = true) ||
                candidate.lowercase().startsWith(root.lowercase() + "/")

        private fun remapped(candidate: String, oldRoot: String, newRoot: String): String =
            when {
                candidate.equals(oldRoot, ignoreCase = true) -> newRoot
                candidate.lowercase().startsWith(oldRoot.lowercase() + "/") ->
                    newRoot + candidate.substring(oldRoot.length)
                else -> candidate
            }
    }
}

/** Parses megatron.folders text. Unparseable lines are silently skipped. */
fun parseFoldersFile(text: String): FolderLayout {
    val folders = ArrayList<String>()
    val assignments = ArrayList<FileAssignment>()
    var currentFolder: String? = null
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val normalized = line.replace('\\', '/')
        if (normalized.endsWith("/")) {
            val cleaned = normalized.split('/').map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString("/")
            if (cleaned.isEmpty()) continue
            folders.add(cleaned)
            currentFolder = cleaned
        } else {
            val folder = currentFolder ?: continue // file line before any folder: malformed, skip
            assignments.add(FileAssignment(normalized, folder))
        }
    }
    return FolderLayout(folders, assignments)
}

/** Returns an error message, or null when [name] (after trimming) is a valid new sibling name. */
fun validateFolderName(name: String, siblingNames: Collection<String>): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "Folder name cannot be empty"
    if (trimmed.contains('/') || trimmed.contains('\\')) return "Folder name cannot contain slashes"
    if (siblingNames.any { it.equals(trimmed, ignoreCase = true) }) {
        return "A folder named '$trimmed' already exists here"
    }
    return null
}
```

- [ ] **Step 4: Run tests to verify they pass**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.FolderLayoutTest"
```
Expected: BUILD SUCCESSFUL, 17 tests pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/kotlin/com/daverobins/projectfilesbrowser/FolderLayout.kt src/test/kotlin/com/daverobins/projectfilesbrowser/FolderLayoutTest.kt
git commit -m "feat: FolderLayout model, parser, serializer, mutations for megatron.folders"
```

---

### Task 2: ViewMode enum replaces flatMode

**Files:**
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/MegatronFilterState.kt`
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/FlatViewToggleAction.kt`
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/FilteredTreeStructure.kt` (the `isFlatMode()` call site only)
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/MegatronFilterStateTest.kt` (replace flatMode tests)
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/FilteredTreeStructureTest.kt` (replace `setFlatMode` calls)

**Interfaces:**
- Consumes: nothing new.
- Produces: `enum class ViewMode { TREE, FLAT, FOLDERS }` (top level in MegatronFilterState.kt); `MegatronFilterState.getViewMode(): ViewMode` and `setViewMode(mode: ViewMode)`; `State.viewMode: ViewMode = ViewMode.TREE`. `isFlatMode`/`setFlatMode` and `State.flatMode` are DELETED.

- [ ] **Step 1: Update MegatronFilterState.kt**

Add above the class:

```kotlin
/** How the Megatron tree presents files. */
enum class ViewMode { TREE, FLAT, FOLDERS }
```

In `State`, replace `var flatMode: Boolean = false` with:

```kotlin
var viewMode: ViewMode = ViewMode.TREE
```

In `getState()`, replace `flatMode = current.flatMode` with `viewMode = current.viewMode`.

Replace the `isFlatMode`/`setFlatMode` accessors with:

```kotlin
@Synchronized
fun getViewMode(): ViewMode = current.viewMode

@Synchronized
fun setViewMode(mode: ViewMode) {
    current.viewMode = mode
}
```

- [ ] **Step 2: Update FlatViewToggleAction.kt**

Replace the `isSelected`/`setSelected` bodies:

```kotlin
override fun isSelected(e: AnActionEvent): Boolean =
    MegatronFilterState.getInstance(project).getViewMode() == ViewMode.FLAT

override fun setSelected(e: AnActionEvent, state: Boolean) {
    MegatronFilterState.getInstance(project)
        .setViewMode(if (state) ViewMode.FLAT else ViewMode.TREE)
    onModeChanged()
}
```

- [ ] **Step 3: Update the FileNode call site in FilteredTreeStructure.kt**

Replace:

```kotlin
if (isRootNode && MegatronFilterState.getInstance(project).isFlatMode()) {
    return flatChildren()
}
```

with:

```kotlin
if (isRootNode && MegatronFilterState.getInstance(project).getViewMode() == ViewMode.FLAT) {
    return flatChildren()
}
```

(`FOLDERS` intentionally behaves like `TREE` until Task 4.)

- [ ] **Step 4: Update tests**

In `MegatronFilterStateTest.kt`: replace every flatMode-based test with viewMode equivalents. The round-trip/defensive-copy tests must now assert on `viewMode`, e.g.:

```kotlin
fun testViewModeDefaultsToTree() {
    assertEquals(ViewMode.TREE, MegatronFilterState().getViewMode())
}

fun testViewModeRoundTrips() {
    val state = MegatronFilterState()
    state.setViewMode(ViewMode.FOLDERS)
    val restored = MegatronFilterState()
    restored.loadState(state.state)
    assertEquals(ViewMode.FOLDERS, restored.getViewMode())
}

fun testGetStateReturnsDefensiveCopyOfViewMode() {
    val state = MegatronFilterState()
    val snapshot = state.state
    state.setViewMode(ViewMode.FLAT)
    assertEquals(ViewMode.TREE, snapshot.viewMode)
}
```

Keep the exact style/assertion helpers the existing file uses (read it first; it may be JUnit3-style `fun testX()` inside `BasePlatformTestCase` or plain JUnit4 — mirror what is there).

In `FilteredTreeStructureTest.kt`: replace `state.setFlatMode(true)` with `state.setViewMode(ViewMode.FLAT)`, `state.setFlatMode(false)` with `state.setViewMode(ViewMode.TREE)` (including the `finally` blocks).

- [ ] **Step 5: Run the full suite**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
.\gradlew.bat test
```
Expected: BUILD SUCCESSFUL, all tests pass (63 before this task; count may shift ±2 with test renames).

- [ ] **Step 6: Commit**

```powershell
git add -A src
git commit -m "refactor: replace flatMode boolean with ViewMode enum (TREE/FLAT/FOLDERS)"
```

---

### Task 3: FolderLayoutStore + notification group

**Files:**
- Create: `src/main/kotlin/com/daverobins/projectfilesbrowser/FolderLayoutStore.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` (add notificationGroup extension)
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/FolderLayoutStoreTest.kt`

**Interfaces:**
- Consumes: `FolderLayout`, `parseFoldersFile` (Task 1).
- Produces: `class FolderLayoutStore(project: Project, rootDir: VirtualFile)` with
  `fun layout(): FolderLayout`, `fun mutate(change: (FolderLayout) -> FolderLayout)`,
  `companion object { const val FOLDERS_FILE_NAME = "megatron.folders" }`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/daverobins/projectfilesbrowser/FolderLayoutStoreTest.kt`:

```kotlin
package com.daverobins.projectfilesbrowser

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FolderLayoutStoreTest : BasePlatformTestCase() {

    fun testLayoutIsEmptyWithoutFile() {
        myFixture.addFileToProject("s1/src/a.cpp", "")
        val rootDir = requireNotNull(myFixture.findFileInTempDir("s1"))
        val store = FolderLayoutStore(project, rootDir)
        assertEmpty(store.layout().folders)
    }

    fun testMutateCreatesFileAndRewritesIt() {
        myFixture.addFileToProject("s2/src/a.cpp", "")
        val rootDir = requireNotNull(myFixture.findFileInTempDir("s2"))
        val store = FolderLayoutStore(project, rootDir)

        store.mutate { it.withFolder("Core").withAssignment("src/a.cpp", "Core") }

        val file = requireNotNull(rootDir.findChild(FolderLayoutStore.FOLDERS_FILE_NAME))
        assertEquals("Core/\n  src/a.cpp\n", String(file.contentsToByteArray(), file.charset))

        store.mutate { it.withAssignment("src/a.cpp", "Core/Sub") }
        assertEquals(
            "Core/\nCore/Sub/\n  src/a.cpp\n",
            String(file.contentsToByteArray(), file.charset),
        )
    }

    fun testLayoutReloadsAfterExternalEdit() {
        myFixture.addFileToProject("s3/megatron.folders", "Core/\n  a.cpp\n")
        val rootDir = requireNotNull(myFixture.findFileInTempDir("s3"))
        val store = FolderLayoutStore(project, rootDir)
        assertEquals("Core", store.layout().folderFor("a.cpp"))

        val file = requireNotNull(rootDir.findChild(FolderLayoutStore.FOLDERS_FILE_NAME))
        WriteAction.runAndWait<RuntimeException> {
            VfsUtil.saveText(file, "Base/\n  a.cpp\n")
        }
        assertEquals("Base", store.layout().folderFor("a.cpp"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.FolderLayoutStoreTest"
```
Expected: COMPILE FAILURE (`FolderLayoutStore` unresolved).

- [ ] **Step 3: Implement FolderLayoutStore.kt**

```kotlin
package com.daverobins.projectfilesbrowser

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException

/**
 * Panel-owned facade over megatron.folders: caches the parsed layout by the
 * file's VFS modification stamp; mutations rewrite the file (single source of
 * truth) inside a write command. Mutations must run on the EDT.
 */
class FolderLayoutStore(
    private val project: Project,
    private val rootDir: VirtualFile,
) {

    private var cachedStamp = NO_FILE_STAMP
    private var cachedLayout = FolderLayout()

    @Synchronized
    fun layout(): FolderLayout {
        val file = rootDir.findChild(FOLDERS_FILE_NAME)
        if (file == null || file.isDirectory || !file.isValid) {
            cachedStamp = NO_FILE_STAMP
            cachedLayout = FolderLayout()
            return cachedLayout
        }
        if (file.modificationStamp != cachedStamp) {
            cachedLayout = parseFoldersFile(loadText(file))
            cachedStamp = file.modificationStamp
        }
        return cachedLayout
    }

    /** Applies [change] to the current layout and rewrites megatron.folders. */
    fun mutate(change: (FolderLayout) -> FolderLayout) {
        val updated = change(layout())
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                val file = rootDir.findChild(FOLDERS_FILE_NAME)
                    ?: rootDir.createChildData(this, FOLDERS_FILE_NAME)
                VfsUtil.saveText(file, updated.serialize())
            }
        } catch (e: IOException) {
            logger<FolderLayoutStore>().warn("Failed to write $FOLDERS_FILE_NAME", e)
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Megatron")
                .createNotification(
                    "Could not update $FOLDERS_FILE_NAME: ${e.message}",
                    NotificationType.ERROR,
                )
                .notify(project)
        }
    }

    private fun loadText(file: VirtualFile): String =
        try {
            String(file.contentsToByteArray(), file.charset)
        } catch (e: IOException) {
            logger<FolderLayoutStore>().warn("Failed to read ${file.path}", e)
            ""
        }

    companion object {
        const val FOLDERS_FILE_NAME = "megatron.folders"
        private const val NO_FILE_STAMP = -1L
    }
}
```

- [ ] **Step 4: Register the notification group in plugin.xml**

Inside the existing `<extensions defaultExtensionNs="com.intellij">` block add:

```xml
<notificationGroup id="Megatron" displayType="BALLOON"/>
```

- [ ] **Step 5: Run tests to verify they pass**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.FolderLayoutStoreTest"
```
Expected: BUILD SUCCESSFUL, 3 tests pass.

- [ ] **Step 6: Commit**

```powershell
git add src/main/kotlin/com/daverobins/projectfilesbrowser/FolderLayoutStore.kt src/test/kotlin/com/daverobins/projectfilesbrowser/FolderLayoutStoreTest.kt src/main/resources/META-INF/plugin.xml
git commit -m "feat: FolderLayoutStore caching and rewriting megatron.folders"
```

---

### Task 4: Folder view tree structure

**Files:**
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/FilteredTreeStructure.kt`
- Create: `src/main/kotlin/com/daverobins/projectfilesbrowser/VirtualFolderNode.kt`
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/FilteredTreeStructureTest.kt` (add folder-view tests)

**Interfaces:**
- Consumes: `FolderLayout`/`FolderLayoutStore` (Tasks 1, 3), `ViewMode` (Task 2).
- Produces:
  - `FilteredTreeStructure(project, rootDir, engine, store: FolderLayoutStore? = null)` — new optional 4th param.
  - `FileNode` ctor: `parent` type widens to `SimpleNode?`; new optional params `store: FolderLayoutStore? = null`, `displayName: String? = null`, `excludedFiles: Set<String> = emptySet()`; new member `val isUnassignedBucket: Boolean`; companion `const val UNASSIGNED_LABEL = "<Unassigned>"`.
  - `class VirtualFolderNode(project, parent: SimpleNode, folderPath: String, store, engine, rootDir, rootPath)` with `val folderPath: String` and companion `fun resolveRelativePath(root: VirtualFile, relativePath: String): VirtualFile?`.

- [ ] **Step 1: Write the failing tests**

Append to `FilteredTreeStructureTest.kt` (and add a node-agnostic render helper):

```kotlin
fun testFolderViewShowsFoldersThenUnassigned() {
    myFixture.addFileToProject(
        "fv/megatron.folders",
        "Platform/\n  win.cpp\nCore/\n  src/engine.cpp\n  src/engine.h\nEmpty/\n",
    )
    myFixture.addFileToProject("fv/src/engine.cpp", "")
    myFixture.addFileToProject("fv/src/engine.h", "")
    myFixture.addFileToProject("fv/src/misc.cpp", "")
    myFixture.addFileToProject("fv/win.cpp", "")
    myFixture.addFileToProject("fv/CMakeLists.txt", "")

    val state = MegatronFilterState.getInstance(project)
    state.setViewMode(ViewMode.FOLDERS)
    try {
        val rootDir = requireNotNull(myFixture.findFileInTempDir("fv"))
        val store = FolderLayoutStore(project, rootDir)
        val structure = FilteredTreeStructure(project, rootDir, FilterEngine(project, rootDir), store)
        assertEquals(
            """
            fv
              Core
                engine.cpp
                engine.h
              Empty
              Platform
                win.cpp
              <Unassigned>
                src
                  misc.cpp
                CMakeLists.txt

            """.trimIndent(),
            renderNode(structure.rootElement as SimpleNode),
        )
    } finally {
        state.setViewMode(ViewMode.TREE)
    }
}

fun testFolderViewNestsSubfolders() {
    myFixture.addFileToProject("fn/megatron.folders", "Core/\nCore/Math/\n  v.h\n")
    myFixture.addFileToProject("fn/v.h", "")
    myFixture.addFileToProject("fn/main.cpp", "")

    val state = MegatronFilterState.getInstance(project)
    state.setViewMode(ViewMode.FOLDERS)
    try {
        val rootDir = requireNotNull(myFixture.findFileInTempDir("fn"))
        val store = FolderLayoutStore(project, rootDir)
        val structure = FilteredTreeStructure(project, rootDir, FilterEngine(project, rootDir), store)
        assertEquals(
            """
            fn
              Core
                Math
                  v.h
              <Unassigned>
                main.cpp

            """.trimIndent(),
            renderNode(structure.rootElement as SimpleNode),
        )
    } finally {
        state.setViewMode(ViewMode.TREE)
    }
}

fun testFolderViewAppliesFiltersInsideFoldersAndSkipsMissingFiles() {
    myFixture.addFileToProject("ff/megatron.filters", "Sources: *.cpp")
    myFixture.addFileToProject(
        "ff/megatron.folders",
        "Core/\n  a.cpp\n  notes.md\n  gone.cpp\n",
    )
    myFixture.addFileToProject("ff/a.cpp", "")
    myFixture.addFileToProject("ff/notes.md", "hidden by Sources group")

    val state = MegatronFilterState.getInstance(project)
    state.setViewMode(ViewMode.FOLDERS)
    try {
        val rootDir = requireNotNull(myFixture.findFileInTempDir("ff"))
        val store = FolderLayoutStore(project, rootDir)
        val structure = FilteredTreeStructure(project, rootDir, FilterEngine(project, rootDir), store)
        assertEquals(
            """
            ff
              Core
                a.cpp
              <Unassigned>

            """.trimIndent(),
            renderNode(structure.rootElement as SimpleNode),
        )
    } finally {
        state.setViewMode(ViewMode.TREE)
    }
}

fun testFolderViewResolvesAssignmentsCaseInsensitively() {
    myFixture.addFileToProject("fc/megatron.folders", "Core/\n  SRC/Engine.CPP\n")
    myFixture.addFileToProject("fc/src/engine.cpp", "")

    val state = MegatronFilterState.getInstance(project)
    state.setViewMode(ViewMode.FOLDERS)
    try {
        val rootDir = requireNotNull(myFixture.findFileInTempDir("fc"))
        val store = FolderLayoutStore(project, rootDir)
        val structure = FilteredTreeStructure(project, rootDir, FilterEngine(project, rootDir), store)
        assertEquals(
            """
            fc
              Core
                engine.cpp
              <Unassigned>

            """.trimIndent(),
            renderNode(structure.rootElement as SimpleNode),
        )
    } finally {
        state.setViewMode(ViewMode.TREE)
    }
}

fun testFolderViewWithoutStoreOrFileShowsPlainTreeUnderUnassigned() {
    myFixture.addFileToProject("fp/main.cpp", "")

    val state = MegatronFilterState.getInstance(project)
    state.setViewMode(ViewMode.FOLDERS)
    try {
        val rootDir = requireNotNull(myFixture.findFileInTempDir("fp"))
        val structure = FilteredTreeStructure(project, rootDir, FilterEngine(project, rootDir))
        assertEquals(
            """
            fp
              <Unassigned>
                main.cpp

            """.trimIndent(),
            renderNode(structure.rootElement as SimpleNode),
        )
    } finally {
        state.setViewMode(ViewMode.TREE)
    }
}

private fun renderNode(node: SimpleNode, indent: String = ""): String {
    node.update()
    val sb = StringBuilder().append(indent).append(node.presentation.presentableText).append('\n')
    for (child in node.children) {
        sb.append(renderNode(child, "$indent  "))
    }
    return sb.toString()
}
```

Add import `com.intellij.ui.treeStructure.SimpleNode` to the test file. Note the existing `render(FileNode)` helper stays for the older tests.

- [ ] **Step 2: Run tests to verify they fail**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.FilteredTreeStructureTest"
```
Expected: COMPILE FAILURE (4-arg `FilteredTreeStructure`, `VirtualFolderNode` unresolved).

- [ ] **Step 3: Implement**

`FilteredTreeStructure.kt` — full new content:

```kotlin
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
) : SimpleTreeStructure() {
    private val root = FileNode(project, null, rootDir, engine, rootDir.path, store = store)
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
) : SimpleNode(project, parent) {

    private val isRootNode = parent == null

    /** True for the pinned `<Unassigned>` bucket shown in folder view. */
    val isUnassignedBucket: Boolean get() = displayName == UNASSIGNED_LABEL

    override fun getChildren(): Array<SimpleNode> {
        if (!file.isDirectory) return NO_CHILDREN
        if (isRootNode) {
            when (MegatronFilterState.getInstance(project).getViewMode()) {
                ViewMode.FLAT -> return flatChildren()
                ViewMode.FOLDERS -> return folderChildren()
                ViewMode.TREE -> {}
            }
        }
        val visible = (file.children ?: return NO_CHILDREN)
            .filter { it.isValid && isVisible(it) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        if (visible.isEmpty()) return NO_CHILDREN
        return visible
            .map { FileNode(project, this, it, engine, rootPath, excludedFiles = excludedFiles) }
            .toTypedArray()
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

    /** Folder view root: the user's virtual folders, then the `<Unassigned>` bucket. */
    private fun folderChildren(): Array<SimpleNode> {
        val activeStore = store
        val layout = activeStore?.layout() ?: FolderLayout()
        val folderNodes: List<SimpleNode> =
            if (activeStore == null) emptyList()
            else layout.childFolders("").map {
                VirtualFolderNode(project, this, it, activeStore, engine, file, rootPath)
            }
        val unassigned = FileNode(
            project, this, file, engine, rootPath,
            displayName = UNASSIGNED_LABEL,
            excludedFiles = layout.assignedFilesLowercase(),
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
```

`VirtualFolderNode.kt` — new file:

```kotlin
package com.daverobins.projectfilesbrowser

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.treeStructure.SimpleNode

/** A user-defined virtual folder in folder view; children are subfolders plus assigned files. */
class VirtualFolderNode(
    private val project: Project,
    parent: SimpleNode,
    val folderPath: String,
    private val store: FolderLayoutStore,
    private val engine: FilterEngine,
    private val rootDir: VirtualFile,
    private val rootPath: String,
) : SimpleNode(project, parent) {

    override fun getChildren(): Array<SimpleNode> {
        val layout = store.layout()
        val subFolders: List<SimpleNode> = layout.childFolders(folderPath).map {
            VirtualFolderNode(project, this, it, store, engine, rootDir, rootPath)
        }
        val files: List<SimpleNode> = layout.filesIn(folderPath)
            .mapNotNull { resolveRelativePath(rootDir, it) }
            .filter { it.isValid && !it.isDirectory && engine.isFileVisible(it) }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.path.lowercase() }))
            .map { FileNode(project, this, it, engine, rootPath, flatLeaf = true) }
        return (subFolders + files).toTypedArray()
    }

    override fun update(presentation: PresentationData) {
        presentation.presentableText = folderPath.substringAfterLast('/')
        presentation.setIcon(AllIcons.Nodes.Folder)
    }

    override fun getEqualityObjects(): Array<Any> = arrayOf(EQUALITY_KEY, folderPath.lowercase())

    companion object {
        private const val EQUALITY_KEY = "megatron.virtualFolder"

        /** [VirtualFile.findFileByRelativePath] with a case-insensitive fallback per segment. */
        fun resolveRelativePath(root: VirtualFile, relativePath: String): VirtualFile? {
            root.findFileByRelativePath(relativePath)?.let { return it }
            var current: VirtualFile = root
            for (segment in relativePath.split('/')) {
                if (segment.isEmpty()) continue
                current = current.children?.firstOrNull { it.name.equals(segment, ignoreCase = true) }
                    ?: return null
            }
            return current
        }
    }
}
```

- [ ] **Step 4: Run the structure tests, then the full suite**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.FilteredTreeStructureTest"
.\gradlew.bat test
```
Expected: BUILD SUCCESSFUL both times.

- [ ] **Step 5: Commit**

```powershell
git add src/main/kotlin/com/daverobins/projectfilesbrowser/FilteredTreeStructure.kt src/main/kotlin/com/daverobins/projectfilesbrowser/VirtualFolderNode.kt src/test/kotlin/com/daverobins/projectfilesbrowser/FilteredTreeStructureTest.kt
git commit -m "feat: folder view tree structure with virtual folders and <Unassigned> bucket"
```

---

### Task 5: Watcher relevance for megatron.folders

**Files:**
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/VfsChangeWatcher.kt`
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/VfsChangeWatcherRelevanceTest.kt`

**Interfaces:**
- Consumes: `FolderLayoutStore.FOLDERS_FILE_NAME` (Task 3).
- Produces: companion `fun isConfigFileEvent(rootPath: String, oldPath: String?, newPath: String): Boolean` REPLACES `isFilterFileEvent` (same semantics, now matching both `megatron.filters` and `megatron.folders`).

- [ ] **Step 1: Write the failing tests**

In `VfsChangeWatcherRelevanceTest.kt`, first read the file; rename every call/reference of `isFilterFileEvent` to `isConfigFileEvent` (keeping those tests' assertions identical), then ADD:

```kotlin
fun testFoldersFileEventsAreAlwaysRelevant() {
    assertTrue(VfsChangeWatcher.isConfigFileEvent("/root", null, "/root/megatron.folders"))
    assertTrue(VfsChangeWatcher.isConfigFileEvent("/root", null, "/root/MEGATRON.FOLDERS"))
    assertTrue(VfsChangeWatcher.isConfigFileEvent("/root", "/root/megatron.folders", "/elsewhere/renamed.txt"))
    assertFalse(VfsChangeWatcher.isConfigFileEvent("/root", null, "/root/sub/megatron.folders"))
    assertFalse(VfsChangeWatcher.isConfigFileEvent("/root", null, "/other/megatron.folders"))
}
```

(If the test class is JUnit3-style without `assertTrue` imports, mirror the file's existing assertion style exactly.)

- [ ] **Step 2: Run tests to verify they fail**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.VfsChangeWatcherRelevanceTest"
```
Expected: COMPILE FAILURE (`isConfigFileEvent` unresolved).

- [ ] **Step 3: Implement**

In `VfsChangeWatcher.kt`, replace the `isFilterFileEvent` companion function with:

```kotlin
/** Events touching <root>/megatron.filters or <root>/megatron.folders always
 *  trigger a refresh — including content changes, since those files' content
 *  defines what the tree shows. */
fun isConfigFileEvent(rootPath: String, oldPath: String?, newPath: String): Boolean =
    CONFIG_FILE_NAMES.any { name ->
        val configPath = "$rootPath/$name"
        newPath.equals(configPath, ignoreCase = true) ||
            (oldPath != null && oldPath.equals(configPath, ignoreCase = true))
    }

private val CONFIG_FILE_NAMES =
    listOf(FilterEngine.FILTER_FILE_NAME, FolderLayoutStore.FOLDERS_FILE_NAME)
```

and update the single call site in `isRelevant` from `isFilterFileEvent(...)` to `isConfigFileEvent(...)`.

- [ ] **Step 4: Run the full suite**

```powershell
.\gradlew.bat test
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```powershell
git add src/main/kotlin/com/daverobins/projectfilesbrowser/VfsChangeWatcher.kt src/test/kotlin/com/daverobins/projectfilesbrowser/VfsChangeWatcherRelevanceTest.kt
git commit -m "feat: megatron.folders changes trigger auto-refresh"
```

---

### Task 6: Folder view toolbar toggle + panel store wiring

**Files:**
- Create: `src/main/kotlin/com/daverobins/projectfilesbrowser/FolderViewToggleAction.kt`
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/ProjectFilesPanel.kt`

**Interfaces:**
- Consumes: `ViewMode` (Task 2), `FolderLayoutStore` (Task 3), 4-arg `FilteredTreeStructure` (Task 4).
- Produces: `ProjectFilesPanel` field `private val folderStore = FolderLayoutStore(project, rootDir)` (Tasks 7–8 wire actions/DnD to it).

- [ ] **Step 1: Create FolderViewToggleAction.kt**

```kotlin
package com.daverobins.projectfilesbrowser

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.Project

/** Toolbar toggle for the virtual-folders view. Mutually exclusive with flat view. */
class FolderViewToggleAction(
    private val project: Project,
    private val onModeChanged: () -> Unit,
) : ToggleAction("Folder View", "Group files into virtual folders from megatron.folders", AllIcons.Nodes.Folder) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean =
        MegatronFilterState.getInstance(project).getViewMode() == ViewMode.FOLDERS

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        MegatronFilterState.getInstance(project)
            .setViewMode(if (state) ViewMode.FOLDERS else ViewMode.TREE)
        onModeChanged()
    }
}
```

- [ ] **Step 2: Wire the panel**

In `ProjectFilesPanel.kt`:
- Add field after `engine`:

```kotlin
private val folderStore = FolderLayoutStore(project, rootDir)
```

- Change the structure construction to pass it:

```kotlin
private val structureModel =
    StructureTreeModel(FilteredTreeStructure(project, rootDir, engine, folderStore), parentDisposable)
```

- Add the toggle to the toolbar group after `FlatViewToggleAction`:

```kotlin
FolderViewToggleAction(project) { structureModel.invalidateAsync() },
```

- [ ] **Step 3: Run the full suite**

```powershell
.\gradlew.bat test
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```powershell
git add src/main/kotlin/com/daverobins/projectfilesbrowser/FolderViewToggleAction.kt src/main/kotlin/com/daverobins/projectfilesbrowser/ProjectFilesPanel.kt
git commit -m "feat: folder view toolbar toggle"
```

---

### Task 7: Context-menu actions

**Files:**
- Create: `src/main/kotlin/com/daverobins/projectfilesbrowser/FolderActions.kt`
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/ProjectFilesPanel.kt` (install popup)

**Interfaces:**
- Consumes: `FolderLayoutStore` (Task 3), `FolderLayout` mutations + `validateFolderName` (Task 1), `VirtualFolderNode.folderPath` and `FileNode` (Task 4), panel `folderStore` (Task 6).
- Produces: `class MegatronTreePopupGroup(project, rootDir, store, tree, onChanged)`;
  top-level `internal fun selectedFilePaths(tree: Tree, rootDir: VirtualFile): List<String>` and
  `internal fun selectedVirtualFolder(tree: Tree): String?` (Task 8 reuses `selectedFilePaths`).

- [ ] **Step 1: Create FolderActions.kt**

```kotlin
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
```

- [ ] **Step 2: Install the popup in ProjectFilesPanel.kt**

Add import `com.intellij.ui.PopupHandler`, then in `init` (after `setContent(...)`):

```kotlin
PopupHandler.installFollowingSelectionTreePopup(
    tree,
    MegatronTreePopupGroup(project, rootDir, folderStore, tree) { structureModel.invalidateAsync() },
    "MegatronTreePopup",
)
```

- [ ] **Step 3: Run the full suite**

```powershell
.\gradlew.bat test
```
Expected: BUILD SUCCESSFUL. (The popup actions are dialog-driven; per the spec they are verified in the sandbox checklist, not by platform tests.)

- [ ] **Step 4: Commit**

```powershell
git add src/main/kotlin/com/daverobins/projectfilesbrowser/FolderActions.kt src/main/kotlin/com/daverobins/projectfilesbrowser/ProjectFilesPanel.kt
git commit -m "feat: context-menu folder management and file assignment"
```

---

### Task 8: Drag-and-drop + version 0.5.0

**Files:**
- Create: `src/main/kotlin/com/daverobins/projectfilesbrowser/FolderDnD.kt`
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/ProjectFilesPanel.kt` (DnD wiring)
- Modify: `build.gradle.kts` (version)

**Interfaces:**
- Consumes: `selectedFilePaths` (Task 7), `FolderLayoutStore` (Task 3), `VirtualFolderNode.folderPath` + `FileNode.isUnassignedBucket` (Task 4), `ViewMode` (Task 2).
- Produces: `class MegatronTreeTransferHandler(project, rootDir, store, tree, onChanged)` — final consumer, nothing downstream.

- [ ] **Step 1: Create FolderDnD.kt**

```kotlin
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
```

- [ ] **Step 2: Wire it in ProjectFilesPanel.kt**

Add imports `javax.swing.DropMode`; in `init`, after the popup installation:

```kotlin
tree.dragEnabled = true
tree.dropMode = DropMode.ON
tree.transferHandler =
    MegatronTreeTransferHandler(project, rootDir, folderStore, tree) { structureModel.invalidateAsync() }
```

- [ ] **Step 3: Bump the version**

In `build.gradle.kts`, change `version = "0.4.0"` to `version = "0.5.0"`.

- [ ] **Step 4: Run the full suite**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
.\gradlew.bat test
```
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/kotlin/com/daverobins/projectfilesbrowser/FolderDnD.kt src/main/kotlin/com/daverobins/projectfilesbrowser/ProjectFilesPanel.kt build.gradle.kts
git commit -m "feat: drag-and-drop assignment in folder view; version 0.5.0"
```

---

## Sandbox Checklist (post-implementation, human verification)

- Toggle Folder View: folders + `<Unassigned>` appear; Flat/Folder toggles mutually exclusive.
- Right-click file → Add to Folder → New Folder…; file moves out of `<Unassigned>`.
- Nested folders via New Subfolder…; Rename cascades; Delete returns files to `<Unassigned>`.
- Drag multi-selected files onto a folder and back onto `<Unassigned>`.
- Hand-edit megatron.folders → tree updates within ~1s; VCS diff of the file is clean.
- Filters and CMake gate hide assigned files inside folders without losing assignments.
- Double-click still opens files in all three views.
