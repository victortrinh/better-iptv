package dev.betteriptv.core

import kotlinx.datetime.TimeZone

/**
 * The Live tab ([LiveTabContent]), for a Viewer following the [leagues], in Settings' order, in the
 * [region], who reads the [contentLanguages]. Turning a League on or off is asking for another
 * screen: the app asks again with the Leagues now on.
 */
data class LiveTab(
    val leagues: List<League> = emptyList(),
    val region: String = Library.DEFAULT_REGION,
    val contentLanguages: List<String> = emptyList(),
) : Screen<LiveTabContent>() {
    override suspend fun read(library: Library) =
        LiveTabContent(
            library.liveRows(leagues, region, contentLanguages, library.screenClock.now(), library.screenZone ?: TimeZone.currentSystemDefault()).withUniqueCards(),
        )

    // Games start and end, and the Event channels of More live events come and go, by the clock: while
    // one of their Rows shows, the Games are asked for again. A tab with none stands until a change.
    override fun readAgainAfter(content: LiveTabContent) = GAMES_READ_AGAIN_AFTER.takeIf { content.rows.any { it.movesWithTheClock } }
}

/**
 * What is on the Live tab: its [rows] in order, top to bottom, each with its cards in order. Live
 * now, Today and a Row per League hold Games, each Game in one Row only, the highest it fits; then
 * More live events, Replays, the Viewer's Favourites and the Channels' countries. A Kids profile's
 * hold its Favourites and the Kids Channels by country. A Row with nothing is left out. Each Row has
 * its own [key], and each of its cards its own key in it ([cardKeys]).
 *
 * Which Rows there are, and what a Row leaves out, is [Library.liveRows]'s.
 */
data class LiveTabContent(val rows: List<HomeRow>)

/** Whether a Row holds Games, or the Event channels no Game matched: what it holds changes as time passes, with nothing else changing. */
private val HomeRow.movesWithTheClock: Boolean
    get() = this is HomeRow.LiveNow || this is HomeRow.Today || this is HomeRow.LeagueGames || this is HomeRow.MoreLiveEvents
