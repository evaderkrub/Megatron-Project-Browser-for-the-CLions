# Auto-Refresh (Phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Project Files tree rebuilds itself ~0.5 s after relevant file-system changes settle, with no churn from builds or VCS internals.

**Architecture:** A new `VfsChangeWatcher` subscribes a `BulkFileListener` to the project message bus (`VFS_CHANGES` topic), string-checks each event against a pure relevance function (reusing `FileFilter`), and coalesces relevant events through a 500 ms `SingleAlarm` debounce that fires a callback. `ProjectFilesPanel` wires that callback to `structureModel.invalidateAsync()`.

**Tech Stack:** Existing project toolchain — Kotlin 2.3.0, Gradle 9.6.1 wrapper, IntelliJ Platform Gradle Plugin 2.18.1, JDK 21, target CLion 2026.1.1.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-15-auto-refresh-design.md` (Phase 1 of `2026-07-15-clion-project-files-browser-design.md`)
- Package: `com.daverobins.projectfilesbrowser`
- Debounce: 500 ms, coalescing (each relevant event restarts the timer)
- Relevance rules (spec "Behavior"): path under root (or root itself); intermediate segments must pass `FileFilter.includeDirectory`; directory leaf must pass `includeDirectory`, file leaf must pass `includeFile`; renames/moves relevant if OLD or NEW path qualifies; content-change events never relevant
- Manual refresh button unchanged; no settings/toggle
- Plugin version bumps to `0.1.1` in this phase
- Shell is Windows PowerShell 5.1 — no `&&`; Gradle via `.\gradlew.bat`; if a Java-version error appears, first run `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"`
- Commit at the end of every task; work on branch `feature/phase1-auto-refresh`

---

### Task 1: VfsChangeWatcher (TDD on the pure relevance logic)

**Files:**
- Create: `src/main/kotlin/com/daverobins/projectfilesbrowser/VfsChangeWatcher.kt`
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/VfsChangeWatcherRelevanceTest.kt`

**Interfaces:**
- Consumes: `FileFilter.includeFile(name: String): Boolean`, `FileFilter.includeDirectory(name: String): Boolean` (existing).
- Produces (Task 2 relies on): `class VfsChangeWatcher(project: Project, rootDir: VirtualFile, parentDisposable: Disposable, onChange: () -> Unit)` — subscribing happens in its `init`; no further calls needed. Companion functions `isRelevantPath(rootPath: String, path: String, isDirectory: Boolean): Boolean` and `isRelevantEitherPath(rootPath: String, oldPath: String?, newPath: String, isDirectory: Boolean): Boolean`.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/daverobins/projectfilesbrowser/VfsChangeWatcherRelevanceTest.kt`:

```kotlin
package com.daverobins.projectfilesbrowser

import com.daverobins.projectfilesbrowser.VfsChangeWatcher.Companion.isRelevantEitherPath
import com.daverobins.projectfilesbrowser.VfsChangeWatcher.Companion.isRelevantPath
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VfsChangeWatcherRelevanceTest {

    private val root = "/proj"

    @Test
    fun rootItselfIsRelevant() {
        assertTrue(isRelevantPath(root, "/proj", isDirectory = true))
    }

    @Test
    fun pathOutsideRootIsIrrelevant() {
        assertFalse(isRelevantPath(root, "/elsewhere/main.cpp", isDirectory = false))
    }

    @Test
    fun siblingWithRootAsPrefixIsIrrelevant() {
        // "/proj2" starts with "/proj" as a string but is NOT under it
        assertFalse(isRelevantPath(root, "/proj2/main.cpp", isDirectory = false))
    }

    @Test
    fun matchingFileUnderRootIsRelevant() {
        assertTrue(isRelevantPath(root, "/proj/src/main.cpp", isDirectory = false))
    }

    @Test
    fun nonMatchingFileIsIrrelevant() {
        assertFalse(isRelevantPath(root, "/proj/readme.md", isDirectory = false))
    }

    @Test
    fun fileInsideExcludedDirIsIrrelevant() {
        assertFalse(isRelevantPath(root, "/proj/cmake-build-debug/x.cpp", isDirectory = false))
        assertFalse(isRelevantPath(root, "/proj/a/.git/objects/ab12cd", isDirectory = false))
    }

    @Test
    fun includedDirectoryLeafIsRelevant() {
        assertTrue(isRelevantPath(root, "/proj/src/newmodule", isDirectory = true))
    }

    @Test
    fun excludedDirectoryLeafIsIrrelevant() {
        assertFalse(isRelevantPath(root, "/proj/cmake-build-debug", isDirectory = true))
        assertFalse(isRelevantPath(root, "/proj/.git", isDirectory = true))
    }

    @Test
    fun renameIsRelevantWhenOnlyOldPathMatches() {
        // main.cpp renamed to notes.txt: old path qualifies -> relevant
        assertTrue(isRelevantEitherPath(root, "/proj/src/main.cpp", "/proj/src/notes.txt", isDirectory = false))
    }

    @Test
    fun renameIsRelevantWhenOnlyNewPathMatches() {
        // notes.txt renamed to main.cpp: new path qualifies -> relevant
        assertTrue(isRelevantEitherPath(root, "/proj/src/notes.txt", "/proj/src/main.cpp", isDirectory = false))
    }

    @Test
    fun renameIsIrrelevantWhenNeitherPathMatches() {
        assertFalse(isRelevantEitherPath(root, "/proj/src/a.txt", "/proj/src/b.md", isDirectory = false))
    }

    @Test
    fun renameWithNullOldPathFallsBackToNewPathOnly() {
        assertTrue(isRelevantEitherPath(root, null, "/proj/src/main.cpp", isDirectory = false))
        assertFalse(isRelevantEitherPath(root, null, "/proj/src/notes.txt", isDirectory = false))
    }

    @Test
    fun moveOutOfRootIsRelevantViaOldPath() {
        assertTrue(isRelevantEitherPath(root, "/proj/src/main.cpp", "/elsewhere/main.cpp", isDirectory = false))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.VfsChangeWatcherRelevanceTest" --console=plain
```

Expected: FAILED — compilation error, `VfsChangeWatcher` unresolved.

- [ ] **Step 3: Write the implementation**

`src/main/kotlin/com/daverobins/projectfilesbrowser/VfsChangeWatcher.kt`:

```kotlin
package com.daverobins.projectfilesbrowser

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.SingleAlarm

/**
 * Watches VFS changes under [rootDir] and, after a debounce, invokes [onChange].
 * Irrelevant events (outside the root, inside excluded dirs, non-matching files,
 * content-only changes) never schedule a refresh, so build churn in
 * cmake-build-* is ignored entirely.
 */
class VfsChangeWatcher(
    project: Project,
    rootDir: VirtualFile,
    parentDisposable: Disposable,
    onChange: () -> Unit,
) {
    private val rootPath = rootDir.path
    private val alarm = SingleAlarm(Runnable { onChange() }, DEBOUNCE_MS, parentDisposable)

    init {
        project.messageBus.connect(parentDisposable)
            .subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { isRelevant(it) }) alarm.cancelAndRequest()
                }
            })
    }

    private fun isRelevant(event: VFileEvent): Boolean = when (event) {
        is VFileContentChangeEvent -> false
        is VFileCreateEvent -> isRelevantPath(rootPath, event.path, event.isDirectory)
        is VFileDeleteEvent -> isRelevantPath(rootPath, event.path, event.file.isDirectory)
        is VFileCopyEvent -> isRelevantPath(rootPath, event.path, event.file.isDirectory)
        is VFileMoveEvent ->
            isRelevantEitherPath(rootPath, event.oldPath, event.newPath, event.file.isDirectory)
        is VFilePropertyChangeEvent ->
            event.propertyName == VirtualFile.PROP_NAME &&
                isRelevantEitherPath(
                    rootPath,
                    event.file.parent?.path?.let { "$it/${event.oldValue}" },
                    event.path,
                    event.file.isDirectory,
                )
        else -> true // unknown event type: rebuild conservatively rather than miss a change
    }

    companion object {
        const val DEBOUNCE_MS = 500

        fun isRelevantPath(rootPath: String, path: String, isDirectory: Boolean): Boolean {
            if (path == rootPath) return true
            val prefix = "$rootPath/"
            if (!path.startsWith(prefix)) return false
            val segments = path.removePrefix(prefix).split('/')
            for (i in 0 until segments.size - 1) {
                if (!FileFilter.includeDirectory(segments[i])) return false
            }
            val leaf = segments.last()
            return if (isDirectory) FileFilter.includeDirectory(leaf) else FileFilter.includeFile(leaf)
        }

        fun isRelevantEitherPath(
            rootPath: String,
            oldPath: String?,
            newPath: String,
            isDirectory: Boolean,
        ): Boolean =
            isRelevantPath(rootPath, newPath, isDirectory) ||
                (oldPath != null && isRelevantPath(rootPath, oldPath, isDirectory))
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.VfsChangeWatcherRelevanceTest" --console=plain
```

Expected: `BUILD SUCCESSFUL`, all 13 tests pass.

- [ ] **Step 5: Run ALL tests**

```powershell
.\gradlew.bat test --console=plain
```

Expected: `BUILD SUCCESSFUL` — 23 tests green (8 FileFilterTest + 2 FilteredTreeStructureTest + 13 new).

- [ ] **Step 6: Commit**

```powershell
git add src
git commit -m "feat: VfsChangeWatcher with debounced, filtered VFS change detection"
```

---

### Task 2: Wire watcher into the panel + version bump

**Files:**
- Modify: `src/main/kotlin/com/daverobins/projectfilesbrowser/ProjectFilesPanel.kt` (init block)
- Modify: `build.gradle.kts` (version line)

**Interfaces:**
- Consumes: `VfsChangeWatcher(project, rootDir, parentDisposable, onChange)` from Task 1; existing `structureModel` field and `rootDir` constructor val in `ProjectFilesPanel`.

- [ ] **Step 1: Instantiate the watcher in `ProjectFilesPanel`**

In `ProjectFilesPanel.kt`, at the END of the `init` block (after `setContent(...)`), add:

```kotlin
        VfsChangeWatcher(project, rootDir, parentDisposable) {
            structureModel.invalidateAsync()
        }
```

Note: `parentDisposable` is a constructor parameter used inside `init` today; if the compiler reports it unresolved at your insertion point, promote it to `private val parentDisposable: Disposable` in the constructor signature. `rootDir` is already a `private val`.

- [ ] **Step 2: Bump plugin version**

In `build.gradle.kts`: `version = "0.1.0"` → `version = "0.1.1"`.

- [ ] **Step 3: Build + full suite**

```powershell
.\gradlew.bat build --console=plain
```

Expected: `BUILD SUCCESSFUL`, 23/23 tests green.

- [ ] **Step 4: Commit**

```powershell
git add src build.gradle.kts
git commit -m "feat: auto-refresh tree on relevant VFS changes (debounced 500ms)"
```

---

### Task 3: Sandbox verification (human checkpoint)

**Files:** none (unless fixes are needed).

- [ ] **Step 1: Launch sandbox**

```powershell
.\gradlew.bat runIde --console=plain
```

Run in background; it blocks until the IDE closes.

- [ ] **Step 2: Manual verification checklist (user drives)**

With a CMake/C++ project open in the sandbox and the Project Files tool window visible:

1. Create a new `.cpp` file in the IDE's Project view → appears in Project Files within ~1 s, WITHOUT pressing refresh.
2. Delete it → disappears within ~1 s.
3. Rename a `.cpp` to `.txt` → disappears; rename back → reappears.
4. Create a file in Windows Explorer (outside the IDE) → appears once the IDE notices the external change (focus switch back to the IDE forces this; that VFS-level delay is platform behavior, not our debounce).
5. Run a CMake build → tree does NOT flicker/churn while `cmake-build-*` is written to.
6. The manual refresh button still works.

- [ ] **Step 3: Fix anything found, re-verify, commit fixes**

Reproduce → fix → re-run `runIde` → re-check → commit with a `fix:` message naming the defect.

- [ ] **Step 4: Merge and tag (after user confirms checklist passes)**

Merge `feature/phase1-auto-refresh` to `master` per the finishing-a-development-branch skill, then:

```powershell
git tag v0.1.1
```
