package dev.betteriptv.core

import java.lang.Character.UnicodeScript

/**
 * What a text's letters say of the language it is written in, for when the Prefix says otherwise:
 * a Provider files Greek plots under `EN` Listings and names `EN` Listings in Greek, and no table
 * knows which. The script tells Greek from English; it doesn't tell Spanish from English.
 */
internal object Scripts {
    /** Letters read before deciding: a plot's first sentence is in the language of the rest. */
    private const val SAMPLE = 64

    /** Below this every letter is Latin: the plain, accented and extended Latin blocks. */
    const val FIRST_OTHER = '\u0250'

    /** What [language]'s Listings are written in when a script names no one language. */
    const val UNDETERMINED = "und"

    /** The scripts the languages that aren't written in Latin letters are written in, by primary subtag. */
    private val OF_LANGUAGE: Map<String, Set<UnicodeScript>> = buildMap {
        fun put(script: UnicodeScript, vararg languages: String) = languages.forEach { put(it, setOf(script)) }
        put(UnicodeScript.GREEK, "el")
        put(UnicodeScript.CYRILLIC, "ru", "uk", "bg", "mk", "be", "kk", "ky", "mn", "tg")
        put(UnicodeScript.ARABIC, "ar", "fa", "ur", "ps", "ckb")
        put(UnicodeScript.HEBREW, "he", "iw", "yi")
        put(UnicodeScript.DEVANAGARI, "hi", "mr", "ne")
        put(UnicodeScript.BENGALI, "bn", "as")
        put(UnicodeScript.GURMUKHI, "pa")
        put(UnicodeScript.GUJARATI, "gu")
        put(UnicodeScript.TAMIL, "ta")
        put(UnicodeScript.TELUGU, "te")
        put(UnicodeScript.KANNADA, "kn")
        put(UnicodeScript.MALAYALAM, "ml")
        put(UnicodeScript.SINHALA, "si")
        put(UnicodeScript.THAI, "th")
        put(UnicodeScript.KHMER, "km")
        put(UnicodeScript.MYANMAR, "my")
        put(UnicodeScript.ARMENIAN, "hy")
        put(UnicodeScript.GEORGIAN, "ka")
        put(UnicodeScript.ETHIOPIC, "am", "ti")
        put(UnicodeScript.HANGUL, "ko")
        put(UnicodeScript.HAN, "zh")
        put("ja", setOf(UnicodeScript.HAN, UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA))
        // Written in either.
        listOf("sr", "bs", "uz", "az").forEach { put(it, setOf(UnicodeScript.CYRILLIC, UnicodeScript.LATIN)) }
        put("ku", setOf(UnicodeScript.ARABIC, UnicodeScript.LATIN))
    }

    /** The one language a script is written for, where there is one the catalog carries. */
    private val ONLY_LANGUAGE: Map<UnicodeScript, String> = mapOf(
        UnicodeScript.GREEK to "el", UnicodeScript.HEBREW to "he", UnicodeScript.HANGUL to "ko", UnicodeScript.THAI to "th",
        UnicodeScript.HIRAGANA to "ja", UnicodeScript.KATAKANA to "ja", UnicodeScript.ARMENIAN to "hy", UnicodeScript.GEORGIAN to "ka",
        UnicodeScript.TAMIL to "ta", UnicodeScript.TELUGU to "te", UnicodeScript.KANNADA to "kn", UnicodeScript.MALAYALAM to "ml",
        UnicodeScript.GUJARATI to "gu", UnicodeScript.GURMUKHI to "pa", UnicodeScript.SINHALA to "si", UnicodeScript.KHMER to "km",
    )

    private val LATIN = setOf(UnicodeScript.LATIN)

    /** The scripts [language] (a primary subtag) is written in: Latin unless listed otherwise. */
    private fun of(language: String): Set<UnicodeScript> = OF_LANGUAGE[language] ?: LATIN

    /**
     * The script most of [text]'s first letters are in, null when it has no letter. Most names and
     * plots are Latin through and through, and those cost one pass and no lookup.
     */
    fun of(text: CharSequence): UnicodeScript? {
        var latin = 0
        var counts: HashMap<UnicodeScript, Int>? = null
        var seen = 0
        var i = 0
        while (i < text.length && seen < SAMPLE) {
            val char = text[i]
            if (char < FIRST_OTHER) {
                if (char.isLetter()) { latin++; seen++ }
                i++
                continue
            }
            val point = Character.codePointAt(text, i)
            i += Character.charCount(point)
            if (!Character.isLetter(point)) continue
            val script = UnicodeScript.of(point)
            if (script == UnicodeScript.LATIN) latin++ else (counts ?: HashMap<UnicodeScript, Int>().also { counts = it }).merge(script, 1, Int::plus)
            seen++
        }
        if (seen == 0) return null
        val other = counts?.maxByOrNull { it.value } ?: return UnicodeScript.LATIN
        return if (other.value > latin) other.key else UnicodeScript.LATIN
    }

    /**
     * Whether a Viewer whose App is in [appLanguage] reads [text]: it is written in that language's
     * script, or in Latin letters (English, which every Viewer falls back to). False for no text.
     */
    fun reads(appLanguage: String, text: CharSequence?): Boolean {
        val script = text?.let(::of) ?: return false
        return script == UnicodeScript.LATIN || script in of(primaryLanguage(appLanguage))
    }

    /**
     * The language a Listing [listed] in a language (its Prefix's, or null) is written in, going by
     * its [texts] (its name, then its plot): the first with letters outside Latin decides. Letters of
     * the listed language's script confirm it; others say the Prefix is wrong, and the Listing is in
     * the script's one language (Greek letters are Greek) or in none known ([UNDETERMINED]), never in
     * the listed one. Latin letters say nothing either way: `GR - Verity (2026)` is a Greek Listing.
     */
    fun written(listed: String?, vararg texts: () -> CharSequence?): String? {
        for (text in texts) {
            val script = text()?.let(::of) ?: continue
            if (script == UnicodeScript.LATIN) continue
            if (listed != null && script in of(listed)) return listed
            return ONLY_LANGUAGE[script] ?: UNDETERMINED
        }
        return listed
    }
}
