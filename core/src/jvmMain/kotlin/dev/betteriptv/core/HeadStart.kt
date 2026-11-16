package dev.betteriptv.core


/**
 * Reads the Categories of one kind that a first Sync lands as its Head start (CONTEXT.md, ADR 0019),
 * one [read] at a time: it is handed a Category's Provider id and answers how many of its Listings
 * show. For each of the Viewer's [contentLanguages], in their order: up to [HEAD_START_CATEGORIES]
 * of the [categories] (id to name, in the Provider's order) in that language that the [table] says
 * hold new releases ([PrefixTable.isFreshCategory], as of [year]). A language with none is read from
 * its first Category on, in the Provider's order, until [HEAD_START_ROW] Listings that show are
 * read or [HEAD_START_FALLBACK_CATEGORIES] Categories are: the Category list carries no counts, and
 * a Provider's first Category in a language can hold a single Listing (a quality variant such as
 * `ENGLISH SERIES ⁴ᴷ`). Clutter and Sports-replay Categories never qualify, and a Category is read
 * once however many Content languages it serves (`en-CA` and `en-US`).
 *
 * A Category is in a language when the table names it for the Category's Prefix or hint (`EN - NEW
 * RELEASE`); without one, when its name has the language's name or its main country's as a word, in
 * English or in the language itself (`ENGLISH SERIES`, `FRANCE DERNIER AJOUT`, `ESPAÑA SERIES`),
 * which is how Providers name show Categories.
 */
internal fun readHeadStart(
    table: PrefixTable,
    categories: List<Pair<String, String>>,
    contentLanguages: List<String>,
    year: Int = thisYear(),
    read: (category: String) -> Int,
) {
    val eligible = categories.filter { (_, name) -> !table.isClutterCategory(name) && !table.isSportsReplayCategory(name) }
    val shown = HashMap<String, Int>()
    fun shownIn(category: String) = shown.getOrPut(category) { read(category) }
    contentLanguages.map(::primaryLanguage).filter { it.isNotEmpty() }.distinct().forEach { language ->
        val words = languageWords(language)
        val inLanguage = eligible.filter { (_, name) ->
            val named = table.categoryLanguage(name)
            if (named != null) named == language else PrefixTable.fold(name).let { folded -> words.any { it.isIn(folded) } }
        }
        val fresh = inLanguage.filter { (_, name) -> table.isFreshCategory(name, year) }.take(HEAD_START_CATEGORIES)
        if (fresh.isNotEmpty()) {
            fresh.forEach { (category, _) -> shownIn(category) }
        } else {
            var row = 0
            for ((category, _) in inLanguage.take(HEAD_START_FALLBACK_CATEGORIES)) {
                row += shownIn(category)
                if (row >= HEAD_START_ROW) break
            }
        }
    }
}

/** How many fresh Categories of a kind a Head start reads for one Content language: a count, not a timer, so the same rows show every time. */
internal const val HEAD_START_CATEGORIES = 2

/** How many Listings that show a Content language with no fresh Category is read up to, per kind: more than a row of cards. */
internal const val HEAD_START_ROW = 40

/** How many Categories of a kind a Content language with no fresh Category is read for at most: about a second each. */
internal const val HEAD_START_FALLBACK_CATEGORIES = 3

/** The words that say a Category is in [language] (a primary subtag): its name and its main country's, in English and in itself. */
private fun languageWords(language: String): List<PrefixTable.WholeWord> {
    val country = languageCountries(language).firstOrNull()
    return listOf("en", language)
        .flatMap { into -> listOfNotNull(displayLanguage(language, into), country?.let { displayCountry(it, into) }) }
        // A language the platform can't name is given back as its code, which is no word of a Category.
        .filter { it.length > 3 }
        .map { PrefixTable.WholeWord(PrefixTable.fold(it)) }
}
