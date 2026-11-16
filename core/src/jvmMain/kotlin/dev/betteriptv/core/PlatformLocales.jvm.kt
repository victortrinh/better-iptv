package dev.betteriptv.core

import java.util.Locale

internal actual fun tagLanguage(tag: String): String = Locale.forLanguageTag(tag).language

internal actual fun tagCountry(tag: String): String = Locale.forLanguageTag(tag).country

internal actual fun localeCountries(language: String): List<String> =
    Locale.getAvailableLocales().filter { it.language == language }.map { it.country }.filter { it.isNotEmpty() }.distinct().sorted()

internal actual fun isoCountries(): Set<String> = Locale.getISOCountries().toSet()

internal actual fun isoLanguages(): Set<String> = Locale.getISOLanguages().toSet()

internal actual fun iso3Language(language: String): String? = runCatching { Locale(language).isO3Language }.getOrNull()

internal actual fun displayLanguage(language: String, into: String): String = Locale.forLanguageTag(language).getDisplayLanguage(Locale.forLanguageTag(into))

internal actual fun displayCountry(country: String, into: String): String = Locale.Builder().setRegion(country).build().getDisplayCountry(Locale.forLanguageTag(into))
