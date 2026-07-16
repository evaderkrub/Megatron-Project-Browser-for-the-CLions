# CMake Project-Model Gate (Phase 4) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A pinned "Only CMake project files" toggle in the filter dropdown that narrows visibility to files in CLion's project model (no-op until the model loads, auto-refreshing when it does) — plus the watcher fix so group-only-visible files trigger auto-refresh.

**Architecture:** A tiny `ProjectModelGate` interface isolates the CLion API: `OcWorkspaceGate` wraps `OCWorkspace` (membership + model-load listener); tests use a fake. `FilterEngine` gains an optional gate ANDed into `isFileVisible` (which becomes VirtualFile-based); the un-gated `isGroupVisible(relativePath, name)` string form feeds the watcher's relevance predicate.

**Tech Stack:** Existing toolchain + new dependency on the bundled `com.intellij.clion` plugin (module alias `com.intellij.modules.clion`), which owns `OCWorkspace`/`OCWorkspaceListener` (verified public/required in the CLion 2026.1.1 dist; engine-agnostic, works under classic and Nova).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-16-cmake-gate-design.md` — its Behavior, Watcher Gap Fix, and Components sections are the requirements.
- Package: `com.daverobins.projectfilesbrowser`
- Gate semantics: applies ONLY when toggle ON and `gate.isActive()`; then file visible ⇔ group-visible AND `gate.isInModel(file)`. Toggle OFF or model inactive ⇒ exactly today's behavior.
- Watcher relevance: file leaves judged by the ENGINE's un-gated group visibility (predicate param); the gate NEVER affects relevance; directory and intermediate-segment rules unchanged.
- Persistence: `cmakeGateEnabled: Boolean = false` in `MegatronFilterState`, same pattern as `flatMode` (synchronized accessors, defensive copy).
- Dropdown: gate toggle pinned FIRST, then `Separator`, then groups (or the existing no-file info entry). Exact label: `Only CMake Project Files`.
- plugin.xml gains `<depends>com.intellij.modules.clion</depends>`; build.gradle.kts gains the bundled-plugin compile dependency. Version bumps to `0.4.0`.
- Light-test hygiene: any test flipping `cmakeGateEnabled` (or `flatMode`) resets it in `finally`.
- Shell: Windows PowerShell 5.1 (no `&&`); Gradle via `.\gradlew.bat`; on Java-version errors run `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"` first. Transient Windows `instrumentCode` flake: retry/clean.
- Branch: `feature/phase4-cmake-gate`; commit at the end of every task.

---

### Task 1: Watcher relevance via engine predicate (TDD)

**Files:**
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/VfsChangeWatcher.kt`
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/FilterEngine.kt` (add one method)
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/ProjectFilesPanel.kt` (watcher construction)
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/VfsChangeWatcherRelevanceTest.kt` (add tests)

**Interfaces:**
- Produces: companion `isRelevantPath(rootPath, path, isDirectory, fileVisible: (String, String) -> Boolean = builtInFileVisible)` and `isRelevantEitherPath(..., fileVisible = builtInFileVisible)` (existing call sites compile unchanged via the default); `VfsChangeWatcher` constructor gains REQUIRED param `fileVisible: (String, String) -> Boolean` before `onChange`; `FilterEngine.isGroupVisible(relativePath: String, fileName: String): Boolean` (un-gated group visibility; `isFileVisible` currently delegates to it).

- [ ] **Step 1: Add failing tests to `VfsChangeWatcherRelevanceTest.kt`**

```kotlin
    @Test
    fun customPredicateMakesGroupOnlyFileRelevant() {
        val mdVisible: (String, String) -> Boolean = { _, name -> name.endsWith(".md", ignoreCase = true) }
        assertTrue(isRelevantPath(root, "/proj/docs/notes.md", isDirectory = false, fileVisible = mdVisible))
        // same file under the built-in default predicate stays irrelevant
        assertFalse(isRelevantPath(root, "/proj/docs/notes.md", isDirectory = false))
    }

    @Test
    fun customPredicateReceivesRelativePathAndName() {
        val seen = mutableListOf<Pair<String, String>>()
        val spy: (String, String) -> Boolean = { rel, name -> seen.add(rel to name); true }
        assertTrue(isRelevantPath(root, "/proj/src/main.cpp", isDirectory = false, fileVisible = spy))
        assertEquals(listOf("src/main.cpp" to "main.cpp"), seen)
    }
```

(Also add `import org.junit.Assert.assertEquals` if missing.)

- [ ] **Step 2: Run to verify failure**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.VfsChangeWatcherRelevanceTest" --console=plain
```

Expected: FAILED — compilation error (no `fileVisible` parameter exists yet).

- [ ] **Step 3: Modify `VfsChangeWatcher.kt`**

(a) Constructor gains the required predicate (BEFORE `onChange`):

```kotlin
class VfsChangeWatcher(
    project: Project,
    rootDir: VirtualFile,
    parentDisposable: Disposable,
    private val fileVisible: (String, String) -> Boolean,
    onChange: () -> Unit,
) {
```

(b) In the instance `isRelevant`, pass `fileVisible` through every `isRelevantPath`/`isRelevantEitherPath` call (four call sites: create, delete, copy → `isRelevantPath(rootPath, event.path, ..., fileVisible)`; move and rename → `isRelevantEitherPath(rootPath, ..., fileVisible)`).

(c) Companion: add the default and thread the predicate:

```kotlin
        /** Default: the built-in extension filter (used when no engine is in play, e.g. pure tests). */
        val builtInFileVisible: (String, String) -> Boolean = { _, name -> FileFilter.includeFile(name) }

        fun isRelevantPath(
            rootPath: String,
            path: String,
            isDirectory: Boolean,
            fileVisible: (String, String) -> Boolean = builtInFileVisible,
        ): Boolean {
            if (path == rootPath) return true
            val prefix = "$rootPath/"
            if (!path.startsWith(prefix)) return false
            val relative = path.removePrefix(prefix)
            val segments = relative.split('/')
            for (i in 0 until segments.size - 1) {
                if (!FileFilter.includeDirectory(segments[i])) return false
            }
            val leaf = segments.last()
            return if (isDirectory) FileFilter.includeDirectory(leaf) else fileVisible(relative, leaf)
        }

        fun isRelevantEitherPath(
            rootPath: String,
            oldPath: String?,
            newPath: String,
            isDirectory: Boolean,
            fileVisible: (String, String) -> Boolean = builtInFileVisible,
        ): Boolean =
            isRelevantPath(rootPath, newPath, isDirectory, fileVisible) ||
                (oldPath != null && isRelevantPath(rootPath, oldPath, isDirectory, fileVisible))
```

(`isFilterFileEvent` unchanged.)

- [ ] **Step 4: Add `isGroupVisible` to `FilterEngine.kt`**

Rename nothing; add next to `isFileVisible` and make `isFileVisible` delegate:

```kotlin
    /** Group/default visibility only — no project-model gating. Used by the VFS watcher. */
    fun isGroupVisible(relativePath: String, fileName: String): Boolean =
        visibleByGroups(enabledGroups(), relativePath, fileName)

    fun isFileVisible(relativePath: String, fileName: String): Boolean =
        isGroupVisible(relativePath, fileName)
```

- [ ] **Step 5: Update the watcher construction in `ProjectFilesPanel.kt`**

```kotlin
        VfsChangeWatcher(
            project,
            rootDir,
            parentDisposable,
            { relativePath, fileName -> engine.isGroupVisible(relativePath, fileName) },
        ) {
            structureModel.invalidateAsync()
        }
```

- [ ] **Step 6: Run watcher tests, then ALL tests**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.VfsChangeWatcherRelevanceTest" --console=plain
.\gradlew.bat test --console=plain
```

Expected: both `BUILD SUCCESSFUL`; 56 tests total (54 + 2 new; existing tests unchanged thanks to the defaulted parameter).

- [ ] **Step 7: Commit**

```powershell
git add src
git commit -m "fix: watcher relevance uses engine group visibility (group-only files now auto-refresh)"
```

---

### Task 2: cmakeGateEnabled in MegatronFilterState (TDD)

**Files:**
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/MegatronFilterState.kt`
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/MegatronFilterStateTest.kt` (add tests)

**Interfaces:**
- Produces: `isCmakeGateEnabled(): Boolean` / `setCmakeGateEnabled(enabled: Boolean)`; `State.cmakeGateEnabled: Boolean = false`.

- [ ] **Step 1: Add failing tests (mirror the flatMode trio)**

```kotlin
    @Test
    fun cmakeGateDefaultsToFalse() {
        assertFalse(MegatronFilterState().isCmakeGateEnabled())
    }

    @Test
    fun cmakeGateRoundTrips() {
        val state = MegatronFilterState()
        state.setCmakeGateEnabled(true)
        assertTrue(state.isCmakeGateEnabled())
        state.setCmakeGateEnabled(false)
        assertFalse(state.isCmakeGateEnabled())
    }

    @Test
    fun getStateCopiesCmakeGate() {
        val state = MegatronFilterState()
        state.setCmakeGateEnabled(true)
        val snapshot = state.state
        assertTrue(snapshot.cmakeGateEnabled)
        state.setCmakeGateEnabled(false)
        assertTrue(snapshot.cmakeGateEnabled)
    }
```

- [ ] **Step 2: Run to verify compilation failure**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.MegatronFilterStateTest" --console=plain
```

- [ ] **Step 3: Implement — exactly the flatMode pattern**

Add `var cmakeGateEnabled: Boolean = false` to `State`; add `cmakeGateEnabled = current.cmakeGateEnabled` to the `getState()` copy; add:

```kotlin
    @Synchronized
    fun isCmakeGateEnabled(): Boolean = current.cmakeGateEnabled

    @Synchronized
    fun setCmakeGateEnabled(enabled: Boolean) {
        current.cmakeGateEnabled = enabled
    }
```

- [ ] **Step 4: State tests then ALL tests**

Expected: 59 green (56 + 3).

- [ ] **Step 5: Commit**

```powershell
git add src
git commit -m "feat: persist CMake project-model gate toggle in MegatronFilterState"
```

---

### Task 3: ProjectModelGate + engine gating (platform-test TDD with fake gate)

**Files:**
- Create: `src/main/kotlin/com/daverobins/projectfilesbrowser/ProjectModelGate.kt` (interface ONLY in this task)
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/FilterEngine.kt`
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/FilteredTreeStructure.kt` (call sites)
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/FilterEngineGateTest.kt` (new)

**Interfaces:**
- Produces: `interface ProjectModelGate { fun isActive(): Boolean; fun isInModel(file: VirtualFile): Boolean }`; `FilterEngine(project, rootDir, gate: ProjectModelGate? = null)` (existing 2-arg call sites compile unchanged); `FilterEngine.isFileVisible(file: VirtualFile): Boolean` — NOTE the signature change from strings to VirtualFile (the gate needs the file); `isGroupVisible(relativePath, fileName)` stays string-based for the watcher.

- [ ] **Step 1: Write `ProjectModelGate.kt` (interface only)**

```kotlin
package com.daverobins.projectfilesbrowser

import com.intellij.openapi.vfs.VirtualFile

/**
 * Answers whether files belong to the IDE's native project model (CMake targets
 * in CLion). Inactive until the model has loaded at least one configuration.
 * Isolated behind an interface so the engine stays testable without CLion APIs.
 */
interface ProjectModelGate {
    fun isActive(): Boolean
    fun isInModel(file: VirtualFile): Boolean
}
```

- [ ] **Step 2: Write the failing platform test `FilterEngineGateTest.kt`**

```kotlin
package com.daverobins.projectfilesbrowser

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FilterEngineGateTest : BasePlatformTestCase() {

    private class FakeGate(
        var active: Boolean,
        val modelPaths: MutableSet<String> = mutableSetOf(),
    ) : ProjectModelGate {
        override fun isActive(): Boolean = active
        override fun isInModel(file: VirtualFile): Boolean = file.path in modelPaths
    }

    fun testGateOnAndActiveNarrowsVisibility() {
        val inModel = myFixture.addFileToProject("g1/src/in_model.cpp", "").virtualFile
        val outOfModel = myFixture.addFileToProject("g1/src/generated.cpp", "").virtualFile
        val rootDir = requireNotNull(myFixture.findFileInTempDir("g1"))
        val gate = FakeGate(active = true, modelPaths = mutableSetOf(inModel.path))
        val engine = FilterEngine(project, rootDir, gate)

        val state = MegatronFilterState.getInstance(project)
        state.setCmakeGateEnabled(true)
        try {
            assertTrue(engine.isFileVisible(inModel))
            assertFalse(engine.isFileVisible(outOfModel))
        } finally {
            state.setCmakeGateEnabled(false)
        }
    }

    fun testGateOnButInactiveIsNoOp() {
        val anyFile = myFixture.addFileToProject("g2/src/main.cpp", "").virtualFile
        val rootDir = requireNotNull(myFixture.findFileInTempDir("g2"))
        val engine = FilterEngine(project, rootDir, FakeGate(active = false))

        val state = MegatronFilterState.getInstance(project)
        state.setCmakeGateEnabled(true)
        try {
            assertTrue(engine.isFileVisible(anyFile))
        } finally {
            state.setCmakeGateEnabled(false)
        }
    }

    fun testGateOffIgnoresModel() {
        val anyFile = myFixture.addFileToProject("g3/src/main.cpp", "").virtualFile
        val rootDir = requireNotNull(myFixture.findFileInTempDir("g3"))
        // active gate, file NOT in model — but the toggle is off
        val engine = FilterEngine(project, rootDir, FakeGate(active = true))

        assertFalse(MegatronFilterState.getInstance(project).isCmakeGateEnabled())
        assertTrue(engine.isFileVisible(anyFile))
    }

    fun testGroupVisibilityStillGatesFirst() {
        val mdFile = myFixture.addFileToProject("g4/readme.md", "").virtualFile
        val rootDir = requireNotNull(myFixture.findFileInTempDir("g4"))
        // even in-model files must pass group/default filtering
        val engine = FilterEngine(project, rootDir, FakeGate(active = true, modelPaths = mutableSetOf(mdFile.path)))

        val state = MegatronFilterState.getInstance(project)
        state.setCmakeGateEnabled(true)
        try {
            assertFalse("md fails built-in defaults regardless of model membership", engine.isFileVisible(mdFile))
        } finally {
            state.setCmakeGateEnabled(false)
        }
    }
}
```

- [ ] **Step 3: Run to verify failure**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.FilterEngineGateTest" --console=plain
```

Expected: FAILED — compilation errors (no 3-arg FilterEngine constructor, no `isFileVisible(VirtualFile)`).

- [ ] **Step 4: Modify `FilterEngine.kt`**

Constructor and visibility methods (rest of the class unchanged):

```kotlin
class FilterEngine(
    private val project: Project,
    private val rootDir: VirtualFile,
    private val gate: ProjectModelGate? = null,
) {
```

```kotlin
    /** Group/default visibility only — no project-model gating. Used by the VFS watcher. */
    fun isGroupVisible(relativePath: String, fileName: String): Boolean =
        visibleByGroups(enabledGroups(), relativePath, fileName)

    /** Full visibility: group filtering AND (when enabled and active) the project-model gate. */
    fun isFileVisible(file: VirtualFile): Boolean {
        if (!isGroupVisible(relativePath(file), file.name)) return false
        val activeGate = gate ?: return true
        if (!MegatronFilterState.getInstance(project).isCmakeGateEnabled()) return true
        if (!activeGate.isActive()) return true
        return activeGate.isInModel(file)
    }

    private fun relativePath(file: VirtualFile): String =
        file.path.removePrefix("${rootDir.path}/")
```

Remove the old string-based `isFileVisible(relativePath, fileName)` (Task 1 left it delegating; it has exactly two callers, both in `FilteredTreeStructure` — updated next step).

- [ ] **Step 5: Update the two call sites in `FilteredTreeStructure.kt`**

In `isVisible`: `engine.isFileVisible(relativePath(candidate), candidate.name)` → `engine.isFileVisible(candidate)`.
In `collectVisibleFiles`: `engine.isFileVisible(relativePath(child), child.name)` → `engine.isFileVisible(child)`.
(`FileNode.relativePath` stays — still used for sorting and location strings.)

- [ ] **Step 6: Run gate tests, structure tests, then ALL tests**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.FilterEngineGateTest" --console=plain
.\gradlew.bat test --console=plain
```

Expected: `BUILD SUCCESSFUL`; 63 tests total (59 + 4).

- [ ] **Step 7: Commit**

```powershell
git add src
git commit -m "feat: ProjectModelGate narrows engine visibility when enabled and model active"
```

---

### Task 4: CLion dependency + OcWorkspaceGate

**Files:**
- Modify: `build.gradle.kts` (add bundled plugin dependency)
- Modify: `src/main/resources/META-INF/plugin.xml` (add depends)
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/ProjectModelGate.kt` (add the real implementation)

**Interfaces:**
- Consumes: `ProjectModelGate` (Task 3).
- Produces: `class OcWorkspaceGate(project: Project) : ProjectModelGate` with additional `fun subscribe(parentDisposable: Disposable, onModelChanged: () -> Unit)`. Task 5 constructs and subscribes it.

- [ ] **Step 1: Add the compile dependency in `build.gradle.kts`**

Inside `dependencies { intellijPlatform { ... } }`, after `clion("2026.1.1")`:

```kotlin
        bundledPlugin("com.intellij.clion")
```

- [ ] **Step 2: Add the plugin dependency in `plugin.xml`**

After the existing `<depends>com.intellij.modules.platform</depends>`:

```xml
    <depends>com.intellij.modules.clion</depends>
```

- [ ] **Step 3: Add `OcWorkspaceGate` to `ProjectModelGate.kt`**

```kotlin
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.jetbrains.cidr.lang.workspace.OCWorkspace
import com.jetbrains.cidr.lang.workspace.OCWorkspaceListener

/** Real gate backed by CLion's shared project model (works under classic and Nova engines). */
class OcWorkspaceGate(private val project: Project) : ProjectModelGate {

    override fun isActive(): Boolean =
        OCWorkspace.getInstance(project).configurations.isNotEmpty()

    override fun isInModel(file: VirtualFile): Boolean =
        OCWorkspace.getInstance(project).getConfigurationsForFile(file).isNotEmpty()

    /** Refreshes the tree when the project model (re)loads. */
    fun subscribe(parentDisposable: Disposable, onModelChanged: () -> Unit) {
        project.messageBus.connect(parentDisposable).subscribe(
            OCWorkspaceListener.TOPIC,
            object : OCWorkspaceListener {
                override fun workspaceChanged(event: OCWorkspaceListener.OCWorkspaceEvent) {
                    onModelChanged()
                }

                override fun workspaceInitializationFinished(success: Boolean) {
                    onModelChanged()
                }
            },
        )
    }
}
```

API-caveat: the exact `OCWorkspaceListener` member signatures (TOPIC field, event type nesting, method names) were read from javap during research but MUST be re-verified if compilation fails — check with javap against `plugins/clion/lib/modules/intellij.cidr.projectModel.jar` in the CLion dist under `C:\Users\dave\.gradle\caches\8.13\transforms\*\transformed\CLion-2026.1.1-win`, correct minimally, record evidence. Kotlin default-method note: `OCWorkspaceListener` methods are default methods — overriding only the two needed is intended.

- [ ] **Step 4: Build + full suite**

```powershell
.\gradlew.bat build --console=plain
```

Expected: `BUILD SUCCESSFUL`, 63/63 (the fake-gate tests still run with plugins disabled; `OcWorkspaceGate` merely compiles — its class is not loaded in tests).

- [ ] **Step 5: Commit**

```powershell
git add src build.gradle.kts
git commit -m "feat: OcWorkspaceGate over CLion OCWorkspace + com.intellij.modules.clion dependency"
```

---

### Task 5: Dropdown pinned toggle + panel wiring + version bump

**Files:**
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/FilterDropdownAction.kt`
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/ProjectFilesPanel.kt`
- Modify: `build.gradle.kts` (version only)

**Interfaces:**
- Consumes: `OcWorkspaceGate` (Task 4), `MegatronFilterState.isCmakeGateEnabled/setCmakeGateEnabled` (Task 2), 3-arg `FilterEngine` (Task 3).

- [ ] **Step 1: Pin the gate toggle in `FilterDropdownAction.getChildren`**

Add `import com.intellij.openapi.actionSystem.Separator` and replace the `getChildren` body:

```kotlin
    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val head = arrayOf<AnAction>(CmakeGateToggleAction(), Separator.getInstance())
        val groups = engine.groupsForUi()
        val tail: Array<AnAction> =
            if (groups.isEmpty()) arrayOf(NoFiltersInfoAction())
            else groups.map { (name, _) -> GroupToggleAction(name) }.toTypedArray()
        return head + tail
    }
```

and add the inner class next to `GroupToggleAction`:

```kotlin
    private inner class CmakeGateToggleAction :
        ToggleAction("Only CMake Project Files", "Show only files that belong to the CMake project model", null) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun isSelected(e: AnActionEvent): Boolean =
            MegatronFilterState.getInstance(project).isCmakeGateEnabled()

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            MegatronFilterState.getInstance(project).setCmakeGateEnabled(state)
            onFilterChanged()
        }
    }
```

- [ ] **Step 2: Wire the gate in `ProjectFilesPanel.kt`**

Property changes (order matters — gate before engine):

```kotlin
    private val projectModelGate = OcWorkspaceGate(project)
    private val engine = FilterEngine(project, rootDir, projectModelGate)
```

At the end of `init` (after the `VfsChangeWatcher(...)` block):

```kotlin
        projectModelGate.subscribe(parentDisposable) {
            structureModel.invalidateAsync()
        }
```

- [ ] **Step 3: Bump version**

`version = "0.3.0"` → `version = "0.4.0"` in build.gradle.kts.

- [ ] **Step 4: Build + verify + full suite**

```powershell
.\gradlew.bat build verifyPluginProjectConfiguration --console=plain
```

Expected: `BUILD SUCCESSFUL`, 63/63.

- [ ] **Step 5: Commit**

```powershell
git add src build.gradle.kts
git commit -m "feat: pinned CMake-gate toggle in filter dropdown, model-load auto-refresh"
```

---

### Task 6: Sandbox verification + merge + tag (human checkpoint)

**Files:** none (unless fixes are needed).

- [ ] **Step 1: Launch sandbox** (`.\gradlew.bat runIde --console=plain`, background)

- [ ] **Step 2: Manual verification checklist (user drives, real CMake project)**

1. Funnel dropdown now opens with "Only CMake Project Files" at the top, separator below, groups underneath.
2. With the gate OFF: everything as before.
3. Turn the gate ON (after CMake has loaded): files not in any target disappear (e.g. a stray `.cpp` never added to CMakeLists, and typically `CMakeLists.txt`/`megatron.filters` themselves). Toggle OFF brings them back.
4. Close the project, reopen, quickly open the tool window with the gate ON: initially the tree shows ungated files, then auto-refreshes to the gated view when CMake finishes loading.
5. Watcher-fix regression test: add a `Docs: *.md` group to megatron.filters, create a `notes.md` in Explorer or the IDE → it appears within ~1 s WITHOUT manual refresh; delete it → disappears.
6. Gate works in flat mode too.
7. Regressions: group toggles, live reload, flat toggle, refresh button, cmake-build-* hiding.

- [ ] **Step 3: Fix anything found, re-verify, commit fixes**

- [ ] **Step 4: Merge and tag (after user confirms; via finishing-a-development-branch)**

Merge `feature/phase4-cmake-gate` to `master`, verify tests, delete branch, `git tag v0.4.0`, build the 0.4.0 zip.
