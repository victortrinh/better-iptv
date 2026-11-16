package dev.betteriptv.core

import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * A Game's page ([GamePageContent]), for the [game] a card opened, for a Viewer who reads the
 * [contentLanguages], in the [region]. The Game is known by its League and the schedule's id: the
 * content carries it as the device has it now, so a Game that went live or ended since its card was
 * read says so.
 */
data class GamePage(
    val game: Game,
    val contentLanguages: List<String>,
    val region: String = Library.DEFAULT_REGION,
) : Screen<GamePageContent>() {
    override suspend fun read(library: Library) = library.gamePage(game, contentLanguages, region, Clock.System.now(), TimeZone.currentSystemDefault())
}

/**
 * What is on a Game's page: the Game with its Feeds in order ([entry]: best first, the ones marked
 * "Not this game" in this Profile last and marked, [Feed.dropped]), each named by what tells it
 * apart ([Feed.label]), and a finished Game's [replay] once the Provider has one. [best] is the Feed
 * Play starts; [groups] is how the page lists the Feeds.
 *
 * A card's [GameFeeds] is the page's content before the Core is asked: `GamePageContent(entry)`.
 */
data class GamePageContent(val entry: GameFeeds, val replay: GameReplay? = null) {
    val game: Game get() = entry.game

    /** The Feed Play starts, or null when the Game has none left ([GameFeeds.best]). */
    val best: Feed? get() = entry.best

    /** How many Feeds the Game has left, the ones marked "Not this game" not counted. */
    val feedsLeft: Int get() = entry.feeds.count { !it.dropped }

    /**
     * The Feeds as the page lists them, every Feed in one group: a group per language, in the order
     * of each language's best Feed (so the Viewer's Content languages come first, in their order,
     * then the Feeds whose language isn't known, then the other languages), each group's Feeds best
     * first; then the Feeds marked "Not this game", as one group, whatever their language.
     */
    val groups: List<FeedGroup> = buildList {
        val (dropped, left) = entry.feeds.partition { it.dropped }
        left.groupBy { it.language }.forEach { (language, feeds) -> add(FeedGroup.Language(language, feeds)) }
        if (dropped.isNotEmpty()) add(FeedGroup.NotThisGame(dropped))
    }
}

/** One group of a Game's Feeds on its page ([GamePageContent.groups]), its [feeds] best first. */
sealed interface FeedGroup {
    val feeds: List<Feed>

    /** The Feeds in one [language] (a primary language subtag), or those whose language isn't known (null). */
    data class Language(val language: String?, override val feeds: List<Feed>) : FeedGroup

    /** The Feeds the Viewer marked "Not this game" in this Profile. */
    data class NotThisGame(override val feeds: List<Feed>) : FeedGroup
}

/** A finished Game's Replay on its page: the Sports [replay] ([Library.gameReplay]) and what playing it starts ([Library.replayPlayPlan]). */
data class GameReplay(val replay: Replay, val plan: PlayPlan)

/**
 * [game]'s page as of [now], in the device's [zone]: the Game as the device has it now (the kept
 * schedule's, without waiting on the cloud; [game] itself when the schedule no longer lists it),
 * its Feeds from the catalog ranked for the [contentLanguages] and [region] with this Profile's
 * "Not this game" marks, and its Replay when it is over.
 */
internal suspend fun Library.gamePage(game: Game, contentLanguages: List<String>, region: String, now: Instant, zone: TimeZone): GamePageContent {
    val current = games(listOf(game.league), now, waitForCloud = false).firstOrNull { it.id == game.id } ?: game
    val entry = gameFeeds(current, contentLanguages, region, now)
    val replay = gameReplay(current, zone)?.let { GameReplay(it, replayPlayPlan(it, contentLanguages)) }
    return GamePageContent(entry, replay)
}
