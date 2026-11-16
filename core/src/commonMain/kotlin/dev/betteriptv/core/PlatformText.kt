package dev.betteriptv.core

// What the Core asks of a platform's Unicode tables (#578): Kotlin's shared library has neither
// normalisation nor a code point's category or script, and every platform has its own (the JDK's
// Normalizer and Character, Foundation's on Apple, String.normalize and \p{…} in JavaScript). Search,
// Match and the Prefix table fold accents and split words through these, so a platform that answers
// differently finds and Matches differently: each one must agree with the JDK's on the Core's tests.

/** [text] in Unicode's NFKC form: fullwidth letters, ligatures and superscripts as the plain letters they stand for, accents composed. */
internal expect fun compatibilityComposed(text: String): String

/** [text] in Unicode's NFD form: each accented letter as its plain letter followed by its marks, which is where accent folding starts. */
internal expect fun canonicallyDecomposed(text: String): String

/** Whether [codePoint] is a letter (Unicode's L categories), in any script. */
internal expect fun isLetter(codePoint: Int): Boolean

/** Whether [codePoint] is a number: a decimal digit, a letter number or another number (Unicode's N categories). */
internal expect fun isNumber(codePoint: Int): Boolean

/** Whether [codePoint] is a combining mark (Unicode's M categories): what [canonicallyDecomposed] leaves after a letter for its accent. */
internal expect fun isMark(codePoint: Int): Boolean

/** The Unicode script [codePoint] belongs to, by its name in capitals: [Script.LATIN], [Script.GREEK], `LAO`… */
internal expect fun scriptOf(codePoint: Int): String

/** The names [scriptOf] gives the scripts the Core tells apart. */
internal object Script {
    const val LATIN = "LATIN"
    const val GREEK = "GREEK"
    const val CYRILLIC = "CYRILLIC"
    const val ARABIC = "ARABIC"
    const val HEBREW = "HEBREW"
    const val DEVANAGARI = "DEVANAGARI"
    const val BENGALI = "BENGALI"
    const val GURMUKHI = "GURMUKHI"
    const val GUJARATI = "GUJARATI"
    const val TAMIL = "TAMIL"
    const val TELUGU = "TELUGU"
    const val KANNADA = "KANNADA"
    const val MALAYALAM = "MALAYALAM"
    const val SINHALA = "SINHALA"
    const val THAI = "THAI"
    const val KHMER = "KHMER"
    const val MYANMAR = "MYANMAR"
    const val ARMENIAN = "ARMENIAN"
    const val GEORGIAN = "GEORGIAN"
    const val ETHIOPIC = "ETHIOPIC"
    const val HANGUL = "HANGUL"
    const val HAN = "HAN"
    const val HIRAGANA = "HIRAGANA"
    const val KATAKANA = "KATAKANA"
}

/** The code point that starts at [index] of [text]: two chars when they are a surrogate pair, else the one. */
internal fun codePointAt(text: CharSequence, index: Int): Int {
    val high = text[index]
    if (high.isHighSurrogate() && index + 1 < text.length) {
        val low = text[index + 1]
        if (low.isLowSurrogate()) return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
    }
    return high.code
}

/** How many chars [codePoint] takes in a string: two above the basic plane. */
internal fun charCount(codePoint: Int): Int = if (codePoint >= 0x10000) 2 else 1

/** [codePoint] as a string, or null when it is no code point. */
internal fun codePointString(codePoint: Int): String? = when (codePoint) {
    in 0..0xFFFF -> codePoint.toChar().toString()
    in 0x10000..0x10FFFF -> charArrayOf((0xD800 + ((codePoint - 0x10000) shr 10)).toChar(), (0xDC00 + ((codePoint - 0x10000) and 0x3FF)).toChar()).concatToString()
    else -> null
}
