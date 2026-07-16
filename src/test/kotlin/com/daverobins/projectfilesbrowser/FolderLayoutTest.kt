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
        assertEquals("Core", layout.folderFor("src\\engine.cpp"))
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
    fun `serialize preserves folder declaration order and sorts explicit files`() {
        val layout = parseFoldersFile("Platform/\n  win.cpp\nCore/\n  src/b.cpp\n  src/A.cpp\nEmpty/\n")
        assertEquals(
            "Platform/\n  win.cpp\nCore/\n  src/A.cpp\n  src/b.cpp\nEmpty/\n",
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
}
