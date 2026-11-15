package dev.betteriptv.core

import dev.betteriptv.core.FakeProvider.MovieListing
import dev.betteriptv.core.FakeProvider.ShowListing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
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
import java.time.Year
import kotlin.concurrent.thread

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
    private val thisYear = Year.now().value
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
    private fun showCategories(vararg names: String) =
        provider.serveShows(*names.mapIndexed { i, name -> ShowListing("EN - Show $i (2024)", name, 200L + i) }.toTypedArray())

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

    @Test fun `with no Category named as fresh, the first one in the Content language is read`() = runBlocking {
        movieCategories("FR - ACTION", "EN - COMEDY", "EN - DRAMA")
        showCategories("NETFLIX SERIES", "FRANCE SÉRIES", "ENGLISH SERIES", "ENGLISH KIDS")

        library.sync(contentLanguages = english)

        assertEquals(listOf("get_vod_streams category 2", "get_series category 3"), categoryRequests)
    }

    @Test fun `a Hand line the cloud serves marks a Category fresh, and it is read ahead of the first in the language`() = runBlocking {
        cloud.providerTables[providerKey(provider.login)] = "[category hints]\nDRENIER AJOUTEE | fresh\n"
        movieCategories("FR - ACTION", "FR - LE DRENIER AJOUTEE", "EN - COMEDY")

        library.sync(contentLanguages = listOf("fr"))

        assertEquals(listOf("get_vod_streams category 2"), categoryRequests)
    }

    @Test fun `without the Hand line, a misspelt fresh Category is passed over for the first in the language`() = runBlocking {
        movieCategories("FR - ACTION", "FR - LE DRENIER AJOUTEE", "EN - COMEDY")

        library.sync(contentLanguages = listOf("fr"))

        assertEquals(listOf("get_vod_streams category 1"), categoryRequests)
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

        assertEquals(emptyList<String>(), provider.requests.drop(asked).filter { "category " in it })
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
