package dev.betteriptv.core

import dev.betteriptv.core.FakeProvider.MovieListing
import dev.betteriptv.core.FakeProvider.ShowListing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel as Seen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.seconds

/**
 * The Movies and Shows tabs' finished content ([TitlesTab]), asked of the Library as an app asks for
 * a screen ([Library.screen], #588): each tab's Rows in order with their cards, and its updates as
 * live state, which arrive without the content being asked for again.
 */
class TitlesTabScreenTest {
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
    private val moviesTab = TitlesTab(TitleKind.Movie, english, appLanguage = "en")
    private val showsTab = TitlesTab(TitleKind.Show, english, appLanguage = "en")

    private suspend fun library(): Library {
        val library = Library.open(folder.root.resolve("catalog.db").path, InMemoryCredentials(), cloud.url).also { libraries += it }
        library.logIn(provider.login)
        return library
    }

    private fun movie(titleNumber: Long) = TitleId.Numbered(TitleKind.Movie, titleNumber)
    private fun show(titleNumber: Long) = TitleId.Numbered(TitleKind.Show, titleNumber)

    /** [count] movies of [category], Title numbers from [from], named "[name] 1" on, each newer than the next. */
    private fun movies(name: String, category: String, from: Long, count: Int) =
        (0 until count).map { MovieListing("EN - $name ${it + 1} (2025)", category, from + it) }

    /** [count] shows of [category], Title numbers from [from], named "[name] 1" on, each newer than the next. */
    private fun shows(name: String, category: String, from: Long, count: Int) =
        (0 until count).map { ShowListing("EN - $name ${it + 1} (2025)", category, from + it) }

    /** Serves these as the whole catalog's Titles, the first of each kind the newest. */
    private fun serve(movies: List<MovieListing>, shows: List<ShowListing> = emptyList()) {
        provider.serveMovieListings(*movies.toTypedArray())
        provider.serveShows(*shows.toTypedArray())
    }

    /** Makes these Titles the week's Trending, most read first: each one's English Wikipedia article is scripted and read. */
    private fun trending(vararg ids: TitleId.Numbered) {
        ids.forEach { cloud.titleFacts(it, originalLanguage = "en", enwiki = "Article $it") }
        cloud.trending("en", "2026-11-06", *ids.mapIndexed { i, id -> "Article $id" to 1_000L - i }.toTypedArray())
    }

    /** A tab as an app holds it while the screen shows: collected once, each content it is handed kept in order. */
    private class Watched(scope: CoroutineScope, library: Library, tab: TitlesTab) {
        private val seen = Seen<TitlesTabContent>(Seen.UNLIMITED)
        private val collecting = scope.launch(Dispatchers.Default) { library.screen(tab).collect { seen.send(it) } }

        /** The next content the tab is handed, waited for. */
        suspend fun next(): TitlesTabContent = withTimeout(10.seconds) { seen.receive() }

        /** The first content the tab is handed, from now on, that [shows] what is waited for. */
        suspend fun nextThat(shows: (TitlesTabContent) -> Boolean): TitlesTabContent = withTimeout(10.seconds) {
            var content = seen.receive()
            while (!shows(content)) content = seen.receive()
            content
        }

        /** Whether the tab is handed nothing more for a moment. */
        suspend fun restsFor(millis: Long): Boolean = withTimeoutOrNull(millis) { seen.receive() } == null

        suspend fun leave() = collecting.cancelAndJoin()
    }

    private fun TitlesTabContent.keys() = rows.map { it.key }

    private fun TitlesTabContent.names(key: String) = rows.single { it.key == key }.titles().map { it.name }

    private fun TitlesTabContent.cards() = rows.flatMap { it.titles() }

    private fun HomeRow.titles(): List<TitleSummary> = when (this) {
        is HomeRow.Trending -> titles
        is HomeRow.NewOnYourService -> titles
        is HomeRow.NewForKids -> titles
        is HomeRow.ForKids -> titles
        is HomeRow.Genre -> titles
        else -> error("not a Row of a Titles tab: $this")
    }

    // Asking for a tab

    @Test fun `after a Sync of the fixture, each tab holds its kind's Titles in its Rows, each with its cards`() = runBlocking {
        val library = library()
        library.sync()

        val movies = library.screen(moviesTab).first()
        val shows = library.screen(showsTab).first()

        // The fixture's Titles with a Version in English, newest first; no genre has five of a kind.
        assertEquals(TitleKind.Movie, movies.kind)
        assertEquals(listOf("new"), movies.keys())
        assertEquals(
            listOf("Oz the Great and Powerful", "The Godfather", "Ballerina", "The Godfather: Part II", "Ballerina Assassin", "The Canterville Ghost", "Jones Plantation", "Titanic"),
            movies.names("new"),
        )
        assertEquals(TitleKind.Show, shows.kind)
        assertEquals(listOf("new"), shows.keys())
        assertEquals(listOf("The Chelsea Detective", "Game of Thrones", "Hightown", "Gentefied"), shows.names("new"))
        // Each Row says which tab's it is, and each card is its Title's, by its key.
        for (content in listOf(movies, shows)) {
            val row = content.rows.single() as HomeRow.NewOnYourService
            assertEquals(content.kind, row.kind)
            assertEquals(row.titles.map { it.id.toString() }, row.cardKeys)
            assertEquals(row.cardKeys.distinct(), row.cardKeys)
            assertTrue(row.titles.all { it.id.kind == content.kind })
        }
    }

    @Test fun `a tab's Rows are Trending, New, then its genres, a Title in one Row only, the highest it fits`() = runBlocking {
        val library = library()
        val dramas = movies("Drama", "EN - DRAMA", from = 1, count = 30)
        val comedies = movies("Comedy", "EN - COMEDY", from = 101, count = 10)
        serve(dramas + comedies, shows("Series", "EN - DRAMA", from = 501, count = 3))
        // Three movies and a show trend: the show is the Shows tab's, and one show is no Trending Row.
        trending(movie(25), show(501), movie(102), movie(1))
        library.sync()

        val content = library.screen(moviesTab).first()

        assertEquals(listOf("trending", "new", "genre/Drama", "genre/Comedy"), content.keys())
        assertEquals(listOf("Drama 25", "Comedy 2", "Drama 1"), content.names("trending"))
        assertEquals(TitleKind.Movie, (content.rows.first() as HomeRow.Trending).kind)
        // New takes the newest twenty that Trending left; the genres go on from there.
        assertEquals((2..21).map { "Drama $it" }, content.names("new"))
        assertEquals((22..30).filter { it != 25 }.map { "Drama $it" }, content.names("genre/Drama"))
        assertEquals((1..10).filter { it != 2 }.map { "Comedy $it" }, content.names("genre/Comedy"))
        val cards = content.rows.flatMap { it.cardKeys }
        assertEquals(cards.distinct(), cards)
        // What a card's badges say comes with it: the language its best Version is in.
        assertEquals("en", content.cards().first().best.language?.value)

        // The Shows tab stands by itself: its three shows, and no Row of movies.
        val shows = library.screen(showsTab).first()
        assertEquals(listOf("new"), shows.keys())
        assertEquals(listOf("Series 1", "Series 2", "Series 3"), shows.names("new"))
    }

    @Test fun `a Kids profile's tabs hold only the Kids catalog, with no Trending`() = runBlocking {
        val library = library()
        val kidsMovies = movies("Kid movie", "EN - KIDS ANIMATION", from = 1, count = 45)
        val kidsShows = shows("Kid show", "EN - KIDS ANIMATION", from = 201, count = 45)
        serve(
            movies("Grown-up movie", "EN - DRAMA", from = 101, count = 6) + kidsMovies,
            shows("Grown-up show", "EN - DRAMA", from = 301, count = 6) + kidsShows,
        )
        trending(movie(101), movie(1), movie(2), movie(3))
        library.sync()
        library.switchProfile(library.addProfile("Kids", kids = true)!!.id)

        for ((tab, name) in listOf(moviesTab to "Kid movie", showsTab to "Kid show")) {
            val content = library.screen(tab).first()

            assertEquals(listOf("new-for-kids", "for-kids/${tab.kind}", "genre/Animation"), content.keys())
            assertEquals((1..20).map { "$name $it" }, content.names("new-for-kids"))
            assertEquals((21..40).map { "$name $it" }, content.names("for-kids/${tab.kind}"))
            assertEquals((41..45).map { "$name $it" }, content.names("genre/Animation"))
            assertTrue(content.cards().all { it.id.kind == tab.kind && it.name.startsWith(name) })
        }
    }

    // Live state: a tab is handed its content again when it changes, without asking

    @Test fun `a tab fills as a first Sync lands, from nothing to the catalog's Rows`() = runBlocking {
        val library = library()
        serve(movies("Film", "EN - DRAMA", from = 1, count = 8), shows("Series", "EN - DRAMA", from = 501, count = 4))
        val movies = Watched(this, library, moviesTab)
        val shows = Watched(this, library, showsTab)
        assertEquals(emptyList<String>(), movies.next().keys())
        assertEquals(emptyList<String>(), shows.next().keys())

        library.sync()

        val landed = movies.nextThat { it.cards().size == 8 }
        assertEquals(listOf("new", "genre/Drama").take(landed.rows.size), landed.keys())
        assertEquals((1..8).map { "Film $it" }, landed.names("new"))
        assertEquals((1..4).map { "Series $it" }, shows.nextThat { it.cards().size == 4 }.names("new"))
        movies.leave()
        shows.leave()
    }

    @Test fun `during a first Sync's Head start a tab holds New alone, then its genres once the catalog lands`() = runBlocking {
        val library = library()
        serve(movies("Comedy", "EN - COMEDY", from = 1, count = 30), listOf(ShowListing("EN - Fresh Show (2026)", "EN - NEW RELEASE", 40)))
        val movies = Watched(this, library, moviesTab)
        val shows = Watched(this, library, showsTab)
        assertEquals(emptyList<String>(), movies.next().keys())
        assertEquals(emptyList<String>(), shows.next().keys())

        // The Sync is held where its full read starts: the Head start is all the catalog holds.
        val pause = provider.pauseBeforeFullLists()
        val sync = async(Dispatchers.Default) { library.sync(contentLanguages = english) }
        try {
            pause.awaitReached()

            val headStart = movies.nextThat { it.rows.isNotEmpty() }
            assertEquals(listOf("new"), headStart.keys())
            assertEquals((1..20).map { "Comedy $it" }, headStart.names("new"))
            assertEquals(listOf("Fresh Show"), shows.nextThat { it.rows.isNotEmpty() }.names("new"))
        } finally {
            pause.resume()
            sync.await()
        }

        // The whole catalog: the genre takes what New leaves.
        val whole = movies.nextThat { content -> content.rows.any { it is HomeRow.Genre } }
        assertEquals(listOf("new", "genre/Comedy"), whole.keys())
        assertEquals((1..20).map { "Comedy $it" }, whole.names("new"))
        assertEquals((21..30).map { "Comedy $it" }, whole.names("genre/Comedy"))
        movies.leave()
        shows.leave()
    }

    @Test fun `a Pending catalog shows on its tab once it lands, and not before`() = runBlocking {
        val library = library()
        serve(movies("Film", "EN - DRAMA", from = 1, count = 3), shows("Series", "EN - DRAMA", from = 501, count = 2))
        library.sync()
        val movies = Watched(this, library, moviesTab)
        val shows = Watched(this, library, showsTab)
        assertEquals(listOf("Film 1", "Film 2", "Film 3"), movies.next().names("new"))
        assertEquals(listOf("Series 1", "Series 2"), shows.next().names("new"))

        // The next Sync is held, as under a screen in focus: its catalog waits.
        serve(
            listOf(MovieListing("EN - Fresh Film (2026)", "EN - DRAMA", 9)) + movies("Film", "EN - DRAMA", from = 1, count = 3),
            listOf(ShowListing("EN - Fresh Series (2026)", "EN - DRAMA", 509)) + shows("Series", "EN - DRAMA", from = 501, count = 2),
        )
        assertTrue(library.sync(hold = true) is SyncStatus.Ready)
        assertTrue(movies.restsFor(300))
        assertTrue(shows.restsFor(300))

        assertTrue(library.applyPendingCatalog())

        assertEquals(listOf("Fresh Film", "Film 1", "Film 2", "Film 3"), movies.next().names("new"))
        assertEquals(listOf("Fresh Series", "Series 1", "Series 2"), shows.next().names("new"))
        movies.leave()
        shows.leave()
    }

    @Test fun `switching to a Kids profile and back hands a tab each Profile's content`() = runBlocking {
        val library = library()
        serve(
            movies("Grown-up movie", "EN - DRAMA", from = 101, count = 6) + movies("Kid movie", "EN - KIDS ANIMATION", from = 1, count = 4),
            shows("Grown-up show", "EN - DRAMA", from = 301, count = 3) + shows("Kid show", "EN - KIDS ANIMATION", from = 201, count = 2),
        )
        library.sync()
        val movies = Watched(this, library, moviesTab)
        val shows = Watched(this, library, showsTab)
        val everyones = movies.next()
        assertEquals(10, everyones.cards().size)
        assertEquals(5, shows.next().cards().size)
        val first = library.profiles.value.currentId

        library.switchProfile(library.addProfile("Kids", kids = true)!!.id)

        val kids = movies.next()
        assertEquals(listOf("new-for-kids"), kids.keys())
        assertEquals((1..4).map { "Kid movie $it" }, kids.names("new-for-kids"))
        assertEquals(listOf("Kid show 1", "Kid show 2"), shows.next().names("new-for-kids"))

        library.switchProfile(first)

        assertEquals(everyones, movies.next())
        assertEquals(5, shows.next().cards().size)
        movies.leave()
        shows.leave()
    }

    @Test fun `content read again and found the same is not handed to a tab twice`() = runBlocking {
        val library = library()
        serve(movies("Film", "EN - DRAMA", from = 1, count = 3))
        library.sync()
        val watched = Watched(this, library, moviesTab)
        watched.next()

        // Another Sync of the same catalog lands: the tab is read again, and holds what it held.
        library.sync()

        assertTrue(watched.restsFor(500))
        watched.leave()
    }

    @Test fun `a tab's content stands by the clock alone`() {
        assertEquals(null, moviesTab.readAgainAfter(TitlesTabContent(TitleKind.Movie, listOf(HomeRow.Trending(emptyList(), TitleKind.Movie)))))
    }
}
