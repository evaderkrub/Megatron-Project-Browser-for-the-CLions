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
        if (isFilterFileEvent(rootPath, oldPath, event.path)) return true
        return when (event) {
            is VFileContentChangeEvent -> false
            is VFileCreateEvent -> isRelevantPath(rootPath, event.path, event.isDirectory)
            is VFileDeleteEvent -> isRelevantPath(rootPath, event.path, event.file.isDirectory)
            is VFileCopyEvent -> isRelevantPath(rootPath, event.path, event.file.isDirectory)
            is VFileMoveEvent ->
                isRelevantEitherPath(rootPath, event.oldPath, event.newPath, event.file.isDirectory)
            is VFilePropertyChangeEvent ->
                event.propertyName == VirtualFile.PROP_NAME &&
                    isRelevantEitherPath(rootPath, event.oldPath, event.newPath, event.file.isDirectory)
            else -> true // unknown event type: rebuild conservatively rather than miss a change
        }
    }

    companion object {
        const val DEBOUNCE_MS = 500

        /** Events touching <root>/megatron.filters always trigger a refresh —
         *  including content changes, since the file's content defines the filters. */
        fun isFilterFileEvent(rootPath: String, oldPath: String?, newPath: String): Boolean {
            val filterFilePath = "$rootPath/${FilterEngine.FILTER_FILE_NAME}"
            return newPath == filterFilePath || oldPath == filterFilePath
        }

        fun isRelevantPath(rootPath: String, path: String, isDirectory: Boolean): Boolean {
            if (path == rootPath) return true
            val prefix = "$rootPath/"
            if (!path.startsWith(prefix)) return false
            val segments = path.removePrefix(prefix).split('/')
            for (i in 0 until segments.size - 1) {
                if (!FileFilter.includeDirectory(segments[i])) return false
            }
            val leaf = segments.last()
            return if (isDirectory) FileFilter.includeDirectory(leaf) else FileFilter.includeFile(leaf)
        }

        fun isRelevantEitherPath(
            rootPath: String,
            oldPath: String?,
            newPath: String,
            isDirectory: Boolean,
        ): Boolean =
            isRelevantPath(rootPath, newPath, isDirectory) ||
                (oldPath != null && isRelevantPath(rootPath, oldPath, isDirectory))
    }
}
