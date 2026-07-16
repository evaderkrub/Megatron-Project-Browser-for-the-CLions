package com.daverobins.projectfilesbrowser

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ConfigSetManagerTest : BasePlatformTestCase() {

    fun testScanUnionsExtensionsIgnoresNoiseAndSorts() {
        myFixture.addFileToProject("cs1/megatron/beta.folders", "")
        myFixture.addFileToProject("cs1/megatron/Alpha.filters", "")
        myFixture.addFileToProject("cs1/megatron/alpha.folders", "")
        myFixture.addFileToProject("cs1/megatron/readme.txt", "")
        myFixture.addFileToProject("cs1/megatron/sub/nested.filters", "")
        val rootDir = requireNotNull(myFixture.findFileInTempDir("cs1"))
        val names = ConfigSetManager(project, rootDir).setNames()
        assertEquals(listOf("alpha", "beta"), names.map { it.lowercase() })
    }

    fun testEffectiveSetFallbackChain() {
        myFixture.addFileToProject("cs2/megatron/aaa.filters", "")
        myFixture.addFileToProject("cs2/megatron/bbb.filters", "")
        val rootDir = requireNotNull(myFixture.findFileInTempDir("cs2"))
        val sets = ConfigSetManager(project, rootDir)
        val state = MegatronFilterState.getInstance(project)
        try {
            state.setActiveSet("BBB")
            assertEquals("bbb", sets.effectiveSet().lowercase())
            state.setActiveSet("gone")
            assertEquals("aaa", sets.effectiveSet().lowercase())
        } finally {
            state.setActiveSet("default")
        }
    }

    fun testEffectiveSetWithNoSetsIsDefault() {
        myFixture.addFileToProject("cs3/main.cpp", "")
        val rootDir = requireNotNull(myFixture.findFileInTempDir("cs3"))
        assertEquals("default", ConfigSetManager(project, rootDir).effectiveSet())
        assertEmpty(ConfigSetManager(project, rootDir).setNames())
    }

    fun testCreateDefaultSetWritesParseableDocumentedFiles() {
        myFixture.addFileToProject("cs4/main.cpp", "")
        val rootDir = requireNotNull(myFixture.findFileInTempDir("cs4"))
        val sets = ConfigSetManager(project, rootDir)

        sets.createDefaultSet()

        val filters = requireNotNull(rootDir.findFileByRelativePath("megatron/default.filters"))
        val folders = requireNotNull(rootDir.findFileByRelativePath("megatron/default.folders"))
        val groups = parseFilterFile(String(filters.contentsToByteArray(), filters.charset))
        assertEquals(1, groups.size)
        assertEquals("Sources", groups[0].name)
        val layout = parseFoldersFile(String(folders.contentsToByteArray(), folders.charset))
        assertEmpty(layout.folders)
        assertTrue(layout.header.isNotEmpty())
        assertEquals(listOf("default"), sets.setNames().map { it.lowercase() })
    }

    fun testFileResolutionFindsEffectiveSetsFiles() {
        myFixture.addFileToProject("cs5/megatron/one.filters", "Docs: *.md")
        myFixture.addFileToProject("cs5/megatron/two.filters", "Sources: *.cpp")
        val rootDir = requireNotNull(myFixture.findFileInTempDir("cs5"))
        val sets = ConfigSetManager(project, rootDir)
        val state = MegatronFilterState.getInstance(project)
        try {
            state.setActiveSet("two")
            assertEquals("two.filters", sets.filtersFile()?.name)
            assertNull(sets.foldersFile())
        } finally {
            state.setActiveSet("default")
        }
    }
}
