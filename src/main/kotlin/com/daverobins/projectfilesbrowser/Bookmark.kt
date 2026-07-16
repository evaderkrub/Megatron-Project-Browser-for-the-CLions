package com.daverobins.projectfilesbrowser

/** One bookmark comment: 0-based [line], optional [setName], quoted [title]. */
data class Bookmark(val line: Int, val setName: String?, val title: String)

/** Shown when the marker names no set, or names the active set (case-insensitive). */
fun Bookmark.visibleInSet(activeSet: String): Boolean =
    setName == null || setName.equals(activeSet, ignoreCase = true)

/** The word that identifies bookmark comments; used for cheap change detection. */
const val BOOKMARK_MARKER_WORD = "megatron"

// Comment prefix ('//' or '#'), 'megatron', optional '/set', ':', quoted title.
// Anchored at line start (after indentation) so markers behind code don't count.
private val MARKER = Regex(
    """^\s*(?://|#)\s*$BOOKMARK_MARKER_WORD(?:/([^:"]*))?\s*:\s*"([^"]*)\"""",
    RegexOption.IGNORE_CASE,
)

/**
 * Extracts bookmark comments from file text. Lines without a parsable quoted
 * title are ignored; a blank set name is treated as no set.
 */
fun parseBookmarks(text: String): List<Bookmark> {
    val result = ArrayList<Bookmark>()
    text.lineSequence().forEachIndexed { index, line ->
        val match = MARKER.find(line) ?: return@forEachIndexed
        val set = match.groupValues[1].trim().takeIf { it.isNotEmpty() }
        result.add(Bookmark(index, set, match.groupValues[2]))
    }
    return result
}

/** Text and caret column for a new bookmark line inserted above the caret line. */
data class BookmarkInsertion(val lineText: String, val caretColumn: Int)

/** Builds the inserted line: caret line's indentation, file-appropriate prefix, empty title. */
fun bookmarkInsertion(caretLineText: String, fileName: String, activeSet: String): BookmarkInsertion {
    val indent = caretLineText.takeWhile { it == ' ' || it == '\t' }
    val prefix = if (isCMakeFile(fileName)) "#" else "//"
    val line = "$indent$prefix $BOOKMARK_MARKER_WORD/$activeSet: \"\""
    return BookmarkInsertion(line, line.length - 1)
}

private fun isCMakeFile(fileName: String): Boolean =
    fileName.equals("CMakeLists.txt", ignoreCase = true) ||
        fileName.endsWith(".cmake", ignoreCase = true)
