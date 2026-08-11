package com.magpie.domain

/**
 * §9: only obviously junk filenames are ever renamed, the extension is never
 * touched, and anything containing real words is left alone.
 */
object RenameHeuristics {

    private val GENERIC_STUBS = setOf(
        "file", "download", "document", "untitled", "image", "unnamed", "img", "attachment",
    )
    private val DUP_SUFFIX = Regex("""^(.*?)\s*\((\d+)\)$""")
    private val URL_ESCAPE = Regex("""%[0-9a-fA-F]{2}""")
    private val HEX_ONLY = Regex("""^[0-9a-fA-F]{6,}$""")
    private val DIGITS_ONLY = Regex("""^\d{6,}$""")
    private val UUIDISH = Regex("""^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$""")

    fun splitExtension(fileName: String): Pair<String, String> {
        val dot = fileName.lastIndexOf('.')
        return if (dot <= 0) fileName to "" else fileName.substring(0, dot) to fileName.substring(dot)
    }

    /** A token reads as an actual word: letters, has a vowel, and isn't a hex blob. */
    private fun isRealWord(token: String): Boolean {
        if (token.length < 3) return false
        if (!token.all { it.isLetter() }) return false
        if (!token.any { it.lowercaseChar() in "aeiouy" }) return false
        if (HEX_ONLY.matches(token) && token.length >= 6) return false
        return true
    }

    fun containsRealWords(base: String): Boolean =
        Regex("""[^A-Za-z0-9]+|(?<=[a-z])(?=[A-Z])""").split(base)
            .flatMap { it.split(Regex("""(?<=\D)(?=\d)|(?<=\d)(?=\D)""")) }
            .any { isRealWord(it) }

    fun isJunk(fileName: String): Boolean {
        val (base, _) = splitExtension(fileName)
        val stripped = DUP_SUFFIX.matchEntire(base)?.groupValues?.get(1)?.trim() ?: base
        if (stripped.isEmpty()) return true
        if (stripped.lowercase() in GENERIC_STUBS) return true
        if (HEX_ONLY.matches(stripped) || DIGITS_ONLY.matches(stripped) || UUIDISH.matches(stripped)) return true
        if (URL_ESCAPE.containsMatchIn(stripped)) return true
        // Bare timestamps like 2024-01-15_133702 or 20240115133702
        if (Regex("""^[\d\-_. ]{8,}$""").matches(stripped)) return true
        if (containsRealWords(stripped)) return false
        // In doubt → leave it.
        return false
    }

    /**
     * Local rename proposal, or null. Only two shapes are fixed locally:
     * browser duplicate suffixes and URL-encoded names that decode to real
     * words. Other junk (hex, timestamps, stubs) is left for the AI to name.
     */
    fun proposeRename(fileName: String, siblingNames: Set<String> = emptySet()): String? {
        val (base, ext) = splitExtension(fileName)

        DUP_SUFFIX.matchEntire(base)?.let { m ->
            val clean = m.groupValues[1].trim()
            if (clean.isNotEmpty() && containsRealWords(clean)) {
                val candidate = clean + ext
                if (candidate != fileName && candidate !in siblingNames) return candidate
            }
        }

        if (URL_ESCAPE.containsMatchIn(base)) {
            val decoded = try {
                java.net.URLDecoder.decode(base, "UTF-8")
            } catch (_: Exception) {
                return null
            }
            val cleaned = decoded.replace(Regex("""[\\/:*?"<>|]"""), " ").replace(Regex("""\s+"""), " ").trim()
            if (cleaned.isNotEmpty() && containsRealWords(cleaned)) {
                val candidate = cleaned + ext
                if (candidate != fileName && candidate !in siblingNames) return candidate
            }
        }
        return null
    }

    /**
     * §9 gate for AI-proposed renames: accepted only when the original is
     * junk, the extension is unchanged, and the proposal is sane.
     */
    fun acceptAiRename(originalName: String, proposed: String?): String? {
        if (proposed.isNullOrBlank()) return null
        if (!isJunk(originalName)) return null
        val (_, origExt) = splitExtension(originalName)
        val (newBase, newExt) = splitExtension(proposed)
        if (!newExt.equals(origExt, ignoreCase = true)) return null
        if (newBase.isBlank() || proposed.contains('/') || proposed.contains('\\')) return null
        return proposed
    }
}
