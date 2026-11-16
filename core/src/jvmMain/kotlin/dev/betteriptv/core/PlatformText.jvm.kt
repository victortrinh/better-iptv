package dev.betteriptv.core

import java.lang.Character.UnicodeScript
import java.text.Normalizer

internal actual fun compatibilityComposed(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)

internal actual fun canonicallyDecomposed(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)

internal actual fun isLetter(codePoint: Int): Boolean = Character.isLetter(codePoint)

internal actual fun isNumber(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
    Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
    else -> false
}

internal actual fun isMark(codePoint: Int): Boolean = when (Character.getType(codePoint).toByte()) {
    Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
    else -> false
}

internal actual fun scriptOf(codePoint: Int): String = UnicodeScript.of(codePoint).name
