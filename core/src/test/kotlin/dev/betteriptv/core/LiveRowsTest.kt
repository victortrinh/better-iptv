package dev.betteriptv.core

import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The Live tab's rows, seen through the Library after a Sync: the Favourites row, until the sports hub's rows join it. */
class LiveRowsTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private lateinit var library: Library

    @Before fun logIn() = runBlocking {
        library = Library.open(folder.root.resolve("catalog.db").path, InMemoryCredentials())
        library.logIn(provider.login)
        Unit
    }

    @After fun tearDown() {
        library.close()
        provider.close()
    }

    private val table = PrefixTable.bundled()

    /** The Channel whose variant is the Listing named [name], searched for by the Channel's name (a Listing's quality words aren't indexed). */
    private suspend fun channel(name: String): Channel {
        val query = table.channelName(name).let { listOfNotNull(it.country, it.name).joinToString(" ") }
        return library.search(query, listOf("en"), "en").last().channels.first { it.variants.any { variant -> variant.name == name } }
    }

    /** The Favourites row's Channels, by key (the country tag and name without the Provider's quality words). */
    private fun List<HomeRow>.favourites() = filterIsInstance<HomeRow.Favourites>().singleOrNull()?.channels?.map { it.key }

    /** How a Provider names the start of a Game [ago] before now, in its `(2026-10-29 18:00:30)` style. */
    private fun startedAgo(ago: Duration) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format((Instant.now() - ago).atOffset(ZoneOffset.UTC))

    @Test fun `without Favourites the Live tab has no rows`() = runBlocking {
        provider.serveChannels("US: ESPN HD" to "US| SPORT")
        library.sync()

        assertEquals(emptyList<HomeRow>(), library.liveRows())
    }

    @Test fun `the Favourites row holds the Favourites in the order they were added, as on Home`() = runBlocking {
        provider.serveChannels("US: ESPN HD" to "US| SPORT", "CA: TSN 1" to "CA| SPORT", "UK: BBC ONE" to "UK| GENERAL")
        library.sync()
        library.setFavourite(channel("UK: BBC ONE").key, true)
        library.setFavourite(channel("US: ESPN HD").key, true)

        val rows = library.liveRows()
        assertEquals(listOf("UK: BBC ONE", "US: ESPN"), rows.favourites())
        assertEquals(1, rows.size)
        assertEquals(rows.favourites(), library.homeRows(listOf("en"), "en").favourites())
    }

    @Test fun `a Favourite removed leaves the row, and the last one takes the row with it`() = runBlocking {
        provider.serveChannels("US: ESPN HD" to "US| SPORT", "CA: TSN 1" to "CA| SPORT")
        library.sync()
        library.setFavourite(channel("US: ESPN HD").key, true)
        library.setFavourite(channel("CA: TSN 1").key, true)

        library.setFavourite(channel("US: ESPN HD").key, false)
        assertEquals(listOf("CA: TSN 1"), library.liveRows().favourites())

        library.setFavourite(channel("CA: TSN 1").key, false)
        assertEquals(emptyList<HomeRow>(), library.liveRows())
    }

    @Test fun `a finished Event channel among the Favourites is left out`() = runBlocking {
        val over = "Arena: Jets vs Oilers (${startedAgo(Duration.ofHours(30))})"
        provider.serveChannels(over to "US| ARENA PPV", "CA: TSN 1" to "CA| SPORT")
        library.sync()
        library.setFavourite(channel("CA: TSN 1").key, true)
        // Saved while it was on: a finished Event channel is out of Search, so by its key (its name, which carries no tags).
        library.setFavourite(over, true)

        assertEquals(listOf("CA: TSN 1"), library.liveRows().favourites())
    }
}
