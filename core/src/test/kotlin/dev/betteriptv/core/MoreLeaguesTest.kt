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
 * The Leagues #354, #375 and #383 added, seen through the Library: the fake cloud's Games, with their teams as
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
            // Two more ways the Provider writes it (survey 2026-11-06): Paramount+'s "English Football League", and Viaplay's bare "CHAMPIONSHIP", which both teams carry.
            "English Football League: Cardiff City vs West Ham United @ Nov 1 5:00 PM :Paramount+  05" to "US| PARAMOUNT+ PPV",
            "NEXT | CHAMPIONSHIP CARDIFF - WEST HAM | Sun 01 Nov 23:00 CET (SE) | 8K EXCLUSIVE | SE: VIAPLAY PPV 13" to "SE| VIAPLAY PPV",
            // No League in Championship's names is a bare "Championship": the NFL's slot keeps its Game.
            "NFL: AFC Championship: Texans vs Broncos (2026-11-01 17:00:00)" to "US| NFL PPV",
        )
        library.sync()

        val feeds = feeds(League.EflChampionship, League.Nfl)
        assertEquals(
            setOf(
                "EFL Championship: West Ham United vs Cardiff City (2026-11-01 17:00:00)", "EFL | West Ham v Cardiff @ Nov 1 5:00 PM",
                "English Football League: Cardiff City vs West Ham United @ Nov 1 5:00 PM :Paramount+  05",
                "NEXT | CHAMPIONSHIP CARDIFF - WEST HAM | Sun 01 Nov 23:00 CET (SE) | 8K EXCLUSIVE | SE: VIAPLAY PPV 13",
            ),
            feeds["1"]?.toSet(),
        )
        assertEquals(listOf("NFL: AFC Championship: Texans vs Broncos (2026-11-01 17:00:00)"), feeds["100"])
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

    @Test fun `a Europa League Game takes the Provider's UEFA slots, which name both teams and no League, and a Serie A Game of one of its clubs takes none`() = runBlocking {
        // ESPN's uefa.europa scoreboard for 2026-11-05, and the Provider's slots for that Game (survey 2026-11-06), moved to this test's day.
        cloud.games("europa-league", game("1", League.EuropaLeague, EspnTeam("AC Milan", "Milan", "MIL"), EspnTeam("Ferencvaros", "Ferencvaros", "FER")))
        cloud.games("serie-a", game("10", League.SerieA, EspnTeam("AC Milan", "Milan", "MIL"), EspnTeam("Udinese", "Udinese", "UDI")))
        provider.serveChannels(
            "Next | AC Milan vs. Ferencváros | all | 01-11-2026 | 22:00 (GMT) | 8K EXCLUSIVE | CA: SOCCER PPV 22" to "CA| SOCCER PPV",
            "Live | AC Milan vs. Ferencváros | all | 8K EXCLUSIVE | DE: SOCCER PPV 17" to "DE| SOCCER PPV",
            "UEFA | 01 - AC Milan vs Ferencváros 17:00pm" to "US| UEFA PPV",
            "Europa League: Milan vs Ferencvaros (2026-11-01 17:00:00)" to "UK| UEFA PPV",
        )
        library.sync()

        val feeds = feeds(League.EuropaLeague, League.SerieA)
        // The UEFA PPV slot gives a time but no date, so it can't be told an Event channel (Channels.kt: isEventChannel).
        assertEquals(
            setOf(
                "Next | AC Milan vs. Ferencváros | all | 01-11-2026 | 22:00 (GMT) | 8K EXCLUSIVE | CA: SOCCER PPV 22",
                "Live | AC Milan vs. Ferencváros | all | 8K EXCLUSIVE | DE: SOCCER PPV 17",
                "Europa League: Milan vs Ferencvaros (2026-11-01 17:00:00)",
            ),
            feeds["1"]?.toSet(),
        )
        // Milan and no Serie A, or the Europa League named: none of Serie A's Game.
        assertEquals(emptyList<String>(), feeds["10"].orEmpty())
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

    @Test fun `a whole Top 14 round takes the Provider's FloRugby slots, which name the clubs as fans do and no League, each Game only its own`() = runBlocking {
        // ESPN's round of 2026-10-24 and 25 (rugby/270559), moved to Monday 2026-11-02 at the same UTC times, its clubs
        // with the aliases the cloud gives them (cloud/src/espn.ts: ESPN's names, none of its abbreviations, and MORE_NAMES).
        fun club(name: String, vararg more: String) = mapOf("id" to name, "name" to name, "shortName" to name, "abbreviation" to "STA", "aliases" to listOf(name, *more), "logo" to null)
        fun round(id: String, start: String, home: Map<String, Any?>, away: Map<String, Any?>) =
            mapOf("id" to id, "league" to "top-14", "start" to start, "state" to "upcoming", "home" to home, "away" to away, "broadcasters" to emptyList<String>())
        cloud.games(
            "top-14",
            round("1", "2026-11-02T12:30:00Z", club("Vannes"), club("Clermont Auvergne", "Clermont")),
            round("2", "2026-11-02T14:35:00Z", club("Bayonne", "Aviron Bayonnais"), club("Lyon")),
            round("3", "2026-11-02T14:35:00Z", club("Castres Olympique", "Castres"), club("Stade Francais Paris", "Stade Francais")),
            round("4", "2026-11-02T14:35:00Z", club("Racing 92"), club("Montpellier Herault", "Montpellier")),
            round("5", "2026-11-02T14:35:00Z", club("Stade Toulousain", "Toulouse"), club("Perpignan")),
            round("6", "2026-11-02T19:00:00Z", club("Toulon"), club("Pau", "Section Paloise")),
            round("7", "2026-11-02T20:05:00Z", club("La Rochelle", "Stade Rochelais"), club("Bordeaux Begles")),
        )
        // The Provider's slots for that round (survey 2026-10-24), moved to the same day, in its Eastern time.
        val slots = mapOf(
            "1" to "(FLSP 056) | rugby:  RC Vannes vs Clermont (RC Vannes vs Clermont) (2026-11-02 07:30:00)",
            "2" to "(FLSP 091) | rugby:  Bayonne vs Lyon (Bayonne vs Lyon) (2026-11-02 09:35:15)",
            "3" to "(FLSP 090) | rugby:  Castres vs Stade Francais (Castres vs Stade Francais) (2026-11-02 09:35:10)",
            "4" to "(FLSP 088) | rugby:  Racing 92 vs Montpellier (Racing 92 vs Montpellier) (2026-11-02 09:35:00)",
            "5" to "(FLSP 089) | rugby:  Toulouse vs Perpignan (Toulouse vs Perpignan) (2026-11-02 09:35:05)",
            "6" to "(FLSP 402) | rugby:  Toulon vs Pau (Toulon vs Pau) (2026-11-02 14:00:25)",
            "7" to "(FLSP 816) | rugby:  La Rochelle vs Bordeaux_Begles (La Rochelle vs Bordeaux_Begles) (2026-11-02 15:05:00)",
        )
        provider.serveChannels(
            *slots.values.map { it to "US| FLO VIP PPV" }.toTypedArray(),
            // The same morning's Premiership and URC slots name no Top 14 club.
            "(FLSP 084) | rugby:  Leicester Tigers vs Northampton Saints (Leicester vs Saints) (2026-11-02 09:35:00)" to "US| FLO VIP PPV",
            "(FLSP 245) | rugby:  Leinster Rugby vs Munster Rugby (Leinster vs Munster) (2026-11-02 09:35:40)" to "US| FLO VIP PPV",
        )
        library.sync()

        assertEquals(slots.mapValues { listOf(it.value) }, feeds(League.Top14))
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
