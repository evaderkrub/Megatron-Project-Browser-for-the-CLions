package com.daverobins.projectfilesbrowser

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.project.Project
import javax.swing.JComponent

/**
 * Combo-style toolbar button (like the run-configuration picker) that always
 * shows the active config set and drops down to switch between the scanned
 * sets (recomputed per-show).
 */
class SetSwitcherAction(
    private val project: Project,
    private val sets: ConfigSetManager,
    private val onChanged: () -> Unit,
) : ComboBoxAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.text = sets.effectiveSet()
        e.presentation.description = "Switch Megatron config set"
    }

    override fun createPopupActionGroup(button: JComponent, dataContext: DataContext): DefaultActionGroup {
        val group = DefaultActionGroup()
        val names = sets.setNames()
        if (names.isEmpty()) {
            group.add(CreateDefaultSetAction())
        } else {
            for (name in names) group.add(SetToggleAction(name))
        }
        return group
    }

    private inner class SetToggleAction(private val name: String) : ToggleAction(name) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun isSelected(e: AnActionEvent): Boolean =
            name.equals(sets.effectiveSet(), ignoreCase = true)

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) {
                MegatronFilterState.getInstance(project).setActiveSet(name)
                onChanged()
            }
        }
    }

    private inner class CreateDefaultSetAction : AnAction("Create Default Set") {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) {
            sets.createDefaultSet()
            onChanged()
        }
    }
}
