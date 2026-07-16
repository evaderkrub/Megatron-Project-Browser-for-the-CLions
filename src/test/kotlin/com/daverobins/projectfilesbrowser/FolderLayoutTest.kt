package com.daverobins.projectfilesbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderLayoutTest {

    @Test
    fun `parses folders and assignments`() {
        val layout = parseFoldersFile(
            """
            # comment
            Core/
              src/engine.cpp
              src/engine.h
            Core/Math/
              src/vec.h
            Platform/
            """.trimIndent()
        )
        assertEquals(listOf("Core", "Core/Math", "Platform"), layout.folders)
        assertEquals("Core", layout.folderFor("src/engine.cpp"))
        assertEquals("Core/Math", layout.folderFor("src/vec.h"))
        assertNull(layout.folderFor("src/other.cpp"))
    }

    @Test
    fun `file line before any folder is ignored`() {
        val layout = parseFoldersFile("orphan.cpp\nCore/\n  a.cpp\n")
        assertNull(layout.folderFor("orphan.cpp"))
        assertEquals("Core", layout.folderFor("a.cpp"))
    }

    @Test
    fun `blank lines comments and indentation are cosmetic`() {
        val layout = parseFoldersFile("\n  # note\n  Core/  \n\n    src/a.cpp   \n")
        assertEquals(listOf("Core"), layout.folders)
        assertEquals("Core", layout.folderFor("src/a.cpp"))
    }

    @Test
    fun `backslashes normalize and lookups are case-insensitive`() {
        val layout = parseFoldersFile("Core/\n  src\\Engine.CPP\n")
        assertEquals("Core", layout.folderFor("SRC/engine.cpp"))
        assertTrue("src/engine.cpp" in layout.assignedFilesLowercase())
    }

    @Test
    fun `duplicate assignment last wins`() {
        val layout = parseFoldersFile("A/\n  x.cpp\nB/\n  x.cpp\n")
        assertEquals("B", layout.folderFor("x.cpp"))
        assertEquals(emptyList<String>(), layout.filesIn("A"))
        assertEquals(listOf("x.cpp"), layout.filesIn("B"))
    }

    @Test
    fun `nested folder declaration auto-creates parents`() {
        val layout = parseFoldersFile("Core/Math/Linear/\n")
        assertEquals(listOf("Core", "Core/Math", "Core/Math/Linear"), layout.folders)
    }

    @Test
    fun `folder casing merges case-insensitively with first declaration winning`() {
        val layout = parseFoldersFile("Core/\ncore/\n  a.cpp\nCORE/Math/\n")
        assertEquals(listOf("Core", "Core/Math"), layout.folders)
        assertEquals("Core", layout.folderFor("a.cpp"))
    }

    @Test
    fun `childFolders returns direct children sorted by name`() {
        val layout = parseFoldersFile("B/\nA/\nA/zz/\nA/mm/\n")
        assertEquals(listOf("A", "B"), layout.childFolders(""))
        assertEquals(listOf("A/mm", "A/zz"), layout.childFolders("A"))
        assertEquals(listOf("A/mm", "A/zz"), layout.childFolders("a"))
    }

    @Test
    fun `serialize writes sorted folders then sorted files with two-space indent`() {
        val layout = parseFoldersFile("Platform/\n  win.cpp\nCore/\n  src/b.cpp\n  src/A.cpp\nEmpty/\n")
        assertEquals(
            "Core/\n  src/A.cpp\n  src/b.cpp\nEmpty/\nPlatform/\n  win.cpp\n",
            layout.serialize(),
        )
    }

    @Test
    fun `serialize then parse round-trips`() {
        val original = parseFoldersFile("Core/\n  src/a.cpp\nCore/Math/\n  v.h\nPlatform/\n")
        val reparsed = parseFoldersFile(original.serialize())
        assertEquals(original.serialize(), reparsed.serialize())
        assertEquals(original.folders.sorted(), reparsed.folders.sorted())
    }

    @Test
    fun `withFolder adds folder and keeps assignments`() {
        val layout = parseFoldersFile("Core/\n  a.cpp\n").withFolder("New/Sub")
        assertTrue(layout.hasFolder("New"))
        assertTrue(layout.hasFolder("new/sub"))
        assertEquals("Core", layout.folderFor("a.cpp"))
    }

    @Test
    fun `withAssignment moves file between folders`() {
        val layout = parseFoldersFile("A/\n  x.cpp\nB/\n").withAssignment("x.cpp", "B")
        assertEquals("B", layout.folderFor("x.cpp"))
        assertEquals(emptyList<String>(), layout.filesIn("A"))
    }

    @Test
    fun `withAssignment to unknown folder creates it`() {
        val layout = FolderLayout().withAssignment("src/a.cpp", "Fresh")
        assertEquals(listOf("Fresh"), layout.folders)
        assertEquals("Fresh", layout.folderFor("src/a.cpp"))
    }

    @Test
    fun `withUnassigned removes the assignment`() {
        val layout = parseFoldersFile("A/\n  x.cpp\n").withUnassigned("X.CPP")
        assertNull(layout.folderFor("x.cpp"))
        assertTrue(layout.hasFolder("A"))
    }

    @Test
    fun `withFolderRenamed cascades to descendants and assignments`() {
        val layout = parseFoldersFile("Core/\n  a.cpp\nCore/Math/\n  v.h\nOther/\n")
            .withFolderRenamed("Core", "Base")
        assertEquals(listOf("Base", "Base/Math", "Other"), layout.folders)
        assertEquals("Base", layout.folderFor("a.cpp"))
        assertEquals("Base/Math", layout.folderFor("v.h"))
    }

    @Test
    fun `withFolderDeleted removes subtree and unassigns its files`() {
        val layout = parseFoldersFile("Core/\n  a.cpp\nCore/Math/\n  v.h\nOther/\n  o.cpp\n")
            .withFolderDeleted("Core")
        assertEquals(listOf("Other"), layout.folders)
        assertNull(layout.folderFor("a.cpp"))
        assertNull(layout.folderFor("v.h"))
        assertEquals("Other", layout.folderFor("o.cpp"))
    }

    @Test
    fun `validateFolderName rejects empty slashes and duplicates`() {
        assertNotNull(validateFolderName("", emptyList()))
        assertNotNull(validateFolderName("   ", emptyList()))
        assertNotNull(validateFolderName("a/b", emptyList()))
        assertNotNull(validateFolderName("a\\b", emptyList()))
        assertNotNull(validateFolderName("core", listOf("Core", "Other")))
        assertNull(validateFolderName("Fresh", listOf("Core", "Other")))
        assertNull(validateFolderName("  Fresh  ", listOf("Core")))
    }
}
