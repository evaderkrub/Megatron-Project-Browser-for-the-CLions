package com.daverobins.projectfilesbrowser

/**
 * The toolbar quick filter: a final AND-term on file visibility. Bare text
 * (no wildcards, no '/') means name-contains; anything else is one glob with
 * the megatron.filters rules. Blank input parses to null (filter off).
 */
class QuickFilter private constructor(private val glob: GlobPattern) {

    fun matches(relativePath: String, fileName: String): Boolean =
        glob.matches(relativePath, fileName)

    companion object {
        fun parse(text: String): QuickFilter? {
            val normalized = text.trim().replace('\\', '/')
            if (normalized.isEmpty()) return null
            val pattern =
                if ('*' in normalized || '?' in normalized || '/' in normalized) normalized
                else "*$normalized*"
            return QuickFilter(GlobPattern(pattern))
        }
    }
}
