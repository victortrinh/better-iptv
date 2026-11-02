package dev.betteriptv.core

import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The Live tab's Game rows, seen through the Library: the fake cloud's Games matched to a fake
 * Provider's Event channels and Broadcaster Channels (#254). Prior art: [HomeRowsTest].
 */
class GameRowsTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private lateinit var library: Library

    /** A Sunday, 4 PM in Toronto. */
    private val now = Instant.parse("2026-11-01T20:00:00Z")
    private val toronto = ZoneId.of("America/Toronto")

    @Before fun logIn() = runBlocking {
        library = Library.open(folder.root.resolve("catalog.db").path, InMemoryCredentials(), cloud.url)
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

    private suspend fun rows(vararg leagues: League = arrayOf(League.Nhl, League.Nfl), languages: List<String> = listOf("en"), at: Instant = now) =
        library.liveRows(leagues.toList(), languages, at, toronto)

    private fun List<HomeRow>.liveNow() = filterIsInstance<HomeRow.LiveNow>().singleOrNull()?.games
    private fun List<HomeRow>.today() = filterIsInstance<HomeRow.Today>().singleOrNull()?.games
    private fun List<HomeRow>.league(league: League) = filterIsInstance<HomeRow.LeagueGames>().singleOrNull { it.league == league }?.games
    private fun List<GameFeeds>?.ids() = this?.map { it.game.id }
    private fun List<GameFeeds>?.feedNames(id: String) = this?.single { it.game.id == id }?.feeds?.map { it.channel.variants.first().name }

    /** A row's name, so a whole screen reads as a list. */
    private fun HomeRow.name() = when (this) {
        is HomeRow.LiveNow -> "live now"
        is HomeRow.Today -> "today"
        is HomeRow.LeagueGames -> "league ${league.key}"
        is HomeRow.Favourites -> "favourites"
        else -> "a Home row"
    }

    /** Sunday's slate: a Game on now, one tonight, one on Monday and an NFL Game on Wednesday; nothing in the MLB. */
    private fun serveSlate() {
        cloud.games(
            "nhl",
            game("1", "2026-11-01T18:00:00Z", state = "live", home = "Toronto Maple Leafs", away = "Montreal Canadiens"),
            game("2", "2026-11-02T00:00:00Z", home = "Boston Bruins", away = "Ottawa Senators", broadcasters = listOf("TSN")),
            game("3", "2026-11-03T00:00:00Z", home = "Edmonton Oilers", away = "Calgary Flames"),
        )
        cloud.games("nfl", game("100", "2026-11-05T01:15:00Z", league = "nfl", home = "Denver Broncos", away = "Houston Texans", broadcasters = listOf("Prime Video")))
    }

    @Test fun `Live now, Today and a row per League come in that order, each Game once, with the Favourites last and an empty League left out`() = runBlocking {
        serveSlate()
        provider.serveChannels(
            "NHL: Canadiens vs Maple Leafs (2026-11-01 18:00:00)" to "US| ESPN+ PPV",
            "Senators @ Bruins (2026-11-01 19:00:00)" to "US| NHL PPV",
            "Texans vs Broncos (2026-11-04 20:15:00)" to "US| NFL PPV",
            "CA: TSN 1" to "CA| SPORT",
        )
        library.sync()
        library.setFavourite(library.search("TSN", listOf("en"), "en").last().channels.single().key, true)

        val rows = rows(League.Nhl, League.Nfl, League.Mlb)
        assertEquals(listOf("live now", "today", "league nhl", "league nfl", "favourites"), rows.map { it.name() })
        assertEquals(listOf("1"), rows.liveNow().ids())
        // Tonight's Game is the NHL's too, but Today is the highest row it fits.
        assertEquals(listOf("2"), rows.today().ids())
        assertEquals(listOf("3"), rows.league(League.Nhl).ids())
        assertEquals(listOf("100"), rows.league(League.Nfl).ids())
        assertEquals(listOf("NHL: Canadiens vs Maple Leafs (2026-11-01 18:00:00)"), rows.liveNow().feedNames("1"))
        assertEquals(listOf("Texans vs Broncos (2026-11-04 20:15:00)"), rows.league(League.Nfl).feedNames("100"))
        // The League rows follow Settings' order, not the schedule's.
        assertEquals(listOf("league nfl", "league nhl"), rows(League.Nfl, League.Nhl).filterIsInstance<HomeRow.LeagueGames>().map { it.name() })
        // With no League on, only the Favourites row is left.
        assertEquals(listOf("favourites"), rows().let { library.liveRows(emptyList(), listOf("en"), now, toronto) }.map { it.name() })
    }

    @Test fun `an Event channel is a Feed when it names both teams, or one team and the League, by any alias, whatever its case and accents`() = runBlocking {
        serveSlate()
        provider.serveChannels(
            // Both teams, by their full names, shouted and accented.
            "LIVE | MONTRÉAL CANADIENS VS TORONTO MAPLE LEAFS | 13:00 EST" to "US| ARENA",
            // One team and a name of the League: "Habs" is no alias ESPN knows.
            "Hockey: Leafs vs Habs @ Nov 1 1:00 PM" to "CA| SPORTS PPV",
            // One team and no League, and an alias found only inside another word: not this Game.
            "Jetsons vs Leafs (2026-11-01 18:00:00)" to "US| ARENA",
            // Both teams, on another day: not this Game.
            "Canadiens vs Maple Leafs (2026-11-03 18:00:00)" to "US| ARENA",
            // The League and a team of another Game.
            "NHL: Bruins vs Senators (2026-11-01 19:00:00)" to "US| ARENA",
        )
        library.sync()

        val live = rows().liveNow()
        assertEquals(
            listOf("LIVE | MONTRÉAL CANADIENS VS TORONTO MAPLE LEAFS | 13:00 EST", "Hockey: Leafs vs Habs @ Nov 1 1:00 PM"),
            live.feedNames("1"),
        )
        assertEquals(listOf("NHL: Bruins vs Senators (2026-11-01 19:00:00)"), rows().today().feedNames("2"))
    }

    @Test fun `an Event channel's time counts as the Game's read in any zone, but not the day before or after`() = runBlocking {
        // 7 PM in Toronto on Sunday, midnight UTC on Monday.
        cloud.games("nhl", game("2", "2026-11-02T00:00:00Z", home = "Boston Bruins", away = "Ottawa Senators"))
        provider.serveChannels(
            // Named in Toronto's clock: Sunday evening.
            "Senators @ Bruins (2026-11-01 19:00:00)" to "US| NHL PPV",
            // Named in UTC: Monday, within the hour.
            "Senators @ Bruins | 2026-11-02 | 00:30 (GMT)" to "UK| PPV",
            // Named in Toronto's clock on Monday evening: the day after, another Game if any.
            "Senators @ Bruins (2026-11-02 19:00:00)" to "US| NHL PPV",
            // Named in Toronto's clock the evening before.
            "Senators @ Bruins (2026-10-31 19:00:00)" to "US| NHL PPV",
        )
        library.sync()

        assertEquals(
            listOf("Senators @ Bruins (2026-11-01 19:00:00)", "Senators @ Bruins | 2026-11-02 | 00:30 (GMT)"),
            rows().today().feedNames("2"),
        )
    }

    @Test fun `an upcoming Game with no Feed is listed without one, and a live Game with no Feed is hidden`() = runBlocking {
        serveSlate()
        cloud.games("nhl", game("1", "2026-11-01T18:00:00Z", state = "live"), game("3", "2026-11-03T00:00:00Z", home = "Edmonton Oilers", away = "Calgary Flames"))
        provider.serveChannels("CA: TSN 1" to "CA| SPORT")
        library.sync()

        val rows = rows()
        assertNull(rows.liveNow())
        val monday = rows.league(League.Nhl)!!.single()
        assertEquals("3", monday.game.id)
        assertEquals(emptyList<Feed>(), monday.feeds)
        assertNull(monday.best)
    }

    @Test fun `Feeds rank Content language first, then Event channel before Broadcaster, then quality, and say their Broadcaster`() = runBlocking {
        serveSlate()
        provider.serveChannels(
            "CA FR: Senators vs Bruins (2026-11-01 19:00:00)" to "CA| RDS PPV",
            "Senators @ Bruins (2026-11-01 19:00:00)" to "US| NHL PPV",
            "CA: TSN" to "CA| SPORT",
            "CA: TSN 1" to "CA| SPORT",
            "US: Senators vs Bruins (2026-11-01 19:00:00) ᴴᴰ" to "US| NHL PPV",
            "FR: TSN" to "FR| SPORT",
        )
        library.sync()

        val english = rows().today()!!.single().feeds
        assertEquals(
            listOf("US: Senators vs Bruins (2026-11-01 19:00:00) ᴴᴰ", "Senators @ Bruins (2026-11-01 19:00:00)", "CA: TSN", "CA FR: Senators vs Bruins (2026-11-01 19:00:00)", "FR: TSN"),
            english.map { it.channel.variants.first().name },
        )
        assertEquals(listOf("en", "en", "en", "fr", "fr"), english.map { it.language })
        assertEquals(listOf(FeedKind.EventChannel, FeedKind.EventChannel, FeedKind.Broadcaster, FeedKind.EventChannel, FeedKind.Broadcaster), english.map { it.kind })
        assertEquals(listOf(null, null, "TSN", null, "TSN"), english.map { it.broadcaster })
        // "TSN 1" is not TSN: a Broadcaster's Channel is named exactly like it.
        assertFalse(english.any { it.channel.name == "TSN 1" })

        val french = rows(languages = listOf("fr", "en")).today()!!.single().feeds
        assertEquals("CA FR: Senators vs Bruins (2026-11-01 19:00:00)", french.first().channel.variants.first().name)
        assertEquals(listOf("fr", "fr", "en", "en", "en"), french.map { it.language })
    }

    @Test fun `the Games are asked of the cloud again after a minute, and the kept copy stands when it fails`() = runBlocking {
        serveSlate()
        provider.serveChannels("NHL: Canadiens vs Maple Leafs (2026-11-01 18:00:00)" to "US| ESPN+ PPV")
        library.sync()

        val first = rows()
        assertEquals(1, cloud.gamesAsked.size)
        rows(at = now + Duration.ofSeconds(30))
        assertEquals(1, cloud.gamesAsked.size)
        // The tab asks every 2 minutes while a Games row shows: each of those asks reaches the cloud.
        rows(at = now + Duration.ofMinutes(2))
        assertEquals(2, cloud.gamesAsked.size)

        cloud.down = true
        val kept = rows(at = now + Duration.ofMinutes(4))
        assertEquals(3, cloud.gamesAsked.size)
        assertEquals(first.map { it.name() }, kept.map { it.name() })
        assertEquals(first.liveNow().ids(), kept.liveNow().ids())
        assertTrue(kept.liveNow()!!.single().feeds.isNotEmpty())
    }
}
