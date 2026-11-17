package dev.betteriptv.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel as Seen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.seconds

/**
 * Search's finished content ([SearchResultsScreen]), asked of the Library as an app asks for a
 * screen ([Library.screen], #592): the cards of its one grid in order, each with its key, the
 * completions, and its updates as live state.
 */
class SearchScreenTest {
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

    private suspend fun library(): Library {
        val library = Library.open(folder.root.resolve("catalog-${libraries.size}.db").path, InMemoryCredentials(), cloud.url).also { libraries += it }
        library.logIn(provider.login)
        return library
    }

    private fun searching(query: String) = SearchResultsScreen(query, english, appLanguage = "en")

    /** A movie Listing to serve: its [name], its Title's [titleNumber] and the Category it is filed under. */
    private class Filed(val name: String, val titleNumber: Long, val category: String = "EN - MOVIES")

    /** Serves these movie Listings, newest first, numbered from 1, each filed under its Category. */
    private fun serveMovies(vararg listings: Filed) {
        val categories = listings.map { it.category }.distinct()
        provider.movieCategoriesOverride = categories.withIndex().joinToString(",", "[", "]") { (i, name) -> """{"category_id":"${i + 1}","category_name":"$name"}""" }
        provider.movieListOverride = listings.withIndex().joinToString(",", "[", "]") { (i, listing) ->
            """{"stream_id":${i + 1},"name":"${listing.name}","tmdb":"${listing.titleNumber}","category_id":"${categories.indexOf(listing.category) + 1}","added":"${1_700_000_000 - i}"}"""
        }
    }

    /** A catalog where "smart" is in a Movie's name, in the name of someone in two Shows' cast, and in a Channel's name. */
    private fun serveSmart() {
        serveMovies(Filed("EN - Get Smart (2008)", 11665), Filed("EN - Heat (1995)", 949))
        provider.serveShows(
            FakeProvider.ShowListing("EN - Succession (2018)", "EN - SERIES", 76331, cast = "Brian Cox, Jeremy Strong, Sarah Snook"),
            FakeProvider.ShowListing("EN - Mare of Easttown (2021)", "EN - SERIES", 115004, cast = "Kate Winslet, Jean Smart"),
            FakeProvider.ShowListing("EN - Hacks (2021)", "EN - SERIES", 105248, cast = "Jean Smart, Hannah Einbinder"),
        )
        provider.serveChannels("US: SMART LIFE TV" to "US| ENTERTAINMENT", "US: NEWS ONE" to "US| NEWS")
    }

    /** A card by what it shows: a Title's name, a Channel's key, a Replay's name. */
    private fun SearchContent.shown() = cards.map {
        when (it) {
            is SearchCard.OfTitle -> it.title.name
            is SearchCard.OfChannel -> "channel ${it.channel.key}"
            is SearchCard.OfReplay -> "replay ${it.replay.name}"
        }
    }

    @Test fun `a query matching Titles, people and Channels is handed as one grid, the Titles by name first, then the Channels, then the people's Titles`() = runBlocking {
        serveSmart()
        val library = library()
        library.sync()

        val content = library.screen(searching("smart")).first()

        assertEquals("smart", content.query)
        assertEquals(listOf("Get Smart", "channel US: SMART LIFE TV", "Mare of Easttown", "Hacks"), content.shown())
        assertEquals(listOf("Get Smart", "Jean Smart"), content.completions)
    }

    @Test fun `the content is what the Library's search finds, as cards`() = runBlocking {
        serveSmart()
        val library = library()
        library.sync()

        val found = library.search("smart", english, "en").last()

        assertEquals(listOf("Jean Smart"), found.people.map { it.name })
        assertEquals(1, found.channels.size)
        val content = library.screen(searching("smart")).first()
        assertEquals(found.cards("smart"), content.cards)
        assertEquals(found.completions, content.completions)
    }

    @Test fun `each card has its own key, by its Title, Channel or Replay`() = runBlocking {
        serveSmart()
        val library = library()
        library.sync()

        val keys = library.screen(searching("smart")).first().cards.map { it.key }

        assertEquals(
            listOf(
                TitleId.Numbered(TitleKind.Movie, 11665).toString(),
                "channel/US: SMART LIFE TV",
                TitleId.Numbered(TitleKind.Show, 115004).toString(),
                TitleId.Numbered(TitleKind.Show, 105248).toString(),
            ),
            keys,
        )
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun `a Title found by name and through someone in its cast is one card, where it first comes`() = runBlocking {
        provider.movieCategoriesOverride = "[]"
        provider.movieListOverride = "[]"
        provider.serveShows(
            FakeProvider.ShowListing("EN - Smart People (2021)", "EN - SERIES", 115004, cast = "Jean Smart"),
            FakeProvider.ShowListing("EN - Hacks (2021)", "EN - SERIES", 105248, cast = "Jean Smart, Hannah Einbinder"),
        )
        val library = library()
        library.sync()

        assertEquals(listOf("Smart People", "Hacks"), library.screen(searching("smart")).first().shown())
    }

    @Test fun `before anything is typed there are genre shortcuts and no card, and one letter finds nothing`() = runBlocking {
        val library = library()
        library.sync()

        val untyped = library.screen(searching("")).first()
        assertTrue(untyped.cards.isEmpty())
        assertEquals(library.search("", english, "en").last().completions, untyped.completions)
        assertTrue(untyped.completions.isNotEmpty())

        val oneLetter = library.screen(searching("g")).first()
        assertEquals("g", oneLetter.query)
        assertTrue(oneLetter.cards.isEmpty())
        assertTrue(oneLetter.completions.isEmpty())
    }

    @Test fun `a query nothing matches has no card and no completion`() = runBlocking {
        serveSmart()
        val library = library()
        library.sync()

        val content = library.screen(searching("zzyzx")).first()

        assertEquals(SearchContent("zzyzx", emptyList(), emptyList()), content)
    }

    @Test fun `on a Kids profile the cards are the Kids catalog's Titles and Kids Categories' Channels`() = runBlocking {
        serveMovies(Filed("EN - Dragon Kid (2025)", 1, "EN - KIDS"), Filed("EN - Dragon Grown-up (2025)", 2, "EN - MOVIES"))
        provider.showCategoriesOverride = "[]"
        provider.showListOverride = "[]"
        provider.serveChannels("US: DRAGON KIDS TV" to "US| KIDS", "US: DRAGON NEWS" to "US| NEWS")
        val library = library()
        library.sync()
        assertEquals(listOf("Dragon Kid", "Dragon Grown-up", "channel US: DRAGON KIDS TV", "channel US: DRAGON NEWS"), library.screen(searching("dragon")).first().shown())

        library.switchProfile(library.addProfile("Kids", kids = true)!!.id)

        assertEquals(listOf("Dragon Kid", "channel US: DRAGON KIDS TV"), library.screen(searching("dragon")).first().shown())
    }

    @Test fun `a catalog that lands while the results show hands them again, without being asked`() = runBlocking {
        serveMovies(Filed("EN - Get Smart (2008)", 11665))
        provider.showCategoriesOverride = "[]"
        provider.showListOverride = "[]"
        val library = library()
        library.sync()
        val seen = Seen<SearchContent>(Seen.UNLIMITED)
        val collecting = launch(Dispatchers.Default) { library.screen(searching("smart")).collect { seen.send(it) } }
        assertEquals(listOf("Get Smart"), withTimeout(10.seconds) { seen.receive() }.shown())

        serveMovies(Filed("EN - Smart People (2008)", 8457), Filed("EN - Get Smart (2008)", 11665))
        library.sync()

        var content = withTimeout(10.seconds) { seen.receive() }
        while (content.cards.size < 2) content = withTimeout(10.seconds) { seen.receive() }
        assertEquals(setOf("Get Smart", "Smart People"), content.shown().toSet())
        collecting.cancelAndJoin()
    }

    @Test fun `a Favourite just saved is marked on its card where it stands, and the next content has it too`() = runBlocking {
        provider.serveChannels("US: SMART LIFE TV" to "US| ENTERTAINMENT", "US: SMART HOME TV" to "US| ENTERTAINMENT")
        val library = library()
        library.sync()
        val content = library.screen(searching("smart")).first()
        val second = (content.cards[1] as SearchCard.OfChannel).channel
        assertFalse(second.favourite)

        library.setFavourite(second.key, true)
        val marked = content.withFavourite(second, true)

        assertEquals(content.cards.map { it.key }, marked.cards.map { it.key })
        assertEquals(listOf(false, true), marked.cards.map { (it as SearchCard.OfChannel).channel.favourite })
        // Asked again, the Favourite leads the equally close matches.
        val next = library.screen(searching("smart")).first()
        assertEquals(listOf(second.key to true, (content.cards[0] as SearchCard.OfChannel).channel.key to false), next.cards.map { (it as SearchCard.OfChannel).channel.let { channel -> channel.key to channel.favourite } })
    }
}
