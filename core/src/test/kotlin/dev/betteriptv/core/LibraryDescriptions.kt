package dev.betteriptv.core

/**
 * What a synced Library shows of the fixture's Listings, one line each, to compare two Prefix tables
 * by: every movie Listing with its attributes (Clutter and Sports replays are left out of that list),
 * the Sports replays, every Show's best Version, the Title counts and the unknown Prefixes.
 */
internal suspend fun libraryDescriptions(library: Library): String = buildString {
    library.movieListings(0, 100_000).forEach { appendLine("movie\t$it") }
    library.replays().forEach { appendLine("replay\t${it.listingId}\t${it.name}\t${it.category}\t${it.away}\t${it.home}") }
    library.titles(0, 100_000, listOf("en"), "en", TitleKind.Show).forEach { appendLine("show\t${it.id}\t${it.name}\t${it.year}\t${it.best}") }
    appendLine("titles\t${library.titleCount(TitleKind.Movie)}\t${library.titleCount(TitleKind.Show)}")
    appendLine("unknown\t${library.syncStatus.value.last?.unknownPrefixes}")
}
