package com.daverobins.projectfilesbrowser

/** A file explicitly assigned to a virtual folder: original-case relative path plus owning folder path. */
data class FileAssignment(val path: String, val folder: String)

/**
 * A pattern or exclusion line from megatron.folders. [raw] is the normalized
 * line text (without the leading '!' for exclusions); [folder] is the block it
 * was declared under. Exclusions are global in effect — [folder] only controls
 * where they serialize. Glob semantics are shared with megatron.filters.
 */
data class FolderRule(val raw: String, val folder: String, val isExclusion: Boolean) {
    private val glob = GlobPattern(raw)

    /** True when the rule names one exact path (no wildcards) — the only kind the UI writes. */
    val isExactPath: Boolean = '*' !in raw && '?' !in raw

    fun matches(relativePath: String): Boolean =
        glob.matches(relativePath, relativePath.substringAfterLast('/'))
}

/**
 * Immutable model of megatron.folders: the user's virtual-folder tree, explicit
 * file assignments, and pattern/exclusion rules. Folder paths use '/' separators
 * ("Core/Math"); all comparisons are case-insensitive with the first-declared
 * casing winning for display. One folder per file, resolved by precedence:
 * explicit entry (last duplicate wins) > any matching exclusion > matching
 * pattern latest in the file.
 */
class FolderLayout(
    folders: List<String> = emptyList(),
    assignments: List<FileAssignment> = emptyList(),
    rules: List<FolderRule> = emptyList(),
) {

    /** Canonical folder paths (parents auto-created), first-declared casing, declaration order. */
    val folders: List<String>

    /** Pattern and exclusion lines in declaration order (order = pattern precedence). */
    val rules: List<FolderRule>

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
        val normalizedRules = ArrayList<FolderRule>()
        for (rule in rules) {
            val raw = normalizeFilePath(rule.raw)
            if (raw.isEmpty()) continue
            val folder = canonicalize(rule.folder) ?: continue
            normalizedRules.add(FolderRule(raw, folder, rule.isExclusion))
        }
        val files = LinkedHashMap<String, FileAssignment>()
        for (assignment in assignments) {
            val path = normalizeFilePath(assignment.path)
            if (path.isEmpty()) continue
            val folder = canonicalize(assignment.folder) ?: continue
            files[path.lowercase()] = FileAssignment(path, folder)
        }
        this.folders = canonical.values.toList()
        this.rules = normalizedRules
        this.byFile = files
    }

    /** Full precedence: explicit entry, else rule resolution. */
    fun folderFor(relativePath: String): String? {
        val norm = normalizeFilePath(relativePath)
        byFile[norm.lowercase()]?.let { return it.folder }
        return patternFolderFor(norm)
    }

    /** Rule-only resolution (ignores explicit entries): exclusions veto, last matching pattern wins. */
    fun patternFolderFor(relativePath: String): String? {
        val norm = normalizeFilePath(relativePath)
        if (rules.any { it.isExclusion && it.matches(norm) }) return null
        return rules.lastOrNull { !it.isExclusion && it.matches(norm) }?.folder
    }

    /** Original-case paths of files EXPLICITLY assigned to [folder], sorted. */
    fun filesIn(folder: String): List<String> =
        byFile.values.filter { it.folder.equals(folder, ignoreCase = true) }
            .map { it.path }
            .sortedBy { it.lowercase() }

    /** Rules declared under [folder], in declaration order. */
    fun rulesIn(folder: String): List<FolderRule> =
        rules.filter { it.folder.equals(folder, ignoreCase = true) }

    /** Direct child folders of [parent] ("" = top level), sorted by display name. */
    fun childFolders(parent: String): List<String> =
        folders.filter { it.substringBeforeLast('/', "").equals(parent, ignoreCase = true) }
            .sortedBy { it.substringAfterLast('/').lowercase() }

    /** Every folder path, sorted — stable order for menus. */
    fun allFolders(): List<String> = folders.sortedBy { it.lowercase() }

    fun hasFolder(path: String): Boolean = folders.any { it.equals(path, ignoreCase = true) }

    fun withFolder(path: String): FolderLayout =
        FolderLayout(folders + path, byFile.values.toList(), rules)

    /** Assigns explicitly; also removes exact-path exclusions that pinned this file out. */
    fun withAssignment(relativePath: String, folder: String): FolderLayout {
        val norm = normalizeFilePath(relativePath)
        val keptRules = rules.filterNot {
            it.isExclusion && it.isExactPath && it.raw.equals(norm, ignoreCase = true)
        }
        return FolderLayout(folders, byFile.values.toList() + FileAssignment(relativePath, folder), keptRules)
    }

    /**
     * Sends the file to <Unassigned>: removes its explicit entry, and when a
     * pattern would still claim it, adds an exact-path exclusion under the
     * claiming folder.
     */
    fun withUnassigned(relativePath: String): FolderLayout {
        val norm = normalizeFilePath(relativePath)
        val key = norm.lowercase()
        val remaining = byFile.values.filterNot { it.path.lowercase() == key }
        val claiming = patternFolderFor(norm)
        val newRules =
            if (claiming != null) rules + FolderRule(norm, claiming, isExclusion = true)
            else rules
        return FolderLayout(folders, remaining, newRules)
    }

    fun withFolderRenamed(path: String, newName: String): FolderLayout {
        val parent = path.substringBeforeLast('/', "")
        val newPath = if (parent.isEmpty()) newName else "$parent/$newName"
        return FolderLayout(
            folders.map { remapped(it, path, newPath) },
            byFile.values.map { it.copy(folder = remapped(it.folder, path, newPath)) },
            rules.map { it.copy(folder = remapped(it.folder, path, newPath)) },
        )
    }

    fun withFolderDeleted(path: String): FolderLayout =
        FolderLayout(
            folders.filterNot { inSubtree(it, path) },
            byFile.values.filterNot { inSubtree(it.folder, path) },
            rules.filterNot { inSubtree(it.folder, path) },
        )

    /** Folders in declaration order; rules (declaration order) then sorted explicit files per block. */
    fun serialize(): String {
        val sb = StringBuilder()
        for (folder in folders) {
            sb.append(folder).append("/\n")
            for (rule in rulesIn(folder)) {
                sb.append("  ")
                if (rule.isExclusion) sb.append('!')
                sb.append(rule.raw).append('\n')
            }
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

/**
 * Parses megatron.folders text. Line kinds inside a folder block: `!rest` is an
 * exclusion, a line containing '*' or '?' is a pattern, anything else is an
 * explicit file path. Unparseable lines (including any rule/file line before
 * the first folder declaration, and bare `!`) are silently skipped.
 */
fun parseFoldersFile(text: String): FolderLayout {
    val folders = ArrayList<String>()
    val assignments = ArrayList<FileAssignment>()
    val rules = ArrayList<FolderRule>()
    var currentFolder: String? = null
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val normalized = line.replace('\\', '/')
        when {
            normalized.endsWith("/") -> {
                val cleaned = normalized.split('/').map { it.trim() }.filter { it.isNotEmpty() }
                    .joinToString("/")
                if (cleaned.isEmpty()) continue
                folders.add(cleaned)
                currentFolder = cleaned
            }
            normalized.startsWith("!") -> {
                val folder = currentFolder ?: continue
                val rest = normalized.substring(1).trim()
                if (rest.isEmpty()) continue
                rules.add(FolderRule(rest, folder, isExclusion = true))
            }
            '*' in normalized || '?' in normalized -> {
                val folder = currentFolder ?: continue
                rules.add(FolderRule(normalized, folder, isExclusion = false))
            }
            else -> {
                val folder = currentFolder ?: continue
                assignments.add(FileAssignment(normalized, folder))
            }
        }
    }
    return FolderLayout(folders, assignments, rules)
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
