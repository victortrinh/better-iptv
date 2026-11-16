package dev.betteriptv.core

import dev.betteriptv.core.FakeProvider.MovieListing
import dev.betteriptv.core.FakeProvider.ShowListing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds

/**
 * A first Sync's Head start (ADR 0019): right after its Channels it reads a few fresh Categories,
 * one at a time, and lands them as playable Titles while the rest of the catalog is read, which
 * then replaces them whole.
 */
class HeadStartTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private val credentials = InMemoryCredentials()
    private lateinit var library: Library
    private val english = listOf("en")
    private val bilingual = listOf("en", "fr")
    private val tv = DeviceProfile(maxHeight = 2160, hdr = emptySet())
    private val thisYear = thisYear()
    private val freshFilm = TitleId.Numbered(TitleKind.Movie, 2)

    @Before fun logIn() = runBlocking {
        library = Library.open(folder.root.resolve("catalog.db").path, credentials, cloud.url)
        library.logIn(provider.login)
        provider.serveChannels("CA: CBC HD" to "CA | NEWS")
        provider.serveShows()
    }

    @After fun tearDown() {
        library.close()
        provider.close()
        cloud.close()
    }

    /** Serves one movie Listing per Category name, in this order: Category 1 is the first name. */
    private fun movieCategories(vararg names: String) =
        provider.serveMovieListings(*names.mapIndexed { i, name -> MovieListing("${name.substringBefore(' ')} - Movie $i (2024)", name, 100L + i) }.toTypedArray())

    /** Serves one show Listing per Category name, in this order: Category 1 is the first name. */
    private fun showCategories(vararg names: String) = showCategories(*names.map { it to 1 }.toTypedArray())

    /** Serves each Category name's count of movie Listings, each a Title of its own, in this order: Category 1 is the first name. */
    private fun movieCategories(vararg sizes: Pair<String, Int>) {
        val categories = sizes.flatMap { (name, size) -> List(size) { name } }
        provider.serveMovieListings(*categories.mapIndexed { i, name -> MovieListing("${name.substringBefore(' ')} - Movie $i (2024)", name, 100L + i) }.toTypedArray())
    }

    /** Serves each Category name's count of show Listings, each a Title of its own, in this order: Category 1 is the first name. */
    private fun showCategories(vararg sizes: Pair<String, Int>) {
        val categories = sizes.flatMap { (name, size) -> List(size) { name } }
        provider.serveShows(*categories.mapIndexed { i, name -> ShowListing("EN - Show $i (2024)", name, 10_000L + i) }.toTypedArray())
    }

    /** The per-Category Listing requests the Provider got, in order. */
    private val categoryRequests get() = provider.requests.filter { "category" in it }

    /** Runs a first Sync up to its full read, hands the Library as it then stands to [during], and lets the Sync finish. */
    private suspend fun <T> duringTheFullRead(contentLanguages: List<String> = english, during: suspend () -> T): T {
        val pause = provider.pauseBeforeFullLists()
        return runBlocking(Dispatchers.Default) {
            val sync = async { library.sync(contentLanguages = contentLanguages) }
            try {
                pause.awaitReached()
                during()
            } finally {
                pause.resume()
                sync.await()
            }
        }
    }

    @Test fun `Background reading waits for the full catalog, and reads once it has landed`() = runBlocking {
        movieCategories("EN - COMEDY", "EN - NEW RELEASE")
        provider.movieFiles[2] = matroskaFile(listOf(FileTrack("audio", "eng", "A_AAC")), width = 3840, height = 2160)
        val reading = CoroutineScope(Dispatchers.Default).launch {
            library.readInBackground(english, BackgroundPace(gap = 20.milliseconds, perDay = 100), Clock.System, offlinePause = 15.minutes)
        }
        try {
            duringTheFullRead {
                // The Head start's film is there to read, and the connection is the Viewer's: one stream is all a Provider may allow.
                assertTrue(library.titles(0, 10, english, "en", TitleKind.Movie).isNotEmpty())
                delay(400)
                assertEquals(emptyList<String>(), provider.ranges)
            }
            withTimeout(10_000) { while (provider.ranges.isEmpty()) delay(10) }
        } finally {
            reading.cancelAndJoin()
        }
    }

    @Test fun `a first Sync reads the Categories named as fresh, one at a time, before the full lists`() = runBlocking {
        movieCategories("EN - 2020 & OLD", "EN - NEW RELEASE", "EN - COMEDY", "FR - DERNIERS AJOUTS", "GR - NEW RELEASES", "EN - MOVIES $thisYear")
        showCategories("ENGLISH SERIES", "EN - NEW RELEASES")

        library.sync(contentLanguages = english)

        assertEquals(listOf("get_vod_streams category 2", "get_vod_streams category 6", "get_series category 2"), categoryRequests)
        val firstFullList = provider.requests.indexOf("get_vod_streams")
        assertTrue(provider.requests.indexOf("get_series category 2") < firstFullList)
        assertTrue(provider.requests.indexOf("get_live_streams") < provider.requests.indexOf("get_vod_streams category 2"))
        assertEquals(1, provider.mostRequestsAtOnce)
    }

    @Test fun `a Category named with last year is fresh, one named with an older year is not`() = runBlocking {
        movieCategories("EN - MOVIES ${thisYear - 2}", "EN - MOVIES ${thisYear - 1}")

        library.sync(contentLanguages = english)

        assertEquals(listOf("get_vod_streams category 2"), categoryRequests)
    }

    @Test fun `at most two movie and two show Categories are read for each Content language`() = runBlocking {
        movieCategories("EN - NEW RELEASE", "EN - NEW RELEASES", "EN - MOVIES $thisYear", "FR - DERNIER AJOUT", "FR - FILMS $thisYear", "FR - DERNIERS AJOUTS")
        showCategories("EN - NEW RELEASE", "EN - NEW RELEASES", "EN - SERIES $thisYear")

        library.sync(contentLanguages = listOf("en-CA", "fr-CA", "en-US"))

        assertEquals(
            listOf("get_vod_streams category 1", "get_vod_streams category 2", "get_vod_streams category 4", "get_vod_streams category 5", "get_series category 1", "get_series category 2"),
            categoryRequests,
        )
    }

    @Test fun `with no Category named as fresh, a first Category that fills a row is the only one read`() = runBlocking {
        movieCategories("FR - ACTION" to 3, "EN - COMEDY" to 40, "EN - DRAMA" to 40)
        showCategories("NETFLIX SERIES" to 50, "FRANCE SÉRIES" to 50, "ENGLISH SERIES" to 40, "ENGLISH KIDS" to 40)

        library.sync(contentLanguages = english)

        assertEquals(listOf("get_vod_streams category 2", "get_series category 3"), categoryRequests)
    }

    @Test fun `with no Category named as fresh, a tiny first Category is followed by the next ones until a row is filled`() = runBlocking {
        // The surveyed Provider's English show Categories open on two quality variants of one and nine shows.
        showCategories("ENGLISH SERIES ⁴ᴷ ³⁸⁴⁰ᴾ" to 1, "FRANCE SÉRIES" to 50, "ENGLISH SERIES ᴰᴼᴸᴮʸ ᴬᵁᴰᴵᴼ" to 9, "ENGLISH SERIES" to 30, "ENGLISH KIDS" to 40)
        movieCategories("EN - WESTERN" to 5, "EN - COMEDY" to 35, "EN - DRAMA" to 40)

        duringTheFullRead {
            assertEquals(listOf("get_vod_streams category 1", "get_vod_streams category 2", "get_series category 1", "get_series category 3", "get_series category 4"), categoryRequests)
            assertEquals(40, library.titles(0, 100, english, "en", TitleKind.Movie).size)
            assertEquals(40, library.titles(0, 100, english, "en", TitleKind.Show).size)
        }
        assertEquals(1, provider.mostRequestsAtOnce)
    }

    @Test fun `with no Category named as fresh, no more than three Categories are read for a row that won't fill`() = runBlocking {
        movieCategories("EN - WESTERN" to 1, "EN - COMEDY" to 2, "EN - DRAMA" to 3, "EN - ACTION" to 40)
        showCategories("ENGLISH SERIES" to 1, "ENGLISH KIDS" to 1)

        library.sync(contentLanguages = english)

        assertEquals(
            listOf("get_vod_streams category 1", "get_vod_streams category 2", "get_vod_streams category 3", "get_series category 1", "get_series category 2"),
            categoryRequests,
        )
        assertEquals(1, provider.mostRequestsAtOnce)
    }

    @Test fun `a row is filled for each Content language and each kind apart`() = runBlocking {
        movieCategories("EN - COMEDY" to 40, "FR - ACTION" to 2, "EN - DRAMA" to 40, "FR - COMÉDIE" to 40, "FR - DRAME" to 40)

        library.sync(contentLanguages = bilingual)

        assertEquals(listOf("get_vod_streams category 1", "get_vod_streams category 2", "get_vod_streams category 4"), categoryRequests)
    }

    @Test fun `a Content language with a Category named as fresh reads it alone, however few Listings it holds`() = runBlocking {
        movieCategories("EN - COMEDY" to 40, "EN - NEW RELEASE" to 1, "EN - DRAMA" to 40, "FR - ACTION" to 1, "FR - DRAME" to 40)

        library.sync(contentLanguages = bilingual)

        assertEquals(listOf("get_vod_streams category 2", "get_vod_streams category 4", "get_vod_streams category 5"), categoryRequests)
    }

    @Test fun `a Hand line the cloud serves marks a Category fresh, and it is read ahead of the first in the language`() = runBlocking {
        cloud.providerTables[providerKey(provider.login)] = "[category hints]\nDRENIER AJOUTEE | fresh\n"
        movieCategories("FR - ACTION", "FR - LE DRENIER AJOUTEE", "EN - COMEDY")

        library.sync(contentLanguages = listOf("fr"))

        assertEquals(listOf("get_vod_streams category 2"), categoryRequests)
    }

    @Test fun `without the Hand line, a misspelt fresh Category is read as any other in the language`() = runBlocking {
        movieCategories("FR - ACTION", "FR - LE DRENIER AJOUTEE", "EN - COMEDY")

        library.sync(contentLanguages = listOf("fr"))

        // Neither is named as fresh, so both are read towards a row, in the Provider's order.
        assertEquals(listOf("get_vod_streams category 1", "get_vod_streams category 2"), categoryRequests)
    }

    @Test fun `the surveyed Provider's Hand lines mark its fresh Categories that the Base table's words miss`() = runBlocking {
        cloud.providerTables[providerKey(provider.login)] = surveyedProviderTable
        movieCategories(
            "FR - FILM CAM", "FR - LE DRENIER AJOUTEE", "FR - HORREUR", "FR - LE DRENIER AJOUTEE ᴰᴼᴸᴮʸ ᴬᵁᴰᴵᴼ",
            "QC - FILMS VIEILLE", "QC - FILMS NOUVELLE VERSION",
            "SVENSKA 2020 & GAMMAL", "SVENSKA NYA UTGÅVAN",
            "IL - FREE TV - סרטים", "IL - סרטים 2023 + האחרון",
        )
        showCategories("HEBREW EN SERIES", "HEBREW המהדורות האחרונות")

        library.sync(contentLanguages = listOf("fr-FR", "fr-CA", "sv", "he"))

        // French takes its two (the Québec one is the third and waits for the full catalog), then Swedish and Hebrew one each.
        assertEquals(
            listOf("get_vod_streams category 2", "get_vod_streams category 4", "get_vod_streams category 8", "get_vod_streams category 10", "get_series category 2"),
            categoryRequests,
        )
    }

    @Test fun `the surveyed Provider's Québec fresh Category is read when French has no other`() = runBlocking {
        cloud.providerTables[providerKey(provider.login)] = surveyedProviderTable
        movieCategories("QC - FILMS VIEILLE", "QC - FILMS NOUVELLE VERSION")

        library.sync(contentLanguages = listOf("fr-CA"))

        assertEquals(listOf("get_vod_streams category 2"), categoryRequests)
    }

    @Test fun `a show Category that names a language or its country is in that language`() = runBlocking {
        provider.serveMovies()
        showCategories("ENGLISH SERIES", "FRANCE DERNIER AJOUT", "GERMANY SERIE")

        library.sync(contentLanguages = listOf("fr", "de"))

        assertEquals(listOf("get_series category 2", "get_series category 3"), categoryRequests)
    }

    @Test fun `Clutter and Sports-replay Categories are never read`() = runBlocking {
        movieCategories("EN - KARAOKE $thisYear", "EN - SOCCER NEW RELEASE", "EN - RADIO", "EN - COMEDY")

        library.sync(contentLanguages = english)

        assertEquals(listOf("get_vod_streams category 4"), categoryRequests)
    }

    @Test fun `the Head start's Titles are read and played while the full lists are still to come`() = runBlocking {
        provider.serveMovieListings(
            MovieListing("EN - Old Film (1999)", "EN - 2020 & OLD", 1),
            MovieListing("EN - Fresh Film (2026)", "EN - NEW RELEASE", 2),
            MovieListing("EN - Other Fresh Film (2026)", "EN - NEW RELEASE", 3),
        )
        provider.serveShows(ShowListing("EN - Old Show (2001)", "ENGLISH SERIES 2001", 4), ShowListing("EN - Fresh Show (2026)", "EN - NEW RELEASE", 5))

        duringTheFullRead {
            val status = library.syncStatus.value as SyncStatus.Running
            assertNull(status.last)
            assertNotNull(status.channelsAt)
            assertNotNull(status.headStartAt)
            assertEquals(listOf("Fresh Film", "Other Fresh Film"), library.titles(0, 10, english, "en").map { it.name })
            assertEquals(listOf("Fresh Show"), library.titles(0, 10, english, "en", TitleKind.Show).map { it.name })
            assertEquals("EN - Fresh Film (2026)", library.playPlan(freshFilm, english, tv)?.version?.name)
        }

        val synced = library.syncStatus.value as SyncStatus.Synced
        assertNull(synced.headStartAt)
        assertEquals(3, synced.last.movieListingCount)
        assertEquals(setOf("Old Film", "Fresh Film", "Other Fresh Film"), library.titles(0, 10, english, "en").map { it.name }.toSet())
    }

    @Test fun `screens are told to read again when the Head start lands`() = runBlocking {
        movieCategories("EN - NEW RELEASE")
        val beforeSync = library.catalogChanges.value

        val duringTheFullRead = duringTheFullRead { library.catalogChanges.value }

        // Once for the Channels, once for the Head start.
        assertEquals(beforeSync + 2, duringTheFullRead)
    }

    @Test fun `without a Head start the status says the Channels alone have landed`() = runBlocking {
        movieCategories("EN - NEW RELEASE")

        duringTheFullRead(contentLanguages = listOf("ja")) {
            val status = library.syncStatus.value
            assertNotNull(status.channelsAt)
            assertNull(status.headStartAt)
            assertEquals(0, library.movieListingCount())
        }
        assertEquals(emptyList<String>(), categoryRequests)
    }

    @Test fun `Home shows New on your service and a featured Title from the Head start, and no genre row until the full catalog lands`() = runBlocking {
        provider.serveMovieListings(*(1..30).map { MovieListing("EN - Comedy $it (2024)", "EN - COMEDY", it.toLong()) }.toTypedArray())
        (1L..30L).forEach { provider.serveMovieDetails(it, name = "Comedy $it", plot = "About Comedy $it.", genre = "Comedy", durationMinutes = 90) }

        duringTheFullRead {
            assertEquals(listOf(HomeRow.NewOnYourService::class), library.homeRows(english, "en").map { it::class })
            assertEquals("Comedy 1", library.featured(FeaturedScreen.Home, english, "en", tv)?.name)
        }

        assertTrue(library.homeRows(english, "en").any { it is HomeRow.Genre })
    }

    @Test fun `Movies and Shows show their New row and a featured Title from the Head start, and no genre row until the full catalog lands`() = runBlocking {
        provider.serveMovieListings(*(1..30).map { MovieListing("EN - Comedy $it (2024)", "EN - COMEDY", it.toLong()) }.toTypedArray())
        (1L..30L).forEach { provider.serveMovieDetails(it, name = "Comedy $it", plot = "About Comedy $it.", genre = "Comedy", durationMinutes = 90) }
        provider.serveShows(ShowListing("EN - Fresh Show (2026)", "EN - NEW RELEASE", 40))

        duringTheFullRead {
            assertEquals(listOf(HomeRow.NewOnYourService::class), library.tabRows(TitleKind.Movie, english, "en").map { it::class })
            assertEquals(listOf(HomeRow.NewOnYourService::class), library.tabRows(TitleKind.Show, english, "en").map { it::class })
            assertEquals("Comedy 1", library.featured(FeaturedScreen.Movies, english, "en", tv)?.name)
            assertEquals("Fresh Show", library.featured(FeaturedScreen.Shows, english, "en", tv)?.name)
        }

        assertTrue(library.tabRows(TitleKind.Movie, english, "en").any { it is HomeRow.Genre })
    }

    @Test fun `the full catalog replaces the Head start whole, and a Head start Title keeps its identity and gains its other Versions`() = runBlocking {
        provider.serveMovieListings(
            MovieListing("EN - Fresh Film (2026)", "EN - NEW RELEASE", 2),
            MovieListing("FR - Fresh Film (2026)", "FR - ACTION", 2),
            MovieListing("EN - Old Film (1999)", "EN - 2020 & OLD", 1),
        )

        lateinit var playing: Thread
        val early = duringTheFullRead {
            // Kept once the Sync lets go of the catalog's writer, as while any Sync runs.
            playing = thread { library.saveProgress(freshFilm, 60_000, 6_000_000) }
            library.title(freshFilm, bilingual, "en")!!
        }
        playing.join()

        val late = library.title(freshFilm, bilingual, "en")!!
        assertEquals(early.id, late.id)
        assertEquals(listOf("en"), early.languages.map { it.language })
        assertEquals(listOf("en", "fr"), late.languages.map { it.language })
        val resumed = library.homeRows(english, "en").filterIsInstance<HomeRow.ContinueWatching>().single().titles.single()
        assertEquals(early.id, resumed.title.id)
    }

    @Test fun `a Favourite made during the Head start is kept once the full catalog lands`() = runBlocking {
        movieCategories("EN - NEW RELEASE", "EN - COMEDY")
        val cbc = surveyedPrefixTable().channelName("CA: CBC HD").key

        lateinit var saving: Thread
        duringTheFullRead {
            // Kept once the Sync lets go of the catalog's writer, as while any Sync runs.
            saving = thread { runBlocking { library.setFavourite(cbc, true) } }
        }
        saving.join()

        assertEquals(listOf(cbc), library.favourites().map { it.key })
        assertEquals(listOf(cbc), library.homeRows(english, "en").filterIsInstance<HomeRow.Favourites>().single().channels.map { it.key })
    }

    @Test fun `with the player open, a first Sync's catalog waits as a Pending catalog and lands once the player closes`() = runBlocking {
        provider.serveMovieListings(
            MovieListing("EN - Fresh Film (2026)", "EN - NEW RELEASE", 2),
            MovieListing("FR - Fresh Film (2026)", "FR - ACTION", 2),
            MovieListing("EN - Old Film (1999)", "EN - 2020 & OLD", 1),
        )
        val beforeSync = library.catalogChanges.value

        val held = library.sync(hold = true, contentLanguages = english, playerOpen = { true })

        // The Head start still shows, under the player: nothing told the screens to read again.
        assertTrue(held is SyncStatus.Ready)
        assertNull(held.last)
        assertNotNull(held.channelsAt)
        assertNotNull(held.headStartAt)
        assertTrue(library.hasPendingCatalog)
        assertEquals(beforeSync + 2, library.catalogChanges.value)
        assertEquals(listOf("Fresh Film"), library.titles(0, 10, english, "en").map { it.name })
        assertEquals(listOf("en"), library.title(freshFilm, bilingual, "en")!!.languages.map { it.language })
        // What the player records meanwhile is kept, the Sync no longer holding the catalog's writer.
        library.saveProgress(freshFilm, 60_000, 6_000_000)

        assertTrue(library.applyPendingCatalog())

        val synced = library.syncStatus.value as SyncStatus.Synced
        assertNull(synced.headStartAt)
        assertEquals(3, synced.last.movieListingCount)
        assertFalse(library.hasPendingCatalog)
        assertEquals(beforeSync + 3, library.catalogChanges.value)
        assertEquals(setOf("Fresh Film", "Old Film"), library.titles(0, 10, english, "en").map { it.name }.toSet())
        assertEquals(listOf("en", "fr"), library.title(freshFilm, bilingual, "en")!!.languages.map { it.language })
        assertEquals(freshFilm, library.homeRows(english, "en").filterIsInstance<HomeRow.ContinueWatching>().single().titles.single().title.id)
    }

    @Test fun `with the player closed, a first Sync's catalog lands as soon as it is read`() = runBlocking {
        movieCategories("EN - NEW RELEASE", "EN - COMEDY")

        val synced = library.sync(hold = true, contentLanguages = english, playerOpen = { false })

        assertTrue(synced is SyncStatus.Synced)
        assertEquals(2, library.movieListingCount())
    }

    @Test fun `a Sync started while a first Sync's catalog waits for the player reads the full catalog again, the Head start still showing`() = runBlocking {
        movieCategories("EN - NEW RELEASE", "EN - COMEDY")
        library.sync(hold = true, contentLanguages = english, playerOpen = { true })
        val asked = provider.requests.size

        val synced = library.sync(hold = true, contentLanguages = english, playerOpen = { false })

        assertEquals(emptyList<String>(), provider.requests.drop(asked).filter { "category " in it })
        assertTrue(synced is SyncStatus.Synced)
        assertEquals(2, library.movieListingCount())
    }

    @Test fun `an app closed while a first Sync's catalog waits for the player reopens on the full catalog`() = runBlocking {
        movieCategories("EN - NEW RELEASE", "EN - COMEDY")
        library.sync(hold = true, contentLanguages = english, playerOpen = { true })
        library.close()

        library = Library.open(folder.root.resolve("catalog.db").path, credentials, cloud.url)

        assertTrue(library.syncStatus.value is SyncStatus.Synced)
        assertEquals(2, library.movieListingCount())
    }

    @Test fun `no row comes twice once the full catalog lands, and the New row holds no Title twice`() = runBlocking {
        provider.serveMovieListings(
            *(1..30).map { MovieListing("EN - Fresh $it (2026)", "EN - NEW RELEASE", it.toLong()) }.toTypedArray(),
            *(1..30).map { MovieListing("FR - Fresh $it (2026)", "FR - DERNIERS AJOUTS", it.toLong()) }.toTypedArray(),
            *(31..60).map { MovieListing("EN - Comedy $it (2024)", "EN - COMEDY", it.toLong()) }.toTypedArray(),
        )

        val early = duringTheFullRead { library.homeRows(english, "en") }
        val late = library.homeRows(english, "en")

        val earlyNew = early.filterIsInstance<HomeRow.NewOnYourService>().single().titles.map { it.id }
        val lateNew = late.filterIsInstance<HomeRow.NewOnYourService>().single().titles.map { it.id }
        assertEquals(lateNew.distinct(), lateNew)
        // The Head start's New row was the full catalog's newest already: its cards stay as they are.
        assertEquals(earlyNew, lateNew)
        assertEquals(late.map { it::class to (it as? HomeRow.Genre)?.name }.distinct().size, late.size)
    }

    @Test fun `a Provider that refuses per-Category requests gets a normal first Sync`() = runBlocking {
        movieCategories("EN - NEW RELEASE", "EN - COMEDY")
        provider.refuseCategoryRequests = true

        duringTheFullRead {
            val status = library.syncStatus.value as SyncStatus.Running
            assertNotNull(status.channelsAt)
            assertNull(status.headStartAt)
            assertEquals(0, library.movieListingCount())
        }

        assertTrue(library.syncStatus.value is SyncStatus.Synced)
        assertEquals(2, library.movieListingCount())
    }

    @Test fun `a full read that fails keeps the Channels and the Head start, and Retry reads the full catalog alone`() = runBlocking {
        movieCategories("EN - NEW RELEASE", "EN - COMEDY")

        duringTheFullRead { provider.down = true }

        val failed = library.syncStatus.value as SyncStatus.Failed
        assertNull(failed.last)
        assertNotNull(failed.channelsAt)
        assertNotNull(failed.headStartAt)
        assertEquals(listOf("CA | NEWS"), library.liveCategories().map { it.name })
        assertEquals(1, library.movieListingCount())

        provider.down = false
        val asked = provider.requests.size
        val retried = library.sync(contentLanguages = english)

        assertEquals(emptyList<String>(), provider.requests.drop(asked).filter { "category " in it || it.startsWith("get_live") })
        assertNull(retried.headStartAt)
        assertEquals(2, (retried as SyncStatus.Synced).last.movieListingCount)
        assertEquals(2, library.movieListingCount())
    }

    @Test fun `an app closed during the full read reopens on the Head start, and its Sync goes straight to the full read`() = runBlocking {
        provider.serveMovieListings(MovieListing("EN - Old Film (1999)", "EN - 2020 & OLD", 1), MovieListing("EN - Fresh Film (2026)", "EN - NEW RELEASE", 2))
        provider.serveShows(ShowListing("EN - Old Show (2001)", "ENGLISH SERIES 2001", 4), ShowListing("EN - Fresh Show (2026)", "EN - NEW RELEASE", 5))
        // The catalog as a Sync stopped in its full read leaves it on the device, whatever became of that Sync.
        val left = folder.newFolder("left")
        duringTheFullRead {
            folder.root.listFiles { file -> file.name.startsWith("catalog.db") }!!.forEach { it.copyTo(left.resolve(it.name)) }
        }
        library.close()
        val asked = provider.requests.size

        library = Library.open(left.resolve("catalog.db").path, credentials, cloud.url)

        val reopened = library.syncStatus.value as SyncStatus.NeverSynced
        assertNotNull(reopened.channelsAt)
        assertNotNull(reopened.headStartAt)
        assertEquals(listOf("Fresh Film"), library.titles(0, 10, english, "en").map { it.name })
        assertEquals(listOf("Fresh Show"), library.titles(0, 10, english, "en", TitleKind.Show).map { it.name })
        assertEquals("EN - Fresh Film (2026)", library.playPlan(freshFilm, english, tv)?.version?.name)
        assertEquals(asked, provider.requests.size)

        val synced = library.sync(contentLanguages = english)

        assertEquals(emptyList<String>(), provider.requests.drop(asked).filter { "category " in it })
        assertTrue("get_vod_streams" in provider.requests.drop(asked))
        assertNull(synced.headStartAt)
        assertEquals(2, (synced as SyncStatus.Synced).last.movieListingCount)
        assertEquals(setOf("Old Film", "Fresh Film"), library.titles(0, 10, english, "en").map { it.name }.toSet())
        assertEquals(setOf("Old Show", "Fresh Show"), library.titles(0, 10, english, "en", TitleKind.Show).map { it.name }.toSet())
    }

    /** Closes the app during a first Sync's full read and opens it again on the catalog as that left it on the device. */
    private suspend fun reopenDuringTheFullRead() {
        val left = folder.newFolder("left")
        duringTheFullRead {
            folder.root.listFiles { file -> file.name.startsWith("catalog.db") }!!.forEach { it.copyTo(left.resolve(it.name)) }
        }
        library.close()
        library = Library.open(left.resolve("catalog.db").path, credentials, cloud.url)
    }

    private val channelRequests get() = provider.requests.filter { it == "get_live_categories" || it == "get_live_streams" }

    @Test fun `a reopened first Sync keeps the Channels it landed and reads the full catalog alone`() = runBlocking {
        provider.serveChannels("MAX: TNT SPORT 1 SD" to "UK | SPORTS", "PLUS: TNT SPORT 1 HD" to "UK | SPORTS", "CA: CBC HD" to "CA | NEWS")
        movieCategories("EN - NEW RELEASE", "EN - COMEDY")
        reopenDuringTheFullRead()
        val asked = provider.requests.size

        val running = duringTheFullRead { library.syncStatus.value as SyncStatus.Running }
        val synced = library.syncStatus.value as SyncStatus.Synced

        // The Channels Step is done from the start: the Sync opens on the movies.
        assertEquals(SyncStep.Movies, running.step)
        assertNotNull(running.channelsAt)
        val requests = provider.requests.drop(asked)
        assertEquals(emptyList<String>(), requests.filter { it.startsWith("get_live") || "category " in it })
        assertTrue("get_vod_streams" in requests && "get_series" in requests)
        assertEquals(2, synced.last.movieListingCount)
        assertEquals(listOf("UK | SPORTS", "CA | NEWS"), library.liveCategories().map { it.name })
        // What the Channels read tells a Sync is told from the Channels kept.
        assertEquals(3, synced.last.channelListingCount)
        assertEquals(listOf(CandidateTierTag("MAX", 1, "MAX: TNT SPORT 1 SD"), CandidateTierTag("PLUS", 1, "PLUS: TNT SPORT 1 HD")), synced.last.candidateTierTags)
    }

    @Test fun `a reopened first Sync reads Channels landed more than a day ago again`() = runBlocking {
        movieCategories("EN - NEW RELEASE", "EN - COMEDY")
        reopenDuringTheFullRead()
        val asked = provider.requests.size
        val landed = library.syncStatus.value.channelsAt!!
        provider.serveChannels("CA: CBC HD" to "CA | NEWS", "CA: CTV HD" to "CA | NEWS")
        library.syncClock = object : Clock {
            override fun now() = Clock.System.now() + 25.hours
        }

        val synced = library.sync(contentLanguages = english) as SyncStatus.Synced

        val requests = provider.requests.drop(asked)
        assertTrue(requests.indexOf("get_live_streams") in 0 until requests.indexOf("get_vod_streams"))
        // The Head start is kept all the same.
        assertEquals(emptyList<String>(), requests.filter { "category " in it })
        assertEquals(2, synced.last.channelListingCount)
        assertTrue(library.syncStatus.value.channelsAt!! > landed)
    }

    @Test fun `a later Sync reads the Channels again`() = runBlocking {
        movieCategories("EN - NEW RELEASE", "EN - COMEDY")
        library.sync(contentLanguages = english)
        val asked = channelRequests.size

        library.sync(contentLanguages = english)

        assertEquals(asked + 2, channelRequests.size)
    }

    @Test fun `a Sync cancelled during the full read leaves the Head start in its status, and the next one doesn't read it again`() = runBlocking {
        movieCategories("EN - NEW RELEASE", "EN - COMEDY")
        val pause = provider.pauseBeforeFullLists()
        val closing = CoroutineScope(Dispatchers.Default)
        val sync = closing.async { library.sync(contentLanguages = english) }
        pause.awaitReached()
        // The Provider stops answering as the app goes: the Sync ends without a catalog.
        provider.down = true
        closing.cancel()
        pause.resume()
        sync.join()
        provider.down = false
        val asked = provider.requests.size

        assertNotNull(library.syncStatus.value.headStartAt)
        assertNull(library.syncStatus.value.last)
        assertEquals(1, library.movieListingCount())

        library.sync(contentLanguages = english)

        assertEquals(emptyList<String>(), provider.requests.drop(asked).filter { "category " in it })
        assertEquals(2, library.movieListingCount())
    }

    @Test fun `a later Sync has no Head start`() = runBlocking {
        movieCategories("EN - NEW RELEASE", "EN - COMEDY")
        library.sync(contentLanguages = english)
        val asked = categoryRequests.size

        library.sync(contentLanguages = english)

        assertEquals(1, asked)
        assertEquals(asked, categoryRequests.size)
        assertFalse(provider.requests.takeLast(4).any { "category " in it })
    }
}
