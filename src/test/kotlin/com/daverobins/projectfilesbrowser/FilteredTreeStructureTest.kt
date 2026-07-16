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
