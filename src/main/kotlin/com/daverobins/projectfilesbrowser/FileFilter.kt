package com.daverobins.projectfilesbrowser

/**
 * Decides which files and directories appear in the browser.
 * Operates on bare names (not paths). Hardcoded defaults in v1;
 * becomes settings-driven in a later phase.
 */
object FileFilter {

    private val allowedExtensions = setOf(
        "c", "cc", "cpp", "cxx", "h", "hh", "hpp", "hxx", "inl", "cmake",
    )
    private val allowedFileNames = setOf("cmakelists.txt")
    private val excludedDirNames = setOf(".git", ".idea", "build", "out", ".vs")
    private val excludedDirPrefixes = listOf("cmake-build-")

    fun includeFile(name: String): Boolean {
        val lower = name.lowercase()
        if (lower in allowedFileNames) return true
        val dot = lower.lastIndexOf('.')
        if (dot <= 0) return false // no extension, or dotfile like ".cpp"
        return lower.substring(dot + 1) in allowedExtensions
    }

    fun includeDirectory(name: String): Boolean {
        val lower = name.lowercase()
        if (lower in excludedDirNames) return false
        return excludedDirPrefixes.none { lower.startsWith(it) }
    }
}
