package com.daverobins.projectfilesbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterConfigTest {

    // --- GlobPattern ---

    @Test
    fun starMatchesWithinSegmentOnly() {
        val p = GlobPattern("*.cpp")
        assertTrue(p.matches("src/main.cpp", "main.cpp"))          // name pattern: applies to file name
        assertTrue(p.matches("main.cpp", "main.cpp"))
        assertFalse(p.matches("src/main.h", "main.h"))
    }

    @Test
    fun namePatternMatchesAnywhereInTree() {
        val p = GlobPattern("CMakeLists.txt")
        assertTrue(p.matches("deep/nested/CMakeLists.txt", "CMakeLists.txt"))
    }

    @Test
    fun pathPatternStarDoesNotCrossSlash() {
        val p = GlobPattern("src/*.cpp")
        assertTrue(p.matches("src/main.cpp", "main.cpp"))
        assertFalse(p.matches("src/sub/deep.cpp", "deep.cpp"))
    }

    @Test
    fun doubleStarCrossesDirectories() {
        val p = GlobPattern("src/**")
        assertTrue(p.matches("src/main.cpp", "main.cpp"))
        assertTrue(p.matches("src/sub/deep/x.h", "x.h"))
        assertFalse(p.matches("other/main.cpp", "main.cpp"))
    }

    @Test
    fun questionMarkMatchesExactlyOneNonSlashChar() {
        val p = GlobPattern("a?.cpp")
        assertTrue(p.matches("ab.cpp", "ab.cpp"))
        assertFalse(p.matches("a.cpp", "a.cpp"))
        assertFalse(p.matches("abc.cpp", "abc.cpp"))
        assertFalse(GlobPattern("a?b").matches("a/b", "a/b"))
    }

    @Test
    fun matchingIsCaseInsensitive() {
        assertTrue(GlobPattern("*.CPP").matches("MAIN.cpp", "MAIN.cpp"))
        assertTrue(GlobPattern("SRC/**").matches("src/x.h", "x.h"))
    }

    @Test
    fun regexMetacharactersAreLiteral() {
        val p = GlobPattern("a+b.cpp")
        assertTrue(p.matches("a+b.cpp", "a+b.cpp"))
        assertFalse(p.matches("aab.cpp", "aab.cpp"))
    }

    @Test
    fun patternMustMatchWholeTarget() {
        val p = GlobPattern("main.cpp")
        assertFalse(p.matches("xmain.cpp", "xmain.cpp"))
        assertFalse(p.matches("main.cpp.bak", "main.cpp.bak"))
    }

    // --- parseFilterFile ---

    @Test
    fun parsesGroupsInFileOrder() {
        val groups = parseFilterFile("Sources: *.cpp, *.c\nHeaders: *.h")
        assertEquals(listOf("Sources", "Headers"), groups.map { it.name })
        assertEquals(2, groups[0].patterns.size)
        assertEquals(1, groups[1].patterns.size)
    }

    @Test
    fun ignoresCommentsAndBlankLines() {
        val groups = parseFilterFile("# comment\n\n  \nSources: *.cpp\n  # indented comment")
        assertEquals(listOf("Sources"), groups.map { it.name })
    }

    @Test
    fun skipsMalformedLines() {
        val groups = parseFilterFile("no colon here\n: nameless\nEmptyPatterns: , ,\nGood: *.h")
        assertEquals(listOf("Good"), groups.map { it.name })
    }

    @Test
    fun trimsNamesAndPatterns() {
        val groups = parseFilterFile("  My Group  :  *.cpp ,  *.h  ")
        assertEquals("My Group", groups[0].name)
        assertTrue(groups[0].patterns[1].matches("a.h", "a.h"))
    }

    @Test
    fun duplicateGroupNameLastWins() {
        val groups = parseFilterFile("G: *.cpp\nG: *.md")
        assertEquals(1, groups.size)
        assertTrue(groups[0].patterns[0].matches("x.md", "x.md"))
        assertFalse(groups[0].patterns[0].matches("x.cpp", "x.cpp"))
    }

    @Test
    fun patternAfterFirstColonMayContainColons() {
        val groups = parseFilterFile("G: a:b*.txt")
        assertEquals(1, groups.size)
        assertTrue(groups[0].patterns[0].matches("a:bc.txt", "a:bc.txt"))
    }

    // --- visibleByGroups ---

    @Test
    fun emptyEnabledGroupsFallsBackToBuiltInFilter() {
        assertTrue(visibleByGroups(emptyList(), "src/main.cpp", "main.cpp"))
        assertFalse(visibleByGroups(emptyList(), "readme.md", "readme.md"))
    }

    @Test
    fun unionAcrossEnabledGroups() {
        val groups = parseFilterFile("Docs: *.md\nSources: src/**")
        assertTrue(visibleByGroups(groups, "readme.md", "readme.md"))
        assertTrue(visibleByGroups(groups, "src/anything.xyz", "anything.xyz"))
        assertFalse(visibleByGroups(groups, "other/tool.cpp", "tool.cpp"))
    }
}
