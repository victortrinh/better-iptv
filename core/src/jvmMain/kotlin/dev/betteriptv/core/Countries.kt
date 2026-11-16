package dev.betteriptv.core

/**
 * The countries a Content language [tag] stands for, the likeliest first: the tag's own country
 * ("pt-BR" gives Brazil first), then the main countries of its language ([LANGUAGE_COUNTRIES]), or
 * every country the platform has a locale for in it ([localeCountries]) when the language isn't tabled. The Region list in
 * Settings and the Channel browser's countries read it.
 */
fun languageCountries(tag: String): List<String> {
    val language = tagLanguage(tag)
    return (listOf(tagCountry(tag)) + (LANGUAGE_COUNTRIES[language] ?: localeCountries(language))).filter { it.isNotEmpty() }.distinct()
}

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
