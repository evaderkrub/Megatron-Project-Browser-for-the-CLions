package com.daverobins.projectfilesbrowser

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FolderLayoutStoreTest : BasePlatformTestCase() {

    fun testLayoutIsEmptyWithoutFile() {
        myFixture.addFileToProject("s1/src/a.cpp", "")
        val rootDir = requireNotNull(myFixture.findFileInTempDir("s1"))
        val store = FolderLayoutStore(project, rootDir)
        assertEmpty(store.layout().folders)
    }

    fun testMutateCreatesFileAndRewritesIt() {
        myFixture.addFileToProject("s2/src/a.cpp", "")
        val rootDir = requireNotNull(myFixture.findFileInTempDir("s2"))
        val store = FolderLayoutStore(project, rootDir)

        store.mutate { it.withFolder("Core").withAssignment("src/a.cpp", "Core") }

        val file = requireNotNull(rootDir.findChild(FolderLayoutStore.FOLDERS_FILE_NAME))
        assertEquals("Core/\n  src/a.cpp\n", String(file.contentsToByteArray(), file.charset))

        store.mutate { it.withAssignment("src/a.cpp", "Core/Sub") }
        assertEquals(
            "Core/\nCore/Sub/\n  src/a.cpp\n",
            String(file.contentsToByteArray(), file.charset),
        )
    }

    fun testLayoutReloadsAfterExternalEdit() {
        myFixture.addFileToProject("s3/megatron.folders", "Core/\n  a.cpp\n")
        val rootDir = requireNotNull(myFixture.findFileInTempDir("s3"))
        val store = FolderLayoutStore(project, rootDir)
        assertEquals("Core", store.layout().folderFor("a.cpp"))

        val file = requireNotNull(rootDir.findChild(FolderLayoutStore.FOLDERS_FILE_NAME))
        WriteAction.runAndWait<RuntimeException> {
            VfsUtil.saveText(file, "Base/\n  a.cpp\n")
        }
        assertEquals("Base", store.layout().folderFor("a.cpp"))
    }
}
