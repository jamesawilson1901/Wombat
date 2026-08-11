package com.magpie.domain

import java.io.File

/**
 * The §4 guard rail. Every read and write Magpie performs must pass this —
 * including (especially) folder paths the Claude API suggests. It is applied
 * after the model responds, never as a prompt instruction: anything the model
 * names outside the allowlist is silently dropped.
 */
class PathGuard(storageRoots: List<String>) {

    private val roots: List<String> = storageRoots.map { normalize(it) }.filter { it.isNotEmpty() }

    companion object {
        /** Collapses ".."/"." segments and trailing slashes without touching the filesystem. */
        fun normalize(path: String): String {
            if (path.isBlank()) return ""
            val parts = ArrayDeque<String>()
            for (seg in path.split('/')) {
                when (seg) {
                    "", "." -> {}
                    ".." -> if (parts.isNotEmpty()) parts.removeLast() else return ""
                    else -> parts.addLast(seg)
                }
            }
            return "/" + parts.joinToString("/")
        }
    }

    fun isWithinRoots(path: String): Boolean {
        val p = normalize(path)
        return roots.any { p == it || p.startsWith("$it/") }
    }

    fun isDenied(path: String): Boolean {
        val p = normalize(path)
        if (p.isEmpty() || !isWithinRoots(p)) return true
        val root = roots.first { p == it || p.startsWith("$it/") }
        val relative = p.removePrefix(root).trimStart('/')
        if (relative.isEmpty()) return false
        val segments = relative.split('/')
        if (segments.any { it.startsWith(".") }) return true
        if (segments.first().equals("Android", ignoreCase = true)) return true
        return false
    }

    /** True when [path] sits inside one of [allowedDirs] (or is one of them). */
    fun isUnder(path: String, allowedDirs: Collection<String>): Boolean {
        val p = normalize(path)
        return allowedDirs.map { normalize(it) }.any { p == it || p.startsWith("$it/") }
    }

    /** Sources must live under a watched folder and pass the deny rules. */
    fun isAllowedSource(path: String, watchedDirs: Collection<String>): Boolean =
        !isDenied(path) && isUnder(path, watchedDirs)

    /**
     * Destinations must be a known folder: a preset, the sweep destination, or
     * a folder the user just picked. New folders may be created only directly
     * under an allowed parent.
     */
    fun isAllowedDestination(path: String, allowedDirs: Collection<String>): Boolean {
        if (isDenied(path)) return false
        val p = normalize(path)
        val allowed = allowedDirs.map { normalize(it) }
        if (p in allowed) return true
        val parent = File(p).parent ?: return false
        return normalize(parent) in allowed
    }

    /** §8.2: drop every model-suggested path that isn't an exact preset match. */
    fun filterSuggestions(suggested: List<String>, presetPaths: Collection<String>): List<String> {
        val presets = presetPaths.map { normalize(it) }.toSet()
        return suggested.map { normalize(it) }
            .filter { it in presets && !isDenied(it) }
            .distinct()
    }
}
