package dev.betteriptv.core

import kotlin.time.Clock

/**
 * The Movies or the Shows tab ([TitlesTabContent]), by [kind], for a Viewer who reads the
 * [contentLanguages], with the app in the [appLanguage]. Asked for as Home is ([Library.screen]).
 */
data class TitlesTab(
    val kind: TitleKind,
    val contentLanguages: List<String>,
    val appLanguage: String,
) : Screen<TitlesTabContent>() {
    override suspend fun read(library: Library) =
        TitlesTabContent(kind, library.tabRows(kind, contentLanguages, appLanguage, Clock.System.now()).withUniqueCards())
}

/**
 * What is on the Movies or the Shows tab ([TitlesTab]): its [rows] in order, top to bottom, each with
 * its cards in order, every card a Title of the [kind]. Trending, then New, then the kind's genres;
 * while a first Sync's Head start is all the catalog holds, New alone (ADR 0019). A Kids profile's
 * hold only the Kids catalog: New for kids, the kind for kids, then its genres. Each Row has its own
 * [key], and each of its cards its own key in it ([cardKeys]); a Title is in one Row only, the highest
 * it fits (CONTEXT.md: Row). A card carries what its badges say as of this reading, as on Home
 * ([HomeContent]).
 *
 * Which Rows there are, and what a Row leaves out, is [Library.tabRows]'s.
 */
data class TitlesTabContent(val kind: TitleKind, val rows: List<HomeRow>)
