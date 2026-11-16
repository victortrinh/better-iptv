package dev.betteriptv.core

/*
 * Matching (ADR 0014): a Listing belongs to the Title its Provider files it under, by its Title
 * number, and nothing is looked up by name. A Listing without a number is an Unmatched Title. A
 * Mismatch is told by its year: among the Listings of one number and kind that carry a year, one
 * dated more than [MISMATCH_YEARS] from their year (the most common one, ties going to the earliest)
 * is an Unmatched Title of its own. CatalogStore.stageTitles applies it as a Sync stores its Titles.
 */

/** How far a Listing's year may be from its siblings' before it is a Mismatch. */
internal const val MISMATCH_YEARS = 1

/**
 * The year a Provider's release date names (`2011-04-17`, `June 4, 1970`, `25/05/2020`,
 * `2025-02-28 (Spain)`), or null when it names none.
 */
internal fun releaseYear(date: String?): Int? = date?.let { RELEASE_YEAR.find(it)?.value?.toInt() }

// Compiled once: a Sync reads it for every show Listing.
private val RELEASE_YEAR = Regex("""(?<!\d)(?:18[89]\d|19\d\d|20\d\d)(?!\d)""")
