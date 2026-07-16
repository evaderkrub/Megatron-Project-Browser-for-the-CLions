# Folder Wildcards (Phase 6) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** megatron.folders entries may be glob patterns (auto-assigning matching files to virtual folders) and `!` exclusions (pinning files out of pattern assignment), evaluated live against the files on disk.

**Architecture:** `FolderLayout` gains typed rule entries (`FolderRule`: pattern or exclusion, reusing the existing `GlobPattern`); `folderFor` implements the precedence chain (explicit > exclusion > last-matching-pattern). The folder view switches from "render listed paths" to "resolve rules over the visible-file walk" (same collector flat mode uses). Mutations carry the exclusion semantics internally, so `FolderActions`/`FolderDnD` need no changes.

**Tech Stack:** Kotlin 2.3.0, IntelliJ Platform Gradle Plugin 2.18.1, CLion 2026.1.1, JUnit 4.

## Global Constraints

- Before any Gradle call in a fresh PowerShell: `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"`. Test command: `.\gradlew.bat test` (from `C:\~prj\Dropbox\vibeProjects\clionprojectview`).
- Glob semantics identical to megatron.filters (the existing `GlobPattern` class is reused UNMODIFIED): `*` within a segment, `?` one char, `**` crosses directories; pattern containing `/` matches the project-relative path, otherwise the file name; case-insensitive.
- Line classification: starts with `!` → exclusion; contains `*` or `?` → pattern; otherwise explicit path. A `!` with nothing after it, or any rule line before the first folder declaration, is silently skipped.
- Precedence per visible file: explicit entry (last duplicate wins) > any matching exclusion > matching pattern LATEST in the file.
- Exclusions are global in effect regardless of which folder block they sit in; their block placement only controls where they serialize.
- Serializer: folders in DECLARATION order (no longer sorted); within a block, rule lines (patterns + exclusions, `!` prefix, declaration order) first, then explicit file paths sorted by lowercase path; two-space indent; `\n`-terminated lines. Tree DISPLAY order of folders stays alphabetical (childFolders is unchanged).
- UI mutations: `withAssignment` also removes exact-path exclusions for that file; `withUnassigned` removes the explicit entry AND writes a `!<path>` exclusion under the claiming folder when a pattern would still claim the file.
- The UI never generates glob patterns or glob exclusions — only exact-path exclusion lines.
- `FolderActions.kt` and `FolderDnD.kt` are intentionally NOT modified — the new semantics live entirely inside `FolderLayout` mutations and `folderFor`.
- Plugin version becomes `0.6.0` (Task 2).
- BasePlatformTestCase reuses the project: tests mutating `MegatronFilterState` restore it in a `finally` block (`setViewMode(ViewMode.TREE)`).
- Commit messages: conventional commits.

---

### Task 1: FolderLayout — typed rules, precedence, order-preserving serializer, exclusion-aware mutations

**Files:**
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/FolderLayout.kt` (full replacement below)
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/FolderLayoutTest.kt`

**Interfaces:**
- Consumes: `GlobPattern(pattern: String)` with `fun matches(relativePath: String, fileName: String): Boolean` (FilterConfig.kt, unchanged).
- Produces (Task 2 relies on these):
  - `data class FolderRule(val raw: String, val folder: String, val isExclusion: Boolean)` with `fun matches(relativePath: String): Boolean` and `val isExactPath: Boolean`.
  - `FolderLayout(folders: List<String> = emptyList(), assignments: List<FileAssignment> = emptyList(), rules: List<FolderRule> = emptyList())`.
  - `fun folderFor(relativePath: String): String?` — now implements the full precedence chain.
  - `fun patternFolderFor(relativePath: String): String?` — rules-only resolution (ignores explicit entries).
  - `val rules: List<FolderRule>`, `fun rulesIn(folder: String): List<FolderRule>`.
  - Everything else keeps its existing signature (`folders`, `filesIn` — explicit entries only, `childFolders`, `allFolders`, `hasFolder`, `withFolder`, `withAssignment`, `withUnassigned`, `withFolderRenamed`, `withFolderDeleted`, `serialize`, `assignedFilesLowercase` — kept this task, removed in Task 2, `parseFoldersFile`, `validateFolderName`).

- [ ] **Step 1: Update the tests**

In `src/test/kotlin/com/daverobins/projectfilesbrowser/FolderLayoutTest.kt`:

REPLACE the test `serialize writes sorted folders then sorted files with two-space indent` with:

```kotlin
    @Test
    fun `serialize preserves folder declaration order and sorts explicit files`() {
        val layout = parseFoldersFile("Platform/\n  win.cpp\nCore/\n  src/b.cpp\n  src/A.cpp\nEmpty/\n")
        assertEquals(
            "Platform/\n  win.cpp\nCore/\n  src/A.cpp\n  src/b.cpp\nEmpty/\n",
            layout.serialize(),
        )
    }
```

ADD these tests at the end of the class:

```kotlin
    @Test
    fun `pattern lines assign matching files`() {
        val layout = parseFoldersFile("Engine/\n  src/**\n")
        assertEquals("Engine", layout.folderFor("src/a.cpp"))
        assertEquals("Engine", layout.folderFor("src/deep/b.h"))
        assertNull(layout.folderFor("main.cpp"))
    }

    @Test
    fun `name glob matches file name and path glob matches relative path`() {
        val layout = parseFoldersFile("Tests/\n  *_test.cpp\nDocs/\n  docs/**\n")
        assertEquals("Tests", layout.folderFor("src/deep/foo_test.cpp"))
        assertEquals("Docs", layout.folderFor("docs/guide.md"))
        assertNull(layout.folderFor("src/foo.cpp"))
    }

    @Test
    fun `last matching pattern in file wins across folder blocks`() {
        val layout = parseFoldersFile("A/\n  src/**\nB/\n  *_test.cpp\n")
        assertEquals("A", layout.folderFor("src/main.cpp"))
        assertEquals("B", layout.folderFor("src/main_test.cpp"))
    }

    @Test
    fun `explicit entry beats any pattern`() {
        val layout = parseFoldersFile("A/\n  src/**\nPinned/\n  src/special.cpp\n")
        assertEquals("Pinned", layout.folderFor("src/special.cpp"))
        assertEquals("A", layout.folderFor("src/other.cpp"))
    }

    @Test
    fun `exclusion beats patterns and is global across blocks`() {
        val layout = parseFoldersFile("A/\n  src/**\n  !src/gen.cpp\nB/\n  **/*.h\n")
        assertNull(layout.folderFor("src/gen.cpp"))
        assertEquals("A", layout.folderFor("src/ok.cpp"))
        val crossBlock = parseFoldersFile("A/\n  !src/x.h\nB/\n  **/*.h\n")
        assertNull(crossBlock.folderFor("src/x.h"))
    }

    @Test
    fun `glob exclusions and case-insensitive rule matching work`() {
        val layout = parseFoldersFile("A/\n  SRC/**\n  !**/*_gen.cpp\n")
        assertEquals("A", layout.folderFor("src/Main.CPP"))
        assertNull(layout.folderFor("src/proto_gen.cpp"))
    }

    @Test
    fun `rule lines before any folder and empty exclusions are skipped`() {
        val layout = parseFoldersFile("*.cpp\n!x.cpp\nA/\n  !\n  src/**\n")
        assertEquals(1, layout.rules.size) // only src/** survives
        assertEquals("A", layout.folderFor("src/a.cpp"))
        assertNull(layout.folderFor("x.cpp")) // the pre-folder lines were skipped, not applied
    }

    @Test
    fun `serialize writes rules in declaration order before sorted explicit files`() {
        val text = "Engine/\n  src/**\n  !src/gen.cpp\n  zz.cpp\n  aa.cpp\n"
        assertEquals(
            "Engine/\n  src/**\n  !src/gen.cpp\n  aa.cpp\n  zz.cpp\n",
            parseFoldersFile(text).serialize(),
        )
    }

    @Test
    fun `rules round-trip through serialize and reparse`() {
        val original = parseFoldersFile("B/\n  **/*.h\nA/\n  src/**\n  !src/gen.cpp\n  pinned.cpp\n")
        val reparsed = parseFoldersFile(original.serialize())
        assertEquals(original.serialize(), reparsed.serialize())
        assertEquals(original.folderFor("src/x.cpp"), reparsed.folderFor("src/x.cpp"))
        assertEquals(original.folderFor("src/gen.cpp"), reparsed.folderFor("src/gen.cpp"))
        assertEquals(original.folderFor("y.h"), reparsed.folderFor("y.h"))
    }

    @Test
    fun `withAssignment removes exact-path exclusions but keeps glob exclusions`() {
        val layout = parseFoldersFile("A/\n  src/**\n  !src/gen.cpp\n  !**/*_skip.cpp\n")
            .withAssignment("SRC/gen.cpp", "A")
        assertEquals("A", layout.folderFor("src/gen.cpp"))
        assertEquals(1, layout.rules.count { it.isExclusion })
        assertNull(layout.folderFor("src/a_skip.cpp"))
    }

    @Test
    fun `withUnassigned on pattern-claimed file writes exclusion under claiming folder`() {
        val layout = parseFoldersFile("A/\n  src/**\n").withUnassigned("src/gen.cpp")
        assertNull(layout.folderFor("src/gen.cpp"))
        val exclusion = layout.rules.single { it.isExclusion }
        assertEquals("src/gen.cpp", exclusion.raw)
        assertEquals("A", exclusion.folder)
        assertEquals("A/\n  src/**\n  !src/gen.cpp\n", layout.serialize())
    }

    @Test
    fun `withUnassigned deletes explicit entry and excludes when a pattern would reclaim`() {
        val layout = parseFoldersFile("A/\n  src/**\nPinned/\n  src/x.cpp\n").withUnassigned("src/x.cpp")
        assertNull(layout.folderFor("src/x.cpp"))
        assertTrue(layout.rules.any { it.isExclusion && it.raw == "src/x.cpp" && it.folder == "A" })
    }

    @Test
    fun `withUnassigned without any pattern claim just deletes the explicit entry`() {
        val layout = parseFoldersFile("A/\n  x.cpp\n").withUnassigned("x.cpp")
        assertNull(layout.folderFor("x.cpp"))
        assertTrue(layout.rules.isEmpty())
    }

    @Test
    fun `rename and delete cascade to rules`() {
        val renamed = parseFoldersFile("Core/\n  src/**\nCore/Math/\n  !src/vec.h\n")
            .withFolderRenamed("Core", "Base")
        assertEquals(listOf("Base", "Base/Math"), renamed.folders)
        assertTrue(renamed.rules.all { it.folder.startsWith("Base") })
        assertEquals("Base", renamed.folderFor("src/a.cpp"))

        val deleted = parseFoldersFile("Core/\n  src/**\nOther/\n  o.cpp\n").withFolderDeleted("Core")
        assertTrue(deleted.rules.isEmpty())
        assertNull(deleted.folderFor("src/a.cpp"))
        assertEquals("Other", deleted.folderFor("o.cpp"))
    }

    @Test
    fun `phase-5 file without rules parses and serializes identically`() {
        val text = "Core/\n  src/a.cpp\nCore/Math/\n  v.h\nPlatform/\n"
        assertEquals(text, parseFoldersFile(text).serialize())
    }
```

Also fix the pre-existing test `duplicate assignment last wins` if it relied on sorted-folder serialization — it does not (it only checks `folderFor`/`filesIn`), leave it. The test `serialize then parse round-trips` also survives unchanged (it compares serialize-to-serialize).

Note: the existing test file imports `org.junit.Assert.assertTrue` already? If not, add the missing `import org.junit.Assert.assertTrue`.

- [ ] **Step 2: Run tests to verify the new ones fail**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.FolderLayoutTest"
```
Expected: COMPILE FAILURE (`rules`, `FolderRule` unresolved) or test failures on the replaced serialize test.

- [ ] **Step 3: Replace FolderLayout.kt**

Full new content of `src/main/kotlin/com/daverobins/projectfilesbrowser/FolderLayout.kt`:

```kotlin
package com.daverobins.projectfilesbrowser

/** A file explicitly assigned to a virtual folder: original-case relative path plus owning folder path. */
data class FileAssignment(val path: String, val folder: String)

/**
 * A pattern or exclusion line from megatron.folders. [raw] is the normalized
 * line text (without the leading '!' for exclusions); [folder] is the block it
 * was declared under. Exclusions are global in effect — [folder] only controls
 * where they serialize. Glob semantics are shared with megatron.filters.
 */
data class FolderRule(val raw: String, val folder: String, val isExclusion: Boolean) {
    private val glob = GlobPattern(raw)

    /** True when the rule names one exact path (no wildcards) — the only kind the UI writes. */
    val isExactPath: Boolean = '*' !in raw && '?' !in raw

    fun matches(relativePath: String): Boolean =
        glob.matches(relativePath, relativePath.substringAfterLast('/'))
}

/**
 * Immutable model of megatron.folders: the user's virtual-folder tree, explicit
 * file assignments, and pattern/exclusion rules. Folder paths use '/' separators
 * ("Core/Math"); all comparisons are case-insensitive with the first-declared
 * casing winning for display. One folder per file, resolved by precedence:
 * explicit entry (last duplicate wins) > any matching exclusion > matching
 * pattern latest in the file.
 */
class FolderLayout(
    folders: List<String> = emptyList(),
    assignments: List<FileAssignment> = emptyList(),
    rules: List<FolderRule> = emptyList(),
) {

    /** Canonical folder paths (parents auto-created), first-declared casing, declaration order. */
    val folders: List<String>

    /** Pattern and exclusion lines in declaration order (order = pattern precedence). */
    val rules: List<FolderRule>

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
        val normalizedRules = ArrayList<FolderRule>()
        for (rule in rules) {
            val raw = normalizeFilePath(rule.raw)
            if (raw.isEmpty()) continue
            val folder = canonicalize(rule.folder) ?: continue
            normalizedRules.add(FolderRule(raw, folder, rule.isExclusion))
        }
        val files = LinkedHashMap<String, FileAssignment>()
        for (assignment in assignments) {
            val path = normalizeFilePath(assignment.path)
            if (path.isEmpty()) continue
            val folder = canonicalize(assignment.folder) ?: continue
            files[path.lowercase()] = FileAssignment(path, folder)
        }
        this.folders = canonical.values.toList()
        this.rules = normalizedRules
        this.byFile = files
    }

    /** Full precedence: explicit entry, else rule resolution. */
    fun folderFor(relativePath: String): String? {
        val norm = normalizeFilePath(relativePath)
        byFile[norm.lowercase()]?.let { return it.folder }
        return patternFolderFor(norm)
    }

    /** Rule-only resolution (ignores explicit entries): exclusions veto, last matching pattern wins. */
    fun patternFolderFor(relativePath: String): String? {
        val norm = normalizeFilePath(relativePath)
        if (rules.any { it.isExclusion && it.matches(norm) }) return null
        return rules.lastOrNull { !it.isExclusion && it.matches(norm) }?.folder
    }

    /** Lowercase normalized relative paths of every EXPLICITLY assigned file. */
    fun assignedFilesLowercase(): Set<String> = byFile.keys

    /** Original-case paths of files EXPLICITLY assigned to [folder], sorted. */
    fun filesIn(folder: String): List<String> =
        byFile.values.filter { it.folder.equals(folder, ignoreCase = true) }
            .map { it.path }
            .sortedBy { it.lowercase() }

    /** Rules declared under [folder], in declaration order. */
    fun rulesIn(folder: String): List<FolderRule> =
        rules.filter { it.folder.equals(folder, ignoreCase = true) }

    /** Direct child folders of [parent] ("" = top level), sorted by display name. */
    fun childFolders(parent: String): List<String> =
        folders.filter { it.substringBeforeLast('/', "").equals(parent, ignoreCase = true) }
            .sortedBy { it.substringAfterLast('/').lowercase() }

    /** Every folder path, sorted — stable order for menus. */
    fun allFolders(): List<String> = folders.sortedBy { it.lowercase() }

    fun hasFolder(path: String): Boolean = folders.any { it.equals(path, ignoreCase = true) }

    fun withFolder(path: String): FolderLayout =
        FolderLayout(folders + path, byFile.values.toList(), rules)

    /** Assigns explicitly; also removes exact-path exclusions that pinned this file out. */
    fun withAssignment(relativePath: String, folder: String): FolderLayout {
        val norm = normalizeFilePath(relativePath)
        val keptRules = rules.filterNot {
            it.isExclusion && it.isExactPath && it.raw.equals(norm, ignoreCase = true)
        }
        return FolderLayout(folders, byFile.values.toList() + FileAssignment(relativePath, folder), keptRules)
    }

    /**
     * Sends the file to <Unassigned>: removes its explicit entry, and when a
     * pattern would still claim it, adds an exact-path exclusion under the
     * claiming folder.
     */
    fun withUnassigned(relativePath: String): FolderLayout {
        val norm = normalizeFilePath(relativePath)
        val key = norm.lowercase()
        val remaining = byFile.values.filterNot { it.path.lowercase() == key }
        val claiming = patternFolderFor(norm)
        val newRules =
            if (claiming != null) rules + FolderRule(norm, claiming, isExclusion = true)
            else rules
        return FolderLayout(folders, remaining, newRules)
    }

    fun withFolderRenamed(path: String, newName: String): FolderLayout {
        val parent = path.substringBeforeLast('/', "")
        val newPath = if (parent.isEmpty()) newName else "$parent/$newName"
        return FolderLayout(
            folders.map { remapped(it, path, newPath) },
            byFile.values.map { it.copy(folder = remapped(it.folder, path, newPath)) },
            rules.map { it.copy(folder = remapped(it.folder, path, newPath)) },
        )
    }

    fun withFolderDeleted(path: String): FolderLayout =
        FolderLayout(
            folders.filterNot { inSubtree(it, path) },
            byFile.values.filterNot { inSubtree(it.folder, path) },
            rules.filterNot { inSubtree(it.folder, path) },
        )

    /** Folders in declaration order; rules (declaration order) then sorted explicit files per block. */
    fun serialize(): String {
        val sb = StringBuilder()
        for (folder in folders) {
            sb.append(folder).append("/\n")
            for (rule in rulesIn(folder)) {
                sb.append("  ")
                if (rule.isExclusion) sb.append('!')
                sb.append(rule.raw).append('\n')
            }
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

/**
 * Parses megatron.folders text. Line kinds inside a folder block: `!rest` is an
 * exclusion, a line containing '*' or '?' is a pattern, anything else is an
 * explicit file path. Unparseable lines (including any rule/file line before
 * the first folder declaration, and bare `!`) are silently skipped.
 */
fun parseFoldersFile(text: String): FolderLayout {
    val folders = ArrayList<String>()
    val assignments = ArrayList<FileAssignment>()
    val rules = ArrayList<FolderRule>()
    var currentFolder: String? = null
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val normalized = line.replace('\\', '/')
        when {
            normalized.endsWith("/") -> {
                val cleaned = normalized.split('/').map { it.trim() }.filter { it.isNotEmpty() }
                    .joinToString("/")
                if (cleaned.isEmpty()) continue
                folders.add(cleaned)
                currentFolder = cleaned
            }
            normalized.startsWith("!") -> {
                val folder = currentFolder ?: continue
                val rest = normalized.substring(1).trim()
                if (rest.isEmpty()) continue
                rules.add(FolderRule(rest, folder, isExclusion = true))
            }
            '*' in normalized || '?' in normalized -> {
                val folder = currentFolder ?: continue
                rules.add(FolderRule(normalized, folder, isExclusion = false))
            }
            else -> {
                val folder = currentFolder ?: continue
                assignments.add(FileAssignment(normalized, folder))
            }
        }
    }
    return FolderLayout(folders, assignments, rules)
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

- [ ] **Step 4: Run the FolderLayout tests, then the full suite**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.FolderLayoutTest"
.\gradlew.bat test
```
Expected: BUILD SUCCESSFUL both times (all pre-existing suites still pass — `FolderLayoutStoreTest`'s serialization expectations are declaration-order-compatible).

- [ ] **Step 5: Commit**

```powershell
git add src/main/kotlin/com/daverobins/projectfilesbrowser/FolderLayout.kt src/test/kotlin/com/daverobins/projectfilesbrowser/FolderLayoutTest.kt
git commit -m "feat: glob patterns and exclusions in megatron.folders with precedence resolution"
```

---

### Task 2: Folder view resolves rules over the visible-file walk + version 0.6.0

**Files:**
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/FilteredTreeStructure.kt` (folderChildren)
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/VirtualFolderNode.kt` (full replacement below)
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/FolderLayout.kt` (delete `assignedFilesLowercase`)
- Modify: `build.gradle.kts` (version)
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/FilteredTreeStructureTest.kt`

**Interfaces:**
- Consumes: `FolderLayout.folderFor(relativePath)`, `childFolders(parent)` (Task 1).
- Produces: `VirtualFolderNode(project, parent: SimpleNode, folderPath: String, layout: FolderLayout, filesByFolder: Map<String, List<VirtualFile>>, engine: FilterEngine, rootPath: String)` — final consumer; `FolderActions`/`FolderDnD` compile unchanged (they only use `folderPath` and `FileNode` members).

- [ ] **Step 1: Write the failing tests**

Append to `FilteredTreeStructureTest.kt` (uses the existing `renderNode` helper and store/fixture patterns already in the file):

```kotlin
    fun testFolderViewPatternsAssignFilesAndShrinkUnassigned() {
        myFixture.addFileToProject("pw/megatron.folders", "Engine/\n  src/**\n")
        myFixture.addFileToProject("pw/src/a.cpp", "")
        myFixture.addFileToProject("pw/src/deep/b.h", "")
        myFixture.addFileToProject("pw/main.cpp", "")

        val state = MegatronFilterState.getInstance(project)
        state.setViewMode(ViewMode.FOLDERS)
        try {
            val rootDir = requireNotNull(myFixture.findFileInTempDir("pw"))
            val store = FolderLayoutStore(project, rootDir)
            val structure = FilteredTreeStructure(project, rootDir, FilterEngine(project, rootDir), store)
            assertEquals(
                """
                pw
                  Engine
                    a.cpp
                    b.h
                  <Unassigned>
                    main.cpp

                """.trimIndent(),
                renderNode(structure.rootElement as SimpleNode),
            )
        } finally {
            state.setViewMode(ViewMode.TREE)
        }
    }

    fun testFolderViewExclusionReturnsFileToUnassigned() {
        myFixture.addFileToProject("px/megatron.folders", "Engine/\n  src/**\n  !src/gen.cpp\n")
        myFixture.addFileToProject("px/src/a.cpp", "")
        myFixture.addFileToProject("px/src/gen.cpp", "")

        val state = MegatronFilterState.getInstance(project)
        state.setViewMode(ViewMode.FOLDERS)
        try {
            val rootDir = requireNotNull(myFixture.findFileInTempDir("px"))
            val store = FolderLayoutStore(project, rootDir)
            val structure = FilteredTreeStructure(project, rootDir, FilterEngine(project, rootDir), store)
            assertEquals(
                """
                px
                  Engine
                    a.cpp
                  <Unassigned>
                    src
                      gen.cpp

                """.trimIndent(),
                renderNode(structure.rootElement as SimpleNode),
            )
        } finally {
            state.setViewMode(ViewMode.TREE)
        }
    }

    fun testFolderViewEngineFiltersApplyToPatternMatches() {
        myFixture.addFileToProject("pf/megatron.filters", "Sources: *.cpp")
        myFixture.addFileToProject("pf/megatron.folders", "All/\n  **\n")
        myFixture.addFileToProject("pf/a.cpp", "")
        myFixture.addFileToProject("pf/notes.md", "hidden by Sources group")

        val state = MegatronFilterState.getInstance(project)
        state.setViewMode(ViewMode.FOLDERS)
        try {
            val rootDir = requireNotNull(myFixture.findFileInTempDir("pf"))
            val store = FolderLayoutStore(project, rootDir)
            val structure = FilteredTreeStructure(project, rootDir, FilterEngine(project, rootDir), store)
            assertEquals(
                """
                pf
                  All
                    a.cpp
                  <Unassigned>

                """.trimIndent(),
                renderNode(structure.rootElement as SimpleNode),
            )
        } finally {
            state.setViewMode(ViewMode.TREE)
        }
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.FilteredTreeStructureTest"
```
Expected: the three new tests FAIL (pattern lines currently parse as literal file paths that resolve to nothing, so Engine/All render empty and `<Unassigned>` keeps everything).

- [ ] **Step 3: Implement**

In `FilteredTreeStructure.kt`, replace the `folderChildren()` method of `FileNode` with:

```kotlin
    /** Folder view root: resolve rules over the visible-file walk, then group. */
    private fun folderChildren(): Array<SimpleNode> {
        val activeStore = store
        val layout = activeStore?.layout() ?: FolderLayout()
        val visible = ArrayList<VirtualFile>()
        collectVisibleFiles(file, visible)
        val filesByFolder = HashMap<String, MutableList<VirtualFile>>() // folder.lowercase() -> files
        val assigned = HashSet<String>() // lowercase relative paths
        for (candidate in visible) {
            val rel = relativePath(candidate)
            val folder = layout.folderFor(rel) ?: continue
            filesByFolder.getOrPut(folder.lowercase()) { ArrayList() }.add(candidate)
            assigned.add(rel.lowercase())
        }
        val folderNodes: List<SimpleNode> =
            if (activeStore == null) emptyList()
            else layout.childFolders("").map {
                VirtualFolderNode(project, this, it, layout, filesByFolder, engine, rootPath)
            }
        val unassigned = FileNode(
            project, this, file, engine, rootPath,
            displayName = UNASSIGNED_LABEL,
            excludedFiles = assigned,
        )
        return (folderNodes + unassigned).toTypedArray()
    }
```

Replace `VirtualFolderNode.kt` entirely with:

```kotlin
package com.daverobins.projectfilesbrowser

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.treeStructure.SimpleNode

/**
 * A user-defined virtual folder in folder view. Children are subfolders plus the
 * files that resolved to this folder during the root's visible-file walk.
 */
class VirtualFolderNode(
    private val project: Project,
    parent: SimpleNode,
    val folderPath: String,
    private val layout: FolderLayout,
    private val filesByFolder: Map<String, List<VirtualFile>>,
    private val engine: FilterEngine,
    private val rootPath: String,
) : SimpleNode(project, parent) {

    override fun getChildren(): Array<SimpleNode> {
        val subFolders: List<SimpleNode> = layout.childFolders(folderPath).map {
            VirtualFolderNode(project, this, it, layout, filesByFolder, engine, rootPath)
        }
        val files: List<SimpleNode> = (filesByFolder[folderPath.lowercase()] ?: emptyList())
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
    }
}
```

(The `resolveRelativePath` companion helper is deleted — resolution is now walk-based; missing-file entries and case differences are handled naturally because only real on-disk files are grouped, matched via lowercase relative paths.)

In `FolderLayout.kt`, DELETE the now-unused method:

```kotlin
    /** Lowercase normalized relative paths of every EXPLICITLY assigned file. */
    fun assignedFilesLowercase(): Set<String> = byFile.keys
```

and remove the one usage-check: `FolderLayoutTest` has one assertion using it (`backslashes normalize and lookups are case-insensitive` asserts `"src/engine.cpp" in layout.assignedFilesLowercase()`). Replace that assertion with:

```kotlin
        assertEquals("Core", layout.folderFor("src\\engine.cpp"))
```

In `build.gradle.kts`, change `version = "0.5.0"` to `version = "0.6.0"`.

- [ ] **Step 4: Run the structure tests, then the full suite**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.FilteredTreeStructureTest"
.\gradlew.bat test
```
Expected: BUILD SUCCESSFUL both times. All five pre-existing folder-view tests must still pass unchanged (explicit entries resolve identically under the walk-based grouping — including the case-insensitive one, since matching is by lowercase relative path).

- [ ] **Step 5: Commit**

```powershell
git add src/main/kotlin/com/daverobins/projectfilesbrowser/FilteredTreeStructure.kt src/main/kotlin/com/daverobins/projectfilesbrowser/VirtualFolderNode.kt src/main/kotlin/com/daverobins/projectfilesbrowser/FolderLayout.kt src/test/kotlin/com/daverobins/projectfilesbrowser/FilteredTreeStructureTest.kt build.gradle.kts
git commit -m "feat: folder view resolves patterns over visible files; version 0.6.0"
```

---

## Intentionally unchanged (verify at final review, do not "fix")

- `FolderActions.kt` / `FolderDnD.kt`: menu visibility (`folderFor != null`) now covers pattern-assigned files automatically; `withAssignment`/`withUnassigned` carry the exclusion semantics internally. Zero UI-file changes needed.
- `FolderLayoutStore`, `VfsChangeWatcher`, toggles, plugin.xml.

## Sandbox Checklist (post-implementation, human verification)

- Hand-edit megatron.folders: add `src/**` under a folder → matching files move in live; create a new file under src/ → it appears in the folder.
- Drag a pattern-matched file to `<Unassigned>` → a `!path` line appears; drag it back onto the folder → exclusion line removed, explicit entry written.
- Last-pattern-wins: two overlapping patterns in different folders behave per file order.
- Filters/CMake gate still constrain what patterns pick up; rename/delete folders with patterns behaves sanely.
