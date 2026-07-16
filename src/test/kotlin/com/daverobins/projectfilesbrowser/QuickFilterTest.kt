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
