package com.daverobins.projectfilesbrowser

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

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
    }

    private var current = State()

    override fun getState(): State = current

    override fun loadState(state: State) {
        current = state
    }

    fun isEnabled(name: String): Boolean = name !in current.disabledGroups

    fun setEnabled(name: String, enabled: Boolean) {
        if (enabled) current.disabledGroups.remove(name) else current.disabledGroups.add(name)
    }

    companion object {
        fun getInstance(project: Project): MegatronFilterState = project.service()
    }
}
