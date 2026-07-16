package com.daverobins.projectfilesbrowser

/** A file assigned to a virtual folder: original-case relative path plus owning folder path. */
data class FileAssignment(val path: String, val folder: String)

/**
 * Immutable model of megatron.folders: the user's virtual-folder tree plus file
 * assignments. Folder paths use '/' separators ("Core/Math"); all comparisons are
 * case-insensitive with the first-declared casing winning for display. One folder
 * per file: a later assignment replaces an earlier one.
 */
class FolderLayout(
    folders: List<String> = emptyList(),
    assignments: List<FileAssignment> = emptyList(),
) {

    /** Canonical folder paths (parents auto-created), first-declared casing, declaration order. */
    val folders: List<String>

    private val byFile: Map<String, FileAssignment> // key: lowercase normalized relative path

    init {
        val canonical = LinkedHashMap<String, String>() // lowercase path -> display path
        fun canonicalize(rawPath: String): String? {
            val segments = rawPath.replace('\\', '/').split('/')
                .map { it.trim() }.filter { it.isNotEmpty() }
            if (segments.isEmpty()) return null
            var lower = ""
            var display = ""
            for (segment in segments) {
                lower = if (lower.isEmpty()) segment.lowercase() else "$lower/${segment.lowercase()}"
                val existing = canonical[lower]
                if (existing == null) {
                    display = if (display.isEmpty()) segment else "$display/$segment"
                    canonical[lower] = display
                } else {
                    display = existing
                }
            }
            return display
        }
        folders.forEach { canonicalize(it) }
        val files = LinkedHashMap<String, FileAssignment>()
        for (assignment in assignments) {
            val path = normalizeFilePath(assignment.path)
            if (path.isEmpty()) continue
            val folder = canonicalize(assignment.folder) ?: continue
            files[path.lowercase()] = FileAssignment(path, folder)
        }
        this.folders = canonical.values.toList()
        this.byFile = files
    }

    fun folderFor(relativePath: String): String? =
        byFile[normalizeFilePath(relativePath).lowercase()]?.folder

    /** Lowercase normalized relative paths of every assigned file (for tree exclusion). */
    fun assignedFilesLowercase(): Set<String> = byFile.keys

    /** Original-case paths of files assigned directly to [folder], sorted. */
    fun filesIn(folder: String): List<String> =
        byFile.values.filter { it.folder.equals(folder, ignoreCase = true) }
            .map { it.path }
            .sortedBy { it.lowercase() }

    /** Direct child folders of [parent] ("" = top level), sorted by display name. */
    fun childFolders(parent: String): List<String> =
        folders.filter { it.substringBeforeLast('/', "").equals(parent, ignoreCase = true) }
            .sortedBy { it.substringAfterLast('/').lowercase() }

    /** Every folder path, sorted — stable order for serialization and menus. */
    fun allFolders(): List<String> = folders.sortedBy { it.lowercase() }

    fun hasFolder(path: String): Boolean = folders.any { it.equals(path, ignoreCase = true) }

    fun withFolder(path: String): FolderLayout =
        FolderLayout(folders + path, byFile.values.toList())

    fun withAssignment(relativePath: String, folder: String): FolderLayout =
        FolderLayout(folders, byFile.values.toList() + FileAssignment(relativePath, folder))

    fun withUnassigned(relativePath: String): FolderLayout {
        val key = normalizeFilePath(relativePath).lowercase()
        return FolderLayout(folders, byFile.values.filterNot { it.path.lowercase() == key })
    }

    fun withFolderRenamed(path: String, newName: String): FolderLayout {
        val parent = path.substringBeforeLast('/', "")
        val newPath = if (parent.isEmpty()) newName else "$parent/$newName"
        return FolderLayout(
            folders.map { remapped(it, path, newPath) },
            byFile.values.map { it.copy(folder = remapped(it.folder, path, newPath)) },
        )
    }

    fun withFolderDeleted(path: String): FolderLayout =
        FolderLayout(
            folders.filterNot { inSubtree(it, path) },
            byFile.values.filterNot { inSubtree(it.folder, path) },
        )

    fun serialize(): String {
        val sb = StringBuilder()
        for (folder in allFolders()) {
            sb.append(folder).append("/\n")
            for (file in filesIn(folder)) sb.append("  ").append(file).append('\n')
        }
        return sb.toString()
    }

    companion object {
        private fun normalizeFilePath(path: String): String =
            path.replace('\\', '/').trim().trimStart('/').trimEnd('/')

        private fun inSubtree(candidate: String, root: String): Boolean =
            candidate.equals(root, ignoreCase = true) ||
                candidate.lowercase().startsWith(root.lowercase() + "/")

        private fun remapped(candidate: String, oldRoot: String, newRoot: String): String =
            when {
                candidate.equals(oldRoot, ignoreCase = true) -> newRoot
                candidate.lowercase().startsWith(oldRoot.lowercase() + "/") ->
                    newRoot + candidate.substring(oldRoot.length)
                else -> candidate
            }
    }
}

/** Parses megatron.folders text. Unparseable lines are silently skipped. */
fun parseFoldersFile(text: String): FolderLayout {
    val folders = ArrayList<String>()
    val assignments = ArrayList<FileAssignment>()
    var currentFolder: String? = null
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val normalized = line.replace('\\', '/')
        if (normalized.endsWith("/")) {
            val cleaned = normalized.split('/').map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString("/")
            if (cleaned.isEmpty()) continue
            folders.add(cleaned)
            currentFolder = cleaned
        } else {
            val folder = currentFolder ?: continue // file line before any folder: malformed, skip
            assignments.add(FileAssignment(normalized, folder))
        }
    }
    return FolderLayout(folders, assignments)
}

/** Returns an error message, or null when [name] (after trimming) is a valid new sibling name. */
fun validateFolderName(name: String, siblingNames: Collection<String>): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "Folder name cannot be empty"
    if (trimmed.contains('/') || trimmed.contains('\\')) return "Folder name cannot contain slashes"
    if (siblingNames.any { it.equals(trimmed, ignoreCase = true) }) {
        return "A folder named '$trimmed' already exists here"
    }
    return null
}
