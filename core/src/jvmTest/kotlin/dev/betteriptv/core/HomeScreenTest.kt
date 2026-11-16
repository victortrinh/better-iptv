package dev.betteriptv.core

import dev.betteriptv.core.FakeProvider.MovieListing
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Home's finished content ([Screen.Home]), asked of the Library as an app asks for a screen
 * ([Library.screen], #587): its Rows in order with their cards, and its updates as live state, which
 * arrive without the content being asked for again.
 */
class HomeScreenTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private val libraries = mutableListOf<Library>()

    @After fun tearDown() {
        libraries.forEach { it.close() }
        provider.close()
        cloud.close()
    }

    private val english = listOf("en")
    private val home = Screen.Home(english, appLanguage = "en", region = "CA")

    private suspend fun library(name: String = "catalog"): Library {
        val library = Library.open(folder.root.resolve("$name.db").path, InMemoryCredentials(), cloud.url).also { libraries += it }
        library.logIn(provider.login)
        return library
    }

    private fun movie(titleNumber: Long) = TitleId.Numbered(TitleKind.Movie, titleNumber)

    /** [count] movies of [category], Title numbers from [from], named "[name] 1" on, each newer than the next. */
    private fun movies(name: String, category: String, from: Long, count: Int) =
        (0 until count).map { MovieListing("EN - $name ${it + 1} (2025)", category, from + it) }

    /** Serves these movie Listings as the whole catalog's Titles, the first the newest: no Show beside them. */
    private fun serve(listings: List<MovieListing>) {
        provider.serveMovieListings(*listings.toTypedArray())
        provider.showCategoriesOverride = "[]"
        provider.showListOverride = "[]"
    }

    /** Home as an app holds it while the screen shows: collected once, each content it is handed kept in order. */
    private class Watched(scope: CoroutineScope, library: Library, screen: Screen.Home) {
        private val seen = Seen<HomeContent>(Seen.UNLIMITED)
        private val collecting = scope.launch(Dispatchers.Default) { library.screen(screen).collect { seen.send(it) } }

        /** The next content Home is handed, waited for. */
        suspend fun next(): HomeContent = withTimeout(10.seconds) { seen.receive() }

        /** The first content Home is handed, from now on, that [shows] what is waited for. */
        suspend fun nextThat(shows: (HomeContent) -> Boolean): HomeContent = withTimeout(10.seconds) {
            var content = seen.receive()
            while (!shows(content)) content = seen.receive()
            content
        }

        /** Whether Home is handed nothing more for a moment. */
        suspend fun restsFor(millis: Long): Boolean = withTimeoutOrNull(millis) { seen.receive() } == null

        suspend fun leave() = collecting.cancelAndJoin()
    }

    private fun HomeContent.keys() = rows.map { it.key }

    private fun HomeContent.names(key: String) = rows.single { it.key == key }.titleNames()

    private fun HomeRow.titleNames(): List<String> = when (this) {
        is HomeRow.ContinueWatching -> titles.map { it.title.name }
        is HomeRow.Trending -> titles.map { it.name }
        is HomeRow.NewOnYourService -> titles.map { it.name }
        is HomeRow.NewForKids -> titles.map { it.name }
        is HomeRow.ForKids -> titles.map { it.name }
        is HomeRow.Genre -> titles.map { it.name }
        else -> emptyList()
    }

    // Asking for Home

    @Test fun `after a Sync of the fixture, Home holds its Rows in order, each with its cards`() = runBlocking {
        val library = library()
        library.sync()

        val content = library.screen(home).first()

        // The fixture's Titles with a Version in English, movies and shows together, newest first; no genre has five.
        assertEquals(listOf("new"), content.keys())
        assertEquals(
            listOf(
                "Oz the Great and Powerful", "The Chelsea Detective", "Game of Thrones", "The Godfather", "Ballerina", "The Godfather: Part II", "Ballerina Assassin",
                "Hightown", "The Canterville Ghost", "Jones Plantation", "Gentefied", "Titanic", "Exposure 36", "Titanic",
            ),
            content.names("new"),
        )
        // Each card is its Title's, by its key: the two Titanics are two Titles, one of them Unmatched.
        val row = content.rows.single() as HomeRow.NewOnYourService
        assertEquals(row.titles.map { it.id.toString() }, row.cardKeys)
        assertEquals(row.cardKeys.distinct(), row.cardKeys)
        assertEquals(TitleId.Numbered(TitleKind.Movie, 68728), row.titles.first().id)
        assertEquals(TitleId.Numbered(TitleKind.Show, 1399), row.titles[2].id)
    }

    @Test fun `a Title is in one Row of Home only, the highest it fits, and no Row or card comes twice`() = runBlocking {
        val library = library()
        val dramas = movies("Drama", "EN - DRAMA", from = 1, count = 30)
        val comedies = movies("Comedy", "EN - COMEDY", from = 101, count = 10)
        serve(dramas + comedies)
        library.sync()

        val content = library.screen(home).first()

        assertEquals(listOf("new", "genre/Drama", "genre/Comedy"), content.keys())
        // New on your service takes the newest twenty; Drama goes on from the twenty-first, and skips none of its own.
        assertEquals((1..20).map { "Drama $it" }, content.names("new"))
        assertEquals((21..30).map { "Drama $it" }, content.names("genre/Drama"))
        assertEquals((1..10).map { "Comedy $it" }, content.names("genre/Comedy"))
        val cards = content.rows.flatMap { it.cardKeys }
        assertEquals(cards.distinct(), cards)
        // What a card's badges say comes with it: the language its best Version is in.
        val card = (content.rows.first() as HomeRow.NewOnYourService).titles.first()
        assertEquals(movie(1), card.id)
        assertEquals("en", card.best.language?.value)
    }

    @Test fun `Continue watching and Favourites stand apart, a Title in them still in its browse Row`() = runBlocking {
        val library = library()
        serve(movies("Film", "EN - DRAMA", from = 1, count = 6))
        provider.serveChannels("US: ESPN HD" to "US| SPORT")
        library.sync()
        library.saveProgress(movie(2), position = 30 * 60_000L, duration = 120 * 60_000L)
        library.setFavourite(library.search("ESPN", english, "en").last().channels.single().key, true)

        val content = library.screen(home).first()

        assertEquals(listOf("continue", "new", "favourites"), content.keys())
        assertEquals(listOf("Film 2"), content.names("continue"))
        assertEquals(0.25f, (content.rows.first() as HomeRow.ContinueWatching).titles.single().progress)
        assertEquals((1..6).map { "Film $it" }, content.names("new"))
        assertEquals(listOf("ESPN"), (content.rows.last() as HomeRow.Favourites).channels.map { it.name })
    }

    // Live state: Home is handed its content again when it changes, without asking

    @Test fun `Home fills as a first Sync lands, from nothing to the catalog's Rows`() = runBlocking {
        val library = library()
        serve(movies("Film", "EN - DRAMA", from = 1, count = 8))
        val watched = Watched(this, library, home)
        assertEquals(emptyList<String>(), watched.next().keys())

        library.sync()

        val landed = watched.nextThat { it.rows.sumOf { row -> row.cardKeys.size } == 8 }
        assertEquals(listOf("new"), landed.keys())
        assertEquals((1..8).map { "Film $it" }, landed.names("new"))
        watched.leave()
    }

    @Test fun `a Pending catalog shows on Home once it lands, and not before`() = runBlocking {
        val library = library()
        serve(movies("Film", "EN - DRAMA", from = 1, count = 3))
        library.sync()
        val watched = Watched(this, library, home)
        assertEquals(listOf("Film 1", "Film 2", "Film 3"), watched.next().names("new"))

        // The next Sync is held, as under a screen in focus: its catalog waits.
        serve(listOf(MovieListing("EN - Fresh (2026)", "EN - DRAMA", 9)) + movies("Film", "EN - DRAMA", from = 1, count = 3))
        assertTrue(library.sync(hold = true) is SyncStatus.Ready)
        assertTrue(watched.restsFor(300))

        assertTrue(library.applyPendingCatalog())

        assertEquals(listOf("Fresh", "Film 1", "Film 2", "Film 3"), watched.next().names("new"))
        watched.leave()
    }

    @Test fun `switching Profile hands Home the other Profile's content`() = runBlocking {
        val library = library()
        serve(movies("Film", "EN - DRAMA", from = 1, count = 3))
        library.sync()
        library.saveProgress(movie(2), position = 30 * 60_000L, duration = 120 * 60_000L)
        val watched = Watched(this, library, home)
        assertEquals(listOf("continue", "new"), watched.next().keys())
        val first = library.profiles.value.currentId

        library.switchProfile(library.addProfile("Guest", kids = false)!!.id)

        // The Guest stopped nowhere: the same catalog, without the first Profile's Continue watching.
        assertEquals(listOf("new"), watched.next().keys())

        library.switchProfile(first)

        assertEquals(listOf("Film 2"), watched.next().names("continue"))
        watched.leave()
    }

    @Test fun `a Kids profile's Home holds only the Kids catalog`() = runBlocking {
        val library = library()
        val kids = movies("Kid movie", "EN - KIDS ANIMATION", from = 1, count = 4)
        val grownUp = movies("Grown-up", "EN - DRAMA", from = 101, count = 6)
        serve(grownUp + kids)
        library.sync()
        val watched = Watched(this, library, home)
        assertTrue(watched.next().names("new").any { it.startsWith("Grown-up") })

        library.switchProfile(library.addProfile("Kids", kids = true)!!.id)

        val content = watched.next()
        assertEquals(listOf("new-for-kids"), content.keys())
        assertEquals((1..4).map { "Kid movie $it" }, content.names("new-for-kids"))
        val shown = content.rows.flatMap { it.titleNames() }
        assertTrue(shown.none { it.startsWith("Grown-up") })
        watched.leave()
    }

    @Test fun `Title facts that land show on Home, an Untagged Title joining its Row`() = runBlocking {
        val library = library()
        // An Untagged Listing: nothing says its language until the cloud's Title facts give the Title's original one.
        serve(listOf(MovieListing("Quiet Film (2025)", "MOVIES", 50)) + movies("Film", "EN - DRAMA", from = 1, count = 3))
        library.sync()
        val watched = Watched(this, library, home)
        assertEquals(listOf("Film 1", "Film 2", "Film 3"), watched.next().names("new"))

        cloud.titleFacts(movie(50), originalLanguage = "en")
        assertTrue(library.sync(hold = true) is SyncStatus.Ready)
        assertTrue(library.applyPendingCatalog())
        // The facts are fetched and wait, as a Pending catalog does: Home holds what it held.
        assertTrue(watched.restsFor(300))

        assertTrue(library.applyPendingTitleFacts())

        assertEquals(listOf("Quiet Film", "Film 1", "Film 2", "Film 3"), watched.next().names("new"))
        watched.leave()
    }

    @Test fun `history synced from another TV of the Account shows in Continue watching once it lands`() = runBlocking {
        serve(movies("Film", "EN - DRAMA", from = 1, count = 3))
        val living = library("living").apply { sync(); signIn() }
        val bedroom = library("bedroom").apply { sync(); signIn() }
        val watched = Watched(this, bedroom, home)
        assertEquals(listOf("new"), watched.next().keys())

        living.saveProgress(movie(3), position = 62 * 60_000L, duration = 120 * 60_000L)
        living.syncHistory()
        assertTrue(bedroom.syncHistory())
        // Pulled, and waiting for a screen entry: nothing moved on the Home that shows.
        assertTrue(watched.restsFor(300))

        assertTrue(bedroom.applyPendingHistory())

        assertEquals(listOf("Film 3"), watched.next().names("continue"))
        watched.leave()
    }

    private suspend fun Library.signIn(email: String = "home@example.com") {
        assertTrue(cloud.phoneSignsIn(startSignIn()!!.code, email))
        assertEquals(email, checkSignIn())
        // The merge into the Account runs in the background as sign-in completes: this waits for it.
        syncHistory()
    }

    @Test fun `content read again and found the same is not handed to Home twice`() = runBlocking {
        val library = library()
        serve(movies("Film", "EN - DRAMA", from = 1, count = 3))
        library.sync()
        val watched = Watched(this, library, home)
        watched.next()

        // Another Sync of the same catalog lands: Home is read again, and holds what it held.
        library.sync()

        assertTrue(watched.restsFor(500))
        watched.leave()
    }

    // Read again by the clock

    @Test fun `Home asks for the Games again every two minutes while Live now shows, and stands otherwise`() {
        assertEquals(GAMES_READ_AGAIN_AFTER, home.readAgainAfter(HomeContent(listOf(HomeRow.LiveNow(emptyList())))))
        assertNull(home.readAgainAfter(HomeContent(listOf(HomeRow.Favourites(emptyList())))))
    }

    @Test fun `content that stands for a time is read again when the time is up, though nothing told the Core`() = runBlocking {
        val library = library()
        serve(movies("Film", "EN - DRAMA", from = 1, count = 3))
        library.sync()
        val seen = Seen<HomeContent>(Seen.UNLIMITED)
        val collecting = launch(Dispatchers.Default) { library.liveContent(home) { 50.milliseconds }.collect { seen.send(it) } }
        assertEquals(listOf("new"), withTimeout(10.seconds) { seen.receive() }.keys())

        // The player records where the Viewer is while a screen is behind it: no change is signalled.
        library.saveProgress(movie(1), position = 30 * 60_000L, duration = 120 * 60_000L)

        assertEquals(listOf("continue", "new"), withTimeout(10.seconds) { seen.receive() }.keys())
        collecting.cancelAndJoin()
    }
}
