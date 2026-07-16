package com.daverobins.projectfilesbrowser

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.Project

/** Toolbar toggle between the directory tree and the flat file list. */
class FlatViewToggleAction(
    private val project: Project,
    private val onModeChanged: () -> Unit,
) : ToggleAction("Flat View", "Show all files as a flat list", AllIcons.Actions.ListFiles) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean =
        MegatronFilterState.getInstance(project).getViewMode() == ViewMode.FLAT

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        MegatronFilterState.getInstance(project)
            .setViewMode(if (state) ViewMode.FLAT else ViewMode.TREE)
        onModeChanged()
    }
}
