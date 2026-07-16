package com.daverobins.projectfilesbrowser

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.treeStructure.SimpleNode

internal const val OPEN_TABS_CONFIRM_THRESHOLD = 20

private val HEADER_EXTENSIONS = setOf("h", "hh", "hpp", "hxx")
private val SOURCE_EXTENSIONS = setOf("c", "cc", "cpp", "cxx")

private fun counterpartExtensions(extension: String): Set<String>? {
    val lower = extension.lowercase()
    return when {
        lower in HEADER_EXTENSIONS -> SOURCE_EXTENSIONS
        lower in SOURCE_EXTENSIONS -> HEADER_EXTENSIONS
        else -> null
    }
}

/**
 * The header/source counterpart of [relativePath] among [candidates] (relative
 * paths): opposite extension family, same base name (case-insensitive). Same
 * directory wins; else the candidate sharing the most leading path segments
 * with the file's directory; ties broken by case-insensitive path order.
 */
internal fun findCounterpart(relativePath: String, candidates: Collection<String>): String? {
    val targets = counterpartExtensions(relativePath.substringAfterLast('.', "")) ?: return null
    val base = relativePath.substringAfterLast('/').substringBeforeLast('.').lowercase()
    val dir = relativePath.substringBeforeLast('/', "")
    val matches = candidates.filter { candidate ->
        candidate.substringAfterLast('.', "").lowercase() in targets &&
            candidate.substringAfterLast('/').substringBeforeLast('.').lowercase() == base
    }
    if (matches.isEmpty()) return null
    matches.firstOrNull { it.substringBeforeLast('/', "").equals(dir, ignoreCase = true) }
        ?.let { return it }
    return matches.sortedWith(
        compareByDescending<String> { sharedLeadingSegments(dir, it.substringBeforeLast('/', "")) }
            .thenBy { it.lowercase() }
    ).first()
}

private fun sharedLeadingSegments(a: String, b: String): Int {
    if (a.isEmpty() || b.isEmpty()) return 0
    val left = a.lowercase().split('/')
    val right = b.lowercase().split('/')
    var i = 0
    while (i < left.size && i < right.size && left[i] == right[i]) i++
    return i
}

/** Resolves the counterpart of [file] among the currently visible project files. */
internal fun findCounterpartFile(file: VirtualFile, rootDir: VirtualFile, engine: FilterEngine): VirtualFile? {
    val prefix = rootDir.path + "/"
    if (!file.path.startsWith(prefix)) return null
    val byRelativePath = visibleFilesUnder(rootDir, engine).associateBy { it.path.removePrefix(prefix) }
    val match = findCounterpart(file.path.removePrefix(prefix), byRelativePath.keys) ?: return null
    return byRelativePath[match]
}

/**
 * Every non-directory file the tree would show under [nodes], recursively —
 * walking the nodes' own children so visibility, exclusions, and folder
 * resolution all apply. Deduplicated, in tree order.
 */
internal fun collectFilesUnder(nodes: List<SimpleNode>): List<VirtualFile> {
    val out = LinkedHashSet<VirtualFile>()
    fun walk(node: SimpleNode) {
        if (node is FileNode && !node.file.isDirectory) {
            out.add(node.file)
            return
        }
        for (child in node.children) walk(child)
    }
    nodes.forEach(::walk)
    return out.toList()
}

/** The filtered file walk (same rules as flat mode's collector). */
internal fun visibleFilesUnder(rootDir: VirtualFile, engine: FilterEngine): List<VirtualFile> {
    val out = ArrayList<VirtualFile>()
    fun walk(dir: VirtualFile) {
        for (child in dir.children ?: return) {
            if (!child.isValid) continue
            if (child.isDirectory) {
                if (FileFilter.includeDirectory(child.name)) walk(child)
            } else if (engine.isFileVisible(child)) {
                out.add(child)
            }
        }
    }
    walk(rootDir)
    return out
}
