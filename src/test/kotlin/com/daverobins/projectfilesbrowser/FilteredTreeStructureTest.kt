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
        state.setFlatMode(true)
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
            state.setFlatMode(false)
        }
    }

    fun testFlatLeafLocationStrings() {
        myFixture.addFileToProject("loc/src/main.cpp", "")
        myFixture.addFileToProject("loc/top.cpp", "")
        myFixture.addFileToProject("loc/src/deep/inner.cpp", "")

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

            val inner = nodes.first { it.file.name == "inner.cpp" }
            inner.update()
            assertEquals("src/deep", inner.presentation.locationString)
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

    private fun render(node: FileNode, indent: String = ""): String {
        val sb = StringBuilder().append(indent).append(node.file.name).append('\n')
        for (child in node.children) {
            sb.append(render(child as FileNode, "$indent  "))
        }
        return sb.toString()
    }
}
