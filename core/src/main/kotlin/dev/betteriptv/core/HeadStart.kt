package dev.betteriptv.core

import java.time.Year
import java.util.Locale

/**
 * Which Categories of one kind a first Sync reads as its Head start (CONTEXT.md, ADR 0019), as their
 * Provider ids in the order to read them. For each of the Viewer's [contentLanguages], in their
 * order: up to [HEAD_START_CATEGORIES] of the [categories] (id to name, in the Provider's order) in
 * that language that the [table] says hold new releases ([PrefixTable.isFreshCategory], as of
 * [year]), else the first one in that language. Clutter and Sports-replay Categories never qualify,
 * and a Category is read once however many Content languages it serves (`en-CA` and `en-US`).
 *
 * A Category is in a language when the table names it for the Category's Prefix or hint (`EN - NEW
 * RELEASE`); without one, when its name has the language's name or its main country's as a word, in
 * English or in the language itself (`ENGLISH SERIES`, `FRANCE DERNIER AJOUT`, `ESPAÑA SERIES`),
 * which is how Providers name show Categories.
 */
internal fun headStartCategories(table: PrefixTable, categories: List<Pair<String, String>>, contentLanguages: List<String>, year: Int = Year.now().value): List<String> {
    val eligible = categories.filter { (_, name) -> !table.isClutterCategory(name) && !table.isSportsReplayCategory(name) }
    return contentLanguages.map(::primaryLanguage).filter { it.isNotEmpty() }.distinct().flatMap { language ->
        val words = languageWords(language)
        val inLanguage = eligible.filter { (_, name) ->
            val named = table.categoryLanguage(name)
            if (named != null) named == language else PrefixTable.fold(name).let { folded -> words.any { it.isIn(folded) } }
        }
        inLanguage.filter { (_, name) -> table.isFreshCategory(name, year) }.take(HEAD_START_CATEGORIES).ifEmpty { inLanguage.take(1) }
    }.map { it.first }.distinct()
}

/** How many Categories of a kind a Head start reads for one Content language: a count, not a timer, so the same rows show every time. */
internal const val HEAD_START_CATEGORIES = 2

/** The words that say a Category is in [language] (a primary subtag): its name and its main country's, in English and in itself. */
private fun languageWords(language: String): List<PrefixTable.WholeWord> {
    val locale = Locale.forLanguageTag(language)
    val country = languageCountries(language).firstOrNull()?.let { Locale.Builder().setRegion(it).build() }
    return listOf(Locale.ENGLISH, locale)
        .flatMap { into -> listOfNotNull(locale.getDisplayLanguage(into), country?.getDisplayCountry(into)) }
        // A language the JDK can't name is given back as its code, which is no word of a Category.
        .filter { it.length > 3 }
        .map { PrefixTable.WholeWord(PrefixTable.fold(it)) }
}
