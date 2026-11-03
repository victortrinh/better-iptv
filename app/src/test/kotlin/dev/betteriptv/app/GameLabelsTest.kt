package dev.betteriptv.app

import android.content.Context
import dev.betteriptv.app.ui.components.CardInfo
import dev.betteriptv.core.Channel
import dev.betteriptv.core.ChannelVariant
import dev.betteriptv.core.Feed
import dev.betteriptv.core.FeedKind
import dev.betteriptv.core.Game
import dev.betteriptv.core.GameFeeds
import dev.betteriptv.core.GameEvent
import dev.betteriptv.core.GameFight
import dev.betteriptv.core.GameFighter
import dev.betteriptv.core.GameSession
import dev.betteriptv.core.GameState
import dev.betteriptv.core.GameTeam
import dev.betteriptv.core.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    private fun race(session: GameSession = GameSession.Race) = Game(
        "f1", "405", Instant.parse("2026-11-01T17:00:00Z"), GameState.Live, home = null, away = null, broadcasters = listOf("Apple TV"),
        event = GameEvent("MSC Cruises São Paulo Grand Prix", "MSC Cruises São Paulo GP", session, listOf("São Paulo"), null),
    )
    private fun round(session: GameSession, start: String = "2026-11-05T13:30:00Z", dayOnly: Boolean = false) = Game(
        "pga", "401850979-1", Instant.parse(start), GameState.Upcoming, home = null, away = null, broadcasters = listOf("Golf Chnl"),
        event = GameEvent("World Wide Technology Championship", "World Wide Technology Championship", session, emptyList(), null), dayOnly = dayOnly,
    )
    private val toto = Game(
        "lpga", "401835175-1", Instant.parse("2026-11-04T23:00:00Z"), GameState.Upcoming, home = null, away = null, broadcasters = listOf("Golf Chnl"),
        event = GameEvent("TOTO Japan Classic", "TOTO Japan Classic", GameSession.Round1, listOf("TOTO Japan Classic", "Japan Classic"), null),
    )
    private fun card(name: String = "UFC 334: Gane vs. Hokit", shortName: String = "UFC 334") = Game(
        "ufc", "600061400", Instant.parse("2026-11-01T22:00:00Z"), GameState.Upcoming, home = null, away = null, broadcasters = listOf("Paramount+"),
        fight = GameFight(name, shortName, Instant.parse("2026-11-02T02:00:00Z"), listOf(GameFighter("Ciryl Gane", "Gane", listOf("Gane"), null), GameFighter("Josh Hokit", "Hokit", listOf("Hokit"), null))),
    )
    private fun channel(name: String, country: String? = "US") = Channel("$country: $name", name, country, null, false, listOf(ChannelVariant(1, name, Quality.HD, false)))

    @Test fun anEventChannelsStartReadsAsAGamesDoesTheTimeAloneTodayAndTheWeekdayLater() {
        assertEquals("7:00 PM", context.startCaption(Instant.parse("2026-11-02T00:00:00Z"), now, toronto))
        assertEquals("Mon 7:00 PM", context.startCaption(Instant.parse("2026-11-03T00:00:00Z"), now, toronto))
    }

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

    @Test fun anF1SessionIsNamedByItsGrandPrixAndSessionWhereAGameNamesItsTeams() {
        assertEquals("MSC Cruises São Paulo GP · Race", context.matchup(race()))
        assertEquals("MSC Cruises São Paulo Grand Prix · Qualifying", context.matchup(race(GameSession.Qualifying), short = false))
        assertEquals("MSC Cruises São Paulo GP · Sprint", context.matchup(race(GameSession.Sprint)))
        assertEquals(CardInfo.Game("F1 · MSC Cruises São Paulo GP · Race", "No stream found"), context.gameInfo(GameFeeds(race(), emptyList())))
    }

    @Test
    @Config(qualifiers = "fr")
    fun anF1SessionReadsInFrenchToo() {
        assertEquals("MSC Cruises São Paulo GP · Course", context.matchup(race()))
        assertEquals("MSC Cruises São Paulo GP · Qualifications", context.matchup(race(GameSession.Qualifying)))
        assertEquals("F1 · MSC Cruises São Paulo GP · Sprint", context.gameInfo(GameFeeds(race(GameSession.Sprint), emptyList())).teams)
    }

    @Test fun aUfcCardIsNamedByItsMainEventsFightersAndTheCardsShortNameStandsForTheLeague() {
        assertEquals("Gane vs. Hokit", context.matchup(card()))
        assertEquals("UFC 334: Gane vs. Hokit", context.matchup(card(), short = false))
        assertEquals(CardInfo.Game("UFC 334 · Gane vs. Hokit", "No stream found"), context.gameInfo(GameFeeds(card(), emptyList())))
        assertEquals("UFC Fight Night · Gane vs. Hokit", context.gameInfo(GameFeeds(card("UFC Fight Night: Gane vs. Hokit", "UFC Fight Night"), emptyList())).teams)
        // The main card's start, in the device's clock, for the page's header.
        assertEquals("Main card 9:00 PM", context.mainCardCaption(card(), Instant.parse("2026-11-01T22:00:00Z"), ZoneId.of("America/Toronto")))
    }

    @Test
    @Config(qualifiers = "fr")
    fun aUfcCardReadsInFrenchToo() {
        assertEquals("Gane c. Hokit", context.matchup(card()))
        assertEquals("UFC 334 · Gane c. Hokit", context.gameInfo(GameFeeds(card(), emptyList())).teams)
        assertTrue(context.mainCardCaption(card(), Instant.parse("2026-11-01T22:00:00Z"), ZoneId.of("America/Toronto"))!!.startsWith("Carte principale "))
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

    @Test fun aGolfRoundIsNamedByItsTournamentAndRound() {
        assertEquals("World Wide Technology Championship · Round 1", context.matchup(round(GameSession.Round1)))
        assertEquals("World Wide Technology Championship · Round 3", context.matchup(round(GameSession.Round3)))
        assertEquals("World Wide Technology Championship · Final round", context.matchup(round(GameSession.FinalRound)))
        assertEquals(CardInfo.Game("PGA Tour · World Wide Technology Championship · Round 2", "No stream found"), context.gameInfo(GameFeeds(round(GameSession.Round2), emptyList())))
    }

    @Test fun anLpgaRoundIsNamedByTheLpgaItsTournamentAndRound() {
        assertEquals(CardInfo.Game("LPGA · TOTO Japan Classic · Round 1", "No stream found"), context.gameInfo(GameFeeds(toto, emptyList())))
    }

    @Test fun aRoundWhoseTeeTimesAreNotOutYetSaysItsDayAlone() {
        // Noon in Toronto: the day is known, not the time.
        assertEquals("Today", context.gameCaption(round(GameSession.Round1, "2026-11-01T17:00:00Z", dayOnly = true), now, toronto))
        assertEquals("Thu", context.gameCaption(round(GameSession.Round1, "2026-11-05T17:00:00Z", dayOnly = true), now, toronto))
        assertEquals("Thu 8:30 AM", context.gameCaption(round(GameSession.Round1), now, toronto))
    }

    @Test
    @Config(qualifiers = "fr")
    fun aGolfRoundReadsInFrenchToo() {
        assertEquals("World Wide Technology Championship · 1er tour", context.matchup(round(GameSession.Round1)))
        assertEquals("World Wide Technology Championship · Dernier tour", context.matchup(round(GameSession.FinalRound)))
        assertEquals("Aujourd'hui", context.gameCaption(round(GameSession.Round1, "2026-11-01T17:00:00Z", dayOnly = true), now, toronto))
        assertEquals(CardInfo.Game("LPGA · TOTO Japan Classic · 1er tour", "Aucun flux trouvé"), context.gameInfo(GameFeeds(toto, emptyList())))
    }
}
