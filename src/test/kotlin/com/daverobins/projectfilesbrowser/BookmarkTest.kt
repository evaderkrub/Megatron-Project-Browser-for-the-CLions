package com.daverobins.projectfilesbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkTest {

    @Test
    fun `parses cpp marker with set name and title`() {
        val result = parseBookmarks("""// megatron/default: "do something"""")
        assertEquals(listOf(Bookmark(0, "default", "do something")), result)
    }

    @Test
    fun `parses marker without set name`() {
        val result = parseBookmarks("""// megatron: "fix later"""")
        assertEquals(listOf(Bookmark(0, null, "fix later")), result)
    }

    @Test
    fun `parses cmake hash marker`() {
        val result = parseBookmarks("""# megatron/build: "check flags"""")
        assertEquals(listOf(Bookmark(0, "build", "check flags")), result)
    }

    @Test
    fun `marker keyword is case-insensitive and set name casing is preserved`() {
        val result = parseBookmarks("""// MEGATRON/Default: "x"""")
        assertEquals(listOf(Bookmark(0, "Default", "x")), result)
    }

    @Test
    fun `indented markers are parsed`() {
        val result = parseBookmarks("\t   // megatron: \"deep\"")
        assertEquals(listOf(Bookmark(0, null, "deep")), result)
    }

    @Test
    fun `empty title parses to empty string`() {
        val result = parseBookmarks("// megatron: \"\"")
        assertEquals(listOf(Bookmark(0, null, "")), result)
    }

    @Test
    fun `lines without a quoted title are ignored`() {
        assertTrue(parseBookmarks("// megatron: no quotes here").isEmpty())
        assertTrue(parseBookmarks("// megatron/default:").isEmpty())
        assertTrue(parseBookmarks("""// megatron: "unterminated""").isEmpty())
    }

    @Test
    fun `marker after code is not a bookmark`() {
        assertTrue(parseBookmarks("""int x; // megatron: "hi"""").isEmpty())
    }

    @Test
    fun `blank set name means no set`() {
        val result = parseBookmarks("""// megatron/: "x"""")
        assertEquals(listOf(Bookmark(0, null, "x")), result)
    }

    @Test
    fun `line numbers are zero-based over multiple lines`() {
        val text = "int a;\n// megatron: \"first\"\nint b;\n  # megatron/s: \"second\""
        val result = parseBookmarks(text)
        assertEquals(listOf(Bookmark(1, null, "first"), Bookmark(3, "s", "second")), result)
    }

    @Test
    fun `visibleInSet matches null set always and named set case-insensitively`() {
        assertTrue(Bookmark(0, null, "t").visibleInSet("anything"))
        assertTrue(Bookmark(0, "Default", "t").visibleInSet("default"))
        assertFalse(Bookmark(0, "other", "t").visibleInSet("default"))
    }

    @Test
    fun `insertion uses hash prefix for cmake files`() {
        val cmake = bookmarkInsertion("add_library(x)", "CMakeLists.txt", "default")
        assertEquals("# megatron/default: \"\"", cmake.lineText)
        assertEquals(cmake.lineText.length - 1, cmake.caretColumn)
        assertTrue(bookmarkInsertion("", "helpers.cmake", "s").lineText.startsWith("#"))
        assertTrue(bookmarkInsertion("", "main.CMAKE", "s").lineText.startsWith("#"))
        assertTrue(bookmarkInsertion("", "main.cpp", "s").lineText.startsWith("//"))
    }

    @Test
    fun `insertion copies indentation and puts caret between quotes`() {
        val insertion = bookmarkInsertion("    int x = 0;", "main.cpp", "default")
        assertEquals("    // megatron/default: \"\"", insertion.lineText)
        assertEquals(insertion.lineText.length - 1, insertion.caretColumn)
        assertEquals('"', insertion.lineText[insertion.caretColumn])
        assertEquals('"', insertion.lineText[insertion.caretColumn - 1])
    }

    @Test
    fun `change touching the marker word is detected`() {
        val text = "xx // megatron: \"t\" yy"
        assertTrue(changeTouchesMarker(text, changeStart = 6, changeLength = 8))
    }

    @Test
    fun `single char completing the marker word is detected`() {
        val text = "// megatron"
        assertTrue(changeTouchesMarker(text, changeStart = text.length - 1, changeLength = 1))
    }

    @Test
    fun `change far from the marker word is not detected`() {
        val text = "// megatron: \"t\"\n" + "int x = 0;".repeat(10)
        assertFalse(changeTouchesMarker(text, changeStart = text.length - 5, changeLength = 1))
    }

    @Test
    fun `marker detection is case-insensitive`() {
        val text = "// MEGATRON: \"t\""
        assertTrue(changeTouchesMarker(text, changeStart = 3, changeLength = 8))
    }

    @Test
    fun `deletion adjacent to the marker word is detected`() {
        val text = "ab megatron"
        assertTrue(changeTouchesMarker(text, changeStart = text.length, changeLength = 0))
    }

    @Test
    fun `edit later on a marker line is detected`() {
        val text = "int a;\n// megatron: \"a long title\nint b;"
        val quotePos = text.indexOf("title") + "title".length
        assertTrue(changeTouchesMarker(text, changeStart = quotePos, changeLength = 1))
    }

    @Test
    fun `multi-line change spanning a marker line is detected`() {
        val text = "int a;\nx// megatron: \"t\"\nint b;"
        assertTrue(changeTouchesMarker(text, changeStart = 4, changeLength = 10))
    }

    @Test
    fun `window clamps at document edges without throwing`() {
        assertFalse(changeTouchesMarker("", changeStart = 0, changeLength = 0))
        assertFalse(changeTouchesMarker("meg", changeStart = 0, changeLength = 3))
        assertTrue(changeTouchesMarker("megatron", changeStart = 0, changeLength = 8))
    }
}
