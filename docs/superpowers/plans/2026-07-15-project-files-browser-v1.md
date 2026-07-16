# Project Files Browser V1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A CLion plugin adding a "Project Files" tool window showing a filtered directory tree (C/C++ sources, headers, CMake files; build/VCS noise pruned), with double-click-to-open and a manual refresh button.

**Architecture:** Custom tool window built on the modern IntelliJ Platform tree stack: `SimpleTreeStructure` (subclass of `AbstractTreeStructure`) → `StructureTreeModel` → `AsyncTreeModel` → `Tree`. Filtering logic lives in a pure-Kotlin `FileFilter` object; the tree structure walks the VFS through it. Scanning happens on background threads courtesy of the tree models.

**Tech Stack:** Kotlin 2.3.0, Gradle 9.6.1 (wrapper), IntelliJ Platform Gradle Plugin 2.18.1, JDK 21 (Temurin), target CLion 2026.1.1. (Plan originally pinned Gradle 8.13 + IJPGP 2.2.1; IJPGP 2.2.1 predates the 2026.1 split-mode dist layout and its `runIde` fails with "Could not find or load main class com.intellij.idea.Main" — fixed by IJPGP 2.18.1, which in turn requires Gradle 9. Verified 2026-07-15.) (Plan originally pinned Kotlin 2.1.0; CLion 2026.1.1 platform jars carry Kotlin 2.3.0 metadata, which a 2.1.0 compiler cannot read — verified by compile failure on 2026-07-15. 2.3.0 is forced.)

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-15-clion-project-files-browser-design.md`
- Package for all code: `com.daverobins.projectfilesbrowser`
- Plugin id: `com.daverobins.projectfilesbrowser`; plugin name: `Project Files Browser`; version `0.1.0`
- Target: CLion 2026.1.1; `sinceBuild = "261"`; depend ONLY on `com.intellij.modules.platform` (no C++/CMake APIs)
- Extension allow-list (hardcoded, lowercase compare): `c cc cpp cxx h hh hpp hxx inl cmake` + exact filename `CMakeLists.txt`
- Excluded directory names (lowercase compare): `.git .idea build out .vs`; excluded prefix: `cmake-build-`
- Directories left empty after filtering are hidden
- Sorting: directories first, then files, alphabetical case-insensitive
- V1 is read-only browsing: open-in-editor is the ONLY action; manual refresh only (no VFS listener)
- Shell is Windows PowerShell 5.1 — no `&&`; all Gradle calls use `.\gradlew.bat` (except initial wrapper generation)
- Gradle commands that download the IDE the first time need a long timeout (600000 ms) — CLion is a ~1.5 GB download
- Commit at the end of every task

---

### Task 1: Toolchain setup (JDK 21 + Gradle)

The machine currently has only JRE 1.8. IntelliJ Platform Gradle Plugin 2.x requires Gradle to run on JDK 17+, and the 2026.1 platform requires JDK 21 for compilation. Nothing in this task is committed (it changes machine state, not the repo).

**Files:** none.

**Interfaces:**
- Produces: `JAVA_HOME` user env var pointing at a Temurin JDK 21; `gradle` on PATH (used once, in Task 2, to generate the wrapper).

- [ ] **Step 1: Install Temurin JDK 21**

```powershell
winget install --id EclipseAdoptium.Temurin.21.JDK -e --accept-source-agreements --accept-package-agreements
```

Expected: exit 0 ("Successfully installed"). If winget reports it is already installed, that's fine — continue.

- [ ] **Step 2: Set JAVA_HOME (user env var + current session)**

```powershell
$jdk = (Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-21*" | Select-Object -First 1).FullName
[Environment]::SetEnvironmentVariable("JAVA_HOME", $jdk, "User")
$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;" + $env:Path
& "$env:JAVA_HOME\bin\java" -version
```

Expected: version line starting `openjdk version "21`.

Note for later tasks: the user-level env var means NEW shell processes inherit `JAVA_HOME`, but if a Gradle command in a later task fails with a Java-version error, re-run the two `$env:` lines above first.

- [ ] **Step 3: Install Gradle (only used to generate the wrapper)**

```powershell
winget install --id Gradle.Gradle -e --accept-source-agreements --accept-package-agreements
$env:Path = [Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [Environment]::GetEnvironmentVariable("Path","User") + ";" + "$env:JAVA_HOME\bin"
gradle -v
```

Expected: Gradle version banner (any 8.x or 9.x version — it only generates the wrapper).

---

### Task 2: Gradle project scaffold

A buildable, empty plugin project: wrapper pinned to 8.13, build files, `plugin.xml` with metadata but no extensions yet (the tool window is registered in Task 5).

**Files:**
- Create: `gradle/wrapper/*`, `gradlew`, `gradlew.bat` (generated)
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `.gitignore`
- Create: `src/main/resources/META-INF/plugin.xml`

**Interfaces:**
- Produces: a project where `.\gradlew.bat build` succeeds; `plugin.xml` that Task 5 adds a `<toolWindow>` extension to; source roots `src/main/kotlin` and `src/test/kotlin` for Tasks 3–5.

- [ ] **Step 1: Generate the Gradle wrapper pinned to 8.13**

Run in the project root (`C:\~prj\Dropbox\vibeProjects\clionprojectview`), BEFORE writing any build files so the installed Gradle doesn't try to evaluate them:

```powershell
gradle wrapper --gradle-version 8.13
```

Expected: BUILD SUCCESSFUL; `gradlew.bat` and `gradle/wrapper/` now exist.

- [ ] **Step 2: Write `.gitignore`**

```gitignore
.gradle/
build/
.intellijPlatform/
.idea/
*.iml
```

- [ ] **Step 3: Write `settings.gradle.kts`**

```kotlin
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "clionprojectview"
```

- [ ] **Step 4: Write `gradle.properties`**

```properties
kotlin.stdlib.default.dependency=false
org.gradle.jvmargs=-Xmx2g
```

- [ ] **Step 5: Write `build.gradle.kts`**

```kotlin
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    kotlin("jvm") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.daverobins"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        clion("2026.1.1")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
        }
    }
}
```

Troubleshooting (only if the build fails): if plugin version `2.2.1` cannot resolve the CLion 2026.1.1 / build-261 platform, bump `org.jetbrains.intellij.platform` to the latest 2.x listed at https://plugins.gradle.org/plugin/org.jetbrains.intellij.platform — nothing else in this file changes.

- [ ] **Step 6: Write `src/main/resources/META-INF/plugin.xml`**

```xml
<idea-plugin>
    <id>com.daverobins.projectfilesbrowser</id>
    <name>Project Files Browser</name>
    <vendor email="daverobins@intrepidcs.com">Dave Robins</vendor>
    <description><![CDATA[
        A filtered project files browser for CLion. Shows a curated tree of the
        files that matter (C/C++ sources, headers, CMake files) with build
        output and VCS noise pruned away.
    ]]></description>
    <depends>com.intellij.modules.platform</depends>
</idea-plugin>
```

- [ ] **Step 7: Verify the project builds**

```powershell
.\gradlew.bat build --console=plain
```

Use a 600000 ms timeout — the first run downloads CLion 2026.1.1 (~1.5 GB) plus Gradle 8.13.
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```powershell
git add -A
git commit -m "feat: gradle scaffold for CLion plugin targeting 2026.1"
```

---

### Task 3: FileFilter (pure filtering logic, TDD)

**Files:**
- Create: `src/main/kotlin/com/daverobins/projectfilesbrowser/FileFilter.kt`
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/FileFilterTest.kt`

**Interfaces:**
- Produces: `object FileFilter` with `fun includeFile(name: String): Boolean` and `fun includeDirectory(name: String): Boolean` (both take a bare file/dir NAME, not a path). Task 4 consumes both.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/daverobins/projectfilesbrowser/FileFilterTest.kt`:

```kotlin
package com.daverobins.projectfilesbrowser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileFilterTest {

    @Test
    fun includesSourceAndHeaderExtensions() {
        for (name in listOf(
            "main.c", "main.cc", "main.cpp", "main.cxx",
            "util.h", "util.hh", "util.hpp", "util.hxx", "impl.inl",
        )) {
            assertTrue("expected $name included", FileFilter.includeFile(name))
        }
    }

    @Test
    fun extensionMatchingIsCaseInsensitive() {
        assertTrue(FileFilter.includeFile("MAIN.CPP"))
        assertTrue(FileFilter.includeFile("Util.H"))
    }

    @Test
    fun includesCMakeFiles() {
        assertTrue(FileFilter.includeFile("CMakeLists.txt"))
        assertTrue(FileFilter.includeFile("toolchain.cmake"))
    }

    @Test
    fun excludesOtherFiles() {
        for (name in listOf("readme.md", "notes.txt", "app.py", "data.json", "Makefile", "noextension")) {
            assertFalse("expected $name excluded", FileFilter.includeFile(name))
        }
    }

    @Test
    fun fileWithNoExtensionNamedLikeExtensionIsExcluded() {
        assertFalse(FileFilter.includeFile("cpp"))
        assertFalse(FileFilter.includeFile(".cpp")) // dotfile with empty stem: hidden config, not a source
    }

    @Test
    fun excludesNoiseDirectories() {
        for (name in listOf(".git", ".idea", "build", "out", ".vs", "Build", "OUT")) {
            assertFalse("expected dir $name excluded", FileFilter.includeDirectory(name))
        }
    }

    @Test
    fun excludesCmakeBuildDirsByPrefix() {
        assertFalse(FileFilter.includeDirectory("cmake-build-debug"))
        assertFalse(FileFilter.includeDirectory("cmake-build-release"))
        assertFalse(FileFilter.includeDirectory("CMAKE-BUILD-RELWITHDEBINFO"))
    }

    @Test
    fun includesNormalDirectories() {
        for (name in listOf("src", "include", "lib", "tests", "outer", "builder")) {
            assertTrue("expected dir $name included", FileFilter.includeDirectory(name))
        }
    }
}
```

Note: `outer` and `builder` guard against substring/prefix mistakes — exclusion must match whole names (`out`, `build`), not prefixes of them.

- [ ] **Step 2: Run test to verify it fails**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.FileFilterTest" --console=plain
```

Expected: FAILED — compilation error, `FileFilter` unresolved.

- [ ] **Step 3: Write the implementation**

`src/main/kotlin/com/daverobins/projectfilesbrowser/FileFilter.kt`:

```kotlin
package com.daverobins.projectfilesbrowser

/**
 * Decides which files and directories appear in the browser.
 * Operates on bare names (not paths). Hardcoded defaults in v1;
 * becomes settings-driven in a later phase.
 */
object FileFilter {

    private val allowedExtensions = setOf(
        "c", "cc", "cpp", "cxx", "h", "hh", "hpp", "hxx", "inl", "cmake",
    )
    private val allowedFileNames = setOf("cmakelists.txt")
    private val excludedDirNames = setOf(".git", ".idea", "build", "out", ".vs")
    private val excludedDirPrefixes = listOf("cmake-build-")

    fun includeFile(name: String): Boolean {
        val lower = name.lowercase()
        if (lower in allowedFileNames) return true
        val dot = lower.lastIndexOf('.')
        if (dot <= 0) return false // no extension, or dotfile like ".cpp"
        return lower.substring(dot + 1) in allowedExtensions
    }

    fun includeDirectory(name: String): Boolean {
        val lower = name.lowercase()
        if (lower in excludedDirNames) return false
        return excludedDirPrefixes.none { lower.startsWith(it) }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.FileFilterTest" --console=plain
```

Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 5: Commit**

```powershell
git add src
git commit -m "feat: FileFilter with C/C++/CMake allow-list and noise-dir exclusion"
```

---

### Task 4: FilteredTreeStructure + FileNode (TDD, platform test)

The tree structure walking the VFS through `FileFilter`, pruning empty directories, sorted dirs-first.

**Files:**
- Create: `src/main/kotlin/com/daverobins/projectfilesbrowser/FilteredTreeStructure.kt`
- Test: `src/test/kotlin/com/daverobins/projectfilesbrowser/FilteredTreeStructureTest.kt`

**Interfaces:**
- Consumes: `FileFilter.includeFile(name)`, `FileFilter.includeDirectory(name)` from Task 3.
- Produces: `class FilteredTreeStructure(project: Project, rootDir: VirtualFile) : SimpleTreeStructure` and `class FileNode(...) : SimpleNode` with a public `val file: VirtualFile` and standard `SimpleNode.children`. Task 5 constructs `FilteredTreeStructure` and reads `FileNode.file` from tree selections.

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/com/daverobins/projectfilesbrowser/FilteredTreeStructureTest.kt`:

```kotlin
package com.daverobins.projectfilesbrowser

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FilteredTreeStructureTest : BasePlatformTestCase() {

    fun testFiltersNoiseAndPrunesEmptyDirs() {
        myFixture.addFileToProject("proj/CMakeLists.txt", "project(x)")
        myFixture.addFileToProject("proj/src/main.cpp", "int main() { return 0; }")
        myFixture.addFileToProject("proj/src/util.h", "#pragma once")
        myFixture.addFileToProject("proj/src/readme.md", "filtered: wrong extension")
        myFixture.addFileToProject("proj/build/generated.cpp", "filtered: noise dir")
        myFixture.addFileToProject("proj/cmake-build-debug/x.cpp", "filtered: noise dir")
        myFixture.addFileToProject("proj/docs/notes.txt", "dir becomes empty, pruned")

        val rootDir = requireNotNull(myFixture.findFileInTempDir("proj"))
        val structure = FilteredTreeStructure(project, rootDir)
        val rendered = render(structure.rootElement as FileNode)

        assertEquals(
            """
            proj
              src
                main.cpp
                util.h
              CMakeLists.txt

            """.trimIndent(),
            rendered,
        )
    }

    fun testSortsDirectoriesFirstThenAlphabetical() {
        myFixture.addFileToProject("sorted/zeta.cpp", "")
        myFixture.addFileToProject("sorted/Alpha.cpp", "")
        myFixture.addFileToProject("sorted/zz/inner.cpp", "")
        myFixture.addFileToProject("sorted/aa/inner.cpp", "")

        val rootDir = requireNotNull(myFixture.findFileInTempDir("sorted"))
        val structure = FilteredTreeStructure(project, rootDir)
        val root = structure.rootElement as FileNode
        val names = root.children.map { (it as FileNode).file.name }

        assertEquals(listOf("aa", "zz", "Alpha.cpp", "zeta.cpp"), names)
    }

    private fun render(node: FileNode, indent: String = ""): String {
        val sb = StringBuilder().append(indent).append(node.file.name).append('\n')
        for (child in node.children) {
            sb.append(render(child as FileNode, "$indent  "))
        }
        return sb.toString()
    }
}
```

Note: the expected `trimIndent()` block ends with a blank line because `render` emits a trailing `\n`.

- [ ] **Step 2: Run test to verify it fails**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.FilteredTreeStructureTest" --console=plain
```

Expected: FAILED — compilation error, `FilteredTreeStructure` unresolved.

Troubleshooting (only if it fails for a different reason): if the platform test framework fails to BOOT against the CLion distribution (errors about missing modules/product-info during test JVM startup, before any assertion), record the exact error in the task report — do not silently skip the test.

- [ ] **Step 3: Write the implementation**

`src/main/kotlin/com/daverobins/projectfilesbrowser/FilteredTreeStructure.kt`:

```kotlin
package com.daverobins.projectfilesbrowser

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.treeStructure.SimpleNode
import com.intellij.ui.treeStructure.SimpleTreeStructure

/** Tree of project files filtered through [FileFilter], rooted at [rootDir]. */
class FilteredTreeStructure(project: Project, rootDir: VirtualFile) : SimpleTreeStructure() {
    private val root = FileNode(project, null, rootDir)
    override fun getRootElement(): Any = root
}

class FileNode(
    private val project: Project,
    parent: FileNode?,
    val file: VirtualFile,
) : SimpleNode(project, parent) {

    override fun getChildren(): Array<SimpleNode> {
        if (!file.isDirectory) return NO_CHILDREN
        val visible = (file.children ?: return NO_CHILDREN)
            .filter { it.isValid && isVisible(it) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        if (visible.isEmpty()) return NO_CHILDREN
        return visible.map { FileNode(project, this, it) }.toTypedArray()
    }

    override fun update(presentation: PresentationData) {
        presentation.presentableText = file.name
        presentation.setIcon(
            if (file.isDirectory) AllIcons.Nodes.Folder
            else file.fileType.icon ?: AllIcons.FileTypes.Any
        )
    }

    override fun getEqualityObjects(): Array<Any> = arrayOf(file)

    companion object {
        private fun isVisible(file: VirtualFile): Boolean =
            if (file.isDirectory) FileFilter.includeDirectory(file.name) && hasVisibleContent(file)
            else FileFilter.includeFile(file.name)

        /** A directory is shown only if filtering leaves something inside it. */
        private fun hasVisibleContent(dir: VirtualFile): Boolean =
            (dir.children ?: return false).any { it.isValid && isVisible(it) }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```powershell
.\gradlew.bat test --tests "com.daverobins.projectfilesbrowser.FilteredTreeStructureTest" --console=plain
```

Expected: `BUILD SUCCESSFUL`, both tests pass.

- [ ] **Step 5: Run ALL tests**

```powershell
.\gradlew.bat test --console=plain
```

Expected: `BUILD SUCCESSFUL` — FileFilterTest still green.

- [ ] **Step 6: Commit**

```powershell
git add src
git commit -m "feat: FilteredTreeStructure walking VFS through FileFilter with empty-dir pruning"
```

---

### Task 5: Tool window panel + registration

The UI glue: panel with toolbar (refresh) + tree, double-click/Enter opens files, tool window registered in `plugin.xml`. No unit test (pure UI wiring); verified by build now and `runIde` in Task 6.

**Files:**
- Create: `src/main/kotlin/com/daverobins/projectfilesbrowser/ProjectFilesPanel.kt`
- Create: `src/main/kotlin/com/daverobins/projectfilesbrowser/ProjectFilesToolWindowFactory.kt`
- Modify: `src/main/resources/META-INF/plugin.xml` (add `<extensions>` block)

**Interfaces:**
- Consumes: `FilteredTreeStructure(project, rootDir)` and `FileNode.file` from Task 4.
- Produces: tool window id `Project Files`, anchored left.

- [ ] **Step 1: Write `ProjectFilesPanel.kt`**

```kotlin
package com.daverobins.projectfilesbrowser

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.tree.AsyncTreeModel
import com.intellij.ui.tree.StructureTreeModel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.KeyStroke

class ProjectFilesPanel(
    private val project: Project,
    rootDir: VirtualFile,
    parentDisposable: Disposable,
) : SimpleToolWindowPanel(true, true) {

    private val structureModel =
        StructureTreeModel(FilteredTreeStructure(project, rootDir), parentDisposable)
    private val tree = Tree(AsyncTreeModel(structureModel, parentDisposable))

    init {
        tree.isRootVisible = true
        tree.emptyText.text = "No matching files"

        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                openSelection()
                return true
            }
        }.installOn(tree)

        tree.registerKeyboardAction(
            { openSelection() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            JComponent.WHEN_FOCUSED,
        )

        val refresh = object : AnAction("Refresh", "Rebuild the file tree", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                structureModel.invalidateAsync()
            }
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("ProjectFilesBrowser", DefaultActionGroup(refresh), true)
        toolbar.targetComponent = tree
        setToolbar(toolbar.component)
        setContent(ScrollPaneFactory.createScrollPane(tree))
    }

    private fun openSelection() {
        val path = tree.selectionPath ?: return
        val node = TreeUtil.getLastUserObject(FileNode::class.java, path) ?: return
        val file = node.file
        if (!file.isDirectory && file.isValid) {
            OpenFileDescriptor(project, file).navigate(true)
        }
    }
}
```

- [ ] **Step 2: Write `ProjectFilesToolWindowFactory.kt`**

```kotlin
package com.daverobins.projectfilesbrowser

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.content.ContentFactory

class ProjectFilesToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val rootDir = project.guessProjectDir()
        val component =
            if (rootDir == null) JBPanelWithEmptyText().withEmptyText("Project directory not found")
            else ProjectFilesPanel(project, rootDir, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(component, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
```

- [ ] **Step 3: Register the tool window in `plugin.xml`**

Add this block inside `<idea-plugin>`, after `<depends>`:

```xml
    <extensions defaultExtensionNs="com.intellij">
        <toolWindow id="Project Files"
                    anchor="left"
                    icon="AllIcons.Toolwindows.ToolWindowProject"
                    factoryClass="com.daverobins.projectfilesbrowser.ProjectFilesToolWindowFactory"/>
    </extensions>
```

- [ ] **Step 4: Verify build + plugin validity**

```powershell
.\gradlew.bat build verifyPluginProjectConfiguration --console=plain
```

Expected: `BUILD SUCCESSFUL`, no errors about the tool window registration. (Warnings about a missing plugin icon are acceptable for v1.)

- [ ] **Step 5: Commit**

```powershell
git add src
git commit -m "feat: Project Files tool window with filtered tree, open-on-double-click, refresh"
```

---

### Task 6: End-to-end verification in sandbox CLion

Human-in-the-loop checkpoint — the only step that needs eyes on a running IDE.

**Files:** none (unless fixes are needed).

- [ ] **Step 1: Launch the sandbox IDE**

```powershell
.\gradlew.bat runIde --console=plain
```

Run in background (it blocks until the IDE is closed). First launch may take a couple of minutes. Note: the sandbox CLion may ask for license activation on first run — the user's existing CLion license or trial applies.

- [ ] **Step 2: Manual verification checklist (user drives the sandbox IDE)**

Ask the user to open any CMake/C++ project in the sandbox CLion and verify:

1. A "Project Files" tool window button appears on the left edge.
2. Opening it shows the project tree with ONLY C/C++/CMake files.
3. `cmake-build-*`, `.git`, `.idea` etc. do not appear; dirs with no matching files do not appear.
4. Directories sort before files; both alphabetical.
5. Double-click (and Enter) on a file opens it in the editor; double-click on a directory just expands it.
6. Create a new `.cpp` file via the normal Project view, hit the refresh button in the tool window → the new file appears.

- [ ] **Step 3: Fix anything found, re-verify, commit fixes**

Any defect found goes through: reproduce → fix → re-run `runIde` → re-check. Commit with a `fix:` message describing the actual defect.

- [ ] **Step 4: Final commit / tag**

```powershell
git add -A
git commit -m "chore: v1 complete - filtered project files browser" --allow-empty
git tag v0.1.0
```
