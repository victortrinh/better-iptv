package dev.betteriptv.core

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
    private val OF_LANGUAGE: Map<String, Set<String>> = buildMap {
        fun put(script: String, vararg languages: String) = languages.forEach { put(it, setOf(script)) }
        put(Script.GREEK, "el")
        put(Script.CYRILLIC, "ru", "uk", "bg", "mk", "be", "kk", "ky", "mn", "tg")
        put(Script.ARABIC, "ar", "fa", "ur", "ps", "ckb")
        put(Script.HEBREW, "he", "iw", "yi")
        put(Script.DEVANAGARI, "hi", "mr", "ne")
        put(Script.BENGALI, "bn", "as")
        put(Script.GURMUKHI, "pa")
        put(Script.GUJARATI, "gu")
        put(Script.TAMIL, "ta")
        put(Script.TELUGU, "te")
        put(Script.KANNADA, "kn")
        put(Script.MALAYALAM, "ml")
        put(Script.SINHALA, "si")
        put(Script.THAI, "th")
        put(Script.KHMER, "km")
        put(Script.MYANMAR, "my")
        put(Script.ARMENIAN, "hy")
        put(Script.GEORGIAN, "ka")
        put(Script.ETHIOPIC, "am", "ti")
        put(Script.HANGUL, "ko")
        put(Script.HAN, "zh")
        put("ja", setOf(Script.HAN, Script.HIRAGANA, Script.KATAKANA))
        // Written in either.
        listOf("sr", "bs", "uz", "az").forEach { put(it, setOf(Script.CYRILLIC, Script.LATIN)) }
        put("ku", setOf(Script.ARABIC, Script.LATIN))
    }

    /** The one language a script is written for, where there is one the catalog carries. */
    private val ONLY_LANGUAGE: Map<String, String> = mapOf(
        Script.GREEK to "el", Script.HEBREW to "he", Script.HANGUL to "ko", Script.THAI to "th",
        Script.HIRAGANA to "ja", Script.KATAKANA to "ja", Script.ARMENIAN to "hy", Script.GEORGIAN to "ka",
        Script.TAMIL to "ta", Script.TELUGU to "te", Script.KANNADA to "kn", Script.MALAYALAM to "ml",
        Script.GUJARATI to "gu", Script.GURMUKHI to "pa", Script.SINHALA to "si", Script.KHMER to "km",
    )

    private val LATIN = setOf(Script.LATIN)

    /** The scripts [language] (a primary subtag) is written in: Latin unless listed otherwise. */
    private fun of(language: String): Set<String> = OF_LANGUAGE[language] ?: LATIN

    /**
     * The script ([scriptOf]'s name for it) most of [text]'s first letters are in, null when it has no letter. Most names and
     * plots are Latin through and through, and those cost one pass and no lookup.
     */
    fun of(text: CharSequence): String? {
        var latin = 0
        var counts: HashMap<String, Int>? = null
        var seen = 0
        var i = 0
        while (i < text.length && seen < SAMPLE) {
            val char = text[i]
            if (char < FIRST_OTHER) {
                if (char.isLetter()) { latin++; seen++ }
                i++
                continue
            }
            val point = codePointAt(text, i)
            i += charCount(point)
            if (!isLetter(point)) continue
            val script = scriptOf(point)
            if (script == Script.LATIN) latin++ else (counts ?: HashMap<String, Int>().also { counts = it }).let { it[script] = (it[script] ?: 0) + 1 }
            seen++
        }
        if (seen == 0) return null
        val other = counts?.maxByOrNull { it.value } ?: return Script.LATIN
        return if (other.value > latin) other.key else Script.LATIN
    }

    /**
     * Whether a Viewer whose App is in [appLanguage] reads [text]: it is written in that language's
     * script, or in Latin letters (English, which every Viewer falls back to). False for no text.
     */
    fun reads(appLanguage: String, text: CharSequence?): Boolean {
        val script = text?.let(::of) ?: return false
        return script == Script.LATIN || script in of(primaryLanguage(appLanguage))
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
            if (script == Script.LATIN) continue
            if (listed != null && script in of(listed)) return listed
            return ONLY_LANGUAGE[script] ?: UNDETERMINED
        }
        return listed
    }
}
