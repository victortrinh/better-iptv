package dev.betteriptv.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel as Seen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * A Game's page as finished content ([GamePage], #591), asked of the Library as an app asks for a
 * screen ([Library.screen]): its Feeds in order and named, the ones marked "Not this game" last, in
 * the Profile that marked them only, and a finished Game's Replay. Prior art: [HomeScreenTest],
 * [GameRowsTest].
 */
class GamePageTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private lateinit var library: Library

    /** A Sunday, 4 PM in Toronto. */
    private val now = Instant.parse("2026-11-01T20:00:00Z")
    private val toronto = TimeZone.of("America/Toronto")
    private val english = listOf("en")

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

    /** The NHL's Game [id] as the schedule has it at [now]. */
    private suspend fun game(id: String) = library.games(listOf("nhl"), now).single { it.id == id }

    private suspend fun page(game: Game, languages: List<String> = english) = library.gamePage(game, languages, "CA", now, toronto)

    /** A Feed as the page tells it apart: its service and broadcast, or its fallback, then what kind of Channel it is. */
    private fun Feed.told(): String {
        val broadcast = when (val it = label.broadcast) {
            null -> null
            FeedBroadcast.National -> "national"
            is FeedBroadcast.Team -> "${it.shortName} broadcast"
            is FeedBroadcast.Other -> it.text
        }
        return "${listOfNotNull(label.service, broadcast).joinToString(" ").ifEmpty { label.fallback }} (${kind.name})"
    }

    /** The page's groups, top to bottom, each with its Feeds in order. */
    private fun GamePageContent.listed(): List<Pair<String, List<String>>> = groups.map { group ->
        when (group) {
            is FeedGroup.Language -> group.language ?: "untagged"
            is FeedGroup.NotThisGame -> "not this game"
        } to group.feeds.map { it.told() }
    }

    /** Tonight's Game, Senators at Bruins, with two Event channels, its Broadcaster's Channel and the League's network. */
    private suspend fun serveTonight() {
        cloud.games("nhl", game("2", "2026-11-02T00:00:00Z", home = "Boston Bruins", away = "Ottawa Senators", broadcasters = listOf("TSN")))
        provider.serveChannels(
            "CA: TSN" to "CA| SPORT",
            "US: NHL Network" to "US| SPORT",
            "CA FR: Senators vs Bruins (2026-11-01 19:00:00)" to "CA| RDS PPV",
            "US (ESPN+ 335) | Hockey: Senators vs. Bruins (Bruins Broadcast) (2026-11-01 19:00:35)" to "US| ESPN+ PPV",
        )
        library.sync()
    }

    // The Feeds, in order and named

    @Test fun `a Game's Feeds come grouped by language, Content languages first, an Event channel before the Broadcaster's Channel, each named by what tells it apart`() = runBlocking {
        serveTonight()

        val content = page(game("2"))

        assertEquals("2", content.game.id)
        assertEquals(
            listOf(
                "en" to listOf("ESPN+ Bruins broadcast (EventChannel)", "TSN (Broadcaster)"),
                "fr" to listOf("RDS (EventChannel)"),
            ),
            content.listed(),
        )
        // The groups hold every Feed once, in the Game's order; Play starts the first.
        assertEquals(content.entry.feeds, content.groups.flatMap { it.feeds })
        assertEquals("ESPN+ Bruins broadcast (EventChannel)", content.best?.told())
        assertEquals(3, content.feedsLeft)
        assertEquals("TSN", content.entry.feeds[1].broadcaster)
        // Not over: no Replay.
        assertNull(content.replay)

        // A Viewer who reads French first gets the French Feed first, and Play starts it.
        val french = page(game("2"), listOf("fr", "en"))
        assertEquals(listOf("fr", "en"), french.groups.map { (it as FeedGroup.Language).language })
        assertEquals("RDS (EventChannel)", french.best?.told())
    }

    @Test fun `a Feed marked Not this game comes last, in a group of its own, and the League network joins first once no named Feed is left`() = runBlocking {
        serveTonight()
        var content = page(game("2"))

        library.notThisGame(content.entry, content.best!!, english, "CA", now)
        content = page(game("2"))
        assertEquals(
            listOf(
                "en" to listOf("TSN (Broadcaster)"),
                "fr" to listOf("RDS (EventChannel)"),
                "not this game" to listOf("ESPN+ Bruins broadcast (EventChannel)"),
            ),
            content.listed(),
        )
        assertEquals("TSN (Broadcaster)", content.best?.told())
        assertEquals(2, content.feedsLeft)
        assertEquals(listOf(false, false, true), content.entry.feeds.map { it.dropped })

        // With the Broadcaster's and the French one marked too, the League's network stands in, first.
        while (content.best?.kind != FeedKind.LeagueNetwork) {
            library.notThisGame(content.entry, content.best!!, english, "CA", now)
            content = page(game("2"))
        }
        assertEquals(
            listOf(
                "en" to listOf("NHL Network (LeagueNetwork)"),
                "not this game" to listOf("ESPN+ Bruins broadcast (EventChannel)", "TSN (Broadcaster)", "RDS (EventChannel)"),
            ),
            content.listed(),
        )
        assertEquals("NHL Network", content.best?.broadcaster)

        // With that marked too, nothing is left for Play, and every Feed stays listed.
        library.notThisGame(content.entry, content.best!!, english, "CA", now)
        content = page(game("2"))
        assertNull(content.best)
        assertEquals(0, content.feedsLeft)
        assertEquals(listOf("not this game"), content.listed().map { it.first })
        assertEquals(4, content.groups.single().feeds.size)
    }

    @Test fun `a Game nothing carries has a page with no Feed`() = runBlocking {
        cloud.games("nhl", game("3", "2026-11-03T00:00:00Z", home = "Edmonton Oilers", away = "Calgary Flames"))
        library.sync()

        val content = page(game("3"))

        assertEquals(emptyList<FeedGroup>(), content.groups)
        assertNull(content.best)
        assertNull(content.replay)
    }

    // Live state

    /** A Game's page as an app holds it while the screen shows: collected once, each content it is handed kept in order. */
    private class Watched(scope: CoroutineScope, library: Library, screen: GamePage) {
        private val seen = Seen<GamePageContent>(Seen.UNLIMITED)
        private val collecting = scope.launch(Dispatchers.Default) { library.screen(screen).collect { seen.send(it) } }

        /** The first content the page is handed, from now on, that [shows] what is waited for. */
        suspend fun nextThat(shows: (GamePageContent) -> Boolean): GamePageContent = withTimeout(10.seconds) {
            var content = seen.receive()
            while (!shows(content)) content = seen.receive()
            content
        }

        suspend fun leave() = collecting.cancelAndJoin()
    }

    @Test fun `marking a Feed Not this game updates the page without it being asked for again, in that Profile only`() = runBlocking {
        // The page reads by the device's clock: a Game an hour from now, carried by its Broadcaster.
        cloud.games("nhl", game("2", (Clock.System.now() + 1.hours).toString(), home = "Boston Bruins", away = "Ottawa Senators", broadcasters = listOf("TSN")))
        provider.serveChannels("CA: TSN" to "CA| SPORT", "US: NHL Network" to "US| SPORT")
        library.sync()
        val game = library.games(listOf("nhl")).single()
        val first = library.profiles.value.current
        val watched = Watched(this, library, GamePage(game, english, "CA"))
        val before = watched.nextThat { true }
        assertEquals(listOf("en" to listOf("TSN (Broadcaster)")), before.listed())

        library.notThisGame(before.entry, before.best!!, english, "CA")

        val marked = listOf("en" to listOf("NHL Network (LeagueNetwork)"), "not this game" to listOf("TSN (Broadcaster)"))
        assertEquals(marked, watched.nextThat { it.entry.feeds.any { feed -> feed.dropped } }.listed())

        // Another Profile of the household never marked it: its page lists the Feed as before.
        val alex = library.addProfile("Alex")!!
        library.switchProfile(alex.id)
        assertEquals(before.listed(), watched.nextThat { it.entry.feeds.none { feed -> feed.dropped } }.listed())
        assertEquals(before.listed(), library.screen(GamePage(game, english, "CA")).first().listed())

        // Back on the first, the mark is still there.
        library.switchProfile(first.id)
        assertEquals(marked, watched.nextThat { it.entry.feeds.any { feed -> feed.dropped } }.listed())
        watched.leave()
    }

    // A finished Game's Replay

    private suspend fun serveFinished() {
        cloud.games(
            "nhl",
            // Sunday 2025-11-09, 7 PM in Toronto: Monday in UTC.
            game("1", "2025-11-10T00:00:00Z", state = "over", home = "Toronto Maple Leafs", away = "Boston Bruins"),
            game("2", "2026-11-02T00:00:00Z", home = "Ottawa Senators", away = "Montreal Canadiens"),
        )
        provider.serveMovies(
            "SOC - Boston Bruins at Toronto Maple Leafs 09.11.25" to "SOCCER HOCKEY",
            // The same teams on another day are another Game's.
            "SOC - Toronto Maple Leafs at Boston Bruins 07.11.25" to "SOCCER HOCKEY",
            // Yesterday's meeting of tonight's teams: not tonight's Game's.
            "SOC - Montreal Canadiens at Ottawa Senators 31.10.26" to "SOCCER HOCKEY",
        )
        library.sync()
    }

    @Test fun `a Game that is over shows its Replay with what playing it starts, and one not over shows none`() = runBlocking {
        serveFinished()

        val content = page(game("1"))

        val replay = content.replay!!
        assertEquals("SOC - Boston Bruins at Toronto Maple Leafs 09.11.25", replay.replay.name)
        // Playing it plays its Listing's Version, on its own.
        assertEquals(replay.replay.listingId, replay.plan.version.listingId)
        assertEquals(listOf(replay.replay.listingId), replay.plan.versions.map { it.listingId })
        assertEquals(english, replay.plan.contentLanguages)

        assertNull(page(game("2")).replay)
    }

    @Test fun `a Game that ended since its card was read is over on its page, with its Replay`() = runBlocking {
        serveFinished()
        val asItsCardHadIt = game("1").copy(state = GameState.Live)

        val content = page(asItsCardHadIt)

        assertEquals(GameState.Over, content.game.state)
        assertEquals("SOC - Boston Bruins at Toronto Maple Leafs 09.11.25", content.replay?.replay?.name)
    }

    @Test fun `a Game the schedule no longer lists keeps its page as its card had it`() = runBlocking {
        serveFinished()
        val gone = game("1").copy(id = "gone")

        val content = page(gone)

        assertEquals(gone, content.game)
        // Its teams and day are still the Replay's.
        assertEquals("SOC - Boston Bruins at Toronto Maple Leafs 09.11.25", content.replay?.replay?.name)
    }
}
