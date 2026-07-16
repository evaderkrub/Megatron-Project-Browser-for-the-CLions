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
class FilterEngine(
    private val project: Project,
    private val rootDir: VirtualFile,
    private val gate: ProjectModelGate? = null,
) {

    private val sets = ConfigSetManager(project, rootDir)

    private var cachedKey: Pair<String, Long>? = null
    private var cachedGroups: List<FilterGroup> = emptyList()

    @Volatile
    private var quickFilter: QuickFilter? = null

    /** Sets the transient toolbar quick filter; blank text clears it. */
    fun setQuickFilter(text: String) {
        quickFilter = QuickFilter.parse(text)
    }

    /** Group/default visibility only — no project-model gating. Used by the VFS watcher. */
    fun isGroupVisible(relativePath: String, fileName: String): Boolean =
        visibleByGroups(enabledGroups(), relativePath, fileName)

    /** Full visibility: group filtering AND the quick filter AND (when enabled and active) the project-model gate. */
    fun isFileVisible(file: VirtualFile): Boolean {
        val relativePath = relativePath(file)
        if (!isGroupVisible(relativePath, file.name)) return false
        if (quickFilter?.matches(relativePath, file.name) == false) return false
        val activeGate = gate ?: return true
        if (!MegatronFilterState.getInstance(project).isCmakeGateEnabled()) return true
        if (!activeGate.isActive()) return true
        return activeGate.isInModel(file)
    }

    private fun relativePath(file: VirtualFile): String =
        file.path.removePrefix("${rootDir.path}/")

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
        val file = sets.filtersFile()
        if (file == null) {
            cachedKey = null
            cachedGroups = emptyList()
            return cachedGroups
        }
        val key = file.path to file.modificationStamp
        if (key != cachedKey) {
            cachedGroups = parseFilterFile(loadText(file))
            cachedKey = key
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

}
