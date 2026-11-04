package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.ZoneId

/**
 * The Leagues #354 added, seen through the Library: the fake cloud's Games, with their teams as
 * ESPN's scoreboards named them on 2026-11-04 (each team's aliases as cloud/src/espn.ts lists
 * them), matched to Event channels written the ways the surveyed Provider writes them
 * (`LEAGUE: Team vs Team (date time)`, `LEAGUE | Team v Team`). Prior art: [GameRowsTest].
 */
class MoreLeaguesTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private lateinit var library: Library

    /** A Sunday, 3 PM in Toronto. Every Game here kicks off at 5 PM in Toronto. */
    private val now = Instant.parse("2026-11-01T20:00:00Z")
    private val kickOff = "2026-11-01T22:00:00Z"
    private val toronto = ZoneId.of("America/Toronto")

    @Before fun logIn() = runBlocking {
        library = Library.open(folder.root.resolve("catalog.db").path, InMemoryCredentials(), cloud.url)
        library.logIn(provider.login)
        provider.movieCategoriesOverride = "[]"
        provider.movieListOverride = "[]"
        provider.showCategoriesOverride = "[]"
        provider.showListOverride = "[]"
        Unit
    }

    @After fun tearDown() {
        library.close()
        provider.close()
        cloud.close()
    }

    /** A team as ESPN names it: its display name, short display name, abbreviation and location, the aliases the cloud lists in that order, each once. */
    private class EspnTeam(val name: String, val shortName: String, val abbreviation: String, val location: String? = name)

    /** A Game of [league] shaped like the cloud's answer, [home] against [away] at [kickOff]. */
    private fun game(id: String, league: League, home: EspnTeam, away: EspnTeam): Map<String, Any?> {
        fun team(t: EspnTeam) = mapOf(
            "id" to t.abbreviation, "name" to t.name, "shortName" to t.shortName, "abbreviation" to t.abbreviation,
            "aliases" to listOfNotNull(t.name, t.shortName, t.abbreviation, t.location).distinct(), "logo" to null,
        )
        return mapOf("id" to id, "league" to league.key, "start" to kickOff, "state" to "upcoming", "home" to team(home), "away" to team(away), "broadcasters" to emptyList<String>())
    }

    /** The Feeds the Live tab gives each of [league]'s Games, by Game id, as the Provider names their Channels. */
    private suspend fun feeds(vararg leagues: League): Map<String, List<String>> =
        library.liveRows(leagues.toList(), "CA", listOf("en"), now, toronto)
            .flatMap { row ->
                when (row) {
                    is HomeRow.LiveNow -> row.games
                    is HomeRow.Today -> row.games
                    is HomeRow.LeagueGames -> row.games
                    else -> emptyList()
                }
            }
            .associate { it.game.id to it.feeds.map { feed -> feed.channel.variants.first().name } }

    @Test fun `an EFL Championship Game takes its Event channels, and a bare Championship elsewhere still names the NFL's`() = runBlocking {
        cloud.games("efl-championship", game("1", League.EflChampionship, EspnTeam("Cardiff City", "Cardiff", "CAR"), EspnTeam("West Ham United", "West Ham", "WHU")))
        cloud.games("nfl", game("100", League.Nfl, EspnTeam("Denver Broncos", "Broncos", "DEN", "Denver"), EspnTeam("Houston Texans", "Texans", "HOU", "Houston")))
        provider.serveChannels(
            "EFL Championship: West Ham United vs Cardiff City (2026-11-01 17:00:00)" to "UK| PPV",
            "EFL | West Ham v Cardiff @ Nov 1 5:00 PM" to "UK| PPV",
            // No League in Championship's names is a bare "Championship": the NFL's slot keeps its Game.
            "NFL: AFC Championship: Texans vs Broncos (2026-11-01 17:00:00)" to "US| NFL PPV",
        )
        library.sync()

        val feeds = feeds(League.EflChampionship, League.Nfl)
        assertEquals(listOf("EFL Championship: West Ham United vs Cardiff City (2026-11-01 17:00:00)", "EFL | West Ham v Cardiff @ Nov 1 5:00 PM"), feeds["1"])
        assertEquals(listOf("NFL: AFC Championship: Texans vs Broncos (2026-11-01 17:00:00)"), feeds["100"])
    }

    @Test fun `a Scottish Premiership Game takes its Event channels, and Dundee never takes Dundee United's`() = runBlocking {
        cloud.games(
            "scottish-premiership",
            game("1", League.ScottishPremiership, EspnTeam("Dundee United", "Dundee Utd", "DUN"), EspnTeam("Motherwell", "Motherwell", "MOT")),
            game("2", League.ScottishPremiership, EspnTeam("Dundee", "Dundee", "DUN"), EspnTeam("Celtic", "Celtic", "CEL")),
        )
        provider.serveChannels(
            "SPFL: Motherwell vs Dundee United (2026-11-01 17:00:00)" to "UK| PPV",
            "Scottish Premiership | Celtic v Dundee @ Nov 1 5:00 PM" to "UK| PPV",
        )
        library.sync()

        val feeds = feeds(League.ScottishPremiership)
        assertEquals(listOf("SPFL: Motherwell vs Dundee United (2026-11-01 17:00:00)"), feeds["1"])
        // "Dundee" and the League are in Dundee United's slot too, but it names both teams of another Game.
        assertEquals(listOf("Scottish Premiership | Celtic v Dundee @ Nov 1 5:00 PM"), feeds["2"])
    }

    @Test fun `a Liga Portugal Game takes its Event channels, one naming a team and the League too`() = runBlocking {
        cloud.games("liga-portugal", game("1", League.LigaPortugal, EspnTeam("FC Famalicao", "FC Famalicao", "FCF"), EspnTeam("Arouca", "Arouca", "FCA")))
        provider.serveChannels(
            "Liga Portugal: Arouca vs FC Famalicão (2026-11-01 17:00:00)" to "PT| SPORT TV PPV",
            // ESPN has no "Famalicão" alone: Arouca and the League make it this Game's.
            "Primeira Liga | Famalicão v Arouca @ Nov 1 5:00 PM" to "PT| SPORT TV PPV",
            // Arouca without a League is no Game's.
            "Arouca v Benfica B @ Nov 1 5:00 PM" to "PT| SPORT TV PPV",
        )
        library.sync()

        assertEquals(
            listOf("Liga Portugal: Arouca vs FC Famalicão (2026-11-01 17:00:00)", "Primeira Liga | Famalicão v Arouca @ Nov 1 5:00 PM"),
            feeds(League.LigaPortugal)["1"],
        )
    }

    @Test fun `a 2 Bundesliga Game takes its Event channels, though the Bundesliga's name is in its own, and the Bundesliga's never do`() = runBlocking {
        cloud.games("2-bundesliga", game("1", League.Bundesliga2, EspnTeam("Dynamo Dresden", "D Dresden", "SGD"), EspnTeam("SpVgg Greuther Fürth", "Greuther Furth", "SGF")))
        cloud.games("bundesliga", game("10", League.Bundesliga, EspnTeam("Hamburger SV", "Hamburg", "HSV"), EspnTeam("FC Cologne", "Cologne", "KOE")))
        provider.serveChannels(
            "2. Bundesliga: Greuther Fürth vs Dynamo Dresden (2026-11-01 17:00:00)" to "DE| SKY PPV",
            // One team and the League: "Bundesliga" in "2. Bundesliga" is this League's own, so it keeps nothing out.
            "2. BUNDESLIGA | DYNAMO DRESDEN V HERTHA @ Nov 1 5:00 PM" to "DE| SKY PPV",
            "Bundesliga: Cologne vs Hamburger SV (2026-11-01 17:00:00)" to "DE| SKY PPV",
            // A 2. Bundesliga slot naming a Bundesliga team: the other League's.
            "2. Bundesliga: Hamburger SV II vs Hertha (2026-11-01 17:00:00)" to "DE| SKY PPV",
        )
        library.sync()

        val feeds = feeds(League.Bundesliga, League.Bundesliga2)
        assertEquals(
            listOf("2. Bundesliga: Greuther Fürth vs Dynamo Dresden (2026-11-01 17:00:00)", "2. BUNDESLIGA | DYNAMO DRESDEN V HERTHA @ Nov 1 5:00 PM"),
            feeds["1"],
        )
        assertEquals(listOf("Bundesliga: Cologne vs Hamburger SV (2026-11-01 17:00:00)"), feeds["10"])
    }

    @Test fun `a Super Lig Game takes its Event channels, accented or not`() = runBlocking {
        cloud.games("super-lig", game("1", League.SuperLig, EspnTeam("Gaziantep FK", "Gaziantep", "GAZ"), EspnTeam("Kasimpasa", "Kasimpasa", "KAS")))
        provider.serveChannels(
            "Süper Lig: Kasımpaşa vs Gaziantep FK (2026-11-01 17:00:00)" to "TR| BEIN PPV",
            "SUPER LIG | Gaziantep v Kasimpasa @ Nov 1 5:00 PM" to "TR| BEIN PPV",
        )
        library.sync()

        // "Kasımpaşa" has a dotless ı, which no accent folding makes an i: the slot still names Gaziantep and the League.
        assertEquals(
            listOf("Süper Lig: Kasımpaşa vs Gaziantep FK (2026-11-01 17:00:00)", "SUPER LIG | Gaziantep v Kasimpasa @ Nov 1 5:00 PM"),
            feeds(League.SuperLig)["1"],
        )
    }

    @Test fun `a Women's Super League Game and the Premier League's between the same clubs each take only their own League's slots`() = runBlocking {
        cloud.games("wsl", game("1", League.WomensSuperLeague, EspnTeam("Chelsea", "Chelsea", "CHE"), EspnTeam("Everton", "Everton", "EVE")))
        cloud.games("premier-league", game("10", League.PremierLeague, EspnTeam("Chelsea", "Chelsea", "CHE"), EspnTeam("Everton", "Everton", "EVE")))
        provider.serveChannels(
            "WSL: Everton vs Chelsea (2026-11-01 17:00:00)" to "UK| PPV",
            "Women's Super League | Everton Women v Chelsea Women @ Nov 1 5:00 PM" to "UK| PPV",
            "EPL: Everton vs Chelsea (2026-11-01 17:00:00)" to "UK| PPV",
        )
        library.sync()

        val feeds = feeds(League.PremierLeague, League.WomensSuperLeague)
        assertEquals(listOf("WSL: Everton vs Chelsea (2026-11-01 17:00:00)", "Women's Super League | Everton Women v Chelsea Women @ Nov 1 5:00 PM"), feeds["1"])
        assertEquals(listOf("EPL: Everton vs Chelsea (2026-11-01 17:00:00)"), feeds["10"])
    }

    @Test fun `an NRL Game takes its Event channels, and the Knights, whom ESPN calls NEW, never take New Zealand's`() = runBlocking {
        cloud.games(
            "nrl",
            game("1", League.Nrl, EspnTeam("Roosters", "Roosters", "SYD", null), EspnTeam("Knights", "Knights", "NEW", null)),
            game("2", League.Nrl, EspnTeam("Warriors", "Warriors", "NEW", null), EspnTeam("Storm", "Storm", "MEL", null)),
        )
        provider.serveChannels(
            "NRL: Newcastle Knights vs Sydney Roosters (2026-11-01 17:00:00)" to "AU| PPV",
            "NRL: New Zealand Warriors vs Melbourne Storm (2026-11-01 17:00:00)" to "AU| PPV",
        )
        library.sync()

        val feeds = feeds(League.Nrl)
        assertEquals(listOf("NRL: Newcastle Knights vs Sydney Roosters (2026-11-01 17:00:00)"), feeds["1"])
        assertEquals(listOf("NRL: New Zealand Warriors vs Melbourne Storm (2026-11-01 17:00:00)"), feeds["2"])
    }

    @Test fun `an AFL Game takes its Event channels, and Melbourne never takes North Melbourne's`() = runBlocking {
        cloud.games(
            "afl",
            game("1", League.Afl, EspnTeam("Fremantle", "Fremantle", "FRE", null), EspnTeam("Brisbane Lions", "Brisbane Lions", "BL", null)),
            game("2", League.Afl, EspnTeam("Melbourne", "Melbourne", "MELB", null), EspnTeam("Essendon", "Essendon", "ESS", null)),
            game("3", League.Afl, EspnTeam("North Melbourne", "North Melbourne", "NMFC", null), EspnTeam("Carlton", "Carlton", "CARL", null)),
        )
        provider.serveChannels(
            "AFL: Brisbane Lions vs Fremantle (2026-11-01 17:00:00)" to "AU| PPV",
            "AFL | Essendon v Melbourne @ Nov 1 5:00 PM" to "AU| PPV",
            "AFL: North Melbourne vs Carlton (2026-11-01 17:00:00)" to "AU| PPV",
        )
        library.sync()

        val feeds = feeds(League.Afl)
        assertEquals(listOf("AFL: Brisbane Lions vs Fremantle (2026-11-01 17:00:00)"), feeds["1"])
        assertEquals(listOf("AFL | Essendon v Melbourne @ Nov 1 5:00 PM"), feeds["2"])
        assertEquals(listOf("AFL: North Melbourne vs Carlton (2026-11-01 17:00:00)"), feeds["3"])
    }

    @Test fun `an AFL Replay the survey listed is in the Replays row once the AFL is on`() = runBlocking {
        // The survey's own name (2026-10-24, the Provider's "SOCCER RUGBY" Category, S0C its typo of SOC).
        provider.liveListOverride = "[]"
        provider.serveMovies("S0C - Western Bulldogs - Sydney Swans 23.05.2024" to "SOCCER RUGBY")
        cloud.games("afl", game("1", League.Afl, EspnTeam("Sydney Swans", "Sydney Swans", "SYD", null), EspnTeam("Western Bulldogs", "Western Bulldogs", "WB", null)))
        library.sync()

        fun replays(vararg leagues: League) = runBlocking {
            library.liveRows(leagues.toList(), "CA", listOf("en"), now, toronto).filterIsInstance<HomeRow.Replays>().singleOrNull()?.replays?.map { it.replay.name }
        }
        assertEquals(listOf("S0C - Western Bulldogs - Sydney Swans 23.05.2024"), replays(League.Afl))
        assertEquals(null, replays(League.Nhl))
    }
}
