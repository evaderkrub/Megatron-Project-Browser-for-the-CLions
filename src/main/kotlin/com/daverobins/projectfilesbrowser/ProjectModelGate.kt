package com.daverobins.projectfilesbrowser

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.cidr.lang.workspace.OCWorkspace
import com.jetbrains.cidr.lang.workspace.OCWorkspaceListener

/**
 * Answers whether files belong to the IDE's native project model (CMake targets
 * in CLion). Inactive until the model has loaded at least one configuration.
 * Isolated behind an interface so the engine stays testable without CLion APIs.
 */
interface ProjectModelGate {
    fun isActive(): Boolean
    fun isInModel(file: VirtualFile): Boolean
}

/** Real gate backed by CLion's shared project model (works under classic and Nova engines). */
class OcWorkspaceGate(private val project: Project) : ProjectModelGate {

    override fun isActive(): Boolean =
        OCWorkspace.getInstance(project).configurations.isNotEmpty()

    override fun isInModel(file: VirtualFile): Boolean =
        OCWorkspace.getInstance(project).getConfigurationsForFile(file).isNotEmpty()

    /** Refreshes the tree when the project model (re)loads. */
    fun subscribe(parentDisposable: Disposable, onModelChanged: () -> Unit) {
        project.messageBus.connect(parentDisposable).subscribe(
            OCWorkspaceListener.TOPIC,
            object : OCWorkspaceListener {
                override fun workspaceChanged(event: OCWorkspaceListener.OCWorkspaceEvent) {
                    onModelChanged()
                }

                override fun workspaceInitializationFinished(success: Boolean) {
                    onModelChanged()
                }
            },
        )
    }
}
