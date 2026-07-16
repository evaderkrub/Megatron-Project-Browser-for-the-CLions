package com.daverobins.projectfilesbrowser

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException

/**
 * Caches parsed bookmarks per file, keyed on modification stamp. Prefers the
 * in-memory document (unsaved edits included) over disk bytes. Called during
 * tree builds (background thread, inside a read action).
 */
class BookmarkScanner {

    private data class Entry(val stamp: Long, val fromDocument: Boolean, val bookmarks: List<Bookmark>)

    private val cache = HashMap<String, Entry>()

    @Synchronized
    fun bookmarksIn(file: VirtualFile): List<Bookmark> {
        if (file.length > MAX_SIZE_BYTES) {
            cache.remove(file.path)
            return emptyList()
        }
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        val stamp = document?.modificationStamp ?: file.modificationStamp
        val fromDocument = document != null
        val cached = cache[file.path]
        if (cached != null && cached.stamp == stamp && cached.fromDocument == fromDocument) {
            return cached.bookmarks
        }
        val bookmarks = parseBookmarks(document?.text ?: loadText(file))
        cache[file.path] = Entry(stamp, fromDocument, bookmarks)
        return bookmarks
    }

    /** True when the last scan of [path] found bookmarks — detects marker deletion cheaply. */
    @Synchronized
    fun hadBookmarks(path: String): Boolean = cache[path]?.bookmarks?.isNotEmpty() == true

    private fun loadText(file: VirtualFile): String =
        try {
            String(file.contentsToByteArray(), file.charset)
        } catch (e: IOException) {
            logger<BookmarkScanner>().warn("Failed to read ${file.path}", e)
            ""
        }

    companion object {
        private const val MAX_SIZE_BYTES = 1L shl 20 // skip files over 1 MB
    }
}
