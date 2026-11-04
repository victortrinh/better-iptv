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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Sports replays (#258), seen through the Library after a Sync of a fake Provider's replay Listings:
 * their teams and dates read off the survey's name patterns, Live's Replays row for the Leagues on
 * (teams from the fake cloud's Games), a finished Game's Replay, and what playing one starts.
 * Prior art: [GameRowsTest].
 */
class ReplaysTest {
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
        provider.showCategoriesOverride = "[]"
        provider.showListOverride = "[]"
        provider.liveListOverride = "[]"
        Unit
    }

    @After fun tearDown() {
        library.close()
        provider.close()
        cloud.close()
    }

    private suspend fun rows(vararg leagues: League = arrayOf(League.Nhl, League.Nfl)) = library.liveRows(leagues.toList(), "CA", listOf("en"), now, toronto)

    private fun List<HomeRow>.replays() = filterIsInstance<HomeRow.Replays>().singleOrNull()?.replays

    private fun HomeRow.name() = when (this) {
        is HomeRow.Today -> "today"
        is HomeRow.LeagueGames -> "league ${league.key}"
        is HomeRow.Replays -> "replays"
        is HomeRow.Favourites -> "favourites"
        is HomeRow.Channels -> "channels"
        else -> "another row"
    }

    private suspend fun replay(name: String): Replay = library.replays().single { it.name == name }

    @Test fun `the teams and the date are read off the survey's name patterns, and a Replay is never a Title`() = runBlocking {
        // Every one carries TMDB ID 1 (FakeProvider.serveMovies), which is wrong for all of them and ignored.
        provider.serveMovies(
            "SOC - Boston Bruins at Toronto Maple Leafs 09.11.25" to "SOCCER HOCKEY",
            "SOC - Villarreal - Rayo Vallecano 01.11.2025" to "SOCCER FOOTBALL",
            "SOC - STL Blues vs VAN Canucks 13.10.25" to "SOCCER HOCKEY",
            "SOC - Uzbekistan v Colombia 17.06.2026" to "SOCCER FOOTBALL",
            "SOC - Winnipeg Jets vs. Calgary Flames 20.10.25" to "SOCCER HOCKEY",
            "SOC - Colorado Avalanche - Tampa Bay Lightning04.11.25" to "SOCCER HOCKEY",
            "SOC - Newcastle United vs Tottenham Hotspur 29.10" to "SOCCER FOOTBALL",
            "SOC - 16 SMU Mustangs at 23 Louisville Cardinals 27.09.2026" to "SOCCER AMERICAN FOOTBALL",
            "Wresling: WWE NXT 2024.04.23" to "WRESTLING",
            "SOC - UFC 321 Aspinall vs. Gane (2025)" to "SOCCER UFC",
            "SOC - LaLiga Highlights Matchday 38" to "SOCCER FOOTBALL",
            "S0C - Western Bulldogs - Sydney Swans 23.05.2024" to "SOCCER RUGBY",
            // A team written like a Prefix is a team.
            "SOC - LAFC - LA Galaxy 05.10.2025" to "SOCCER FOOTBALL",
            "SOC - PSG - Lyon 02.11.2025" to "SOCCER FOOTBALL",
            "EN - The Godfather (1972)" to "EN - MOVIES",
        )
        library.sync()

        fun check(name: String, away: String?, home: String?, date: String?, year: Int? = date?.take(4)?.toInt()) = runBlocking {
            val replay = replay(name)
            assertEquals(name, listOf(away, home, date?.let(LocalDate::parse), year), listOf(replay.away, replay.home, replay.date, replay.year))
        }
        // "at": the first team is away; "vs", "v" and a dash: the first team is at home.
        check("SOC - Boston Bruins at Toronto Maple Leafs 09.11.25", "Boston Bruins", "Toronto Maple Leafs", "2025-11-09")
        check("SOC - Villarreal - Rayo Vallecano 01.11.2025", "Rayo Vallecano", "Villarreal", "2025-11-01")
        check("SOC - STL Blues vs VAN Canucks 13.10.25", "VAN Canucks", "STL Blues", "2025-10-13")
        check("SOC - Uzbekistan v Colombia 17.06.2026", "Colombia", "Uzbekistan", "2026-06-17")
        check("SOC - Winnipeg Jets vs. Calgary Flames 20.10.25", "Calgary Flames", "Winnipeg Jets", "2025-10-20")
        check("SOC - Colorado Avalanche - Tampa Bay Lightning04.11.25", "Tampa Bay Lightning", "Colorado Avalanche", "2025-11-04")
        // A date cut short is no date, and not part of a team's name.
        check("SOC - Newcastle United vs Tottenham Hotspur 29.10", "Tottenham Hotspur", "Newcastle United", null)
        check("SOC - 16 SMU Mustangs at 23 Louisville Cardinals 27.09.2026", "16 SMU Mustangs", "23 Louisville Cardinals", "2026-09-27")
        check("Wresling: WWE NXT 2024.04.23", null, null, "2024-04-23")
        check("SOC - UFC 321 Aspinall vs. Gane (2025)", "Gane", "UFC 321 Aspinall", null, 2025)
        check("SOC - LaLiga Highlights Matchday 38", null, null, null, null)
        check("S0C - Western Bulldogs - Sydney Swans 23.05.2024", "Sydney Swans", "Western Bulldogs", "2024-05-23")
        check("SOC - LAFC - LA Galaxy 05.10.2025", "LA Galaxy", "LAFC", "2025-10-05")
        check("SOC - PSG - Lyon 02.11.2025", "Lyon", "PSG", "2025-11-02")
        assertEquals("Boston Bruins at Toronto Maple Leafs 09.11.25", replay("SOC - Boston Bruins at Toronto Maple Leafs 09.11.25").heading)
        assertEquals("WWE NXT 2024.04.23", replay("Wresling: WWE NXT 2024.04.23").heading)
        assertEquals("LAFC - LA Galaxy 05.10.2025", replay("SOC - LAFC - LA Galaxy 05.10.2025").heading)
        // The Godfather alone is a Title; the Replays' shared TMDB ID makes none.
        assertEquals(1, library.movieListingCount())
        assertEquals(1, library.titleCount())
        assertEquals(14, library.replays().size)
        library.prepareSearch()
        assertEquals(listOf("SOC - LAFC - LA Galaxy 05.10.2025"), library.search("lafc", listOf("en"), "en").last().replays.map { it.name })
    }

    /** This week's NHL and NFL Games, naming the teams the Replays are matched to. */
    private fun serveSchedule() {
        cloud.games(
            "nhl",
            game("1", "2025-11-10T00:00:00Z", state = "over", home = "Toronto Maple Leafs", away = "Boston Bruins"),
            game("2", "2026-11-02T00:00:00Z", home = "Ottawa Senators", away = "Montreal Canadiens"),
            game("3", "2025-10-20T23:00:00Z", state = "over", home = "Calgary Flames", away = "Winnipeg Jets"),
        )
        cloud.games("nfl", game("100", "2026-11-05T01:15:00Z", league = "nfl", home = "Denver Broncos", away = "Houston Texans"))
    }

    @Test fun `the Replays row holds the Leagues' Replays newest first, after the League rows, and nothing that matches no League`() = runBlocking {
        serveSchedule()
        provider.serveMovies(
            // Newest first in the Provider's list, so a shared date is settled by `added`: the first served was added last.
            "SOC - Ottawa Senators at Boston Bruins 15.11.25" to "SOCCER HOCKEY",
            "SOC - Boston Bruins at Toronto Maple Leafs 09.11.25" to "SOCCER HOCKEY",
            "SOC - Montreal Canadiens - Ottawa Senators 20.10.25" to "SOCCER HOCKEY",
            "SOC - Leafs vs Senators 20.10.25" to "SOCCER HOCKEY",
            "SOC - Houston Texans at Denver Broncos 05.10.2025" to "SOCCER AMERICAN FOOTBALL",
            // One team of a League on and one of none.
            "SOC - Toronto Maple Leafs at Toronto Raptors 01.01.25" to "SOCCER HOCKEY",
            // A city alone names no team: it could be another League's.
            "SOC - Toronto at Boston 02.01.25" to "SOCCER HOCKEY",
            "SOC - Villarreal - Rayo Vallecano 01.11.2025" to "SOCCER FOOTBALL",
            "SOC - Saracens v Northampton Saints" to "SOCCER RUGBY",
            "SOC - NHL All-Star game 15.02.2026" to "SOCCER HOCKEY",
            "SOC - Boston Bruins at Boston Bruins 03.01.25" to "SOCCER HOCKEY",
        )
        library.sync()

        val rows = rows(League.Nhl, League.Nfl)
        // Tonight's NHL Game is in Today; the other NHL Games are over, so the NHL gets no row; no Channels are served.
        assertEquals(listOf("today", "league nfl", "replays"), rows.map { it.name() })
        val replays = rows.replays()!!
        assertEquals(
            listOf(
                "SOC - Ottawa Senators at Boston Bruins 15.11.25",
                "SOC - Boston Bruins at Toronto Maple Leafs 09.11.25",
                "SOC - Montreal Canadiens - Ottawa Senators 20.10.25",
                "SOC - Leafs vs Senators 20.10.25",
                "SOC - Houston Texans at Denver Broncos 05.10.2025",
            ),
            replays.map { it.replay.name },
        )
        assertEquals(listOf(League.Nhl, League.Nhl, League.Nhl, League.Nhl, League.Nfl), replays.map { it.league })
        // The teams come with their logos, from the schedule; "at" puts the first team away, a dash puts it at home.
        assertEquals("Boston Bruins" to "Toronto Maple Leafs", replays[1].let { it.away.name to it.home.name })
        assertEquals("Ottawa Senators" to "Montreal Canadiens", replays[2].let { it.away.name to it.home.name })
        assertEquals("Toronto Maple Leafs" to "Ottawa Senators", replays[3].let { it.home.name to it.away.name })
        assertTrue(replays.all { it.away.logo != null && it.home.logo != null })
        // Only the Leagues on: with the NHL off, its Replays go.
        assertEquals(listOf("SOC - Houston Texans at Denver Broncos 05.10.2025"), rows(League.Nfl).replays()!!.map { it.replay.name })
        // With no League on, or none with a Replay, there is no row.
        assertFalse(library.liveRows(emptyList(), "CA", listOf("en"), now, toronto).any { it is HomeRow.Replays })
        assertFalse(rows(League.Mlb).any { it is HomeRow.Replays })
    }

    @Test fun `a nickname word alone names a team only when the Replay's team is all that team's words`() = runBlocking {
        cloud.games(
            "premier-league",
            game("10", "2026-11-02T15:00:00Z", league = "premier-league", home = "Manchester United", away = "Manchester City"),
            game("11", "2026-11-03T15:00:00Z", league = "premier-league", home = "Arsenal", away = "Chelsea"),
        )
        provider.serveMovies(
            // Leeds has no Game this week: "United" alone must not make this a Manchester derby.
            "SOC - Leeds United - Manchester City 25.10.2025" to "SOCCER FOOTBALL",
            "SOC - Manchester United - Manchester City 18.10.2025" to "SOCCER FOOTBALL",
            "SOC - Man United v Man City 11.10.2025" to "SOCCER FOOTBALL",
        )
        library.sync()

        assertEquals(
            listOf("SOC - Manchester United - Manchester City 18.10.2025", "SOC - Man United v Man City 11.10.2025"),
            rows(League.PremierLeague).replays()!!.map { it.replay.name },
        )
    }

    @Test fun `a finished Game's Replay is the one naming its teams on its day, or the day after when the Provider's clock is ahead`() = runBlocking {
        serveSchedule()
        provider.serveMovies(
            "SOC - Boston Bruins at Toronto Maple Leafs 09.11.25" to "SOCCER HOCKEY",
            // The same teams on another day are another Game's.
            "SOC - Toronto Maple Leafs at Boston Bruins 07.11.25" to "SOCCER HOCKEY",
            // Named in a clock a day ahead of Calgary's evening.
            "SOC - Winnipeg Jets vs. Calgary Flames 21.10.25" to "SOCCER HOCKEY",
            // Yesterday's meeting of tonight's teams: not tonight's Game's.
            "SOC - Montreal Canadiens at Ottawa Senators 31.10.26" to "SOCCER HOCKEY",
            "SOC - Montreal Canadiens at Ottawa Senators 01.11.26" to "SOCCER HOCKEY",
        )
        library.sync()
        val games = library.games(listOf("nhl"), now).associateBy { it.id }

        // Sunday 2025-11-09, 7 PM in Toronto: Monday in UTC.
        assertEquals("SOC - Boston Bruins at Toronto Maple Leafs 09.11.25", library.gameReplay(games.getValue("1"), toronto)?.name)
        assertEquals("SOC - Winnipeg Jets vs. Calgary Flames 21.10.25", library.gameReplay(games.getValue("3"), toronto)?.name)
        // Nothing for a Game not over, whatever the catalog has of its teams.
        assertNull(library.gameReplay(games.getValue("2"), toronto))
    }

    @Test fun `playing a Replay plays its Listing's file, with nothing else to fall back on`() = runBlocking {
        provider.serveMovies("SOC - Boston Bruins at Toronto Maple Leafs 09.11.25" to "SOCCER HOCKEY")
        library.sync()
        val replay = replay("SOC - Boston Bruins at Toronto Maple Leafs 09.11.25")

        val plan = library.replayPlayPlan(replay, listOf("en"))
        assertEquals(replay.listingId, plan.version.listingId)
        assertEquals(replay.name, plan.version.name)
        assertEquals(emptyList<Version>(), plan.version.backups)
        assertEquals(listOf(plan.version), plan.versions)
        // A file, not a live stream: it can be scrubbed and resumed.
        assertFalse(plan.live)
        library.connect(plan.version).use { assertTrue(it.location, it.location.endsWith("/movie/${provider.username}/${provider.password}/${replay.listingId}.mkv")) }
        // Where the Viewer stopped is kept, as a Movie's is.
        library.saveProgress(plan, 600_000, 7_200_000)
        assertEquals(600_000L, library.replayPlayPlan(replay, listOf("en")).from)
        // It never joins Continue watching, which shows Titles.
        assertTrue(library.homeRows(listOf("en"), "en", "CA", emptyList(), now).none { it is HomeRow.ContinueWatching })
    }

    @Test fun `Search finds every Replay by name, highlights shows too, newest first`() = runBlocking {
        provider.serveMovies(
            "SOC - LaLiga Highlights Matchday 38" to "SOCCER FOOTBALL",
            "SOC - Boston Bruins at Toronto Maple Leafs 09.11.25" to "SOCCER HOCKEY",
            "SOC - Toronto Maple Leafs at Boston Bruins 07.11.25" to "SOCCER HOCKEY",
            "SOC - Montreal Canadiens - Toronto Maple Leafs 12.11.25" to "SOCCER HOCKEY",
            "EN - The Godfather (1972)" to "EN - MOVIES",
        )
        library.sync()
        library.prepareSearch()

        val leafs = library.search("maple leafs", listOf("en"), "en").last()
        assertEquals(
            listOf(
                "SOC - Montreal Canadiens - Toronto Maple Leafs 12.11.25",
                "SOC - Boston Bruins at Toronto Maple Leafs 09.11.25",
                "SOC - Toronto Maple Leafs at Boston Bruins 07.11.25",
            ),
            leafs.replays.map { it.name },
        )
        assertEquals(listOf(SearchGroup.Replays(leafs.replays)), leafs.groups("maple leafs"))
        assertEquals(listOf("SOC - LaLiga Highlights Matchday 38"), library.search("laliga highlights", listOf("en"), "en").last().replays.map { it.name })
        assertTrue(library.search("godfather", listOf("en"), "en").last().replays.isEmpty())
    }
}
