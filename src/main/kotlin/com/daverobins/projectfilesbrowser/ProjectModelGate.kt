package com.daverobins.projectfilesbrowser

import com.intellij.openapi.vfs.VirtualFile

/**
 * Answers whether files belong to the IDE's native project model (CMake targets
 * in CLion). Inactive until the model has loaded at least one configuration.
 * Isolated behind an interface so the engine stays testable without CLion APIs.
 */
interface ProjectModelGate {
    fun isActive(): Boolean
    fun isInModel(file: VirtualFile): Boolean
}
