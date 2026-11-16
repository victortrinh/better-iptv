package dev.betteriptv.core

// What the Core asks of a platform's locale data (#578): the ISO code lists, how a language tag
// splits, and what a language or a country is called in another language. The JDK's Locale answers
// on Android; Foundation's Locale and JavaScript's Intl hold the same tables.

/** The language of a BCP 47 [tag], as its primary subtag: `pt` of `pt-BR`. Empty when the tag has none. */
internal expect fun tagLanguage(tag: String): String

/** The country of a BCP 47 [tag], in capitals: `BR` of `pt-BR`. Empty when the tag names none. */
internal expect fun tagCountry(tag: String): String

/** The countries the platform has a locale for in [language], in code order. */
internal expect fun localeCountries(language: String): List<String>

/** Every ISO 3166 two-letter country code. */
internal expect fun isoCountries(): Set<String>

/** Every ISO 639 two-letter language code. */
internal expect fun isoLanguages(): Set<String>

/** The ISO 639-2 three-letter (terminology) code of a two-letter [language], or null when the platform knows none. */
internal expect fun iso3Language(language: String): String?

/** What [language] is called in the language [into], or [language] itself when the platform can't say. */
internal expect fun displayLanguage(language: String, into: String): String

/** What the country [country] is called in the language [into], or [country] itself when the platform can't say. */
internal expect fun displayCountry(country: String, into: String): String
