package com.daverobins.projectfilesbrowser

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.Project

/**
 * Toolbar popup listing filter groups from megatron.filters as checkbox
 * toggles. Children are computed per-show so file edits are reflected
 * without any registration dance.
 */
class FilterDropdownAction(
    private val project: Project,
    private val engine: FilterEngine,
    private val onFilterChanged: () -> Unit,
) : ActionGroup("Filters", "Toggle filter groups", AllIcons.General.Filter) {

    init {
        isPopup = true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val groups = engine.groupsForUi()
        if (groups.isEmpty()) {
            return arrayOf(NoFiltersInfoAction())
        }
        return groups.map { (name, _) -> GroupToggleAction(name) }.toTypedArray()
    }

    private inner class GroupToggleAction(private val groupName: String) : ToggleAction(groupName) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun isSelected(e: AnActionEvent): Boolean =
            MegatronFilterState.getInstance(project).isEnabled(groupName)

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            MegatronFilterState.getInstance(project).setEnabled(groupName, state)
            onFilterChanged()
        }
    }

    private class NoFiltersInfoAction : AnAction("No megatron.filters — using defaults") {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = false
        }

        override fun actionPerformed(e: AnActionEvent) = Unit
    }
}
