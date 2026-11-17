package dev.betteriptv.core

/**
 * A text field as the Provider sends it, read clean: its HTML tags gone (`<br>`, `<b>…</b>`), its
 * character entities decoded (`&amp;`, `&#39;`), and its whitespace, doubled spaces, tabs and line
 * breaks, folded to single spaces and trimmed. Null for an empty one.
 */
internal fun cleanText(raw: String?): String? {
    if (raw == null) return null
    // Each regex runs only where it can match: most fields have no tag, entity or odd space, and the
    // search index cleans every piece of every cast (#567), on a stick where a regex costs.
    val untagged = if (raw.indexOf('<') >= 0) TAG.replace(raw, " ") else raw
    val unescaped = if (untagged.indexOf('&') < 0) untagged else ENTITY.replace(untagged) { match ->
        val entity = match.groupValues[1]
        when {
            entity.startsWith("#x", ignoreCase = true) -> entity.drop(2).toIntOrNull(16)?.let { codePoint(it) }
            entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let { codePoint(it) }
            else -> NAMED_ENTITIES[entity.lowercase()]
        } ?: match.value
    }
    return (if (hasOddSpace(unescaped)) unescaped.replace(WHITESPACE, " ") else unescaped).trim().takeIf { it.isNotEmpty() }
}

/**
 * Whether [WHITESPACE] could change [text]: it has a space other than a lone `' '`. Any Unicode space
 * counts, more than the JVM's `\s` matches, so the answer holds whatever a platform's `\s` is.
 */
private fun hasOddSpace(text: String): Boolean {
    for (i in text.indices) {
        val c = text[i]
        if (if (c == ' ') i + 1 < text.length && isAnySpace(text[i + 1]) else isAnySpace(c)) return true
    }
    return false
}

private fun isAnySpace(c: Char): Boolean = c.isWhitespace() || c == '\u00A0' || c == '\u2007' || c == '\u202F'

/** A list the Provider writes as one string (`Marlon Brando, Al Pacino`), split on commas, each name clean, none twice. */
internal fun cleanList(raw: String?): List<String> =
    cleanText(raw)?.split(',')?.mapNotNull { cleanText(it) }?.distinct().orEmpty()

private fun codePoint(value: Int): String? = codePointString(value)

private val TAG = Regex("""<[^>]{1,200}>""")
private val ENTITY = Regex("""&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z]{2,8});""")
private val WHITESPACE = Regex("""[\s   ]+""")
private val NAMED_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
    "hellip" to "…", "ndash" to "–", "mdash" to "—", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
    "eacute" to "é", "egrave" to "è", "agrave" to "à", "ccedil" to "ç", "ecirc" to "ê", "ocirc" to "ô", "uuml" to "ü", "ouml" to "ö", "auml" to "ä", "szlig" to "ß", "ntilde" to "ñ",
)

/**
 * The people a Provider's `cast` field names: [cleanList], without the pieces that can't be a name
 * (#516). Some Listings put a synopsis there, and its clauses split on commas aren't people.
 */
internal fun castNames(raw: String?): List<String> = cleanList(raw).filter(::readsAsName)

/**
 * Whether a piece of a cast list can be a person's name: at most [MAX_NAME_WORDS] words, no sentence
 * punctuation (`:`, `;`, `!`, `?`, `…`, a spaced dash, or a full stop after a word that isn't an
 * initial or `Jr.`, followed by more text), and not more than one lowercase word unless it's a
 * particle (`van`, `de`, `al`…), nor all lowercase. Lowercase only counts in scripts with case and
 * capitals for names (Latin, Greek, Cyrillic): Georgian names are written in lowercase letters.
 * Measured on the live Provider (2026-11-08): it leaves out 862 of 50,922 cast pieces.
 */
internal fun readsAsName(piece: String): Boolean {
    // Each regex runs only where it can match: most names have neither punctuation nor a full stop.
    if (piece.any { it in PUNCTUATION_MARKS } && SENTENCE_PUNCTUATION.containsMatchIn(piece)) return false
    if (piece.indexOf('.') >= 0 && SENTENCE_STOP.findAll(piece).any { it.groupValues[1].lowercase() !in ABBREVIATIONS }) return false
    val words = piece.split(' ')
    if (words.size > MAX_NAME_WORDS) return false
    val lowercase = words.count(::isLowercaseWord)
    return lowercase < 2 && lowercase < words.size
}

private fun isLowercaseWord(word: String): Boolean {
    val first = word.firstOrNull(Char::isLetter) ?: return false
    if (!first.isLowerCase() || scriptOf(first.code) !in CASED_NAME_SCRIPTS) return false
    return word.trim { !it.isLetter() }.split('-', '\'', '’').first().lowercase() !in PARTICLES
}

private const val MAX_NAME_WORDS = 5
private val SENTENCE_PUNCTUATION = Regex("""[:;!?…：]\s|[；！？…：]|\s[-–—]\s""")
/** Every character one of [SENTENCE_PUNCTUATION]'s matches holds besides spaces. */
private const val PUNCTUATION_MARKS = ":;!?…：；！？-–—"
private val SENTENCE_STOP = Regex("""(\p{L}{3,})\.\s+\S""")
private val ABBREVIATIONS = setOf("mrs", "prof", "gen", "col", "capt", "sgt", "mohd")
private val CASED_NAME_SCRIPTS = setOf(Script.LATIN, Script.GREEK, Script.CYRILLIC)
private val PARTICLES = setOf(
    "van", "von", "de", "der", "den", "del", "della", "di", "da", "das", "dos", "do", "du", "la", "las", "los", "le", "les",
    "y", "e", "bin", "binti", "al", "el", "ten", "ter", "af", "zu", "het", "t", "ibn", "ben", "op", "d", "l", "ul", "ud", "ur",
)
