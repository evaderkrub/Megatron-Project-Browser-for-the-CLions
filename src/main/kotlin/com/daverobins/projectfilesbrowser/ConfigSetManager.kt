package com.daverobins.projectfilesbrowser

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.io.IOException

/**
 * Resolves Megatron config sets: megatron/<set>.filters and megatron/<set>.folders
 * under the project root. Stateless — scans the directory on demand.
 */
class ConfigSetManager(private val project: Project, private val rootDir: VirtualFile) {

    fun megatronDir(): VirtualFile? =
        rootDir.findChild(DIR_NAME)?.takeIf { it.isDirectory && it.isValid }

    /** Base names of all config files: first-seen casing, sorted case-insensitively. */
    fun setNames(): List<String> =
        (megatronDir()?.children ?: VirtualFile.EMPTY_ARRAY)
            .filter { !it.isDirectory && it.isValid && it.extension?.lowercase() in CONFIG_EXTENSIONS }
            .map { it.nameWithoutExtension }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }

    /** The persisted set if it exists, else the first existing set, else "default". */
    fun effectiveSet(): String {
        val names = setNames()
        val persisted = MegatronFilterState.getInstance(project).getActiveSet()
        return names.firstOrNull { it.equals(persisted, ignoreCase = true) }
            ?: names.firstOrNull()
            ?: DEFAULT_SET
    }

    fun filtersFile(): VirtualFile? = configFile("${effectiveSet()}.$FILTERS_EXT")

    fun foldersFile(): VirtualFile? = configFile("${effectiveSet()}.$FOLDERS_EXT")

    private fun configFile(name: String): VirtualFile? =
        megatronDir()?.children?.firstOrNull {
            !it.isDirectory && it.isValid && it.name.equals(name, ignoreCase = true)
        }

    /** Rewrites the effective set's .folders file, creating megatron/ and the file as needed. EDT only. */
    fun writeFoldersFile(text: String) {
        writeConfigFile("${effectiveSet()}.$FOLDERS_EXT", text)
    }

    /** Creates the documented default set and opens both files in the editor. EDT only. */
    fun createDefaultSet() = createSet(DEFAULT_SET)

    /** Creates <name>.filters + <name>.folders from the documented templates and opens both. EDT only. */
    fun createSet(name: String) {
        writeConfigFile("$name.$FILTERS_EXT", DEFAULT_FILTERS_CONTENT)
        writeConfigFile("$name.$FOLDERS_EXT", DEFAULT_FOLDERS_CONTENT)
        val editors = FileEditorManager.getInstance(project)
        configFile("$name.$FOLDERS_EXT")?.let { editors.openFile(it, false) }
        configFile("$name.$FILTERS_EXT")?.let { editors.openFile(it, true) }
    }

    private fun writeConfigFile(name: String, text: String) {
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                val dir = rootDir.findChild(DIR_NAME)
                    ?: rootDir.createChildDirectory(this, DIR_NAME)
                val file = dir.children.firstOrNull { it.name.equals(name, ignoreCase = true) }
                    ?: dir.createChildData(this, name)
                VfsUtil.saveText(file, text)
            }
        } catch (e: IOException) {
            logger<ConfigSetManager>().warn("Failed to write $DIR_NAME/$name", e)
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Megatron")
                .createNotification("Could not write $DIR_NAME/$name: ${e.message}", NotificationType.ERROR)
                .notify(project)
        }
    }

    companion object {
        const val DIR_NAME = "megatron"
        const val DEFAULT_SET = "default"
        const val FILTERS_EXT = "filters"
        const val FOLDERS_EXT = "folders"
        private val CONFIG_EXTENSIONS = setOf(FILTERS_EXT, FOLDERS_EXT)

        val DEFAULT_FILTERS_CONTENT = """
            |# Megatron filter groups — megatron/<set>.filters
            |#
            |# One group per line:   Name: pattern, pattern, ...
            |# Toggle groups on and off from the funnel dropdown in the Megatron toolbar.
            |# A file is shown if it matches ANY pattern of ANY enabled group.
            |# With no groups (or every group off), built-in C/C++/CMake defaults apply.
            |#
            |# Pattern rules (case-insensitive):
            |#   *    matches within one path segment      (*.cpp)
            |#   ?    matches a single character           (test?.h)
            |#   **   crosses directory separators         (src/**)
            |#   A pattern containing '/' matches the project-relative path;
            |#   one without '/' matches the file name only.
            |
            |Sources: *.c, *.cc, *.cpp, *.cxx, *.h, *.hh, *.hpp, *.hxx, *.inl, CMakeLists.txt, *.cmake
            |""".trimMargin()

        val DEFAULT_FOLDERS_CONTENT = """
            |# Megatron virtual folders — megatron/<set>.folders
            |#
            |# Folder lines end with '/':      Core/        (nest with Core/Math/)
            |# Lines under a folder assign files to it:
            |#   src/engine.cpp        exact file (project-relative path)
            |#   src/**                glob pattern — auto-assigns matching files
            |#   !src/generated.cpp    exclusion — keeps a file out of pattern matches
            |#
            |# Precedence per file: exact entry beats exclusions, exclusions beat
            |# patterns, and among patterns the one LATEST in this file wins.
            |# One folder per file. Unclaimed files appear under <Unassigned> in
            |# Folder View. UI edits (right-click, drag-and-drop) rewrite this file
            |# but keep this comment header.
            |""".trimMargin()
    }
}

private const val INVALID_SET_NAME_CHARS = "\\/:*?\"<>|"

/** Returns an error message, or null when [name] (after trimming) is a valid new set name. */
fun validateSetName(name: String, existingSets: Collection<String>): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "Set name cannot be empty"
    if (trimmed.any { it in INVALID_SET_NAME_CHARS }) {
        return "Set name cannot contain ${INVALID_SET_NAME_CHARS.toList().joinToString(" ")}"
    }
    if (trimmed.startsWith(".")) return "Set name cannot start with a dot"
    if (existingSets.any { it.equals(trimmed, ignoreCase = true) }) {
        return "A set named '$trimmed' already exists"
    }
    return null
}
