package com.daverobins.projectfilesbrowser

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException

/**
 * Panel-owned facade over megatron.folders: caches the parsed layout by the
 * file's VFS modification stamp; mutations rewrite the file (single source of
 * truth) inside a write command. Mutations must run on the EDT.
 */
class FolderLayoutStore(
    private val project: Project,
    private val rootDir: VirtualFile,
) {

    private var cachedStamp = NO_FILE_STAMP
    private var cachedLayout = FolderLayout()

    @Synchronized
    fun layout(): FolderLayout {
        val file = rootDir.findChild(FOLDERS_FILE_NAME)
        if (file == null || file.isDirectory || !file.isValid) {
            cachedStamp = NO_FILE_STAMP
            cachedLayout = FolderLayout()
            return cachedLayout
        }
        if (file.modificationStamp != cachedStamp) {
            cachedLayout = parseFoldersFile(loadText(file))
            cachedStamp = file.modificationStamp
        }
        return cachedLayout
    }

    /** Applies [change] to the current layout and rewrites megatron.folders. */
    fun mutate(change: (FolderLayout) -> FolderLayout) {
        val updated = change(layout())
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                val file = rootDir.findChild(FOLDERS_FILE_NAME)
                    ?: rootDir.createChildData(this, FOLDERS_FILE_NAME)
                VfsUtil.saveText(file, updated.serialize())
            }
        } catch (e: IOException) {
            logger<FolderLayoutStore>().warn("Failed to write $FOLDERS_FILE_NAME", e)
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Megatron")
                .createNotification(
                    "Could not update $FOLDERS_FILE_NAME: ${e.message}",
                    NotificationType.ERROR,
                )
                .notify(project)
        }
    }

    private fun loadText(file: VirtualFile): String =
        try {
            String(file.contentsToByteArray(), file.charset)
        } catch (e: IOException) {
            logger<FolderLayoutStore>().warn("Failed to read ${file.path}", e)
            ""
        }

    companion object {
        const val FOLDERS_FILE_NAME = "megatron.folders"
        private const val NO_FILE_STAMP = -1L
    }
}
