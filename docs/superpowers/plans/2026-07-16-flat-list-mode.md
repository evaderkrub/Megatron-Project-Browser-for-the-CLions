# Flat List Mode (Phase 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A toolbar toggle switches Megatron between the directory tree and a flat, name-sorted list of all visible files (name + dimmed parent path), with identical filtering in both modes.

**Architecture:** Mode-aware structure: the root `FileNode` reads `MegatronFilterState.isFlatMode()` in `getChildren()` and returns either the existing directory hierarchy or a recursively collected, sorted list of visible files as leaves. No model/tree rewiring — the toggle just flips state and calls `invalidateAsync()`.

**Tech Stack:** Existing toolchain — Kotlin 2.3.0, Gradle 9.6.1 wrapper, IntelliJ Platform Gradle Plugin 2.18.1, JDK 21, target CLion 2026.1.1.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-16-flat-list-mode-design.md` — behavior rules there are the requirements.
- Package: `com.daverobins.projectfilesbrowser`
- Flat sorting: case-insensitive by file NAME, ties broken by case-insensitive relative path
- Flat rows: file name as label, parent dir's project-relative path as `locationString` (NO location string for root-level files); file-type icons as in tree mode
- Mode persisted as `flatMode: Boolean = false` in the existing `MegatronFilterState` (component name `MegatronFilters` unchanged); default tree mode
- Tree mode behavior byte-for-byte unchanged; filtering identical in both modes; excluded dirs not traversed in the flat walk
- Plugin version bumps to `0.3.0`
- Shell: Windows PowerShell 5.1 (no `&&`); Gradle via `.\gradlew.bat`; on Java-version errors run `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"` first
- Branch: `feature/phase3-flat-mode`; commit at the end of every task
- Light-test hygiene: `BasePlatformTestCase` reuses the project between tests — any test that sets `flatMode = true` MUST reset it to `false` in a `finally` block
- Transient Windows `instrumentCode` flake: retry/clean, don't misdiagnose

---

### Task 1: flatMode in MegatronFilterState (TDD)

**Files:**
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/MegatronFilterState.kt`
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/MegatronFilterStateTest.kt` (add tests)

**Interfaces:**
- Produces: `isFlatMode(): Boolean` / `setFlatMode(flat: Boolean)` on `MegatronFilterState`; `State.flatMode: Boolean`. Tasks 2-3 rely on these.

- [ ] **Step 1: Add failing tests to `MegatronFilterStateTest.kt`**

```kotlin
    @Test
    fun flatModeDefaultsToFalse() {
        assertFalse(MegatronFilterState().isFlatMode())
    }

    @Test
    fun flatModeRoundTrips() {
        val state = MegatronFilterState()
        state.setFlatMode(true)
        assertTrue(state.isFlatMode())
        state.setFlatMode(false)
        assertFalse(state.isFlatMode())
    }

    @Test
    fun getStateCopiesFlatMode() {
        val state = MegatronFilterState()
        state.setFlatMode(true)
        val snapshot = state.state
        assertTrue(snapshot.flatMode)
        state.setFlatMode(false)
        assertTrue(snapshot.flatMode) // snapshot is a copy, unaffected by later changes
    }
```

- [ ] **Step 2: Run to verify failure**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.MegatronFilterStateTest" --console=plain
```

Expected: FAILED — compilation error, `isFlatMode` unresolved.

- [ ] **Step 3: Modify `MegatronFilterState.kt`**

Three edits:

(a) Add the field to `State`:

```kotlin
    class State {
        var disabledGroups: MutableSet<String> = mutableSetOf()
        var flatMode: Boolean = false
    }
```

(b) `getState()` must copy the new field too:

```kotlin
    @Synchronized
    override fun getState(): State =
        State().apply {
            disabledGroups = current.disabledGroups.toMutableSet()
            flatMode = current.flatMode
        }
```

(c) Add accessors (next to `isEnabled`/`setEnabled`):

```kotlin
    @Synchronized
    fun isFlatMode(): Boolean = current.flatMode

    @Synchronized
    fun setFlatMode(flat: Boolean) {
        current.flatMode = flat
    }
```

- [ ] **Step 4: Run the state tests, then ALL tests**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.MegatronFilterStateTest" --console=plain
.\gradlew.bat test --console=plain
```

Expected: both `BUILD SUCCESSFUL`; 51 tests total (48 + 3 new).

- [ ] **Step 5: Commit**

```powershell
git add src
git commit -m "feat: persist flat/tree view mode in MegatronFilterState"
```

---

### Task 2: Flat mode in FilteredTreeStructure (platform-test TDD)

**Files:**
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/FilteredTreeStructure.kt`
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/FilteredTreeStructureTest.kt` (add tests)

**Interfaces:**
- Consumes: `MegatronFilterState.getInstance(project).isFlatMode()` (Task 1); existing `FilterEngine.isFileVisible`, `FileFilter.includeDirectory`.
- Produces: no signature changes — `FilteredTreeStructure(project, rootDir, engine)` and `FileNode.file` unchanged (the `FileNode` constructor gains a defaulted `flatLeaf` parameter, defaulted so existing call sites compile unchanged).

- [ ] **Step 1: Add failing tests to `FilteredTreeStructureTest.kt`**

```kotlin
    fun testFlatModeListsVisibleFilesSortedByNameThenPath() {
        myFixture.addFileToProject("fl/CMakeLists.txt", "")
        myFixture.addFileToProject("fl/src/alpha.cpp", "")
        myFixture.addFileToProject("fl/src/deep/beta.h", "")
        myFixture.addFileToProject("fl/zeta.cpp", "")
        myFixture.addFileToProject("fl/readme.md", "hidden by built-in defaults")
        myFixture.addFileToProject("fl/cmake-build-debug/x.cpp", "excluded dir, never traversed")

        val state = MegatronFilterState.getInstance(project)
        state.setFlatMode(true)
        try {
            val rootDir = requireNotNull(myFixture.findFileInTempDir("fl"))
            val structure = FilteredTreeStructure(project, rootDir, FilterEngine(project, rootDir))
            val root = structure.rootElement as FileNode

            assertEquals(
                listOf("alpha.cpp", "beta.h", "CMakeLists.txt", "zeta.cpp"),
                root.children.map { (it as FileNode).file.name },
            )
            assertTrue("flat rows must be leaves", root.children.all { it.children.isEmpty() })
        } finally {
            state.setFlatMode(false)
        }
    }

    fun testFlatLeafLocationStrings() {
        myFixture.addFileToProject("loc/src/main.cpp", "")
        myFixture.addFileToProject("loc/top.cpp", "")

        val state = MegatronFilterState.getInstance(project)
        state.setFlatMode(true)
        try {
            val rootDir = requireNotNull(myFixture.findFileInTempDir("loc"))
            val structure = FilteredTreeStructure(project, rootDir, FilterEngine(project, rootDir))
            val root = structure.rootElement as FileNode
            val nodes = root.children.map { it as FileNode }

            val main = nodes.first { it.file.name == "main.cpp" }
            main.update()
            assertEquals("src", main.presentation.locationString)

            val top = nodes.first { it.file.name == "top.cpp" }
            top.update()
            assertNull(top.presentation.locationString)
        } finally {
            state.setFlatMode(false)
        }
    }

    fun testFlatAndTreeShowTheSameFileSet() {
        myFixture.addFileToProject("par/megatron.filters", "Docs: *.md\nSrc: src/**")
        myFixture.addFileToProject("par/readme.md", "")
        myFixture.addFileToProject("par/src/a.cpp", "")
        myFixture.addFileToProject("par/hidden.cpp", "matches no group -> hidden in BOTH modes")

        val rootDir = requireNotNull(myFixture.findFileInTempDir("par"))
        val state = MegatronFilterState.getInstance(project)

        fun collectFiles(node: FileNode): Set<String> =
            if (node.file.isDirectory) node.children.flatMap { collectFiles(it as FileNode) }.toSet()
            else setOf(node.file.path)

        state.setFlatMode(false)
        val treeSet = collectFiles(
            FilteredTreeStructure(project, rootDir, FilterEngine(project, rootDir)).rootElement as FileNode
        )
        state.setFlatMode(true)
        try {
            val flatSet = collectFiles(
                FilteredTreeStructure(project, rootDir, FilterEngine(project, rootDir)).rootElement as FileNode
            )
            assertEquals(treeSet, flatSet)
            assertEquals(2, flatSet.size)
        } finally {
            state.setFlatMode(false)
        }
    }
```

(`node.update()` is the public `NodeDescriptor.update()`; it fills `presentation`, which `PresentableNodeDescriptor.getPresentation()` exposes. If either symbol fails to compile, verify the real accessor against the platform jars and record the correction.)

- [ ] **Step 2: Run to verify failure**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.FilteredTreeStructureTest" --console=plain
```

Expected: FAILED — the flat tests fail (flat mode not implemented: children are the tree hierarchy, so the name-list/leaf assertions fail; this is a behavioral RED, not a compile error, since no signatures change).

- [ ] **Step 3: Modify `FilteredTreeStructure.kt`**

Final `FileNode` (only `FilteredTreeStructure`'s class body shown unchanged — replace the `FileNode` class with this):

```kotlin
class FileNode(
    private val project: Project,
    parent: FileNode?,
    val file: VirtualFile,
    private val engine: FilterEngine,
    private val rootPath: String,
    private val flatLeaf: Boolean = false,
) : SimpleNode(project, parent) {

    private val isRootNode = parent == null

    override fun getChildren(): Array<SimpleNode> {
        if (!file.isDirectory) return NO_CHILDREN
        if (isRootNode && MegatronFilterState.getInstance(project).isFlatMode()) {
            return flatChildren()
        }
        val visible = (file.children ?: return NO_CHILDREN)
            .filter { it.isValid && isVisible(it) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        if (visible.isEmpty()) return NO_CHILDREN
        return visible.map { FileNode(project, this, it, engine, rootPath) }.toTypedArray()
    }

    override fun update(presentation: PresentationData) {
        presentation.presentableText = file.name
        if (flatLeaf) {
            val parentRel = relativePath(file).substringBeforeLast('/', "")
            if (parentRel.isNotEmpty()) presentation.locationString = parentRel
        }
        presentation.setIcon(
            if (file.isDirectory) AllIcons.Nodes.Folder
            else file.fileType.icon ?: AllIcons.FileTypes.Any_type
        )
    }

    override fun getEqualityObjects(): Array<Any> = arrayOf(file)

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
            } else if (engine.isFileVisible(relativePath(child), child.name)) {
                out.add(child)
            }
        }
    }

    private fun isVisible(candidate: VirtualFile): Boolean =
        if (candidate.isDirectory) {
            FileFilter.includeDirectory(candidate.name) && hasVisibleContent(candidate)
        } else {
            engine.isFileVisible(relativePath(candidate), candidate.name)
        }

    /** A directory is shown only if filtering leaves something inside it. */
    private fun hasVisibleContent(dir: VirtualFile): Boolean =
        (dir.children ?: return false).any { it.isValid && isVisible(it) }

    private fun relativePath(candidate: VirtualFile): String =
        candidate.path.removePrefix("$rootPath/")
}
```

(New pieces: `flatLeaf` constructor param with default, `isRootNode`, the flat branch in `getChildren`, `flatChildren`, `collectVisibleFiles`, and the `locationString` lines in `update`. Everything else is byte-identical to the current file — verify by diff before committing.)

- [ ] **Step 4: Run structure tests, then ALL tests**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.FilteredTreeStructureTest" --console=plain
.\gradlew.bat test --console=plain
```

Expected: both `BUILD SUCCESSFUL`; 54 tests total (6 structure tests now).

- [ ] **Step 5: Commit**

```powershell
git add src
git commit -m "feat: flat list mode in FilteredTreeStructure (name-sorted, dimmed parent paths)"
```

---

### Task 3: Toolbar toggle + version bump

**Files:**
- Create: `src/main/kotlin/com/daverobins/projectfilesbrowser/FlatViewToggleAction.kt`
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/ProjectFilesPanel.kt` (toolbar group only)
- Modify: `build.gradle.kts` (version only)

**Interfaces:**
- Consumes: `MegatronFilterState.isFlatMode`/`setFlatMode` (Task 1); panel's `structureModel.invalidateAsync()`.

- [ ] **Step 1: Write `FlatViewToggleAction.kt`**

```kotlin
package com.daverobins.projectfilesbrowser

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.Project

/** Toolbar toggle between the directory tree and the flat file list. */
class FlatViewToggleAction(
    private val project: Project,
    private val onModeChanged: () -> Unit,
) : ToggleAction("Flat View", "Show all files as a flat list", AllIcons.Actions.ListFiles) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean =
        MegatronFilterState.getInstance(project).isFlatMode()

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        MegatronFilterState.getInstance(project).setFlatMode(state)
        onModeChanged()
    }
}
```

(If `AllIcons.Actions.ListFiles` doesn't resolve on this platform build, verify the real constant with javap against the platform jars — `AllIcons.Actions.ShowAsTree` inverted-sense is NOT an acceptable substitute; pick a list-like icon and record the correction.)

- [ ] **Step 2: Add to the toolbar in `ProjectFilesPanel.kt`**

In the `DefaultActionGroup(...)` inside the toolbar construction, add a third entry after the filter dropdown:

```kotlin
            DefaultActionGroup(
                refresh,
                FilterDropdownAction(project, engine) { structureModel.invalidateAsync() },
                FlatViewToggleAction(project) { structureModel.invalidateAsync() },
            ),
```

- [ ] **Step 3: Bump version**

In `build.gradle.kts`: `version = "0.2.0"` → `version = "0.3.0"`.

- [ ] **Step 4: Build + full suite + plugin verification**

```powershell
.\gradlew.bat build verifyPluginProjectConfiguration --console=plain
```

Expected: `BUILD SUCCESSFUL`, 54/54 tests green.

- [ ] **Step 5: Commit**

```powershell
git add src build.gradle.kts
git commit -m "feat: flat view toggle on the Megatron toolbar"
```

---

### Task 4: Sandbox verification + merge + tag (human checkpoint)

**Files:** none (unless fixes are needed).

- [ ] **Step 1: Launch sandbox**

```powershell
.\gradlew.bat runIde --console=plain
```

Background; blocks until IDE closes.

- [ ] **Step 2: Manual verification checklist (user drives)**

1. Toggle the new flat-view button → all visible files as a flat list, names first, dimmed parent paths, sorted by name; root-level files show no path.
2. Double-click / Enter opens files from the flat list.
3. Filter dropdown toggles still work in flat mode (list updates immediately).
4. Edit megatron.filters while flat → list live-updates within ~1 s.
5. Create/delete a file while flat → auto-refresh updates the list.
6. Toggle back to tree → exactly the old tree; toggle to flat again; close & reopen the project → mode remembered.
7. `cmake-build-*` contents absent from the flat list.

- [ ] **Step 3: Fix anything found, re-verify, commit fixes**

- [ ] **Step 4: Merge and tag (after user confirms; via finishing-a-development-branch)**

Merge `feature/phase3-flat-mode` to `master`, verify tests on the merged result, delete the branch, then:

```powershell
git tag v0.3.0
```
