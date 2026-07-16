package com.daverobins.projectfilesbrowser

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.treeStructure.SimpleNode

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
        val structure = FilteredTreeStructure(project, rootDir, FilterEngine(project, rootDir))
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
        val structure = FilteredTreeStructure(project, rootDir, FilterEngine(project, rootDir))
        val root = structure.rootElement as FileNode
        val names = root.children.map { (it as FileNode).file.name }

        assertEquals(listOf("aa", "zz", "Alpha.cpp", "zeta.cpp"), names)
    }

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

    fun testFlatModeListsVisibleFilesSortedByNameThenPath() {
        myFixture.addFileToProject("fl/CMakeLists.txt", "")
        myFixture.addFileToProject("fl/src/alpha.cpp", "")
        myFixture.addFileToProject("fl/src/deep/beta.h", "")
        myFixture.addFileToProject("fl/zeta.cpp", "")
        myFixture.addFileToProject("fl/readme.md", "hidden by built-in defaults")
        myFixture.addFileToProject("fl/cmake-build-debug/x.cpp", "excluded dir, never traversed")
        myFixture.addFileToProject("fl/util/common.h", "")
        myFixture.addFileToProject("fl/src/common.h", "")

        val state = MegatronFilterState.getInstance(project)
        state.setViewMode(ViewMode.FLAT)
        try {
            val rootDir = requireNotNull(myFixture.findFileInTempDir("fl"))
            val structure = FilteredTreeStructure(project, rootDir, FilterEngine(project, rootDir))
            val root = structure.rootElement as FileNode

            assertEquals(
                listOf("alpha.cpp", "beta.h", "CMakeLists.txt", "common.h", "common.h", "zeta.cpp"),
                root.children.map { (it as FileNode).file.name },
            )
            val commons = root.children.map { it as FileNode }.filter { it.file.name == "common.h" }
            assertEquals(
                listOf("src/common.h", "util/common.h"),
                commons.map { it.file.path.removePrefix(rootDir.path + "/") },
            )
            assertTrue("flat rows must be leaves", root.children.all { it.children.isEmpty() })
        } finally {
            state.setViewMode(ViewMode.TREE)
        }
    }

    fun testFlatLeafLocationStrings() {
        myFixture.addFileToProject("loc/src/main.cpp", "")
        myFixture.addFileToProject("loc/top.cpp", "")
        myFixture.addFileToProject("loc/src/deep/inner.cpp", "")

        val state = MegatronFilterState.getInstance(project)
        state.setViewMode(ViewMode.FLAT)
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

            val inner = nodes.first { it.file.name == "inner.cpp" }
            inner.update()
            assertEquals("src/deep", inner.presentation.locationString)
        } finally {
            state.setViewMode(ViewMode.TREE)
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

        state.setViewMode(ViewMode.TREE)
        val treeSet = collectFiles(
            FilteredTreeStructure(project, rootDir, FilterEngine(project, rootDir)).rootElement as FileNode
        )
        state.setViewMode(ViewMode.FLAT)
        try {
            val flatSet = collectFiles(
                FilteredTreeStructure(project, rootDir, FilterEngine(project, rootDir)).rootElement as FileNode
            )
            assertEquals(treeSet, flatSet)
            assertEquals(2, flatSet.size)
        } finally {
            state.setViewMode(ViewMode.TREE)
        }
    }

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

    private fun render(node: FileNode, indent: String = ""): String {
        val sb = StringBuilder().append(indent).append(node.file.name).append('\n')
        for (child in node.children) {
            sb.append(render(child as FileNode, "$indent  "))
        }
        return sb.toString()
    }

    private fun renderNode(node: SimpleNode, indent: String = ""): String {
        node.update()
        val sb = StringBuilder().append(indent).append(node.presentation.presentableText).append('\n')
        for (child in node.children) {
            sb.append(renderNode(child, "$indent  "))
        }
        return sb.toString()
    }
}
