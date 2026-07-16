package com.daverobins.projectfilesbrowser

/**
 * One wildcard filter compiled to a regex at construction.
 * A pattern containing '/' matches the project-relative path; one without '/'
 * matches the bare file name. `*` and `?` never cross '/', `**` does.
 */
class GlobPattern(pattern: String) {
    private val matchesPath = '/' in pattern
    private val regex = compile(pattern)

    fun matches(relativePath: String, fileName: String): Boolean =
        regex.matches(if (matchesPath) relativePath else fileName)

    private companion object {
        fun compile(pattern: String): Regex {
            val sb = StringBuilder()
            var i = 0
            while (i < pattern.length) {
                when {
                    pattern.startsWith("**", i) -> { sb.append(".*"); i += 2 }
                    pattern[i] == '*' -> { sb.append("[^/]*"); i++ }
                    pattern[i] == '?' -> { sb.append("[^/]"); i++ }
                    else -> { sb.append(Regex.escape(pattern[i].toString())); i++ }
                }
            }
            return Regex(sb.toString(), RegexOption.IGNORE_CASE)
        }
    }
}

class FilterGroup(val name: String, val patterns: List<GlobPattern>)

/**
 * Parses megatron.filters content: one `Name: pattern, pattern` per line,
 * `#` comments and blank lines ignored, malformed lines skipped,
 * duplicate names last-wins.
 */
fun parseFilterFile(text: String): List<FilterGroup> {
    val byName = LinkedHashMap<String, FilterGroup>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        val name = line.substring(0, colon).trim()
        if (name.isEmpty()) continue
        val patterns = line.substring(colon + 1)
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { GlobPattern(it) }
        if (patterns.isEmpty()) continue
        byName[name] = FilterGroup(name, patterns)
    }
    return byName.values.toList()
}

/**
 * A file is visible if it matches any pattern of any enabled group;
 * with no enabled groups the built-in defaults apply.
 */
fun visibleByGroups(
    enabledGroups: List<FilterGroup>,
    relativePath: String,
    fileName: String,
): Boolean =
    if (enabledGroups.isEmpty()) FileFilter.includeFile(fileName)
    else enabledGroups.any { group -> group.patterns.any { it.matches(relativePath, fileName) } }
