# Filter Groups (Phase 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `megatron.filters` file in the project root defines named filter groups; a toolbar dropdown toggles them; the tree shows files matching any enabled group, falling back to the built-in C/C++ defaults when no groups are active.

**Architecture:** Pure parsing/matching logic in `FilterConfig.kt` (wildcards compiled to regexes). A panel-owned `FilterEngine` caches the parsed file by VFS modification stamp and merges it with per-project toggle state (`MegatronFilterState`, workspace-persisted). `FilteredTreeStructure` delegates file visibility to the engine; `VfsChangeWatcher` treats `megatron.filters` as always-relevant so edits live-reload through the existing 500 ms debounce; a dynamic `ActionGroup` popup renders the toggles.

**Tech Stack:** Existing toolchain — Kotlin 2.3.0, Gradle 9.6.1 wrapper, IntelliJ Platform Gradle Plugin 2.18.1, JDK 21, target CLion 2026.1.1.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-15-filter-groups-design.md` — its format rules, wildcard semantics, filtering semantics, fallback rule, and dropdown behavior are the requirements; this plan implements them exactly.
- Package: `com.daverobins.projectfilesbrowser`
- Filter file: exactly `megatron.filters` (lowercase) in the tree's root directory
- Wildcards: `*` = any run within a segment (never `/`); `?` = one char (never `/`); `**` = any run including `/`; pattern with `/` matches project-relative path, without `/` matches file name; case-insensitive; no other metacharacters (everything else literal)
- Fallback to `FileFilter.includeFile` when: no file, zero valid groups, or all groups disabled
- Noise-dir exclusion (`FileFilter.includeDirectory`) always applies, unchanged
- Toggle state = set of DISABLED group names, persisted in the workspace file; unknown groups default enabled
- Plugin version bumps to `0.2.0`
- Shell: Windows PowerShell 5.1 (no `&&`); Gradle via `.\gradlew.bat`; on Java-version errors run `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"` first
- Branch: `feature/phase2-filter-groups`; commit at the end of every task

---

### Task 1: FilterConfig — parser, glob matcher, composition (TDD, pure)

**Files:**
- Create: `src/main/kotlin/com/daverobins/projectfilesbrowser/FilterConfig.kt`
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/FilterConfigTest.kt`

**Interfaces:**
- Consumes: `FileFilter.includeFile(name: String): Boolean` (existing).
- Produces: `class GlobPattern(pattern: String)` with `matches(relativePath: String, fileName: String): Boolean`; `class FilterGroup(val name: String, val patterns: List<GlobPattern>)`; `fun parseFilterFile(text: String): List<FilterGroup>`; `fun visibleByGroups(enabledGroups: List<FilterGroup>, relativePath: String, fileName: String): Boolean`. Tasks 2–3 rely on these exact signatures.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/daverobins/projectfilesbrowser/FilterConfigTest.kt`:

```kotlin
package com.daverobins.projectfilesbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterConfigTest {

    // --- GlobPattern ---

    @Test
    fun starMatchesWithinSegmentOnly() {
        val p = GlobPattern("*.cpp")
        assertTrue(p.matches("src/main.cpp", "main.cpp"))          // name pattern: applies to file name
        assertTrue(p.matches("main.cpp", "main.cpp"))
        assertFalse(p.matches("src/main.h", "main.h"))
    }

    @Test
    fun namePatternMatchesAnywhereInTree() {
        val p = GlobPattern("CMakeLists.txt")
        assertTrue(p.matches("deep/nested/CMakeLists.txt", "CMakeLists.txt"))
    }

    @Test
    fun pathPatternStarDoesNotCrossSlash() {
        val p = GlobPattern("src/*.cpp")
        assertTrue(p.matches("src/main.cpp", "main.cpp"))
        assertFalse(p.matches("src/sub/deep.cpp", "deep.cpp"))
    }

    @Test
    fun doubleStarCrossesDirectories() {
        val p = GlobPattern("src/**")
        assertTrue(p.matches("src/main.cpp", "main.cpp"))
        assertTrue(p.matches("src/sub/deep/x.h", "x.h"))
        assertFalse(p.matches("other/main.cpp", "main.cpp"))
    }

    @Test
    fun questionMarkMatchesExactlyOneNonSlashChar() {
        val p = GlobPattern("a?.cpp")
        assertTrue(p.matches("ab.cpp", "ab.cpp"))
        assertFalse(p.matches("a.cpp", "a.cpp"))
        assertFalse(p.matches("abc.cpp", "abc.cpp"))
        assertFalse(GlobPattern("a?b").matches("a/b", "a/b"))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertTrue(GlobPattern("*.CPP").matches("MAIN.cpp", "MAIN.cpp"))
        assertTrue(GlobPattern("SRC/**").matches("src/x.h", "x.h"))
    }

    @Test
    fun regexMetacharactersAreLiteral() {
        val p = GlobPattern("a+b.cpp")
        assertTrue(p.matches("a+b.cpp", "a+b.cpp"))
        assertFalse(p.matches("aab.cpp", "aab.cpp"))
    }

    @Test
    fun patternMustMatchWholeTarget() {
        val p = GlobPattern("main.cpp")
        assertFalse(p.matches("xmain.cpp", "xmain.cpp"))
        assertFalse(p.matches("main.cpp.bak", "main.cpp.bak"))
    }

    // --- parseFilterFile ---

    @Test
    fun parsesGroupsInFileOrder() {
        val groups = parseFilterFile("Sources: *.cpp, *.c\nHeaders: *.h")
        assertEquals(listOf("Sources", "Headers"), groups.map { it.name })
        assertEquals(2, groups[0].patterns.size)
        assertEquals(1, groups[1].patterns.size)
    }

    @Test
    fun ignoresCommentsAndBlankLines() {
        val groups = parseFilterFile("# comment\n\n  \nSources: *.cpp\n  # indented comment")
        assertEquals(listOf("Sources"), groups.map { it.name })
    }

    @Test
    fun skipsMalformedLines() {
        val groups = parseFilterFile("no colon here\n: nameless\nEmptyPatterns: , ,\nGood: *.h")
        assertEquals(listOf("Good"), groups.map { it.name })
    }

    @Test
    fun trimsNamesAndPatterns() {
        val groups = parseFilterFile("  My Group  :  *.cpp ,  *.h  ")
        assertEquals("My Group", groups[0].name)
        assertTrue(groups[0].patterns[1].matches("a.h", "a.h"))
    }

    @Test
    fun duplicateGroupNameLastWins() {
        val groups = parseFilterFile("G: *.cpp\nG: *.md")
        assertEquals(1, groups.size)
        assertTrue(groups[0].patterns[0].matches("x.md", "x.md"))
        assertFalse(groups[0].patterns[0].matches("x.cpp", "x.cpp"))
    }

    @Test
    fun patternAfterFirstColonMayContainColons() {
        val groups = parseFilterFile("G: a:b*.txt")
        assertEquals(1, groups.size)
        assertTrue(groups[0].patterns[0].matches("a:bc.txt", "a:bc.txt"))
    }

    // --- visibleByGroups ---

    @Test
    fun emptyEnabledGroupsFallsBackToBuiltInFilter() {
        assertTrue(visibleByGroups(emptyList(), "src/main.cpp", "main.cpp"))
        assertFalse(visibleByGroups(emptyList(), "readme.md", "readme.md"))
    }

    @Test
    fun unionAcrossEnabledGroups() {
        val groups = parseFilterFile("Docs: *.md\nSources: src/**")
        assertTrue(visibleByGroups(groups, "readme.md", "readme.md"))
        assertTrue(visibleByGroups(groups, "src/anything.xyz", "anything.xyz"))
        assertFalse(visibleByGroups(groups, "other/tool.cpp", "tool.cpp"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.FilterConfigTest" --console=plain
```

Expected: FAILED — compilation error, `GlobPattern` unresolved.

- [ ] **Step 3: Write the implementation**

`src/main/kotlin/com/daverobins/projectfilesbrowser/FilterConfig.kt`:

```kotlin
package com.daverobins.projectfilesbrowser

/**
 * One wildcard filter compiled to a regex at construction.
 * A pattern containing '/' matches the project-relative path; one without '/'
 * matches the bare file name. `*` and `?` never cross '/', `**` does.
 */
class GlobPattern(pattern: String) {
    private val matchesPath = '/' in pattern
    private val regex = compile(pattern)

    fun matches(relativePath: String, fileName: String): Boolean =
        regex.matches(if (matchesPath) relativePath else fileName)

    private companion object {
        fun compile(pattern: String): Regex {
            val sb = StringBuilder()
            var i = 0
            while (i < pattern.length) {
                when {
                    pattern.startsWith("**", i) -> { sb.append(".*"); i += 2 }
                    pattern[i] == '*' -> { sb.append("[^/]*"); i++ }
                    pattern[i] == '?' -> { sb.append("[^/]"); i++ }
                    else -> { sb.append(Regex.escape(pattern[i].toString())); i++ }
                }
            }
            return Regex(sb.toString(), RegexOption.IGNORE_CASE)
        }
    }
}

class FilterGroup(val name: String, val patterns: List<GlobPattern>)

/**
 * Parses megatron.filters content: one `Name: pattern, pattern` per line,
 * `#` comments and blank lines ignored, malformed lines skipped,
 * duplicate names last-wins.
 */
fun parseFilterFile(text: String): List<FilterGroup> {
    val byName = LinkedHashMap<String, FilterGroup>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        val name = line.substring(0, colon).trim()
        if (name.isEmpty()) continue
        val patterns = line.substring(colon + 1)
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { GlobPattern(it) }
        if (patterns.isEmpty()) continue
        byName[name] = FilterGroup(name, patterns)
    }
    return byName.values.toList()
}

/**
 * A file is visible if it matches any pattern of any enabled group;
 * with no enabled groups the built-in defaults apply.
 */
fun visibleByGroups(
    enabledGroups: List<FilterGroup>,
    relativePath: String,
    fileName: String,
): Boolean =
    if (enabledGroups.isEmpty()) FileFilter.includeFile(fileName)
    else enabledGroups.any { group -> group.patterns.any { it.matches(relativePath, fileName) } }
```

- [ ] **Step 4: Run test to verify it passes**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.FilterConfigTest" --console=plain
```

Expected: `BUILD SUCCESSFUL`, all 16 tests pass.

- [ ] **Step 5: Run ALL tests**

```powershell
.\gradlew.bat test --console=plain
```

Expected: `BUILD SUCCESSFUL` — 39 tests green (23 existing + 16 new).

- [ ] **Step 6: Commit**

```powershell
git add src
git commit -m "feat: filter-group parser and wildcard matcher for megatron.filters"
```

---

### Task 2: MegatronFilterState + FilterEngine

**Files:**
- Create: `src/main/kotlin/com/daverobins/projectfilesbrowser/MegatronFilterState.kt`
- Create: `src/main/kotlin/com/daverobins/projectfilesbrowser/FilterEngine.kt`
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/MegatronFilterStateTest.kt`

**Interfaces:**
- Consumes: `parseFilterFile`, `visibleByGroups`, `FilterGroup` from Task 1.
- Produces: `MegatronFilterState.getInstance(project)` with `isEnabled(name: String): Boolean` / `setEnabled(name: String, enabled: Boolean)`; `class FilterEngine(project: Project, rootDir: VirtualFile)` with `isFileVisible(relativePath: String, fileName: String): Boolean` and `groupsForUi(): List<Pair<String, Boolean>>`; `FilterEngine.FILTER_FILE_NAME == "megatron.filters"`. Tasks 3–5 rely on these.

- [ ] **Step 1: Write the failing test (state logic only — the engine is VFS glue, covered by Task 3's platform test)**

`src/test/kotlin/com/daverobins/projectfilesbrowser/MegatronFilterStateTest.kt`:

```kotlin
package com.daverobins.projectfilesbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MegatronFilterStateTest {

    @Test
    fun groupsAreEnabledByDefault() {
        val state = MegatronFilterState()
        assertTrue(state.isEnabled("NeverSeenBefore"))
    }

    @Test
    fun disableThenReenableRoundTrips() {
        val state = MegatronFilterState()
        state.setEnabled("Docs", false)
        assertFalse(state.isEnabled("Docs"))
        state.setEnabled("Docs", true)
        assertTrue(state.isEnabled("Docs"))
    }

    @Test
    fun persistedStateHoldsOnlyDisabledNames() {
        val state = MegatronFilterState()
        state.setEnabled("A", false)
        state.setEnabled("B", true)
        assertEquals(setOf("A"), state.state.disabledGroups)
    }

    @Test
    fun loadStateReplacesCurrent() {
        val state = MegatronFilterState()
        val incoming = MegatronFilterState.State().apply { disabledGroups = mutableSetOf("X") }
        state.loadState(incoming)
        assertFalse(state.isEnabled("X"))
        assertTrue(state.isEnabled("Y"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.MegatronFilterStateTest" --console=plain
```

Expected: FAILED — compilation error, `MegatronFilterState` unresolved.

- [ ] **Step 3: Write `MegatronFilterState.kt`**

```kotlin
package com.daverobins.projectfilesbrowser

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/**
 * Per-project toggle state for filter groups. Stores only the DISABLED group
 * names (in the workspace file, not in megatron.filters), so unknown/new
 * groups default to enabled.
 */
@Service(Service.Level.PROJECT)
@State(name = "MegatronFilters", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class MegatronFilterState : PersistentStateComponent<MegatronFilterState.State> {

    class State {
        var disabledGroups: MutableSet<String> = mutableSetOf()
    }

    private var current = State()

    override fun getState(): State = current

    override fun loadState(state: State) {
        current = state
    }

    fun isEnabled(name: String): Boolean = name !in current.disabledGroups

    fun setEnabled(name: String, enabled: Boolean) {
        if (enabled) current.disabledGroups.remove(name) else current.disabledGroups.add(name)
    }

    companion object {
        fun getInstance(project: Project): MegatronFilterState = project.service()
    }
}
```

Note: the test calls `state.state` — that resolves to `getState()` via Kotlin property syntax.

- [ ] **Step 4: Write `FilterEngine.kt`**

```kotlin
package com.daverobins.projectfilesbrowser

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException

/**
 * Panel-owned facade over megatron.filters: caches the parsed groups by the
 * file's VFS modification stamp and combines them with the per-project toggle
 * state to answer visibility queries.
 */
class FilterEngine(private val project: Project, private val rootDir: VirtualFile) {

    private var cachedStamp = NO_FILE_STAMP
    private var cachedGroups: List<FilterGroup> = emptyList()

    fun isFileVisible(relativePath: String, fileName: String): Boolean =
        visibleByGroups(enabledGroups(), relativePath, fileName)

    fun groupsForUi(): List<Pair<String, Boolean>> {
        val state = MegatronFilterState.getInstance(project)
        return groups().map { it.name to state.isEnabled(it.name) }
    }

    private fun enabledGroups(): List<FilterGroup> {
        val state = MegatronFilterState.getInstance(project)
        return groups().filter { state.isEnabled(it.name) }
    }

    @Synchronized
    private fun groups(): List<FilterGroup> {
        val file = rootDir.findChild(FILTER_FILE_NAME)
        if (file == null || file.isDirectory || !file.isValid) {
            cachedStamp = NO_FILE_STAMP
            cachedGroups = emptyList()
            return cachedGroups
        }
        if (file.modificationStamp != cachedStamp) {
            cachedGroups = parseFilterFile(loadText(file))
            cachedStamp = file.modificationStamp
        }
        return cachedGroups
    }

    private fun loadText(file: VirtualFile): String =
        try {
            String(file.contentsToByteArray(), file.charset)
        } catch (e: IOException) {
            logger<FilterEngine>().warn("Failed to read ${file.path}", e)
            ""
        }

    companion object {
        const val FILTER_FILE_NAME = "megatron.filters"
        private const val NO_FILE_STAMP = -1L
    }
}
```

- [ ] **Step 5: Run the new test, then ALL tests**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.MegatronFilterStateTest" --console=plain
.\gradlew.bat test --console=plain
```

Expected: both `BUILD SUCCESSFUL`; 43 tests total (39 + 4 new).

- [ ] **Step 6: Commit**

```powershell
git add src
git commit -m "feat: FilterEngine and workspace-persisted group toggle state"
```

---

### Task 3: Tree structure delegates to the engine (platform-test TDD)

**Files:**
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/FilteredTreeStructure.kt` (full replacement below)
- Modify: `src/test/kotlin/com/daverobins/projectfilesbrowser/FilteredTreeStructureTest.kt` (constructor updates + new test)

**Interfaces:**
- Consumes: `FilterEngine(project, rootDir)`, `isFileVisible(relativePath, fileName)` from Task 2.
- Produces: `FilteredTreeStructure(project: Project, rootDir: VirtualFile, engine: FilterEngine)` — Task 5 constructs it with the third argument. `FileNode.file` unchanged.

- [ ] **Step 1: Write the failing test — add to `FilteredTreeStructureTest.kt`**

Update the TWO existing constructions of `FilteredTreeStructure(project, rootDir)` to
`FilteredTreeStructure(project, rootDir, FilterEngine(project, rootDir))`, then ADD this test method:

```kotlin
    fun testFilterGroupsFromProjectFileDriveVisibility() {
        myFixture.addFileToProject("gp/megatron.filters", "Docs: *.md\nSources: src/**")
        myFixture.addFileToProject("gp/readme.md", "shown by Docs")
        myFixture.addFileToProject("gp/src/main.cpp", "shown by Sources")
        myFixture.addFileToProject("gp/src/notes.txt", "shown by Sources (src/** matches everything under src)")
        myFixture.addFileToProject("gp/other/tool.cpp", "hidden: matches no group, and fallback is OFF because groups exist")

        val rootDir = requireNotNull(myFixture.findFileInTempDir("gp"))
        val structure = FilteredTreeStructure(project, rootDir, FilterEngine(project, rootDir))
        val rendered = render(structure.rootElement as FileNode)

        assertEquals(
            """
            gp
              src
                main.cpp
                notes.txt
              readme.md

            """.trimIndent(),
            rendered,
        )
    }
```

Note what this asserts implicitly: `megatron.filters` itself is hidden (no group matches it), `other/` is pruned as empty, and the built-in extension fallback did NOT apply (`tool.cpp` hidden, `notes.txt` shown).

- [ ] **Step 2: Run tests to verify the new one fails**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.FilteredTreeStructureTest" --console=plain
```

Expected: FAILED — compilation error (`FilteredTreeStructure` has no 3-arg constructor yet).

- [ ] **Step 3: Replace `FilteredTreeStructure.kt` entirely with:**

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
) : SimpleTreeStructure() {
    private val root = FileNode(project, null, rootDir, engine, rootDir.path)
    override fun getRootElement(): Any = root
}

class FileNode(
    private val project: Project,
    parent: FileNode?,
    val file: VirtualFile,
    private val engine: FilterEngine,
    private val rootPath: String,
) : SimpleNode(project, parent) {

    override fun getChildren(): Array<SimpleNode> {
        if (!file.isDirectory) return NO_CHILDREN
        val visible = (file.children ?: return NO_CHILDREN)
            .filter { it.isValid && isVisible(it) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        if (visible.isEmpty()) return NO_CHILDREN
        return visible.map { FileNode(project, this, it, engine, rootPath) }.toTypedArray()
    }

    override fun update(presentation: PresentationData) {
        presentation.presentableText = file.name
        presentation.setIcon(
            if (file.isDirectory) AllIcons.Nodes.Folder
            else file.fileType.icon ?: AllIcons.FileTypes.Any_type
        )
    }

    override fun getEqualityObjects(): Array<Any> = arrayOf(file)

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

(The former `companion object` helpers become instance methods because visibility now needs the engine and root path. `update` and `getEqualityObjects` are unchanged.)

- [ ] **Step 4: Run the structure tests, then ALL tests**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.FilteredTreeStructureTest" --console=plain
.\gradlew.bat test --console=plain
```

Expected: both `BUILD SUCCESSFUL`; 44 tests total (3 structure tests now). NOTE: `ProjectFilesPanel` still calls the 2-arg constructor at this point — if the full build fails ONLY on `ProjectFilesPanel.kt`, apply the minimal bridging edit there now (construct `FilterEngine(project, rootDir)` into a local `val engine` and pass it), and say so in your report; Task 5 finishes the panel work.

- [ ] **Step 5: Commit**

```powershell
git add src
git commit -m "feat: tree visibility driven by FilterEngine (megatron.filters groups)"
```

---

### Task 4: VfsChangeWatcher — filter file always relevant + rename-branch cleanup (TDD)

**Files:**
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/VfsChangeWatcher.kt`
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/VfsChangeWatcherRelevanceTest.kt` (add tests)

**Interfaces:**
- Consumes: `FilterEngine.FILTER_FILE_NAME` from Task 2.
- Produces: companion `fun isFilterFileEvent(rootPath: String, oldPath: String?, newPath: String): Boolean`. Existing companion functions unchanged.

- [ ] **Step 1: Add failing tests to `VfsChangeWatcherRelevanceTest.kt`**

Add these test methods (and `import com.daverobins.projectfilesbrowser.VfsChangeWatcher.Companion.isFilterFileEvent`):

```kotlin
    @Test
    fun filterFileEventIsAlwaysRelevant() {
        assertTrue(isFilterFileEvent(root, null, "/proj/megatron.filters"))
    }

    @Test
    fun filterFileRenameAwayIsRelevantViaOldPath() {
        assertTrue(isFilterFileEvent(root, "/proj/megatron.filters", "/proj/renamed.txt"))
    }

    @Test
    fun otherFilesAreNotFilterFileEvents() {
        assertFalse(isFilterFileEvent(root, null, "/proj/main.cpp"))
        assertFalse(isFilterFileEvent(root, null, "/proj/sub/megatron.filters")) // only root-level file counts
        assertFalse(isFilterFileEvent(root, null, "/other/megatron.filters"))
    }
```

- [ ] **Step 2: Run to verify failure**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.VfsChangeWatcherRelevanceTest" --console=plain
```

Expected: FAILED — compilation error, `isFilterFileEvent` unresolved.

- [ ] **Step 3: Modify `VfsChangeWatcher.kt`**

Three changes:

(a) Add to the companion object:

```kotlin
        /** Events touching <root>/megatron.filters always trigger a refresh —
         *  including content changes, since the file's content defines the filters. */
        fun isFilterFileEvent(rootPath: String, oldPath: String?, newPath: String): Boolean {
            val filterFilePath = "$rootPath/${FilterEngine.FILTER_FILE_NAME}"
            return newPath == filterFilePath || oldPath == filterFilePath
        }
```

(b) Replace the `isRelevant` function body so the filter-file check runs FIRST (it must catch content-change events, which the `when` otherwise rejects):

```kotlin
    private fun isRelevant(event: VFileEvent): Boolean {
        val oldPath = when (event) {
            is VFileMoveEvent -> event.oldPath
            is VFilePropertyChangeEvent ->
                if (event.propertyName == VirtualFile.PROP_NAME) event.oldPath else null
            else -> null
        }
        if (isFilterFileEvent(rootPath, oldPath, event.path)) return true
        return when (event) {
            is VFileContentChangeEvent -> false
            is VFileCreateEvent -> isRelevantPath(rootPath, event.path, event.isDirectory)
            is VFileDeleteEvent -> isRelevantPath(rootPath, event.path, event.file.isDirectory)
            is VFileCopyEvent -> isRelevantPath(rootPath, event.path, event.file.isDirectory)
            is VFileMoveEvent ->
                isRelevantEitherPath(rootPath, event.oldPath, event.newPath, event.file.isDirectory)
            is VFilePropertyChangeEvent ->
                event.propertyName == VirtualFile.PROP_NAME &&
                    isRelevantEitherPath(rootPath, event.oldPath, event.newPath, event.file.isDirectory)
            else -> true // unknown event type: rebuild conservatively rather than miss a change
        }
    }
```

(c) This replaces the old hand-built rename path (`event.file.parent?.path?.let { "$it/${event.oldValue}" }`) with the platform's `event.oldPath`/`event.newPath` — the deferred phase-1 review cleanup. `VFilePropertyChangeEvent.getOldPath()`/`getNewPath()` exist and compute exactly the old/new path for PROP_NAME changes (verified against the platform in the phase-1 review).

- [ ] **Step 4: Run watcher tests, then ALL tests**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.VfsChangeWatcherRelevanceTest" --console=plain
.\gradlew.bat test --console=plain
```

Expected: both `BUILD SUCCESSFUL`; 47 tests total (16 watcher tests).

- [ ] **Step 5: Commit**

```powershell
git add src
git commit -m "feat: megatron.filters changes always trigger refresh; use platform oldPath/newPath for renames"
```

---

### Task 5: Filter dropdown + panel wiring + version bump

**Files:**
- Create: `src/main/kotlin/com/daverobins/projectfilesbrowser/FilterDropdownAction.kt`
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/ProjectFilesPanel.kt`
- Modify: `build.gradle.kts` (version only)

**Interfaces:**
- Consumes: `FilterEngine` (Task 2: `groupsForUi()`), `MegatronFilterState` (Task 2), `FilteredTreeStructure(project, rootDir, engine)` (Task 3).
- Produces: final UI; no later task depends on new symbols.

- [ ] **Step 1: Write `FilterDropdownAction.kt`**

```kotlin
package com.daverobins.projectfilesbrowser

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.Project

/**
 * Toolbar popup listing filter groups from megatron.filters as checkbox
 * toggles. Children are computed per-show so file edits are reflected
 * without any registration dance.
 */
class FilterDropdownAction(
    private val project: Project,
    private val engine: FilterEngine,
    private val onFilterChanged: () -> Unit,
) : ActionGroup("Filters", "Toggle filter groups", AllIcons.General.Filter) {

    init {
        isPopup = true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val groups = engine.groupsForUi()
        if (groups.isEmpty()) {
            return arrayOf(NoFiltersInfoAction())
        }
        return groups.map { (name, _) -> GroupToggleAction(name) }.toTypedArray()
    }

    private inner class GroupToggleAction(private val groupName: String) : ToggleAction(groupName) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun isSelected(e: AnActionEvent): Boolean =
            MegatronFilterState.getInstance(project).isEnabled(groupName)

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            MegatronFilterState.getInstance(project).setEnabled(groupName, state)
            onFilterChanged()
        }
    }

    private class NoFiltersInfoAction : AnAction("No megatron.filters — using defaults") {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = false
        }

        override fun actionPerformed(e: AnActionEvent) = Unit
    }
}
```

- [ ] **Step 2: Wire into `ProjectFilesPanel.kt`**

Three edits (read the file first; if Task 3 already applied a bridging edit, reconcile to this end state):

1. Add a property BEFORE `structureModel` (property initialization order matters):

```kotlin
    private val engine = FilterEngine(project, rootDir)
```

2. Change the `structureModel` initializer to pass it:

```kotlin
    private val structureModel =
        StructureTreeModel(FilteredTreeStructure(project, rootDir, engine), parentDisposable)
```

3. In the toolbar setup, add the dropdown after the refresh action:

```kotlin
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "ProjectFilesBrowser",
            DefaultActionGroup(
                refresh,
                FilterDropdownAction(project, engine) { structureModel.invalidateAsync() },
            ),
            true,
        )
```

(If the current code passes `DefaultActionGroup(refresh)` in a single expression, replace that expression; keep `toolbar.targetComponent = tree` and the rest untouched.)

- [ ] **Step 3: Bump version**

In `build.gradle.kts`: `version = "0.1.1"` → `version = "0.2.0"`.

- [ ] **Step 4: Build + full suite + plugin verification**

```powershell
.\gradlew.bat build verifyPluginProjectConfiguration --console=plain
```

Expected: `BUILD SUCCESSFUL`, 47/47 tests green.

- [ ] **Step 5: Commit**

```powershell
git add src build.gradle.kts
git commit -m "feat: filter-groups dropdown on the Megatron toolbar"
```

---

### Task 6: Sandbox verification + merge + tag (human checkpoint)

**Files:** none (unless fixes are needed).

- [ ] **Step 1: Launch sandbox**

```powershell
.\gradlew.bat runIde --console=plain
```

Run in background; blocks until the IDE closes.

- [ ] **Step 2: Manual verification checklist (user drives)**

With a CMake/C++ project open in the sandbox and the Project Files tool window visible:

1. No `megatron.filters` yet → dropdown shows the disabled "No megatron.filters — using defaults" entry; tree shows the usual C/C++/CMake files.
2. Create `megatron.filters` in the project root with two groups (e.g. `Sources: *.cpp, *.c` and `Docs: *.md`) → within ~1 s the dropdown lists both groups (open it fresh) and the tree now shows ONLY files matching them (headers disappear — they match no group).
3. Toggle `Docs` off → `.md` files vanish immediately. Toggle back on → they return.
4. Toggle BOTH groups off → tree falls back to the built-in defaults (headers reappear).
5. Edit the file (add `Headers: *.h, *.hpp`) → dropdown gains the group within ~1 s; tree updates.
6. Close and reopen the sandbox project → toggle states were remembered.
7. Regression: refresh button works; auto-refresh on file create/delete still works; `cmake-build-*` still hidden.

- [ ] **Step 3: Fix anything found, re-verify, commit fixes**

Reproduce → fix → re-run → re-check → commit with a `fix:` message naming the defect.

- [ ] **Step 4: Merge and tag (after user confirms; via finishing-a-development-branch)**

Merge `feature/phase2-filter-groups` to `master`, verify tests on the merge result, delete the branch, then:

```powershell
git tag v0.2.0
```
