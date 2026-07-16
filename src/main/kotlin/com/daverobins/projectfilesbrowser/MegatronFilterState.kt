package com.daverobins.projectfilesbrowser

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** How the Megatron tree presents files. */
enum class ViewMode { TREE, FLAT, FOLDERS }

/**
 * Per-project toggle state for filter groups. Stores only the DISABLED group
 * names (in the workspace file, not in megatron.filters), so unknown/new
 * groups default to enabled.
 */
@Service(Service.Level.PROJECT)
@State(name = "MegatronFilters", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class MegatronFilterState : PersistentStateComponent<MegatronFilterState.State> {

    class State {
        var disabledGroups: MutableSet<String> = mutableSetOf()
        var viewMode: ViewMode = ViewMode.TREE
        var cmakeGateEnabled: Boolean = false
        var activeSet: String = "default"
    }

    @Volatile
    private var current = State()

    @Synchronized
    override fun getState(): State =
        State().apply {
            disabledGroups = current.disabledGroups.toMutableSet()
            viewMode = current.viewMode
            cmakeGateEnabled = current.cmakeGateEnabled
            activeSet = current.activeSet
        }

    @Synchronized
    override fun loadState(state: State) {
        current = state
    }

    @Synchronized
    fun isEnabled(name: String): Boolean = name !in current.disabledGroups

    @Synchronized
    fun setEnabled(name: String, enabled: Boolean) {
        if (enabled) current.disabledGroups.remove(name) else current.disabledGroups.add(name)
    }

    @Synchronized
    fun getViewMode(): ViewMode = current.viewMode

    @Synchronized
    fun setViewMode(mode: ViewMode) {
        current.viewMode = mode
    }

    @Synchronized
    fun isCmakeGateEnabled(): Boolean = current.cmakeGateEnabled

    @Synchronized
    fun setCmakeGateEnabled(enabled: Boolean) {
        current.cmakeGateEnabled = enabled
    }

    @Synchronized
    fun getActiveSet(): String = current.activeSet

    @Synchronized
    fun setActiveSet(name: String) {
        current.activeSet = name
    }

    companion object {
        fun getInstance(project: Project): MegatronFilterState = project.service()
    }
}
