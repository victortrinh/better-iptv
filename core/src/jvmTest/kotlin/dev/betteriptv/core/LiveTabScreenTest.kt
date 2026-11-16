package dev.betteriptv.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel as Seen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The Live tab's finished content ([LiveTab]), asked of the Library as an app asks for a screen
 * ([Library.screen], #589): its Rows in order with their cards, each Game in one Row only, and its
 * updates as live state, as Games start and end by a clock the test moves. Prior art:
 * [HomeScreenTest]; what each Row holds is [GameRowsTest]'s, [MoreLiveEventsTest]'s and [ReplaysTest]'s.
 */
class LiveTabScreenTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private lateinit var library: Library

    /** The time the Library reads the Live tab at: the test moves it. */
    private class MovedClock(var at: Instant) : Clock {
        override fun now() = at
    }

    /** A Sunday, 4 PM in Toronto. */
    private val clock = MovedClock(Instant.parse("2026-11-01T20:00:00Z"))
    private val english = listOf("en")
    private val live = LiveTab(listOf(League.Nhl, League.Nfl), region = "CA", contentLanguages = english)

    @Before fun logIn() = runBlocking {
        library = Library.open(folder.root.resolve("catalog.db").path, InMemoryCredentials(), cloud.url)
        library.screenClock = clock
        library.screenZone = TimeZone.of("America/Toronto")
        library.logIn(provider.login)
        Unit
    }

    @After fun tearDown() {
        library.close()
        provider.close()
        cloud.close()
    }

    /** Sunday's slate: a Game on now, one tonight, one on Monday and an NFL Game on Wednesday. */
    private fun serveSlate(firstState: String = "live", secondState: String = "upcoming") {
        cloud.games(
            "nhl",
            game("1", "2026-11-01T18:00:00Z", state = firstState, home = "Toronto Maple Leafs", away = "Montreal Canadiens"),
            game("2", "2026-11-02T00:00:00Z", state = secondState, home = "Boston Bruins", away = "Ottawa Senators"),
            game("3", "2026-11-03T00:00:00Z", home = "Edmonton Oilers", away = "Calgary Flames"),
        )
        cloud.games("nfl", game("100", "2026-11-05T01:15:00Z", league = "nfl", home = "Denver Broncos", away = "Houston Texans"))
    }

    /** The slate's Event channels, an Event channel no Game matches, a Channel to save and a Sports replay of the Leafs and the Canadiens. */
    private fun serveCatalog() {
        provider.serveChannels(
            "NHL: Canadiens vs Maple Leafs (2026-11-01 13:00:00)" to "US| ESPN+ PPV",
            "Senators @ Bruins (2026-11-01 19:00:00)" to "US| NHL PPV",
            "ATP Paris: Djokovic vs Alcaraz (2026-11-01 16:00:00)" to "US| TENNIS PPV",
            "CA: TSN 1" to "CA| SPORT",
        )
    }

    private suspend fun saveTsn() = library.setFavourite(library.search("TSN", english, "en").last().channels.single().key, true)

    private fun LiveTabContent.keys() = rows.map { it.key }

    /** The ids of the Games in the Row keyed [key], null when the tab has no such Row. */
    private fun LiveTabContent.games(key: String): List<String>? = rows.singleOrNull { it.key == key }?.let { row ->
        when (row) {
            is HomeRow.LiveNow -> row.games
            is HomeRow.Today -> row.games
            is HomeRow.LeagueGames -> row.games
            else -> emptyList()
        }.map { it.game.id }
    }

    /** Every Game on the tab, by id, in the order it shows. */
    private fun LiveTabContent.allGames() = rows.flatMap { games(it.key).orEmpty() }

    /** The Live tab as an app holds it while the screen shows, read again every [readAgainAfter] as the two minutes are in the app. */
    private inner class Watched(scope: CoroutineScope, screen: LiveTab, readAgainAfter: Duration? = null) {
        private val seen = Seen<LiveTabContent>(Seen.UNLIMITED)
        private val collecting = scope.launch(Dispatchers.Default) {
            val content = if (readAgainAfter == null) library.screen(screen) else library.liveContent(screen) { readAgainAfter }
            content.collect { seen.send(it) }
        }

        /** The next content the tab is handed, waited for. */
        suspend fun next(): LiveTabContent = withTimeout(10.seconds) { seen.receive() }

        /** The first content the tab is handed, from now on, that [shows] what is waited for. */
        suspend fun nextThat(shows: (LiveTabContent) -> Boolean): LiveTabContent = withTimeout(10.seconds) {
            var content = seen.receive()
            while (!shows(content)) content = seen.receive()
            content
        }

        /** Whether the tab is handed nothing more for a moment. */
        suspend fun restsFor(millis: Long): Boolean = withTimeoutOrNull(millis) { seen.receive() } == null

        suspend fun leave() = collecting.cancelAndJoin()
    }

    // Asking for the Live tab

    @Test fun `after a Sync of the fixture with Games from the cloud, the Live tab holds its Rows in order, each with its cards`() = runBlocking {
        serveSlate()
        library.sync()

        val content = library.screen(live).first()

        // The fixture has no Event channel: the Game on now has no Feed, so it is hidden, and the upcoming ones show without one.
        assertEquals(listOf("today", "league/nhl", "league/nfl"), content.keys())
        assertEquals(listOf("2"), content.games("today"))
        assertEquals(listOf("3"), content.games("league/nhl"))
        assertEquals(listOf("100"), content.games("league/nfl"))
        // Each card is its Game's, by its key.
        assertEquals(listOf("game/${(content.rows.first() as HomeRow.Today).games.single().key}"), content.rows.first().cardKeys)
    }

    @Test fun `Live now, Today, each League, More live events, Replays, Favourites and the Channels' countries come in that order`() = runBlocking {
        serveSlate()
        serveCatalog()
        provider.serveMovies("SOC - Montreal Canadiens at Toronto Maple Leafs 25.10.26" to "SOCCER HOCKEY")
        provider.showCategoriesOverride = "[]"
        provider.showListOverride = "[]"
        library.sync()
        saveTsn()

        val content = library.screen(live).first()

        assertEquals(listOf("live-now", "today", "league/nhl", "league/nfl", "more-live-events", "replays", "favourites", "channels"), content.keys())
        assertEquals(listOf("1"), content.games("live-now"))
        assertEquals(listOf("ATP Paris: Djokovic vs Alcaraz (2026-11-01 16:00:00)"), content.rows.filterIsInstance<HomeRow.MoreLiveEvents>().single().events.map { it.channel.variants.first().name })
        assertEquals(1, content.rows.filterIsInstance<HomeRow.Replays>().single().replays.size)
        assertEquals(listOf("TSN 1"), content.rows.filterIsInstance<HomeRow.Favourites>().single().channels.map { it.name })
        // The Channels row is the Channel browser's countries: an Event channel's Category names none.
        assertEquals(library.liveCountries("CA", english), content.rows.filterIsInstance<HomeRow.Channels>().single().countries)
        assertEquals(listOf("country/CA"), content.rows.last().cardKeys)
    }

    @Test fun `a Game is in one Row of the Live tab only, the highest it fits, and no Row or card comes twice`() = runBlocking {
        serveSlate()
        serveCatalog()
        library.sync()

        val content = library.screen(live).first()

        // The Game on now is today's and the NHL's too, and tonight's is the NHL's: each shows once, where it comes first.
        assertEquals(listOf("1"), content.games("live-now"))
        assertEquals(listOf("2"), content.games("today"))
        assertEquals(listOf("3"), content.games("league/nhl"))
        assertEquals(listOf("100"), content.games("league/nfl"))
        assertEquals(listOf("1", "2", "3", "100"), content.allGames())
        assertEquals(content.keys().distinct(), content.keys())
        content.rows.forEach { row -> assertEquals(row.cardKeys.distinct(), row.cardKeys) }
        val gameCards = content.rows.flatMap { row -> row.cardKeys.filter { it.startsWith("game/") } }
        assertEquals(gameCards.distinct(), gameCards)
    }

    @Test fun `a Kids profile's Live tab holds no Game, only the Kids Channels`() = runBlocking {
        serveSlate()
        provider.serveChannels("NHL: Canadiens vs Maple Leafs (2026-11-01 13:00:00)" to "US| ESPN+ PPV", "CA: TSN 1" to "CA| SPORT", "CA: Treehouse" to "CA| KIDS")
        library.sync()
        val watched = Watched(this, live)
        assertEquals(listOf("live-now", "today", "league/nhl", "league/nfl", "channels"), watched.next().keys())

        library.switchProfile(library.addProfile("Kids", kids = true)!!.id)

        val content = watched.next()
        assertEquals(listOf("kids-channels/CA"), content.keys())
        assertEquals(listOf("Treehouse"), (content.rows.single() as HomeRow.KidsChannels).channels.map { it.name })
        watched.leave()
    }

    // Leagues turned on and off

    @Test fun `turning a League off takes its Row and its Games off the tab, and turning it on brings them back`() = runBlocking {
        serveSlate()
        serveCatalog()
        library.sync()
        assertEquals(listOf("live-now", "today", "league/nhl", "league/nfl", "more-live-events", "channels"), library.screen(live).first().keys())

        // The NHL off: its Games go, and tonight's Event channel is one no Game matched.
        val nflOnly = library.screen(live.copy(leagues = listOf(League.Nfl))).first()
        assertEquals(listOf("league/nfl", "more-live-events", "channels"), nflOnly.keys())
        assertEquals(listOf("100"), nflOnly.allGames())
        assertEquals(listOf("ATP Paris: Djokovic vs Alcaraz (2026-11-01 16:00:00)", "Senators @ Bruins (2026-11-01 19:00:00)"), nflOnly.rows.filterIsInstance<HomeRow.MoreLiveEvents>().single().events.map { it.channel.variants.first().name })

        // Every League off: no Game at all.
        val none = library.screen(live.copy(leagues = emptyList())).first()
        assertEquals(listOf("more-live-events", "channels"), none.keys())

        // Back on, in Settings' order, which the League rows follow.
        val back = library.screen(live.copy(leagues = listOf(League.Nfl, League.Nhl))).first()
        assertEquals(listOf("live-now", "today", "league/nfl", "league/nhl", "more-live-events", "channels"), back.keys())
        assertEquals(listOf("1", "2", "100", "3"), back.allGames())
    }

    // Live state: the tab is handed its content again as Games start and end, without asking

    @Test fun `a Game that starts moves up to Live now, and one that ends leaves the tab, as the clock moves`() = runBlocking {
        serveSlate()
        serveCatalog()
        library.sync()
        val watched = Watched(this, live, readAgainAfter = 50.milliseconds)
        val afternoon = watched.next()
        assertEquals(listOf("1"), afternoon.games("live-now"))
        assertEquals(listOf("2"), afternoon.games("today"))
        // Read again and again with the same Games on: the tab is handed nothing.
        assertTrue(watched.restsFor(300))

        // 7:05 PM: the afternoon Game is over and tonight's has started.
        serveSlate(firstState = "over", secondState = "live")
        clock.at += 3.hours + 5.minutes

        val evening = watched.nextThat { it.games("live-now") == listOf("2") }
        assertEquals(listOf("live-now", "league/nhl", "league/nfl", "channels"), evening.keys())
        assertEquals(listOf("2", "3", "100"), evening.allGames())

        // 10:05 PM: tonight's is over too, and nothing is on.
        serveSlate(firstState = "over", secondState = "over")
        clock.at += 3.hours

        val night = watched.nextThat { it.games("live-now") == null }
        assertEquals(listOf("league/nhl", "league/nfl", "channels"), night.keys())
        assertEquals(listOf("3", "100"), night.allGames())
        watched.leave()
    }

    @Test fun `a Game moves from its League's Row to Today when its day comes`() = runBlocking {
        serveSlate(firstState = "over")
        serveCatalog()
        library.sync()
        val watched = Watched(this, live, readAgainAfter = 50.milliseconds)
        assertEquals(listOf("3"), watched.next().games("league/nhl"))

        // Monday, 9 AM in Toronto: Monday night's Game is today's, and Sunday night's is over.
        serveSlate(firstState = "over", secondState = "over")
        clock.at = Instant.parse("2026-11-02T14:00:00Z")

        val monday = watched.nextThat { it.games("today") == listOf("3") }
        assertEquals(listOf("today", "league/nfl", "channels"), monday.keys())
        watched.leave()
    }

    @Test fun `a catalog that lands reaches a tab that stands, and one read again and found the same is not handed over twice`() = runBlocking {
        cloud.games("nhl", game("3", "2026-11-03T00:00:00Z", home = "Edmonton Oilers", away = "Calgary Flames"))
        provider.serveChannels("CA: TSN 1" to "CA| SPORT")
        library.sync()
        val watched = Watched(this, live)
        assertEquals(listOf("league/nhl", "channels"), watched.next().keys())

        // A Sync lands another country's Channel.
        provider.serveChannels("CA: TSN 1" to "CA| SPORT", "US: ESPN HD" to "US| SPORT")
        library.sync()

        assertEquals(listOf("CA", "US"), (watched.next().rows.last() as HomeRow.Channels).countries.map { it.code })

        // The same catalog again: the tab is read again, and holds what it held.
        library.sync()
        assertTrue(watched.restsFor(500))
        watched.leave()
    }

    // Read again by the clock

    @Test fun `the tab asks for the Games again every two minutes while a Games row or More live events shows, and stands otherwise`() {
        assertEquals(GAMES_READ_AGAIN_AFTER, live.readAgainAfter(LiveTabContent(listOf(HomeRow.LiveNow(emptyList())))))
        assertEquals(GAMES_READ_AGAIN_AFTER, live.readAgainAfter(LiveTabContent(listOf(HomeRow.Today(emptyList())))))
        assertEquals(GAMES_READ_AGAIN_AFTER, live.readAgainAfter(LiveTabContent(listOf(HomeRow.LeagueGames(League.Nhl, emptyList())))))
        assertEquals(GAMES_READ_AGAIN_AFTER, live.readAgainAfter(LiveTabContent(listOf(HomeRow.MoreLiveEvents(emptyList())))))
        assertNull(live.readAgainAfter(LiveTabContent(listOf(HomeRow.Replays(emptyList()), HomeRow.Favourites(emptyList()), HomeRow.Channels(emptyList())))))
        assertNull(live.readAgainAfter(LiveTabContent(emptyList())))
    }
}
