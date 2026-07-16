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
