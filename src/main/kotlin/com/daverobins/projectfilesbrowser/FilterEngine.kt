package com.daverobins.projectfilesbrowser

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException

/**
 * Panel-owned facade over megatron.filters: caches the parsed groups by the
 * file's VFS modification stamp and combines them with the per-project toggle
 * state to answer visibility queries.
 */
class FilterEngine(private val project: Project, private val rootDir: VirtualFile) {

    private var cachedStamp = NO_FILE_STAMP
    private var cachedGroups: List<FilterGroup> = emptyList()

    /** Group/default visibility only — no project-model gating. Used by the VFS watcher. */
    fun isGroupVisible(relativePath: String, fileName: String): Boolean =
        visibleByGroups(enabledGroups(), relativePath, fileName)

    fun isFileVisible(relativePath: String, fileName: String): Boolean =
        isGroupVisible(relativePath, fileName)

    fun groupsForUi(): List<Pair<String, Boolean>> {
        val state = MegatronFilterState.getInstance(project)
        return groups().map { it.name to state.isEnabled(it.name) }
    }

    private fun enabledGroups(): List<FilterGroup> {
        val state = MegatronFilterState.getInstance(project)
        return groups().filter { state.isEnabled(it.name) }
    }

    @Synchronized
    private fun groups(): List<FilterGroup> {
        val file = rootDir.findChild(FILTER_FILE_NAME)
        if (file == null || file.isDirectory || !file.isValid) {
            cachedStamp = NO_FILE_STAMP
            cachedGroups = emptyList()
            return cachedGroups
        }
        if (file.modificationStamp != cachedStamp) {
            cachedGroups = parseFilterFile(loadText(file))
            cachedStamp = file.modificationStamp
        }
        return cachedGroups
    }

    private fun loadText(file: VirtualFile): String =
        try {
            String(file.contentsToByteArray(), file.charset)
        } catch (e: IOException) {
            logger<FilterEngine>().warn("Failed to read ${file.path}", e)
            ""
        }

    companion object {
        const val FILTER_FILE_NAME = "megatron.filters"
        private const val NO_FILE_STAMP = -1L
    }
}
