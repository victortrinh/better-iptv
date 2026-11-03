package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.time.Instant

/** The Games of the Leagues on, seen through the Library, with the schedule from the fake Cloud. */
class GamesTest {
    @get:Rule val folder = TemporaryFolder()
    private val cloud = FakeCloud()
    private lateinit var library: Library
    private val now = Instant.parse("2026-11-01T20:00:00Z")

    @Before fun open() {
        library = Library.open(folder.root.resolve("catalog.db").path, InMemoryCredentials(), cloud.url)
    }

    @After fun tearDown() {
        library.close()
        cloud.close()
    }

    @Test fun `the Games of the Leagues on come from the cloud, by start, and the answer is kept a minute`() = runBlocking {
        cloud.games(
            "nhl",
            game("2", "2026-11-02T00:00:00Z", home = "Boston Bruins", away = "Ottawa Senators"),
            game("1", "2026-11-01T19:00:00Z", state = "live", broadcasters = listOf("ESPN+")),
        )
        cloud.games("nfl", game("100", "2026-11-01T21:25:00Z", home = "Denver Broncos", away = "Houston Texans", league = "nfl"))
        cloud.games("mlb", game("300", "2026-11-01T22:00:00Z", league = "mlb"))

        val games = library.games(listOf("nhl", "nfl"), now)
        assertEquals(listOf("nhl/1", "nfl/100", "nhl/2"), games.map { "${it.league}/${it.id}" })
        val live = games.first()
        assertEquals(GameState.Live, live.state)
        assertEquals(Instant.parse("2026-11-01T19:00:00Z"), live.start)
        val home = live.home!!
        assertEquals("Toronto Maple Leafs", home.name)
        assertEquals("Maple Leafs", home.shortName)
        assertEquals("TOR", home.abbreviation)
        assertEquals(listOf("Toronto Maple Leafs", "Maple Leafs", "TOR", "Toronto"), home.aliases)
        assertEquals("https://logos.example/TOR.png", home.logo)
        assertEquals("Montreal Canadiens", live.away?.name)
        assertEquals(listOf("ESPN+"), live.broadcasters)
        assertEquals(listOf(GameState.Upcoming, GameState.Upcoming), games.drop(1).map { it.state })
        assertEquals(listOf(listOf("nhl", "nfl")), cloud.gamesAsked)

        // Asked again within the minute, the kept copies answer without the cloud.
        assertEquals(3, library.games(listOf("nhl", "nfl"), now + Duration.ofSeconds(30)).size)
        assertEquals(1, cloud.gamesAsked.size)
        // A minute on, the cloud is asked again.
        library.games(listOf("nhl", "nfl"), now + Duration.ofSeconds(61))
        assertEquals(2, cloud.gamesAsked.size)
    }

    @Test fun `an F1 session comes as a Game naming its Grand Prix and session instead of teams, and one of a session this release doesn't know is left out`() = runBlocking {
        cloud.games(
            "f1",
            session("405", "2026-11-01T17:00:00Z", "race", state = "live"),
            session("404", "2026-10-31T18:00:00Z", "qualifying", state = "over"),
            session("406", "2026-11-01T18:00:00Z", "parade"),
        )

        val games = library.games(listOf("f1"), now)
        assertEquals(listOf("404", "405"), games.map { it.id })
        val race = games.last()
        assertNull(race.home)
        assertNull(race.away)
        val event = race.event!!
        assertEquals("MSC Cruises São Paulo Grand Prix", event.name)
        assertEquals("MSC Cruises São Paulo GP", event.shortName)
        assertEquals(GameSession.Race, event.session)
        assertEquals(listOf("MSC Cruises São Paulo", "Cruises São Paulo", "São Paulo", "Paulo", "Brazil"), event.aliases)
        assertEquals("https://logos.example/f1.png", event.logo)
        assertEquals(GameSession.Qualifying, games.first().event?.session)
        assertEquals(listOf("Apple TV"), race.broadcasters)
    }

    @Test fun `a UFC card comes as one Game naming its main event's two fighters instead of teams, and one without two fighters is left out`() = runBlocking {
        cloud.games(
            "ufc",
            fightCard("600061268", "2026-11-01T22:00:00Z", "2026-11-02T01:00:00Z", state = "live"),
            fightCard("600061269", "2026-11-07T22:00:00Z", "2026-11-08T01:00:00Z", fighters = listOf("Ciryl Gane" to "Gane")),
        )

        val card = library.games(listOf("ufc"), now).single()
        assertEquals("600061268", card.id)
        assertEquals(GameState.Live, card.state)
        assertNull(card.home)
        assertNull(card.event)
        assertEquals(emptyList<GameTeam>(), card.teams)
        val fight = card.fight!!
        assertEquals("UFC Fight Night: Cortes Acosta vs. Walker", fight.name)
        assertEquals("UFC Fight Night", fight.shortName)
        assertEquals(Instant.parse("2026-11-02T01:00:00Z"), fight.mainCard)
        assertEquals(listOf("Waldo Cortes Acosta", "Johnny Walker"), fight.fighters.map { it.name })
        assertEquals(listOf("Cortes Acosta", "Walker"), fight.fighters.map { it.shortName })
        assertEquals(listOf("Waldo Cortes Acosta", "Cortes Acosta"), fight.fighters.first().aliases)
        assertEquals("https://headshots.example/CortesAcosta.png", fight.fighters.first().headshot)
        assertEquals(listOf("Paramount+"), card.broadcasters)
    }

    @Test fun `the kept Games serve while the cloud fails, and a League never kept is left out`() = runBlocking {
        cloud.games("nhl", game("1", "2026-11-01T19:00:00Z", state = "live"), game("2", "2026-11-02T00:00:00Z"))
        library.games(listOf("nhl"), now)

        cloud.down = true
        val later = library.games(listOf("nhl", "nfl"), now + Duration.ofMinutes(10))
        assertEquals(2, cloud.gamesAsked.size)
        assertEquals(listOf("1", "2"), later.map { it.id })
        assertTrue(later.all { it.league == "nhl" })
        // Nothing kept of the MLB and the cloud down: no Games, rather than a failure.
        assertEquals(emptyList<Game>(), library.games(listOf("mlb"), now + Duration.ofMinutes(20)))
    }

    @Test fun `a League the cloud has no copy of yet keeps what the device has, and the Leagues asked for are the caller's`() = runBlocking {
        cloud.games("nhl", game("1", "2026-11-01T19:00:00Z"))
        cloud.games("nfl", game("100", "2026-11-01T21:25:00Z", league = "nfl"))
        library.games(listOf("nhl", "nfl"), now)
        // The cloud dropped the NFL (nobody wanted it in 30 days, say): its answer no longer lists it.
        cloud.games("nhl", game("1", "2026-11-01T19:00:00Z"), game("2", "2026-11-02T00:00:00Z"))
        cloud.forgetGames("nfl")

        val later = library.games(listOf("nhl", "nfl"), now + Duration.ofMinutes(2))
        assertEquals(listOf("nhl/1", "nfl/100", "nhl/2"), later.map { "${it.league}/${it.id}" })
        assertEquals(emptyList<Game>(), library.games(emptyList(), now + Duration.ofMinutes(3)))
        assertEquals(listOf(listOf("nhl", "nfl"), listOf("nhl", "nfl")), cloud.gamesAsked)
    }
}
