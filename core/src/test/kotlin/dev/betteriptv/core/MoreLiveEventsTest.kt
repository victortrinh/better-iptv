package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.ZoneId

/**
 * The Live tab's More live events row, seen through the Library: the fake Provider's Event channels
 * that none of the fake cloud's Games matched (#257). Event channel names are read in the Prefix
 * table's event time zone, Toronto's. Prior art: [GameRowsTest].
 */
class MoreLiveEventsTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private lateinit var library: Library

    /** A Sunday, 3 PM in Toronto (standard time, five hours behind UTC). */
    private val now = Instant.parse("2026-11-01T20:00:00Z")
    private val toronto = ZoneId.of("America/Toronto")
    private val stick = DeviceProfile(maxHeight = 2160, hdr = emptySet(), videoDecoders = setOf("video/avc", "video/hevc"))

    @Before fun logIn() = runBlocking {
        library = openSurveyedLibrary(folder.root.resolve("catalog.db").path, InMemoryCredentials(), cloud.url)
        library.logIn(provider.login)
        provider.movieListOverride = "[]"
        provider.showListOverride = "[]"
        Unit
    }

    @After fun tearDown() {
        library.close()
        provider.close()
        cloud.close()
    }

    private suspend fun rows(vararg leagues: League = arrayOf(League.Nhl), at: Instant = now) = library.liveRows(leagues.toList(), "CA", listOf("en"), at, toronto)

    private fun List<HomeRow>.moreLiveEvents() = filterIsInstance<HomeRow.MoreLiveEvents>().singleOrNull()?.events
    private fun List<UnmatchedEventChannel>?.names() = this?.map { it.channel.variants.first().name }

    private fun HomeRow.name() = when (this) {
        is HomeRow.LiveNow -> "live now"
        is HomeRow.Today -> "today"
        is HomeRow.LeagueGames -> "league ${league.key}"
        is HomeRow.MoreLiveEvents -> "more live events"
        is HomeRow.Favourites -> "favourites"
        is HomeRow.Channels -> "channels"
        else -> "a Home row"
    }

    @Test fun `the row follows the League rows with the Event channels no Game matched, within six hours either way, by start`() = runBlocking {
        cloud.games(
            "nhl",
            game("1", "2026-11-01T18:00:00Z", state = "live", home = "Toronto Maple Leafs", away = "Montreal Canadiens"),
            game("3", "2026-11-03T00:00:00Z", home = "Edmonton Oilers", away = "Calgary Flames"),
        )
        provider.serveChannels(
            // The Game's Feed: a Game matched it.
            "NHL: Canadiens vs Maple Leafs (2026-11-01 13:00:00)" to "US| ESPN+ PPV",
            // Starting in an hour, listed before one that started earlier: the row sorts by start.
            "ATP Paris: Djokovic vs Alcaraz (2026-11-01 16:00:00)" to "US| TENNIS PPV",
            // A stated zone: 14:30 GMT is 9:30 AM in Toronto.
            "Snooker: Champion of Champions | 2026-11-01 | 14:30 (GMT)" to "UK| PPV",
            // Just inside the window, either way: six hours ago and six hours ahead.
            "Handball: Kiel vs Flensburg (2026-11-01 09:00:00)" to "DE| PPV",
            "PBR Unleash the Beast @ Nov 1, 2026 9:00 PM" to "US| ARENA",
            // Started seven hours ago: finished, so Clutter.
            "College Football: Canisius vs Fairfield (2026-11-01 08:00:00)" to "US| ARENA",
            // Seven hours ahead: too far off.
            "NBA Summer Showcase (2026-11-01 22:00:00)" to "US| ARENA",
            // An empty slot: Clutter.
            "- NO EVENT STREAMING - | 8K EXCLUSIVE | US: ARENA PPV 25" to "US| ARENA PPV",
            // Marked on without a start the name gives: not on the row, whose cards show a time.
            "Live | Darts Grand Slam | 20:00 CET" to "UK| PPV",
            "CA: TSN 1" to "CA| SPORT",
        )
        library.sync()

        val rows = rows(League.Nhl)
        assertEquals(listOf("live now", "league nhl", "more live events", "channels"), rows.map { it.name() })
        assertEquals(
            listOf(
                "Handball: Kiel vs Flensburg (2026-11-01 09:00:00)",
                "Snooker: Champion of Champions | 2026-11-01 | 14:30 (GMT)",
                "ATP Paris: Djokovic vs Alcaraz (2026-11-01 16:00:00)",
                "PBR Unleash the Beast @ Nov 1, 2026 9:00 PM",
            ),
            rows.moreLiveEvents().names(),
        )
        assertEquals(listOf("2026-11-01T14:00:00Z", "2026-11-01T14:30:00Z", "2026-11-01T21:00:00Z", "2026-11-02T02:00:00Z"), rows.moreLiveEvents()!!.map { it.start.toString() })
        // With no League on, no Game matches anything: the NHL's Event channel is unmatched too, placed by its start.
        val noLeagues = library.liveRows(emptyList(), "CA", listOf("en"), now, toronto)
        assertEquals(listOf("more live events", "channels"), noLeagues.map { it.name() })
        assertEquals("NHL: Canadiens vs Maple Leafs (2026-11-01 13:00:00)", noLeagues.moreLiveEvents().names()!![2])
    }

    @Test fun `the row is left out when no Event channel is within six hours, and a finished Game's Event channel never joins it`() = runBlocking {
        // A Game the schedule says is over, though its Event channel's name puts its start two hours ago.
        cloud.games("nhl", game("1", "2026-11-01T18:00:00Z", state = "over", home = "Toronto Maple Leafs", away = "Montreal Canadiens"))
        provider.serveChannels(
            "NHL: Canadiens vs Maple Leafs (2026-11-01 13:00:00)" to "US| ESPN+ PPV",
            "ATP Paris: Djokovic vs Alcaraz (2026-11-02 16:00:00)" to "US| TENNIS PPV",
            "CA: TSN 1" to "CA| SPORT",
        )
        library.sync()

        assertEquals(listOf("channels"), rows().map { it.name() })
        assertNull(rows().moreLiveEvents())
        // A day on, the tennis is within the window and the row is back.
        assertEquals(listOf("ATP Paris: Djokovic vs Alcaraz (2026-11-02 16:00:00)"), rows(at = Instant.parse("2026-11-02T20:00:00Z")).moreLiveEvents().names())
    }

    @Test fun `an Event channel on the row is a Channel, watched best variant first and named without its tags`() = runBlocking {
        provider.serveChannels(
            "VIP: ATP Paris: Djokovic vs Alcaraz (2026-11-01 16:00:00) SD" to "US| TENNIS PPV",
            "VIP: ATP Paris: Djokovic vs Alcaraz (2026-11-01 16:00:00) ᴴᴰ" to "US| TENNIS PPV",
            "CA: TSN 1" to "CA| SPORT",
        )
        library.sync()

        val event = rows().moreLiveEvents()!!.single()
        assertEquals("ATP Paris: Djokovic vs Alcaraz (2026-11-01 16:00:00)", event.channel.name)
        assertEquals(2, event.channel.variants.size)
        val plan = library.channelPlayPlan(event.channel, listOf("en"), stick)
        assertEquals(
            listOf("VIP: ATP Paris: Djokovic vs Alcaraz (2026-11-01 16:00:00) ᴴᴰ", "VIP: ATP Paris: Djokovic vs Alcaraz (2026-11-01 16:00:00) SD"),
            plan.versions.map { it.name },
        )
        assertEquals(plan.versions.first(), plan.version)
    }
}
