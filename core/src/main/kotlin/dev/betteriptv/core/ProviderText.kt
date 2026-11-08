package dev.betteriptv.core

import java.util.Locale

/**
 * A text field as the Provider sends it, read clean: its HTML tags gone (`<br>`, `<b>…</b>`), its
 * character entities decoded (`&amp;`, `&#39;`), and its whitespace, doubled spaces, tabs and line
 * breaks, folded to single spaces and trimmed. Null for an empty one.
 */
internal fun cleanText(raw: String?): String? {
    if (raw == null) return null
    val unescaped = ENTITY.replace(TAG.replace(raw, " ")) { match ->
        val entity = match.groupValues[1]
        when {
            entity.startsWith("#x", ignoreCase = true) -> entity.drop(2).toIntOrNull(16)?.let { codePoint(it) }
            entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let { codePoint(it) }
            else -> NAMED_ENTITIES[entity.lowercase(Locale.ROOT)]
        } ?: match.value
    }
    return unescaped.replace(WHITESPACE, " ").trim().takeIf { it.isNotEmpty() }
}

/** A list the Provider writes as one string (`Marlon Brando, Al Pacino`), split on commas, each name clean, none twice. */
internal fun cleanList(raw: String?): List<String> =
    cleanText(raw)?.split(',')?.mapNotNull { cleanText(it) }?.distinct().orEmpty()

private fun codePoint(value: Int): String? = runCatching { String(Character.toChars(value)) }.getOrNull()

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
    if (SENTENCE_PUNCTUATION.containsMatchIn(piece)) return false
    if (SENTENCE_STOP.findAll(piece).any { it.groupValues[1].lowercase(Locale.ROOT) !in ABBREVIATIONS }) return false
    val words = piece.split(' ')
    if (words.size > MAX_NAME_WORDS) return false
    val lowercase = words.count(::isLowercaseWord)
    return lowercase < 2 && lowercase < words.size
}

private fun isLowercaseWord(word: String): Boolean {
    val first = word.firstOrNull(Char::isLetter) ?: return false
    if (!first.isLowerCase() || Character.UnicodeScript.of(first.code) !in CASED_NAME_SCRIPTS) return false
    return word.trim { !it.isLetter() }.split('-', '\'', '’').first().lowercase(Locale.ROOT) !in PARTICLES
}

private const val MAX_NAME_WORDS = 5
private val SENTENCE_PUNCTUATION = Regex("""[:;!?…：]\s|[；！？…：]|\s[-–—]\s""")
private val SENTENCE_STOP = Regex("""(\p{L}{3,})\.\s+\S""")
private val ABBREVIATIONS = setOf("mrs", "prof", "gen", "col", "capt", "sgt", "mohd")
private val CASED_NAME_SCRIPTS = setOf(Character.UnicodeScript.LATIN, Character.UnicodeScript.GREEK, Character.UnicodeScript.CYRILLIC)
private val PARTICLES = setOf(
    "van", "von", "de", "der", "den", "del", "della", "di", "da", "das", "dos", "do", "du", "la", "las", "los", "le", "les",
    "y", "e", "bin", "binti", "al", "el", "ten", "ter", "af", "zu", "het", "t", "ibn", "ben", "op", "d", "l", "ul", "ud", "ur",
)
