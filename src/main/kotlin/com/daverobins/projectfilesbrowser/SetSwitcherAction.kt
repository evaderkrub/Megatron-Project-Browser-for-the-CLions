package com.daverobins.projectfilesbrowser

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.Project

/**
 * Toolbar dropdown showing the effective config set; children are the scanned
 * sets as radio toggles (computed per-show, like the filter dropdown).
 */
class SetSwitcherAction(
    private val project: Project,
    private val sets: ConfigSetManager,
    private val onChanged: () -> Unit,
) : ActionGroup("Config Set", "Switch Megatron config set", null) {

    init {
        isPopup = true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.text = sets.effectiveSet()
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val names = sets.setNames()
        if (names.isEmpty()) return arrayOf(CreateDefaultSetAction())
        return names.map { SetToggleAction(it) }.toTypedArray()
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
