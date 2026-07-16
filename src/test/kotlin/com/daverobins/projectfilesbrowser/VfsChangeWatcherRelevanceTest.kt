package com.daverobins.projectfilesbrowser

import com.daverobins.projectfilesbrowser.VfsChangeWatcher.Companion.isConfigFileEvent
import com.daverobins.projectfilesbrowser.VfsChangeWatcher.Companion.isRelevantEitherPath
import com.daverobins.projectfilesbrowser.VfsChangeWatcher.Companion.isRelevantPath
import org.junit.Assert.assertEquals
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
    fun configFileEventIsAlwaysRelevant() {
        assertTrue(isConfigFileEvent(root, null, "/proj/megatron.filters"))
    }

    @Test
    fun configFileRenameAwayIsRelevantViaOldPath() {
        assertTrue(isConfigFileEvent(root, "/proj/megatron.filters", "/proj/renamed.txt"))
    }

    @Test
    fun otherFilesAreNotConfigFileEvents() {
        assertFalse(isConfigFileEvent(root, null, "/proj/main.cpp"))
        assertFalse(isConfigFileEvent(root, null, "/proj/sub/megatron.filters")) // only root-level file counts
        assertFalse(isConfigFileEvent(root, null, "/other/megatron.filters"))
    }

    @Test
    fun configFileMatchIsCaseInsensitive() {
        assertTrue(isConfigFileEvent(root, null, "/proj/Megatron.Filters"))
    }

    @Test
    fun customPredicateMakesGroupOnlyFileRelevant() {
        val mdVisible: (String, String) -> Boolean = { _, name -> name.endsWith(".md", ignoreCase = true) }
        assertTrue(isRelevantPath(root, "/proj/docs/notes.md", isDirectory = false, fileVisible = mdVisible))
        // same file under the built-in default predicate stays irrelevant
        assertFalse(isRelevantPath(root, "/proj/docs/notes.md", isDirectory = false))
    }

    @Test
    fun customPredicateReceivesRelativePathAndName() {
        val seen = mutableListOf<Pair<String, String>>()
        val spy: (String, String) -> Boolean = { rel, name -> seen.add(rel to name); true }
        assertTrue(isRelevantPath(root, "/proj/src/main.cpp", isDirectory = false, fileVisible = spy))
        assertEquals(listOf("src/main.cpp" to "main.cpp"), seen)
    }

    @Test
    fun testFoldersFileEventsAreAlwaysRelevant() {
        assertTrue(isConfigFileEvent("/root", null, "/root/megatron.folders"))
        assertTrue(isConfigFileEvent("/root", null, "/root/MEGATRON.FOLDERS"))
        assertTrue(isConfigFileEvent("/root", "/root/megatron.folders", "/elsewhere/renamed.txt"))
        assertFalse(isConfigFileEvent("/root", null, "/root/sub/megatron.folders"))
        assertFalse(isConfigFileEvent("/root", null, "/other/megatron.folders"))
    }
}
