package com.daverobins.projectfilesbrowser

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.util.SingleAlarm

/**
 * Watches VFS changes under [rootDir] and, after a debounce, invokes [onChange].
 * Irrelevant events (outside the root, inside excluded dirs, non-matching files,
 * content-only changes) never schedule a refresh, so build churn in
 * cmake-build-* is ignored entirely.
 */
class VfsChangeWatcher(
    project: Project,
    rootDir: VirtualFile,
    parentDisposable: Disposable,
    private val fileVisible: (String, String) -> Boolean,
    onChange: () -> Unit,
) {
    private val rootPath = rootDir.path
    private val alarm = SingleAlarm(Runnable { onChange() }, DEBOUNCE_MS, parentDisposable)

    init {
        project.messageBus.connect(parentDisposable)
            .subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { isRelevant(it) }) alarm.cancelAndRequest()
                }
            })
    }

    private fun isRelevant(event: VFileEvent): Boolean {
        val oldPath = when (event) {
            is VFileMoveEvent -> event.oldPath
            is VFilePropertyChangeEvent ->
                if (event.propertyName == VirtualFile.PROP_NAME) event.oldPath else null
            else -> null
        }
        if (isConfigFileEvent(rootPath, oldPath, event.path)) return true
        return when (event) {
            is VFileContentChangeEvent -> false
            is VFileCreateEvent -> isRelevantPath(rootPath, event.path, event.isDirectory, fileVisible)
            is VFileDeleteEvent -> isRelevantPath(rootPath, event.path, event.file.isDirectory, fileVisible)
            is VFileCopyEvent -> isRelevantPath(rootPath, event.path, event.file.isDirectory, fileVisible)
            is VFileMoveEvent ->
                isRelevantEitherPath(rootPath, event.oldPath, event.newPath, event.file.isDirectory, fileVisible)
            is VFilePropertyChangeEvent ->
                event.propertyName == VirtualFile.PROP_NAME &&
                    isRelevantEitherPath(rootPath, event.oldPath, event.newPath, event.file.isDirectory, fileVisible)
            else -> true // unknown event type: rebuild conservatively rather than miss a change
        }
    }

    companion object {
        const val DEBOUNCE_MS = 500

        /** Events touching <root>/megatron.filters or <root>/megatron.folders always
         *  trigger a refresh — including content changes, since those files' content
         *  defines what the tree shows. */
        fun isConfigFileEvent(rootPath: String, oldPath: String?, newPath: String): Boolean =
            CONFIG_FILE_NAMES.any { name ->
                val configPath = "$rootPath/$name"
                newPath.equals(configPath, ignoreCase = true) ||
                    (oldPath != null && oldPath.equals(configPath, ignoreCase = true))
            }

        private val CONFIG_FILE_NAMES =
            listOf(FilterEngine.FILTER_FILE_NAME, FolderLayoutStore.FOLDERS_FILE_NAME)

        /** Default: the built-in extension filter (used when no engine is in play, e.g. pure tests). */
        val builtInFileVisible: (String, String) -> Boolean = { _, name -> FileFilter.includeFile(name) }

        fun isRelevantPath(
            rootPath: String,
            path: String,
            isDirectory: Boolean,
            fileVisible: (String, String) -> Boolean = builtInFileVisible,
        ): Boolean {
            if (path == rootPath) return true
            val prefix = "$rootPath/"
            if (!path.startsWith(prefix)) return false
            val relative = path.removePrefix(prefix)
            val segments = relative.split('/')
            for (i in 0 until segments.size - 1) {
                if (!FileFilter.includeDirectory(segments[i])) return false
            }
            val leaf = segments.last()
            return if (isDirectory) FileFilter.includeDirectory(leaf) else fileVisible(relative, leaf)
        }

        fun isRelevantEitherPath(
            rootPath: String,
            oldPath: String?,
            newPath: String,
            isDirectory: Boolean,
            fileVisible: (String, String) -> Boolean = builtInFileVisible,
        ): Boolean =
            isRelevantPath(rootPath, newPath, isDirectory, fileVisible) ||
                (oldPath != null && isRelevantPath(rootPath, oldPath, isDirectory, fileVisible))
    }
}
