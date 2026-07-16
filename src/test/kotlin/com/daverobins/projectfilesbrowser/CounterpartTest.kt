package com.daverobins.projectfilesbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CounterpartTest {

    @Test
    fun `matches opposite extension family with same base name`() {
        assertEquals("src/engine.h", findCounterpart("src/engine.cpp", listOf("src/engine.h", "src/other.h")))
        assertEquals("src/engine.cpp", findCounterpart("src/engine.h", listOf("src/engine.cpp")))
        assertEquals("a.cc", findCounterpart("a.hh", listOf("a.cc")))
        assertEquals("a.cxx", findCounterpart("a.hxx", listOf("a.cxx")))
    }

    @Test
    fun `non-pairable extensions and missing matches return null`() {
        assertNull(findCounterpart("notes.md", listOf("notes.h", "notes.cpp")))
        assertNull(findCounterpart("src/engine.cpp", listOf("src/other.h")))
        assertNull(findCounterpart("src/engine.cpp", emptyList()))
        assertNull(findCounterpart("src/engine.cpp", listOf("src/engine.cc"))) // same family, not a pair
    }

    @Test
    fun `same directory beats closer name elsewhere`() {
        assertEquals(
            "src/deep/engine.h",
            findCounterpart("src/deep/engine.cpp", listOf("include/engine.h", "src/deep/engine.h")),
        )
    }

    @Test
    fun `most shared leading segments wins then path order breaks ties`() {
        assertEquals(
            "src/deep/inc/engine.h",
            findCounterpart(
                "src/deep/engine.cpp",
                listOf("include/engine.h", "src/deep/inc/engine.h", "src/other/engine.h"),
            ),
        )
        assertEquals(
            "include/a/engine.h",
            findCounterpart("src/engine.cpp", listOf("include/b/engine.h", "include/a/engine.h")),
        )
    }

    @Test
    fun `matching is case-insensitive on base name extension and directory`() {
        assertEquals("SRC/Engine.H", findCounterpart("src/ENGINE.CPP", listOf("SRC/Engine.H")))
    }
}
