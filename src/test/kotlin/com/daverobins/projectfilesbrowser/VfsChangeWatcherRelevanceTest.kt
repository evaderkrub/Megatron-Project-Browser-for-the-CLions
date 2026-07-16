package com.daverobins.projectfilesbrowser

import com.daverobins.projectfilesbrowser.VfsChangeWatcher.Companion.isFilterFileEvent
import com.daverobins.projectfilesbrowser.VfsChangeWatcher.Companion.isRelevantEitherPath
import com.daverobins.projectfilesbrowser.VfsChangeWatcher.Companion.isRelevantPath
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VfsChangeWatcherRelevanceTest {

    private val root = "/proj"

    @Test
    fun rootItselfIsRelevant() {
        assertTrue(isRelevantPath(root, "/proj", isDirectory = true))
    }

    @Test
    fun pathOutsideRootIsIrrelevant() {
        assertFalse(isRelevantPath(root, "/elsewhere/main.cpp", isDirectory = false))
    }

    @Test
    fun siblingWithRootAsPrefixIsIrrelevant() {
        // "/proj2" starts with "/proj" as a string but is NOT under it
        assertFalse(isRelevantPath(root, "/proj2/main.cpp", isDirectory = false))
    }

    @Test
    fun matchingFileUnderRootIsRelevant() {
        assertTrue(isRelevantPath(root, "/proj/src/main.cpp", isDirectory = false))
    }

    @Test
    fun nonMatchingFileIsIrrelevant() {
        assertFalse(isRelevantPath(root, "/proj/readme.md", isDirectory = false))
    }

    @Test
    fun fileInsideExcludedDirIsIrrelevant() {
        assertFalse(isRelevantPath(root, "/proj/cmake-build-debug/x.cpp", isDirectory = false))
        assertFalse(isRelevantPath(root, "/proj/a/.git/objects/ab12cd", isDirectory = false))
    }

    @Test
    fun includedDirectoryLeafIsRelevant() {
        assertTrue(isRelevantPath(root, "/proj/src/newmodule", isDirectory = true))
    }

    @Test
    fun excludedDirectoryLeafIsIrrelevant() {
        assertFalse(isRelevantPath(root, "/proj/cmake-build-debug", isDirectory = true))
        assertFalse(isRelevantPath(root, "/proj/.git", isDirectory = true))
    }

    @Test
    fun renameIsRelevantWhenOnlyOldPathMatches() {
        // main.cpp renamed to notes.txt: old path qualifies -> relevant
        assertTrue(isRelevantEitherPath(root, "/proj/src/main.cpp", "/proj/src/notes.txt", isDirectory = false))
    }

    @Test
    fun renameIsRelevantWhenOnlyNewPathMatches() {
        // notes.txt renamed to main.cpp: new path qualifies -> relevant
        assertTrue(isRelevantEitherPath(root, "/proj/src/notes.txt", "/proj/src/main.cpp", isDirectory = false))
    }

    @Test
    fun renameIsIrrelevantWhenNeitherPathMatches() {
        assertFalse(isRelevantEitherPath(root, "/proj/src/a.txt", "/proj/src/b.md", isDirectory = false))
    }

    @Test
    fun renameWithNullOldPathFallsBackToNewPathOnly() {
        assertTrue(isRelevantEitherPath(root, null, "/proj/src/main.cpp", isDirectory = false))
        assertFalse(isRelevantEitherPath(root, null, "/proj/src/notes.txt", isDirectory = false))
    }

    @Test
    fun moveOutOfRootIsRelevantViaOldPath() {
        assertTrue(isRelevantEitherPath(root, "/proj/src/main.cpp", "/elsewhere/main.cpp", isDirectory = false))
    }

    @Test
    fun filterFileEventIsAlwaysRelevant() {
        assertTrue(isFilterFileEvent(root, null, "/proj/megatron.filters"))
    }

    @Test
    fun filterFileRenameAwayIsRelevantViaOldPath() {
        assertTrue(isFilterFileEvent(root, "/proj/megatron.filters", "/proj/renamed.txt"))
    }

    @Test
    fun otherFilesAreNotFilterFileEvents() {
        assertFalse(isFilterFileEvent(root, null, "/proj/main.cpp"))
        assertFalse(isFilterFileEvent(root, null, "/proj/sub/megatron.filters")) // only root-level file counts
        assertFalse(isFilterFileEvent(root, null, "/other/megatron.filters"))
    }

    @Test
    fun filterFileMatchIsCaseInsensitive() {
        assertTrue(isFilterFileEvent(root, null, "/proj/Megatron.Filters"))
    }
}
