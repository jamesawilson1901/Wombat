package com.magpie.filer.core

/**
 * Filename handling: what to ignore while a download is still in flight, and
 * what to propose renaming a finished file to.
 *
 * Deliberately free of Android APIs so it can be unit-tested on the JVM.
 */
object Naming {

    /** Extensions browsers and torrent clients use for a part-written file. */
    private val TEMPORARY_EXTENSIONS = setOf(
        "crdownload", "part", "partial", "tmp", "download", "opdownload", "!ut",
    )

    private val PERCENT_ESCAPE = Regex("%[0-9A-Fa-f]{2}")

    /**
     * Characters a destination will not take. FAT's set is included
     * deliberately: an SD card is one of the folders people file into.
     */
    private const val ILLEGAL_CHARACTERS = "/\\:*?\"<>|"

    /** True while a file is still arriving, or is hidden. */
    fun isTemporary(name: String): Boolean {
        if (name.startsWith(".")) return true
        return extension(name).removePrefix(".").lowercase() in TEMPORARY_EXTENSIONS
    }

    /** ".pdf" for "report.pdf"; "" when there is nothing extension-shaped. */
    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        val candidate = name.substring(dot + 1)
        if (candidate.length > 12 || candidate.any { it.isWhitespace() }) return ""
        return ".$candidate"
    }

    /** "report" for "report.pdf". */
    fun stem(name: String): String = name.removeSuffix(extension(name))

    /**
     * Names to offer in the rename step: the original first, then up to two
     * tidied variants. Built only from the filename — never the date, the site
     * it came from, or where it is going. The extension always survives.
     */
    fun suggestions(name: String): List<String> {
        val out = LinkedHashSet<String>()
        out += name
        out += tidy(name, deep = false)
        out += tidy(name, deep = true)
        return out.filter { it.isNotBlank() }
    }

    /**
     * Clean up a filename. [deep] additionally drops hash- and id-shaped runs
     * and evens out capitalisation; without it the words are only unpicked from
     * their separators, which is often all a name needs.
     */
    fun tidy(name: String, deep: Boolean): String {
        val extension = extension(name)
        val original = stem(name)
        var text = original

        // URL leftovers: a query string, a fragment, then any stray escapes.
        text = text.substringBefore('?').substringBefore('#')
        text = text.replace("%20", " ").replace(PERCENT_ESCAPE, "")
        // key=value pairs a download link left behind
        text = text.split('&', ';')
            .filterNot { it.length > 3 && it.contains('=') }
            .joinToString(" ")

        text = text.replace('_', ' ').replace('-', ' ').replace('+', ' ')

        var words = collapse(text)
        var allOpaque = false
        if (deep) {
            val kept = words.filterNot(::isOpaqueToken)
            // Refuse to strip a name down to nothing: if every word looked like
            // an id, the id is the name.
            if (kept.isNotEmpty()) words = kept else allOpaque = true
        }

        var cleaned = words.joinToString(" ")
        // Recapitalising is only worth doing to a name that needed work anyway,
        // or to one that is shouting. Turning "photo.jpg" into "Photo.jpg" is
        // not a suggestion, it is noise.
        val shouting = original.any { it.isLetter() } && original.none { it.isLowerCase() }
        if (deep && !allOpaque && (cleaned != original || shouting)) {
            cleaned = evenCapitalisation(cleaned)
        }
        cleaned = sanitise(cleaned)

        return if (cleaned.isBlank()) name else cleaned + extension
    }

    /** Put the extension back if an edited name lost it. */
    fun withExtensionOf(edited: String, original: String): String {
        val wanted = extension(original)
        if (wanted.isEmpty()) return edited
        if (edited.endsWith(wanted, ignoreCase = true)) return edited
        return edited + wanted
    }

    /**
     * A name worth writing: something is left of it, and it is not a hidden
     * dotfile. Typing "." or "?" in the rename box must not file the user's only
     * copy somewhere they will never see it again.
     */
    fun isUsable(name: String): Boolean {
        if (name.isBlank()) return false
        if (name.startsWith(".")) return false
        return stem(name).isNotBlank()
    }

    /** Strip what no filesystem will accept, and trim to something sane. */
    fun sanitise(name: String): String {
        val stripped = name.map {
            if (it in ILLEGAL_CHARACTERS || it.code < 0x20) ' ' else it
        }
        return collapse(stripped.joinToString("")).joinToString(" ").take(180)
    }

    private fun collapse(text: String): List<String> =
        text.split(' ', '\t').filter { it.isNotBlank() }

    /**
     * A run that carries no meaning for a reader: a content hash, a database
     * id, a timestamp. Word-shaped tokens with a couple of digits in them, like
     * "Screenshot2024", are left alone.
     */
    private fun isOpaqueToken(token: String): Boolean {
        if (token.length < 6) return false
        if (!token.all { it.isLetterOrDigit() }) return false
        val digits = token.count { it.isDigit() }
        val letters = token.count { it.isLetter() }

        if (letters == 0) return true // a date stamp or a counter
        if (token.length >= 8 && digits > 0 &&
            token.all { it.isDigit() || it.lowercaseChar() in "abcdef" }
        ) {
            return true // hex: a checksum or a git-style id
        }
        return token.length >= 10 && digits >= 4 && digits.toDouble() / token.length >= 0.35
    }

    private fun evenCapitalisation(text: String): String {
        val base = if (text.none { it.isLowerCase() }) text.lowercase() else text
        return base.split(' ').joinToString(" ") { word ->
            when {
                word.isEmpty() -> word
                // Leave anything that already carries capitals: acronyms,
                // product names like "iPhone", deliberate spellings.
                word.any { it.isUpperCase() } -> word
                else -> word.replaceFirstChar { it.uppercaseChar() }
            }
        }
    }
}
