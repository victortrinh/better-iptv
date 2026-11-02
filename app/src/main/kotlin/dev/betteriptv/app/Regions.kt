package dev.betteriptv.app

import dev.betteriptv.core.languageCountries
import java.text.Normalizer
import java.util.Locale

/**
 * The countries the Region list suggests above all the others: the Viewer's [region], the
 * [deviceCountry], then the countries of the [contentLanguages] in their order ([languageCountries]),
 * each once and at most [SUGGESTED] of them.
 */
internal fun suggestedRegions(region: String, deviceCountry: String, contentLanguages: List<String>): List<String> {
    val countries = Locale.getISOCountries().toSet()
    return (listOf(region, deviceCountry) + contentLanguages.flatMap(::languageCountries)).filter { it in countries }.distinct().take(SUGGESTED)
}

/** The letter of the rail a country's [name] is under: its first letter, without accents, so É is under E. */
internal fun indexLetter(name: String): Char =
    Normalizer.normalize(name.take(1), Normalizer.Form.NFD).first().uppercaseChar()

private const val SUGGESTED = 8

