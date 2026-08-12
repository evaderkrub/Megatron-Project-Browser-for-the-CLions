package com.daverobins.projectfilesbrowser

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project

/**
 * Toolbar popup listing filter groups from megatron.filters as checkbox
 * toggles. Children are computed per-show so file edits are reflected
 * without any registration dance.
 */
class FilterDropdownAction(
    private val project: Project,
    private val engine: FilterEngine,
    private val sets: ConfigSetManager,
    private val onFilterChanged: () -> Unit,
) : ActionGroup("Filters", "Toggle filter groups", AllIcons.General.Filter) {

    init {
        isPopup = true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val head = arrayOf<AnAction>(CmakeGateToggleAction(), BookmarksToggleAction(), Separator.getInstance())
        val groups = engine.groupsForUi()
        val tail: Array<AnAction> =
            if (groups.isEmpty()) arrayOf(NoFiltersInfoAction())
            else groups.map { (name, _) -> GroupToggleAction(name) }.toTypedArray()
        return head + tail + arrayOf<AnAction>(Separator.getInstance(), EditFiltersAction())
    }

    private inner class EditFiltersAction :
        AnAction("Edit Filters…", "Open the active set's .filters file for editing", AllIcons.Actions.Edit) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = sets.filtersFile() != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val file = sets.filtersFile() ?: return
            OpenFileDescriptor(project, file).navigate(true)
        }
    }

    private inner class CmakeGateToggleAction :
        ToggleAction("Only CMake Project Files", "Show only files that belong to the CMake project model", null) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun isSelected(e: AnActionEvent): Boolean =
            MegatronFilterState.getInstance(project).isCmakeGateEnabled()

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            MegatronFilterState.getInstance(project).setCmakeGateEnabled(state)
            onFilterChanged()
        }
    }

    private inner class BookmarksToggleAction :
        ToggleAction(
            "Comment Bookmarks",
            "Scan files for megatron bookmark comments and show them in the tree",
            null,
        ) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun isSelected(e: AnActionEvent): Boolean =
            MegatronFilterState.getInstance(project).isBookmarksEnabled()

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            MegatronFilterState.getInstance(project).setBookmarksEnabled(state)
            onFilterChanged()
        }
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
