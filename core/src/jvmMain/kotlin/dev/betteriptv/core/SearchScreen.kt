package dev.betteriptv.core

import kotlinx.coroutines.flow.first

/**
 * Search's results ([SearchContent]) for the [query] as typed, for a Viewer who reads the
 * [contentLanguages], with the app in the [appLanguage]. A screen asks again for each query; the
 * content it is handed says which one it answers.
 */
data class SearchResultsScreen(
    val query: String,
    val contentLanguages: List<String>,
    val appLanguage: String,
) : Screen<SearchContent>() {
    override suspend fun read(library: Library): SearchContent {
        val results = library.search(query, contentLanguages, appLanguage).first()
        return SearchContent(query, results.cards(query), results.completions)
    }
}

/**
 * What Search shows for [query]: its [cards] in order, as one grid ([SearchResults.cards]: the
 * Titles found by name, then the genres' Titles, the Channels, the Sports replays and the people's
 * Titles, a Title once), each with its own key ([SearchCard.key]), and the [completions], queries
 * one press away (genre shortcuts before anything is typed). A Kids profile's hold only the Kids
 * catalog. A query too short to search ([tooShortToSearch]) has no cards and no completions.
 *
 * What is found, and in which order, is [Library.search]'s.
 */
data class SearchContent(val query: String, val cards: List<SearchCard>, val completions: List<String>) {
    /**
     * This content with [channel]'s card marked a Favourite or not, where it stands: what a screen
     * shows once it has saved the Favourite, the cards under the Viewer staying in place. The next
     * content the Core hands over has the Channel where it ranks.
     */
    fun withFavourite(channel: Channel, favourite: Boolean): SearchContent = copy(
        cards = cards.map { if (it is SearchCard.OfChannel && it.channel.key == channel.key) SearchCard.OfChannel(it.channel.copy(favourite = favourite)) else it },
    )
}

/** This card's key among Search's [SearchContent.cards]: no other card of the grid has it. */
val SearchCard.key: String
    get() = when (this) {
        is SearchCard.OfTitle -> title.id.toString()
        is SearchCard.OfChannel -> "channel/${channel.key}"
        is SearchCard.OfReplay -> "replay/${replay.listingId}"
    }
