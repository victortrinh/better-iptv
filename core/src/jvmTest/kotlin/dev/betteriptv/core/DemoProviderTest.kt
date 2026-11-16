package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The Demo Provider's catalog (CONTEXT.md) through the Library: the answers the Cloud serves at
 * `/player_api.php` (cloud/demo-provider, served by cloud/src/demo-provider.ts) are Synced as a
 * household's Provider's are, and land as Movies grouped into Titles, Shows with Episodes, and Channels.
 */
class DemoProviderTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private lateinit var library: Library
    private lateinit var synced: SyncStatus.Synced

    @Before fun sync() = runBlocking {
        val answers = File(System.getProperty("user.dir")).resolve("../cloud/demo-provider")
        assertTrue("no Demo Provider answers in $answers", answers.resolve("vod_streams.json").isFile)
        provider.catalogDirectory = answers
        library = Library.open(folder.root.resolve("catalog.db").path, InMemoryCredentials())
        library.logIn(provider.login)
        synced = library.sync() as SyncStatus.Synced
    }

    @After fun tearDown() {
        library.close()
        provider.close()
    }

    private val english = listOf("en")

    private suspend fun versions(id: TitleId): List<Version> {
        val details = library.title(id, english, appLanguage = "en") ?: throw AssertionError("no Title $id")
        return (details.languages + details.otherLanguages).flatMap { group -> group.versions.flatMap { listOf(it) + it.backups } }
    }

    @Test fun `every Listing lands, and the Base table knows every Prefix`() {
        assertEquals(15, synced.last.movieListingCount)
        assertEquals(3, synced.last.showListingCount)
        assertEquals(2, synced.last.channelListingCount)
        assertEquals(emptyList<UnknownPrefix>(), synced.last.unknownPrefixes)
        assertEquals(0, synced.last.mismatches)
    }

    @Test fun `the Movies are grouped into a dozen Titles, named without their Prefix`() = runBlocking {
        val movies = library.titles(0, 100, english, appLanguage = "en")

        assertEquals(12, movies.size)
        assertEquals(12, library.titleCount(TitleKind.Movie))
        assertTrue(movies.all { it.id is TitleId.Numbered })
        assertEquals(
            setOf(
                "Big Buck Bunny" to 2008, "Sintel" to 2010, "Tears of Steel" to 2012, "Elephants Dream" to 2006, "Cosmos Laundromat" to 2015,
                "Spring" to 2019, "Hero" to 2018, "A Trip to the Moon" to 1902, "The Great Train Robbery" to 1903,
                "The Cabinet of Dr. Caligari" to 1920, "Battleship Potemkin" to 1925, "Man with a Movie Camera" to 1929,
            ),
            movies.map { it.name to it.year }.toSet(),
        )
    }

    @Test fun `Big Buck Bunny is one Title with two Versions, English in 4K and in the Provider's plain copy`() = runBlocking {
        val versions = versions(TitleId.Numbered(TitleKind.Movie, 10378))

        assertEquals(setOf("4K-EN - Big Buck Bunny (2008)", "EN - Big Buck Bunny (2008)"), versions.map { it.name }.toSet())
        assertEquals(listOf("en", "en"), versions.map { it.attributes.language?.value })
        assertEquals(setOf(Quality.UHD, null), versions.map { it.attributes.quality?.value }.toSet())
    }

    @Test fun `the Shows land with their Episodes, one with a Title number and two without`() = runBlocking {
        val shows = library.titles(0, 100, english, appLanguage = "en", kind = TitleKind.Show)

        assertEquals(setOf("BBS: The Documentary", "Caminandes", "Les Vampires"), shows.map { it.name }.toSet())
        val bbs = TitleId.Numbered(TitleKind.Show, 84836)
        assertEquals(1, shows.count { it.id == bbs })
        val episodes = shows.associate { it.name to library.episodes(it.id, english)!!.seasons.single().episodes }
        assertEquals(mapOf("BBS: The Documentary" to 8, "Caminandes" to 3, "Les Vampires" to 10), episodes.mapValues { it.value.size })
        assertEquals((1..10).toList(), episodes.getValue("Les Vampires").map { it.number })
        assertEquals("fr", versions(shows.single { it.name == "Les Vampires" }.id).single().attributes.language?.value)
    }

    @Test fun `the Channels land under their country, in a Category the browser knows`() = runBlocking {
        val category = library.browsedCategories("US").single()

        assertEquals("DOCUMENTARY", category.name)
        assertEquals(listOf("NASA TV", "NASA ISS LIVE"), library.categoryChannels(category).map { it.name })
        assertEquals(listOf("US: NASA TV HD"), library.categoryChannels(category).first().variants.map { it.name })
    }
}
