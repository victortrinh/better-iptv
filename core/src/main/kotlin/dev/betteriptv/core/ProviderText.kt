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
