package com.daverobins.projectfilesbrowser

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException

/**
 * Panel-owned facade over megatron.folders: caches the parsed layout by the
 * file's VFS modification stamp; mutations rewrite the file (single source of
 * truth) inside a write command. Mutations must run on the EDT.
 */
class FolderLayoutStore(
    private val project: Project,
    rootDir: VirtualFile,
) {

    private val sets = ConfigSetManager(project, rootDir)

    private var cachedKey: Pair<String, Long>? = null
    private var cachedLayout = FolderLayout()

    @Synchronized
    fun layout(): FolderLayout {
        val file = sets.foldersFile()
        if (file == null) {
            cachedKey = null
            cachedLayout = FolderLayout()
            return cachedLayout
        }
        val key = file.path to file.modificationStamp
        if (key != cachedKey) {
            cachedLayout = parseFoldersFile(loadText(file))
            cachedKey = key
        }
        return cachedLayout
    }

    /** Applies [change] to the current layout and rewrites the effective set's folders file. EDT only. */
    fun mutate(change: (FolderLayout) -> FolderLayout) {
        sets.writeFoldersFile(change(layout()).serialize())
    }

    private fun loadText(file: VirtualFile): String =
        try {
            String(file.contentsToByteArray(), file.charset)
        } catch (e: IOException) {
            logger<FolderLayoutStore>().warn("Failed to read ${file.path}", e)
            ""
        }

}
