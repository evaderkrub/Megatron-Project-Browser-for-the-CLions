# Quick Filter Box (Phase 9) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A SearchTextField in the tool window header applying a final wildcard filter (ANDed after groups and the CMake gate) to displayed files, live with a 300 ms debounce.

**Architecture:** A pure `QuickFilter` (text → GlobPattern with a contains-wrapping rule for bare text) lives behind a `@Volatile` field in `FilterEngine`, ANDed at the end of `isFileVisible` so all three view modes narrow identically. The panel wires a `SearchTextField` document listener through a `SingleAlarm` debounce to `setQuickFilter` + `invalidateAsync`.

**Tech Stack:** Kotlin 2.3.0, IntelliJ Platform Gradle Plugin 2.18.1, CLion 2026.1.1, JUnit 4.

## Global Constraints

- Before any Gradle call in a fresh PowerShell: `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"`. Test command: `.\gradlew.bat test`.
- Semantics: blank/whitespace → null (no-op); backslashes normalized to `/`; text with NO `*`/`?`/`/` (after normalization) → `*text*` name-contains; otherwise one glob with the existing `GlobPattern` rules (`/` → relative-path match, else file name; case-insensitive).
- The quick filter is the LAST AND term of `isFileVisible`, right after group visibility, before the gate (order is semantically irrelevant, cheapest-first). `isGroupVisible` (the watcher's relevance path) is UNTOUCHED.
- Transient: never persisted, no state component changes.
- Debounce 300 ms (`QUICK_FILTER_DEBOUNCE_MS` const); rebuild via the existing `structureModel.invalidateAsync()`.
- Verified platform APIs (javap, CLion 2026.1.1 — trust these): `SearchTextField(boolean)` ctor, `addDocumentListener(javax.swing.event.DocumentListener)`, `getText()`, `getTextEditor(): JBTextField` (whose `emptyText.text` works like the tree's). Use a plain `javax.swing.event.DocumentListener` (three overrides), NOT DocumentAdapter.
- Ghost text exactly `Filter results…`.
- Plugin version becomes `0.9.0` (Task 2).
- Tests mutating `MegatronFilterState` restore it in `finally`; engine instances are test-local so `setQuickFilter` needs no restore.
- Commit messages: conventional commits (no double quotes in -m strings when committing via PowerShell; prefer the Bash tool for commits).

---

### Task 1: QuickFilter + engine integration

**Files:**
- Create: `src/main/kotlin/com/daverobins/projectfilesbrowser/QuickFilter.kt`
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/FilterEngine.kt`
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/QuickFilterTest.kt` (new, pure JUnit4)
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/FilteredTreeStructureTest.kt` (two structure tests)

**Interfaces:**
- Consumes: `GlobPattern(pattern)` with `matches(relativePath, fileName)` (FilterConfig.kt, unchanged).
- Produces (Task 2 relies on): `FilterEngine.setQuickFilter(text: String)`; `QuickFilter.parse(text: String): QuickFilter?` with `matches(relativePath: String, fileName: String): Boolean`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/daverobins/projectfilesbrowser/QuickFilterTest.kt`:

```kotlin
package com.daverobins.projectfilesbrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickFilterTest {

    @Test
    fun `blank and whitespace parse to null`() {
        assertNull(QuickFilter.parse(""))
        assertNull(QuickFilter.parse("   "))
        assertNotNull(QuickFilter.parse("x"))
    }

    @Test
    fun `bare text is a case-insensitive name-contains match`() {
        val filter = QuickFilter.parse("wow")!!
        assertTrue(filter.matches("src/wowz.cpp", "wowz.cpp"))
        assertTrue(filter.matches("A/B/MyWOW.txt", "MyWOW.txt"))
        assertFalse(filter.matches("src/other.cpp", "other.cpp"))
    }

    @Test
    fun `wildcard text stays a name glob`() {
        val filter = QuickFilter.parse("*.h")!!
        assertTrue(filter.matches("src/a.h", "a.h"))
        assertFalse(filter.matches("src/a.hpp", "a.hpp"))
        val question = QuickFilter.parse("?ow")!!
        assertTrue(question.matches("x/cow", "cow"))
        assertFalse(question.matches("x/know", "know"))
    }

    @Test
    fun `text with a slash matches the relative path`() {
        val filter = QuickFilter.parse("src/**")!!
        assertTrue(filter.matches("src/deep/a.cpp", "a.cpp"))
        assertFalse(filter.matches("other/a.cpp", "a.cpp"))
    }

    @Test
    fun `backslashes normalize to slashes`() {
        val filter = QuickFilter.parse("src\\**")!!
        assertTrue(filter.matches("src/deep/a.cpp", "a.cpp"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        val filter = QuickFilter.parse("  wow  ")!!
        assertTrue(filter.matches("wowz.cpp", "wowz.cpp"))
    }
}
```

Append to `FilteredTreeStructureTest.kt`:

```kotlin
    fun testQuickFilterNarrowsTreeAndPrunesEmptyDirs() {
        myFixture.addFileToProject("qf/src/wowz.cpp", "")
        myFixture.addFileToProject("qf/src/other.cpp", "")
        myFixture.addFileToProject("qf/lib/misc.cpp", "")

        val rootDir = requireNotNull(myFixture.findFileInTempDir("qf"))
        val engine = FilterEngine(project, rootDir)
        engine.setQuickFilter("wow")
        val structure = FilteredTreeStructure(project, rootDir, engine)
        assertEquals(
            """
            qf
              src
                wowz.cpp

            """.trimIndent(),
            renderNode(structure.rootElement as SimpleNode),
        )

        engine.setQuickFilter("")
        assertEquals(
            """
            qf
              lib
                misc.cpp
              src
                other.cpp
                wowz.cpp

            """.trimIndent(),
            renderNode(FilteredTreeStructure(project, rootDir, engine).rootElement as SimpleNode),
        )
    }

    fun testQuickFilterAppliesInsideFolderView() {
        myFixture.addFileToProject("qv/megatron/default.folders", "Code/\n  src/**\n")
        myFixture.addFileToProject("qv/src/wowz.cpp", "")
        myFixture.addFileToProject("qv/src/other.cpp", "")

        val state = MegatronFilterState.getInstance(project)
        state.setViewMode(ViewMode.FOLDERS)
        try {
            val rootDir = requireNotNull(myFixture.findFileInTempDir("qv"))
            val store = FolderLayoutStore(project, rootDir)
            val engine = FilterEngine(project, rootDir)
            engine.setQuickFilter("wow")
            val structure = FilteredTreeStructure(project, rootDir, engine, store)
            assertEquals(
                """
                qv
                  Code
                    wowz.cpp
                  <Unassigned>

                """.trimIndent(),
                renderNode(structure.rootElement as SimpleNode),
            )
        } finally {
            state.setViewMode(ViewMode.TREE)
        }
    }
```

- [ ] **Step 2: Run to verify failure**

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.QuickFilterTest"
```
Expected: COMPILE FAILURE (`QuickFilter` unresolved).

- [ ] **Step 3: Implement**

Create `src/main/kotlin/com/daverobins/projectfilesbrowser/QuickFilter.kt`:

```kotlin
package com.daverobins.projectfilesbrowser

/**
 * The toolbar quick filter: a final AND-term on file visibility. Bare text
 * (no wildcards, no '/') means name-contains; anything else is one glob with
 * the megatron.filters rules. Blank input parses to null (filter off).
 */
class QuickFilter private constructor(private val glob: GlobPattern) {

    fun matches(relativePath: String, fileName: String): Boolean =
        glob.matches(relativePath, fileName)

    companion object {
        fun parse(text: String): QuickFilter? {
            val normalized = text.trim().replace('\\', '/')
            if (normalized.isEmpty()) return null
            val pattern =
                if ('*' in normalized || '?' in normalized || '/' in normalized) normalized
                else "*$normalized*"
            return QuickFilter(GlobPattern(pattern))
        }
    }
}
```

In `FilterEngine.kt`: add the field and setter (below the cache fields):

```kotlin
    @Volatile
    private var quickFilter: QuickFilter? = null

    /** Sets the transient toolbar quick filter; blank text clears it. */
    fun setQuickFilter(text: String) {
        quickFilter = QuickFilter.parse(text)
    }
```

and rework `isFileVisible` to reuse the relative path and AND the filter in:

```kotlin
    /** Full visibility: group filtering AND the quick filter AND (when enabled and active) the project-model gate. */
    fun isFileVisible(file: VirtualFile): Boolean {
        val relativePath = relativePath(file)
        if (!isGroupVisible(relativePath, file.name)) return false
        if (quickFilter?.matches(relativePath, file.name) == false) return false
        val activeGate = gate ?: return true
        if (!MegatronFilterState.getInstance(project).isCmakeGateEnabled()) return true
        if (!activeGate.isActive()) return true
        return activeGate.isInModel(file)
    }
```

- [ ] **Step 4: Run QuickFilterTest + FilteredTreeStructureTest, then the full suite** — all BUILD SUCCESSFUL.

- [ ] **Step 5: Commit** (use bash)

```bash
git add src/main/kotlin/com/daverobins/projectfilesbrowser/QuickFilter.kt src/main/kotlin/com/daverobins/projectfilesbrowser/FilterEngine.kt src/test/kotlin/com/daverobins/projectfilesbrowser/QuickFilterTest.kt src/test/kotlin/com/daverobins/projectfilesbrowser/FilteredTreeStructureTest.kt
git commit -m "feat: quick filter as a final AND-term in file visibility"
```

---

### Task 2: SearchTextField in the header + version 0.9.0

**Files:**
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/ProjectFilesPanel.kt`
- Modify: `build.gradle.kts` (version)

**Interfaces:**
- Consumes: `FilterEngine.setQuickFilter(text)` (Task 1); verified APIs: `SearchTextField(false)`, `addDocumentListener(javax.swing.event.DocumentListener)`, `getText()`, `getTextEditor()`; `SingleAlarm(Runnable, Int, Disposable)` (already used by the watcher).
- Produces: final consumer.

- [ ] **Step 1: Wire the panel**

In `ProjectFilesPanel.kt`:

Add imports: `com.intellij.ui.SearchTextField`, `com.intellij.util.SingleAlarm`, `java.awt.BorderLayout` (already present since phase 7), `javax.swing.JPanel` (already present), `javax.swing.event.DocumentEvent`, `javax.swing.event.DocumentListener`.

Add a field after `banner`:

```kotlin
    private val quickFilterField = SearchTextField(false)
```

In `init`, REPLACE `setToolbar(toolbar.component)` with a header wrapper:

```kotlin
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
```

Add a companion (or extend an existing one) on the panel:

```kotlin
    companion object {
        private const val QUICK_FILTER_DEBOUNCE_MS = 300
    }
```

- [ ] **Step 2: Bump the version** — `build.gradle.kts`: `version = "0.8.3"` → `version = "0.9.0"`.

- [ ] **Step 3: Run the full suite** — BUILD SUCCESSFUL.

- [ ] **Step 4: Commit** (use bash)

```bash
git add src/main/kotlin/com/daverobins/projectfilesbrowser/ProjectFilesPanel.kt build.gradle.kts
git commit -m "feat: quick-filter search box in the tool window header; version 0.9.0"
```

---

## Sandbox Checklist (post-implementation, human verification)

- Type `wow` → tree narrows to matching names within ~a third of a second; ✕ clears fully.
- `*.h`, `src/**` behave as globs; bare text behaves as contains.
- Works identically in tree, flat, and folder views (folder assignments unchanged underneath).
- Empty on IDE restart.
