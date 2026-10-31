package dev.betteriptv.app

import java.text.Normalizer
import java.util.Locale

/**
 * The countries the Region list suggests above all the others: the Viewer's [region], the
 * [deviceCountry], then the countries of the [contentLanguages] in their order, each once and at
 * most [SUGGESTED] of them. A language tag's own country ("pt-BR") comes before its language's.
 */
internal fun suggestedRegions(region: String, deviceCountry: String, contentLanguages: List<String>): List<String> {
    val countries = Locale.getISOCountries().toSet()
    val languageCountries = contentLanguages.flatMap { tag ->
        val locale = Locale.forLanguageTag(tag)
        listOf(locale.country) + (LANGUAGE_COUNTRIES[locale.language] ?: localeCountries(locale.language))
    }
    return (listOf(region, deviceCountry) + languageCountries).filter { it in countries }.distinct().take(SUGGESTED)
}

/** The countries the JDK has a locale for in [language], when it isn't in [LANGUAGE_COUNTRIES]. */
private fun localeCountries(language: String): List<String> =
    Locale.getAvailableLocales().filter { it.language == language }.map { it.country }.filter { it.isNotEmpty() }.distinct().sorted()

/** The letter of the rail a country's [name] is under: its first letter, without accents, so É is under E. */
internal fun indexLetter(name: String): Char =
    Normalizer.normalize(name.take(1), Normalizer.Form.NFD).first().uppercaseChar()

private const val SUGGESTED = 8

/** The main countries of the languages Viewers are likeliest to pick, most populous first. */
private val LANGUAGE_COUNTRIES = mapOf(
    "en" to listOf("US", "GB", "CA", "AU", "IE", "NZ"),
    "fr" to listOf("FR", "BE", "CH", "CA"),
    "es" to listOf("ES", "MX", "AR", "CO", "US"),
    "pt" to listOf("BR", "PT"),
    "de" to listOf("DE", "AT", "CH"),
    "it" to listOf("IT", "CH"),
    "nl" to listOf("NL", "BE"),
    "ar" to listOf("EG", "SA", "AE", "MA", "DZ"),
    "zh" to listOf("CN", "TW", "HK", "SG"),
    "ru" to listOf("RU"),
    "hi" to listOf("IN"),
    "ja" to listOf("JP"),
    "ko" to listOf("KR"),
    "tr" to listOf("TR"),
    "pl" to listOf("PL"),
    "sv" to listOf("SE"),
    "da" to listOf("DK"),
    "no" to listOf("NO"),
    "nb" to listOf("NO"),
    "fi" to listOf("FI"),
    "el" to listOf("GR", "CY"),
    "ro" to listOf("RO", "MD"),
    "fa" to listOf("IR"),
    "he" to listOf("IL"),
    "uk" to listOf("UA"),
)
