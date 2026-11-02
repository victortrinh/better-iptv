package dev.betteriptv.app

import android.content.Context
import dev.betteriptv.app.ui.components.CardInfo
import dev.betteriptv.core.Channel
import dev.betteriptv.core.ChannelVariant
import dev.betteriptv.core.Feed
import dev.betteriptv.core.FeedKind
import dev.betteriptv.core.Game
import dev.betteriptv.core.GameFeeds
import dev.betteriptv.core.GameState
import dev.betteriptv.core.GameTeam
import dev.betteriptv.core.Quality
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

/** How a Game is named on its card, its page and under its rail: "Live" or its start, the teams, never a score. */
@RunWith(RobolectricTestRunner::class)
class GameLabelsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val toronto = ZoneId.of("America/Toronto")
    /** Sunday 2026-11-01, 4 PM in Toronto. */
    private val now = Instant.parse("2026-11-01T20:00:00Z")

    private fun team(name: String, short: String) = GameTeam(name, short, short.take(3).uppercase(), listOf(name, short), null)
    private val leafs = team("Toronto Maple Leafs", "Maple Leafs")
    private val habs = team("Montreal Canadiens", "Canadiens")
    private fun game(start: String, state: GameState = GameState.Upcoming, broadcasters: List<String> = emptyList()) =
        Game("nhl", "1", Instant.parse(start), state, home = leafs, away = habs, broadcasters = broadcasters)
    private fun channel(name: String, country: String? = "US") = Channel("$country: $name", name, country, null, false, listOf(ChannelVariant(1, name, Quality.HD, false)))

    @Test fun aGameOnNowSaysLiveAndOneTonightSaysWhenItStartsInTheDevicesClock() {
        assertEquals("Live", context.gameCaption(game("2026-11-01T18:00:00Z", GameState.Live), now, toronto))
        // 7 PM in Toronto, midnight UTC: still Sunday for the Viewer.
        assertEquals("7:00 PM", context.gameCaption(game("2026-11-02T00:00:00Z"), now, toronto))
    }

    @Test fun aGameOnAnotherDayNamesItsWeekday() {
        assertEquals("Tue 7:00 PM", context.gameCaption(game("2026-11-04T00:00:00Z"), now, toronto))
    }

    @Test
    @Config(qualifiers = "fr")
    fun theCaptionAndTheTeamsReadInFrenchToo() {
        assertEquals("En direct", context.gameCaption(game("2026-11-01T18:00:00Z", GameState.Live), now, toronto))
        assertEquals("Canadiens c. Maple Leafs", context.matchup(game("2026-11-02T00:00:00Z")))
        assertEquals("Aucun flux trouvé", context.gameInfo(GameFeeds(game("2026-11-02T00:00:00Z"), emptyList())).feeds)
    }

    @Test fun theMatchupPutsTheVisitorsFirst() {
        assertEquals("Canadiens at Maple Leafs", context.matchup(game("2026-11-02T00:00:00Z")))
        assertEquals("Montreal Canadiens at Toronto Maple Leafs", context.matchup(game("2026-11-02T00:00:00Z"), short = false))
    }

    @Test fun theRailInfoNamesTheLeagueTheTeamsTheBroadcastersFoundAndTheFeedCount() {
        val feeds = listOf(Feed(channel("Canadiens vs Maple Leafs"), FeedKind.EventChannel, "en"), Feed(channel("TSN", "CA"), FeedKind.Broadcaster, "en", "TSN"))
        // The schedule also names Sportsnet, but no Sportsnet Channel was found: only TSN is named.
        assertEquals(
            CardInfo.Game("NHL · Canadiens at Maple Leafs", "TSN · 2 Feeds"),
            context.gameInfo(GameFeeds(game("2026-11-02T00:00:00Z", broadcasters = listOf("Sportsnet", "TSN")), feeds)),
        )
        assertEquals(CardInfo.Game("NHL · Canadiens at Maple Leafs", "No stream found"), context.gameInfo(GameFeeds(game("2026-11-02T00:00:00Z"), emptyList())))
        // A dropped Feed is neither counted nor named.
        val dropped = listOf(feeds[0], feeds[1].copy(dropped = true))
        assertEquals(CardInfo.Game("NHL · Canadiens at Maple Leafs", "1 Feed"), context.gameInfo(GameFeeds(game("2026-11-02T00:00:00Z", broadcasters = listOf("TSN")), dropped)))
        assertEquals(CardInfo.Game("NHL · Canadiens at Maple Leafs", "No stream found"), context.gameInfo(GameFeeds(game("2026-11-02T00:00:00Z"), dropped.map { it.copy(dropped = true) })))
    }

    @Test fun theLabelUnderPlayNamesTheFeedAndItsLanguage() {
        assertEquals("Canadiens vs Maple Leafs · English", context.feedLabel(Feed(channel("Canadiens vs Maple Leafs"), FeedKind.EventChannel, "en")))
        assertEquals("ARENA 4 · Language not named", context.feedLabel(Feed(channel("ARENA 4", null), FeedKind.EventChannel, null)))
        assertEquals("Event channel", context.feedKind(Feed(channel("ARENA 4", null), FeedKind.EventChannel, null)))
        assertEquals("Broadcaster · TSN", context.feedKind(Feed(channel("TSN", "CA"), FeedKind.Broadcaster, "en", "TSN")))
        assertEquals("League network · NHL Network", context.feedKind(Feed(channel("NHL Network"), FeedKind.LeagueNetwork, "en", "NHL Network")))
        assertEquals("Event channel · Not this game", context.feedKind(Feed(channel("ARENA 4", null), FeedKind.EventChannel, null, dropped = true)))
    }

    @Test fun thePlayersFeedRowsSayWhatEachFeedIsAndWhichIsPlaying() {
        assertEquals("Playing · Broadcaster · TSN · English", context.feedNote(Feed(channel("TSN", "CA"), FeedKind.Broadcaster, "en", "TSN"), playing = true))
        assertEquals("Event channel · Language not named", context.feedNote(Feed(channel("ARENA 4", null), FeedKind.EventChannel, null), playing = false))
    }
}
