package com.daverobins.projectfilesbrowser

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.Project

/** Toolbar toggle for the virtual-folders view. Mutually exclusive with flat view. */
class FolderViewToggleAction(
    private val project: Project,
    private val onModeChanged: () -> Unit,
) : ToggleAction("Folder View", "Group files into virtual folders from megatron.folders", AllIcons.Nodes.Folder) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean =
        MegatronFilterState.getInstance(project).getViewMode() == ViewMode.FOLDERS

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        MegatronFilterState.getInstance(project)
            .setViewMode(if (state) ViewMode.FOLDERS else ViewMode.TREE)
        onModeChanged()
    }
}
