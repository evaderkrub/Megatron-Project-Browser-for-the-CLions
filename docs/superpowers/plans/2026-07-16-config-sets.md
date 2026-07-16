# Config Sets (Phase 7) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Multiple named Megatron config sets in a `megatron/` directory (`<set>.filters` + `<set>.folders`), switchable from a toolbar dropdown, with a one-click documented default set when none exist.

**Architecture:** A stateless `ConfigSetManager` scans `megatron/` and resolves the effective set's files; `FilterEngine` and `FolderLayoutStore` read through it (cache keyed by file path + stamp so set switches invalidate). `FolderLayout` learns to preserve the leading comment header so the default set's docs survive UI rewrites. The watcher's always-relevant check moves from root files to the `megatron/` directory. Panel gains the set dropdown and an empty-state banner.

**Tech Stack:** Kotlin 2.3.0, IntelliJ Platform Gradle Plugin 2.18.1, CLion 2026.1.1, JUnit 4.

## Global Constraints

- Before any Gradle call in a fresh PowerShell: `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"`. Test command: `.\gradlew.bat test`.
- Config directory name exactly `megatron` at the project root; files `<set>.filters` / `<set>.folders`; set = base name; comparisons case-insensitive.
- Root-level `megatron.filters` / `megatron.folders` are NO LONGER READ anywhere (and no longer watcher-relevant) after this phase.
- Effective set = persisted `activeSet` if a set of that name exists (case-insensitive), else alphabetically first existing set, else `"default"`.
- `MegatronFilterState.State` gains `activeSet: String = "default"` (synchronized accessors, defensive copy). Group toggles stay keyed by group name only.
- Header preservation: the leading run of `#`/blank lines of a `.folders` file (lines trimmed, trailing blank lines dropped) round-trips through parse → serialize; serializer emits it first, then ONE blank line when folders follow. Comments elsewhere still dropped.
- Default-set file contents are the exact constants in Task 3 (documentation comment blocks; starter group `Sources` mirroring the built-in defaults; folders file has docs and no folders).
- Empty-state banner: visible exactly when the set scan is empty; "Create default set" link creates both files, opens them in editors, hides the banner, refreshes the tree.
- Tests mutating `MegatronFilterState` (including `activeSet`) restore prior values in `finally` (BasePlatformTestCase reuses the project). Restore `activeSet` to `"default"`.
- Plugin version becomes `0.7.0` (Task 6).
- Commit messages: conventional commits.

---

### Task 1: activeSet in MegatronFilterState

**Files:**
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/MegatronFilterState.kt`
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/MegatronFilterStateTest.kt`

**Interfaces:**
- Produces: `MegatronFilterState.getActiveSet(): String` / `setActiveSet(name: String)`; `State.activeSet: String = "default"`.

- [ ] **Step 1: Write the failing tests** (append; mirror the file's existing style)

```kotlin
    fun testActiveSetDefaultsToDefault() {
        assertEquals("default", MegatronFilterState().getActiveSet())
    }

    fun testActiveSetRoundTrips() {
        val state = MegatronFilterState()
        state.setActiveSet("gui-work")
        val restored = MegatronFilterState()
        restored.loadState(state.state)
        assertEquals("gui-work", restored.getActiveSet())
    }

    fun testGetStateReturnsDefensiveCopyOfActiveSet() {
        val state = MegatronFilterState()
        val snapshot = state.state
        state.setActiveSet("other")
        assertEquals("default", snapshot.activeSet)
    }
```

- [ ] **Step 2: Run to verify failure**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.MegatronFilterStateTest"
```
Expected: COMPILE FAILURE (`getActiveSet` unresolved).

- [ ] **Step 3: Implement**

In `State`, add `var activeSet: String = "default"`. In `getState()`, add `activeSet = current.activeSet`. Add accessors:

```kotlin
    @Synchronized
    fun getActiveSet(): String = current.activeSet

    @Synchronized
    fun setActiveSet(name: String) {
        current.activeSet = name
    }
```

- [ ] **Step 4: Run to verify pass** — same command, expected BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```powershell
git add src/main/kotlin/com/daverobins/projectfilesbrowser/MegatronFilterState.kt src/test/kotlin/com/daverobins/projectfilesbrowser/MegatronFilterStateTest.kt
git commit -m "feat: persist active config set name"
```

---

### Task 2: FolderLayout header-comment preservation

**Files:**
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/FolderLayout.kt`
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/FolderLayoutTest.kt`

**Interfaces:**
- Produces: `FolderLayout(folders, assignments, rules, header: List<String> = emptyList())` — 4th ctor param; `val header: List<String>`; parse captures it; serialize re-emits it; every `with*` mutation carries it through.

- [ ] **Step 1: Write the failing tests** (append)

```kotlin
    @Test
    fun `header comments round-trip through serialize`() {
        val text = "# Megatron folders\n# docs here\n\nCore/\n  a.cpp\n"
        val layout = parseFoldersFile(text)
        assertEquals(listOf("# Megatron folders", "# docs here"), layout.header)
        assertEquals("# Megatron folders\n# docs here\n\nCore/\n  a.cpp\n", layout.serialize())
        assertEquals(layout.serialize(), parseFoldersFile(layout.serialize()).serialize())
    }

    @Test
    fun `header-only file is a serialize fixed point`() {
        val text = "# just docs\n# nothing else\n"
        assertEquals(text, parseFoldersFile(text).serialize())
    }

    @Test
    fun `mutations preserve the header`() {
        val layout = parseFoldersFile("# docs\nA/\n  a.cpp\n")
            .withFolder("B").withAssignment("x.cpp", "B").withUnassigned("a.cpp")
            .withFolderRenamed("B", "C").withFolderDeleted("A")
        assertEquals(listOf("# docs"), layout.header)
        assertTrue(layout.serialize().startsWith("# docs\n"))
    }

    @Test
    fun `interior comments are still dropped and header trailing blanks trimmed`() {
        val layout = parseFoldersFile("# top\n\n\nA/\n# interior comment\n  a.cpp\n")
        assertEquals(listOf("# top"), layout.header)
        assertEquals("# top\n\nA/\n  a.cpp\n", layout.serialize())
    }
```

- [ ] **Step 2: Run to verify failure**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.FolderLayoutTest"
```
Expected: COMPILE FAILURE (`header` unresolved).

- [ ] **Step 3: Implement**

In `FolderLayout`: add 4th ctor param `header: List<String> = emptyList()`, and in `init` (after the existing normalization) set a new public field declared alongside `folders`/`rules`:

```kotlin
    /** Leading comment/blank lines of the file, preserved across UI rewrites. */
    val header: List<String>
```

```kotlin
        this.header = header.map { it.trim() }.dropLastWhile { it.isEmpty() }
```

Every `with*` mutation passes `header` through as the 4th argument (withFolder, withAssignment, withUnassigned — both branches, withFolderRenamed, withFolderDeleted).

`serialize()` gains a prologue before the folders loop:

```kotlin
        for (line in header) sb.append(line).append('\n')
        if (header.isNotEmpty() && folders.isNotEmpty()) sb.append('\n')
```

`parseFoldersFile` captures the header before the main loop:

```kotlin
    val allLines = text.lines()
    val header = ArrayList<String>()
    var start = 0
    while (start < allLines.size) {
        val t = allLines[start].trim()
        if (t.isEmpty() || t.startsWith("#")) {
            header.add(t)
            start++
        } else break
    }
```

The existing loop then iterates `allLines.subList(start, allLines.size)` instead of `text.lineSequence()`, and the return becomes `FolderLayout(folders, assignments, rules, header)`. (A trailing all-comment file leaves the content loop with nothing — fine.)

- [ ] **Step 4: Run FolderLayoutTest, then the full suite** — both BUILD SUCCESSFUL. (Existing tests are unaffected: their inputs have no leading comments except `blank lines comments and indentation are cosmetic` and `parses folders and assignments`, whose leading `# comment` lines now land in `header` — those tests assert folders/assignments only, so they still pass.)

- [ ] **Step 5: Commit**

```powershell
git add src/main/kotlin/com/daverobins/projectfilesbrowser/FolderLayout.kt src/test/kotlin/com/daverobins/projectfilesbrowser/FolderLayoutTest.kt
git commit -m "feat: preserve leading comment header across megatron.folders rewrites"
```

---

### Task 3: ConfigSetManager + documented default set

**Files:**
- Create: `src/main/kotlin/com/daverobins/projectfilesbrowser/ConfigSetManager.kt`
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/ConfigSetManagerTest.kt`

**Interfaces:**
- Consumes: `MegatronFilterState.getActiveSet()` (Task 1), `parseFilterFile`/`parseFoldersFile` (existing).
- Produces (Tasks 4–6 rely on):
  - `ConfigSetManager(project: Project, rootDir: VirtualFile)`
  - `fun setNames(): List<String>`, `fun effectiveSet(): String`
  - `fun filtersFile(): VirtualFile?`, `fun foldersFile(): VirtualFile?`
  - `fun writeFoldersFile(text: String)` (creates dir/file as needed, EDT)
  - `fun createDefaultSet()`
  - companion consts `DIR_NAME = "megatron"`, `DEFAULT_SET = "default"`, `FILTERS_EXT = "filters"`, `FOLDERS_EXT = "folders"`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.daverobins.projectfilesbrowser

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ConfigSetManagerTest : BasePlatformTestCase() {

    fun testScanUnionsExtensionsIgnoresNoiseAndSorts() {
        myFixture.addFileToProject("cs1/megatron/beta.folders", "")
        myFixture.addFileToProject("cs1/megatron/Alpha.filters", "")
        myFixture.addFileToProject("cs1/megatron/alpha.folders", "")
        myFixture.addFileToProject("cs1/megatron/readme.txt", "")
        myFixture.addFileToProject("cs1/megatron/sub/nested.filters", "")
        val rootDir = requireNotNull(myFixture.findFileInTempDir("cs1"))
        val names = ConfigSetManager(project, rootDir).setNames()
        assertEquals(listOf("alpha", "beta"), names.map { it.lowercase() })
    }

    fun testEffectiveSetFallbackChain() {
        myFixture.addFileToProject("cs2/megatron/aaa.filters", "")
        myFixture.addFileToProject("cs2/megatron/bbb.filters", "")
        val rootDir = requireNotNull(myFixture.findFileInTempDir("cs2"))
        val sets = ConfigSetManager(project, rootDir)
        val state = MegatronFilterState.getInstance(project)
        try {
            state.setActiveSet("BBB")
            assertEquals("bbb", sets.effectiveSet().lowercase())
            state.setActiveSet("gone")
            assertEquals("aaa", sets.effectiveSet().lowercase())
        } finally {
            state.setActiveSet("default")
        }
    }

    fun testEffectiveSetWithNoSetsIsDefault() {
        myFixture.addFileToProject("cs3/main.cpp", "")
        val rootDir = requireNotNull(myFixture.findFileInTempDir("cs3"))
        assertEquals("default", ConfigSetManager(project, rootDir).effectiveSet())
        assertEmpty(ConfigSetManager(project, rootDir).setNames())
    }

    fun testCreateDefaultSetWritesParseableDocumentedFiles() {
        myFixture.addFileToProject("cs4/main.cpp", "")
        val rootDir = requireNotNull(myFixture.findFileInTempDir("cs4"))
        val sets = ConfigSetManager(project, rootDir)

        sets.createDefaultSet()

        val filters = requireNotNull(rootDir.findFileByRelativePath("megatron/default.filters"))
        val folders = requireNotNull(rootDir.findFileByRelativePath("megatron/default.folders"))
        val groups = parseFilterFile(String(filters.contentsToByteArray(), filters.charset))
        assertEquals(1, groups.size)
        assertEquals("Sources", groups[0].name)
        val layout = parseFoldersFile(String(folders.contentsToByteArray(), folders.charset))
        assertEmpty(layout.folders)
        assertTrue(layout.header.isNotEmpty())
        assertEquals(listOf("default"), sets.setNames().map { it.lowercase() })
    }

    fun testFileResolutionFindsEffectiveSetsFiles() {
        myFixture.addFileToProject("cs5/megatron/one.filters", "Docs: *.md")
        myFixture.addFileToProject("cs5/megatron/two.filters", "Sources: *.cpp")
        val rootDir = requireNotNull(myFixture.findFileInTempDir("cs5"))
        val sets = ConfigSetManager(project, rootDir)
        val state = MegatronFilterState.getInstance(project)
        try {
            state.setActiveSet("two")
            assertEquals("two.filters", sets.filtersFile()?.name)
            assertNull(sets.foldersFile())
        } finally {
            state.setActiveSet("default")
        }
    }
}
```

- [ ] **Step 2: Run to verify failure** — `.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.ConfigSetManagerTest"` → COMPILE FAILURE.

- [ ] **Step 3: Implement ConfigSetManager.kt**

```kotlin
package com.daverobins.projectfilesbrowser

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException

/**
 * Resolves Megatron config sets: megatron/<set>.filters and megatron/<set>.folders
 * under the project root. Stateless — scans the directory on demand.
 */
class ConfigSetManager(private val project: Project, private val rootDir: VirtualFile) {

    fun megatronDir(): VirtualFile? =
        rootDir.findChild(DIR_NAME)?.takeIf { it.isDirectory && it.isValid }

    /** Base names of all config files: first-seen casing, sorted case-insensitively. */
    fun setNames(): List<String> =
        (megatronDir()?.children ?: VirtualFile.EMPTY_ARRAY)
            .filter { !it.isDirectory && it.isValid && it.extension?.lowercase() in CONFIG_EXTENSIONS }
            .map { it.nameWithoutExtension }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }

    /** The persisted set if it exists, else the first existing set, else "default". */
    fun effectiveSet(): String {
        val names = setNames()
        val persisted = MegatronFilterState.getInstance(project).getActiveSet()
        return names.firstOrNull { it.equals(persisted, ignoreCase = true) }
            ?: names.firstOrNull()
            ?: DEFAULT_SET
    }

    fun filtersFile(): VirtualFile? = configFile("${effectiveSet()}.$FILTERS_EXT")

    fun foldersFile(): VirtualFile? = configFile("${effectiveSet()}.$FOLDERS_EXT")

    private fun configFile(name: String): VirtualFile? =
        megatronDir()?.children?.firstOrNull {
            !it.isDirectory && it.isValid && it.name.equals(name, ignoreCase = true)
        }

    /** Rewrites the effective set's .folders file, creating megatron/ and the file as needed. EDT only. */
    fun writeFoldersFile(text: String) {
        writeConfigFile("${effectiveSet()}.$FOLDERS_EXT", text)
    }

    /** Creates the documented default set and opens both files in the editor. EDT only. */
    fun createDefaultSet() {
        writeConfigFile("$DEFAULT_SET.$FILTERS_EXT", DEFAULT_FILTERS_CONTENT)
        writeConfigFile("$DEFAULT_SET.$FOLDERS_EXT", DEFAULT_FOLDERS_CONTENT)
        val editors = FileEditorManager.getInstance(project)
        configFile("$DEFAULT_SET.$FOLDERS_EXT")?.let { editors.openFile(it, false) }
        configFile("$DEFAULT_SET.$FILTERS_EXT")?.let { editors.openFile(it, true) }
    }

    private fun writeConfigFile(name: String, text: String) {
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                val dir = rootDir.findChild(DIR_NAME)
                    ?: rootDir.createChildDirectory(this, DIR_NAME)
                val file = dir.children.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: dir.createChildData(this, name)
                VfsUtil.saveText(file, text)
            }
        } catch (e: IOException) {
            logger<ConfigSetManager>().warn("Failed to write $DIR_NAME/$name", e)
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Megatron")
                .createNotification("Could not write $DIR_NAME/$name: ${e.message}", NotificationType.ERROR)
                .notify(project)
        }
    }

    companion object {
        const val DIR_NAME = "megatron"
        const val DEFAULT_SET = "default"
        const val FILTERS_EXT = "filters"
        const val FOLDERS_EXT = "folders"
        private val CONFIG_EXTENSIONS = setOf(FILTERS_EXT, FOLDERS_EXT)

        val DEFAULT_FILTERS_CONTENT = """
            |# Megatron filter groups — megatron/<set>.filters
            |#
            |# One group per line:   Name: pattern, pattern, ...
            |# Toggle groups on and off from the funnel dropdown in the Megatron toolbar.
            |# A file is shown if it matches ANY pattern of ANY enabled group.
            |# With no groups (or every group off), built-in C/C++/CMake defaults apply.
            |#
            |# Pattern rules (case-insensitive):
            |#   *    matches within one path segment      (*.cpp)
            |#   ?    matches a single character           (test?.h)
            |#   **   crosses directory separators         (src/**)
            |#   A pattern containing '/' matches the project-relative path;
            |#   one without '/' matches the file name only.
            |
            |Sources: *.c, *.cc, *.cpp, *.cxx, *.h, *.hh, *.hpp, *.hxx, *.inl, CMakeLists.txt, *.cmake
            |""".trimMargin()

        val DEFAULT_FOLDERS_CONTENT = """
            |# Megatron virtual folders — megatron/<set>.folders
            |#
            |# Folder lines end with '/':      Core/        (nest with Core/Math/)
            |# Lines under a folder assign files to it:
            |#   src/engine.cpp        exact file (project-relative path)
            |#   src/**                glob pattern — auto-assigns matching files
            |#   !src/generated.cpp    exclusion — keeps a file out of pattern matches
            |#
            |# Precedence per file: exact entry beats exclusions, exclusions beat
            |# patterns, and among patterns the one LATEST in this file wins.
            |# One folder per file. Unclaimed files appear under <Unassigned> in
            |# Folder View. UI edits (right-click, drag-and-drop) rewrite this file
            |# but keep this comment header.
            |""".trimMargin()
    }
}
```

- [ ] **Step 4: Run the new tests, then the full suite** — both BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```powershell
git add src/main/kotlin/com/daverobins/projectfilesbrowser/ConfigSetManager.kt src/test/kotlin/com/daverobins/projectfilesbrowser/ConfigSetManagerTest.kt
git commit -m "feat: ConfigSetManager — set scanning, resolution, documented default set"
```

---

### Task 4: FilterEngine + FolderLayoutStore read through the manager

**Files:**
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/FilterEngine.kt`
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/FolderLayoutStore.kt`
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/ConfigSetManagerTest.kt` (add switch test)
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/FilteredTreeStructureTest.kt` (fixture paths)
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/FolderLayoutStoreTest.kt` (fixture paths)
- Check: `src/test/kotlin/com/daverobins/projectfilesbrowser/FilterEngineGateTest.kt` (update any `megatron.filters` fixture paths the same way)

**Interfaces:**
- Consumes: `ConfigSetManager` (Task 3).
- Produces: `FilterEngine`/`FolderLayoutStore` public signatures UNCHANGED (both construct a private `ConfigSetManager` internally). `FilterEngine.FILTER_FILE_NAME` and `FolderLayoutStore.FOLDERS_FILE_NAME` constants REMAIN in this task (the watcher still references them; Task 5 deletes them).

- [ ] **Step 1: Write the failing test** (append to ConfigSetManagerTest)

```kotlin
    fun testSwitchingSetsChangesEngineVisibility() {
        myFixture.addFileToProject("sw/megatron/aa.filters", "Docs: *.md")
        myFixture.addFileToProject("sw/megatron/bb.filters", "Sources: *.cpp")
        val rootDir = requireNotNull(myFixture.findFileInTempDir("sw"))
        val engine = FilterEngine(project, rootDir)
        val state = MegatronFilterState.getInstance(project)
        try {
            state.setActiveSet("aa")
            assertTrue(engine.isGroupVisible("x.md", "x.md"))
            assertFalse(engine.isGroupVisible("y.cpp", "y.cpp"))
            state.setActiveSet("bb")
            assertFalse(engine.isGroupVisible("x.md", "x.md"))
            assertTrue(engine.isGroupVisible("y.cpp", "y.cpp"))
        } finally {
            state.setActiveSet("default")
        }
    }
```

(This is also the cache-collision test: both files typically share modification stamp values, so only a path-aware cache key passes.)

- [ ] **Step 2: Migrate fixture paths in the existing tests**

Mechanical replacements in TEST sources only:
- `FilteredTreeStructureTest.kt`: every `addFileToProject("X/megatron.filters", …)` → `addFileToProject("X/megatron/default.filters", …)` and every `"X/megatron.folders"` → `"X/megatron/default.folders"` (all fixture prefixes: gp, par, fv, fn, ff, fc, pw, px, pf — do a whole-file search).
- `FolderLayoutStoreTest.kt`: same replacement for the s3 fixture; in `testMutateCreatesFileAndRewritesIt` the assertions change from `rootDir.findChild(FolderLayoutStore.FOLDERS_FILE_NAME)` to `rootDir.findFileByRelativePath("megatron/default.folders")` (mutate must create the directory too); in `testLayoutReloadsAfterExternalEdit` resolve the file the same way.
- `FilterEngineGateTest.kt`: search for `megatron.filters`; apply the same relocation if present, otherwise leave untouched.

- [ ] **Step 3: Run to verify failures** — `.\gradlew.bat test` → the new switch test fails (engine still reads root file) and the migrated tests fail (configs now live in megatron/ where the engine doesn't look).

- [ ] **Step 4: Implement**

`FilterEngine.kt` — replace the cache fields and `groups()`:

```kotlin
    private val sets = ConfigSetManager(project, rootDir)

    private var cachedKey: Pair<String, Long>? = null
    private var cachedGroups: List<FilterGroup> = emptyList()
```

```kotlin
    @Synchronized
    private fun groups(): List<FilterGroup> {
        val file = sets.filtersFile()
        if (file == null) {
            cachedKey = null
            cachedGroups = emptyList()
            return cachedGroups
        }
        val key = file.path to file.modificationStamp
        if (key != cachedKey) {
            cachedGroups = parseFilterFile(loadText(file))
            cachedKey = key
        }
        return cachedGroups
    }
```

(`rootDir` stays a constructor property — `relativePath` still uses it. Delete the now-unused `NO_FILE_STAMP` const; KEEP `FILTER_FILE_NAME` for the watcher until Task 5.)

`FolderLayoutStore.kt` — same treatment:

```kotlin
class FolderLayoutStore(
    private val project: Project,
    rootDir: VirtualFile,
) {

    private val sets = ConfigSetManager(project, rootDir)

    private var cachedKey: Pair<String, Long>? = null
    private var cachedLayout = FolderLayout()

    @Synchronized
    fun layout(): FolderLayout {
        val file = sets.foldersFile()
        if (file == null) {
            cachedKey = null
            cachedLayout = FolderLayout()
            return cachedLayout
        }
        val key = file.path to file.modificationStamp
        if (key != cachedKey) {
            cachedLayout = parseFoldersFile(loadText(file))
            cachedKey = key
        }
        return cachedLayout
    }

    /** Applies [change] to the current layout and rewrites the effective set's folders file. EDT only. */
    fun mutate(change: (FolderLayout) -> FolderLayout) {
        sets.writeFoldersFile(change(layout()).serialize())
    }
```

(`loadText` stays; the notification/dir-creation logic moved into `ConfigSetManager.writeConfigFile`, so the store's own try/catch, `WriteCommandAction`, and Notification imports go away. KEEP `FOLDERS_FILE_NAME` for the watcher until Task 5; delete `NO_FILE_STAMP`.)

- [ ] **Step 5: Run the full suite** — BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```powershell
git add -A src
git commit -m "feat: engine and store resolve config through the active set"
```

---

### Task 5: Watcher relevance moves to the megatron/ directory

**Files:**
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/VfsChangeWatcher.kt`
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/FilterEngine.kt` (delete `FILTER_FILE_NAME`)
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/FolderLayoutStore.kt` (delete `FOLDERS_FILE_NAME`)
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/VfsChangeWatcherRelevanceTest.kt`

**Interfaces:**
- Produces: `isConfigFileEvent(rootPath, oldPath, newPath)` keeps its name/signature; semantics become: true iff either path is the `<root>/megatron` directory itself or a DIRECT child of it ending in `.filters`/`.folders` (case-insensitive).

- [ ] **Step 1: Update the tests**

In `VfsChangeWatcherRelevanceTest.kt`, update the existing config-file tests: paths like `"/root/megatron.filters"` become `"/root/megatron/default.filters"`. Then add:

```kotlin
    @Test
    fun `megatron directory relevance covers dir, both extensions, and rejects the rest`() {
        assertTrue(VfsChangeWatcher.isConfigFileEvent("/root", null, "/root/megatron"))
        assertTrue(VfsChangeWatcher.isConfigFileEvent("/root", null, "/root/megatron/work.filters"))
        assertTrue(VfsChangeWatcher.isConfigFileEvent("/root", null, "/root/MEGATRON/Work.FOLDERS"))
        assertTrue(VfsChangeWatcher.isConfigFileEvent("/root", "/root/megatron/a.filters", "/elsewhere/x"))
        assertFalse(VfsChangeWatcher.isConfigFileEvent("/root", null, "/root/megatron.filters"))
        assertFalse(VfsChangeWatcher.isConfigFileEvent("/root", null, "/root/megatron.folders"))
        assertFalse(VfsChangeWatcher.isConfigFileEvent("/root", null, "/root/megatron/sub/a.filters"))
        assertFalse(VfsChangeWatcher.isConfigFileEvent("/root", null, "/root/megatron/readme.txt"))
        assertFalse(VfsChangeWatcher.isConfigFileEvent("/root", null, "/other/megatron/a.filters"))
    }
```

(Match the file's existing assertion style.)

- [ ] **Step 2: Run to verify failure** — the new test fails (root-relative names still pass, megatron/ names don't).

- [ ] **Step 3: Implement**

Replace `isConfigFileEvent` + `CONFIG_FILE_NAMES` in the companion with:

```kotlin
        /** Events touching the megatron config directory, or a config file directly
         *  inside it, always trigger a refresh — including content changes, since
         *  those files define what the tree shows. */
        fun isConfigFileEvent(rootPath: String, oldPath: String?, newPath: String): Boolean =
            isConfigPath(rootPath, newPath) || (oldPath != null && isConfigPath(rootPath, oldPath))

        private fun isConfigPath(rootPath: String, path: String): Boolean {
            val dir = "$rootPath/${ConfigSetManager.DIR_NAME}"
            if (path.equals(dir, ignoreCase = true)) return true
            if (path.length <= dir.length + 1 ||
                !path.regionMatches(0, dir, 0, dir.length, ignoreCase = true) ||
                path[dir.length] != '/'
            ) return false
            val rest = path.substring(dir.length + 1)
            if ('/' in rest) return false
            val lower = rest.lowercase()
            return lower.endsWith(".${ConfigSetManager.FILTERS_EXT}") ||
                lower.endsWith(".${ConfigSetManager.FOLDERS_EXT}")
        }
```

Delete `FilterEngine.FILTER_FILE_NAME` and `FolderLayoutStore.FOLDERS_FILE_NAME` (grep for remaining references first — after Task 4's test migration there should be none; if a test still uses one, replace it with the literal string).

- [ ] **Step 4: Run the full suite** — BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```powershell
git add -A src
git commit -m "feat: watcher treats megatron/ config files as always-relevant"
```

---

### Task 6: Set dropdown, empty-state banner, version 0.7.0

**Files:**
- Create: `src/main/kotlin/com/daverobins/projectfilesbrowser/SetSwitcherAction.kt`
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/ProjectFilesPanel.kt`
- Modify: `build.gradle.kts` (version)

**Interfaces:**
- Consumes: `ConfigSetManager` (Task 3), `MegatronFilterState.setActiveSet` (Task 1).
- Produces: final consumers. Verified platform APIs (javap against CLion 2026.1.1 — trust these): `com.intellij.ui.HyperlinkLabel()` no-arg + `setHyperlinkText(String)` + `addHyperlinkListener(javax.swing.event.HyperlinkListener)`; `FileEditorManager.getInstance(project).openFile(VirtualFile, Boolean)`; `com.intellij.ui.components.JBLabel(String)`.

- [ ] **Step 1: Create SetSwitcherAction.kt**

```kotlin
package com.daverobins.projectfilesbrowser

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.Project

/**
 * Toolbar dropdown showing the effective config set; children are the scanned
 * sets as radio toggles (computed per-show, like the filter dropdown).
 */
class SetSwitcherAction(
    private val project: Project,
    private val sets: ConfigSetManager,
    private val onChanged: () -> Unit,
) : ActionGroup("Config Set", "Switch Megatron config set", null) {

    init {
        isPopup = true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.text = sets.effectiveSet()
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val names = sets.setNames()
        if (names.isEmpty()) return arrayOf(CreateDefaultSetAction())
        return names.map { SetToggleAction(it) }.toTypedArray()
    }

    private inner class SetToggleAction(private val name: String) : ToggleAction(name) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun isSelected(e: AnActionEvent): Boolean =
            name.equals(sets.effectiveSet(), ignoreCase = true)

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) {
                MegatronFilterState.getInstance(project).setActiveSet(name)
                onChanged()
            }
        }
    }

    private inner class CreateDefaultSetAction : AnAction("Create Default Set") {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) {
            sets.createDefaultSet()
            onChanged()
        }
    }
}
```

- [ ] **Step 2: Wire the panel**

In `ProjectFilesPanel.kt`:

Add imports `com.intellij.ui.HyperlinkLabel`, `com.intellij.ui.components.JBLabel`, `java.awt.BorderLayout`, `java.awt.FlowLayout`, `javax.swing.JPanel`, `javax.swing.event.HyperlinkEvent`.

Add fields (after `engine`, before `structureModel` — initialization order matters):

```kotlin
    private val sets = ConfigSetManager(project, rootDir)
```

and after the `tree` field:

```kotlin
    private val banner: JPanel = buildBanner()
```

Add methods:

```kotlin
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
```

In `init`: toolbar group gains `SetSwitcherAction(project, sets) { configChanged() },` inserted directly AFTER the refresh action (so the order is refresh, set switcher, filter dropdown, flat toggle, folder toggle). Content changes from the bare scroll pane to:

```kotlin
        val content = JPanel(BorderLayout())
        content.add(banner, BorderLayout.NORTH)
        content.add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)
        setContent(content)
```

The watcher callback changes from `structureModel.invalidateAsync()` to `configChanged()` (the SingleAlarm fires on the EDT, so this is safe).

- [ ] **Step 3: Bump the version** — `build.gradle.kts`: `version = "0.6.0"` → `version = "0.7.0"`.

- [ ] **Step 4: Run the full suite** — BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```powershell
git add src/main/kotlin/com/daverobins/projectfilesbrowser/SetSwitcherAction.kt src/main/kotlin/com/daverobins/projectfilesbrowser/ProjectFilesPanel.kt build.gradle.kts
git commit -m "feat: config set dropdown and create-default-set banner; version 0.7.0"
```

---

## Sandbox Checklist (post-implementation, human verification)

- Fresh project (no megatron/): banner shows; Create default set → files created with docs, opened in editors, banner hides, "default" in dropdown.
- Two sets: switch from the dropdown → tree and filter groups change; persisted across IDE restart.
- Edit a NON-active set's file → view refreshes (config-file event) but content unchanged.
- Legacy root megatron.filters/megatron.folders are ignored.
- Drag a file in folder view with the default set → megatron/default.folders rewritten WITH its comment header intact.
