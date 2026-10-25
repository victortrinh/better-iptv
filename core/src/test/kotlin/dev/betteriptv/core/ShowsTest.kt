package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Shows Synced, Matched and merged per Episode from the fixture's show details, seen through the Library. */
class ShowsTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private val credentials = InMemoryCredentials()
    private val libraries = mutableListOf<Library>()
    private lateinit var library: Library

    private fun openLibrary() = Library.open(folder.root.resolve("catalog.db").path, credentials, cloud.url).also { libraries += it }

    @Before fun logIn() = runBlocking {
        library = openLibrary()
        library.logIn(provider.login)
        Unit
    }

    @After fun tearDown() {
        libraries.forEach { it.close() }
        provider.close()
        cloud.close()
    }

    private val english = listOf("en")
    private val gameOfThrones = TitleId.Tmdb(TitleKind.Show, 1399)
    private val trotro = TitleId.Tmdb(TitleKind.Show, 36175)
    private val chelseaDetective = TitleId.Tmdb(TitleKind.Show, 156240)
    private val dogImpossible = TitleId.Tmdb(TitleKind.Show, 93422)

    // The fixture's Provider ids for The Chelsea Detective's Versions.
    private val chelseaEn = 2008L
    private val chelseaGr = 2014L
    private val chelseaEs = 2034L
    private val chelseaBeNl = 2024L
    private val chelseaDe = 2043L

    private suspend fun showCards() = library.titles(0, 1000, english, appLanguage = "en", kind = TitleKind.Show)

    private suspend fun versionNames(id: TitleId): List<String> {
        val details = library.title(id, english, appLanguage = "en") ?: throw AssertionError("no Title $id")
        return (details.languages + details.otherLanguages).flatMap { group -> group.versions.flatMap { listOf(it) + it.backups } }.map { it.name }
    }

    private suspend fun episodes(id: TitleId, contentLanguages: List<String> = english): ShowEpisodes =
        library.episodes(id, contentLanguages) ?: throw AssertionError("no Show $id")

    private fun ShowEpisodes.episode(season: Int, number: Int): Episode =
        seasons.single { it.number == season }.episodes.single { it.number == number }

    /** The languages an Episode is available in, the Viewer's first. */
    private fun Episode.languageTags() = (languages + otherLanguages).map { it.language }

    /** Every Version of an Episode, shown or Backup. */
    private fun Episode.allVersions() = (languages + otherLanguages).flatMap { group -> group.versions.flatMap { listOf(it) + it.backups } }

    private fun Episode.version(listingName: String) = allVersions().single { it.name.startsWith(listingName) }

    @Test fun `Shows are Synced into a list of their own, one card per Show`() = runBlocking {
        val status = library.sync() as SyncStatus.Synced

        assertEquals(45, status.last.showListingCount)
        // The movie list is unchanged.
        assertEquals(150, status.last.movieListingCount)
        assertEquals(56, library.titleCount())
        val cards = showCards()
        assertEquals(library.titleCount(TitleKind.Show), cards.size)
        assertEquals(1, cards.count { it.id == gameOfThrones })
        assertEquals(5, versionNames(chelseaDetective).size)
        assertTrue(cards.none { it.id.kind == TitleKind.Movie })
        assertNull(library.title(TitleId.Tmdb(TitleKind.Movie, 156240), english, "en"))
    }

    @Test fun `FR - Trotro, carrying Game of Thrones' ID, no longer sits under Game of Thrones`() = runBlocking {
        cloud.match("Trotro", trotro.tmdbId)

        library.sync()

        assertFalse("FR - Trotro" in versionNames(gameOfThrones))
        assertEquals(listOf("FR - Trotro"), versionNames(trotro))
        assertEquals(mapOf("kind" to "show", "name" to "Trotro", "year" to null, "language" to "fr", "tmdbId" to 1399.0), cloud.matchAsked.single { it["name"] == "Trotro" })
        // Game of Thrones' other Listings agree with each other.
        assertFalse(cloud.matchAsked.any { it["name"] == "Game of Thrones" })
    }

    @Test fun `a Show lists every season any Version has, merged by season and episode number`() = runBlocking {
        library.sync()

        val show = episodes(chelseaDetective)

        // English stops at season 2, German and Spanish at 3; only the Greek Version has season 4.
        assertEquals(listOf(1, 2, 3, 4), show.seasons.map { it.number })
        assertEquals(listOf(1, 2, 3, 4), show.seasons.single { it.number == 4 }.episodes.map { it.number })
        show.seasons.single { it.number == 4 }.episodes.forEach { assertEquals(listOf("el"), it.languageTags()) }
        // Every Version has season 1, episode 1.
        assertEquals(5, show.episode(1, 1).allVersions().size)
        // English stops at season 2: season 3 is in German, Greek and Spanish.
        assertEquals(listOf("de", "el", "es"), show.episode(3, 1).languageTags())
        // The Flemish Version numbers series 1's eight hours as eight Episodes.
        assertEquals(8, show.seasons.first().episodes.size)
        assertEquals(0, show.unreadVersions)
    }

    @Test fun `an Episode only one Version has is available in that Version's language only`() = runBlocking {
        provider.showCategoriesOverride = """[{"category_id":"1","category_name":"SERIES"}]"""
        provider.showListOverride = """[
            {"series_id":1,"name":"FR - Le Bureau des légendes (2015)","tmdb":"62104","category_id":"1","last_modified":"1700000001"},
            {"series_id":2,"name":"EN - The Bureau (2015)","tmdb":"62104","category_id":"1","last_modified":"1700000000"}
        ]"""
        provider.showDetailsOverride[1] = details((1..3).map { season -> episode(100L + season, season, 1, "FR - Le Bureau des légendes (2015) - S0${season}E01", "fre") })
        provider.showDetailsOverride[2] = details((1..4).map { season -> episode(200L + season, season, 1, "EN - The Bureau (2015) - S0${season}E01", "eng") })
        library.sync()

        val show = episodes(TitleId.Tmdb(TitleKind.Show, 62104), contentLanguages = listOf("fr", "en"))

        assertEquals(listOf(1, 2, 3, 4), show.seasons.map { it.number })
        assertEquals(listOf("fr", "en"), show.episode(3, 1).languageTags())
        assertEquals(listOf("en"), show.episode(4, 1).languageTags())
    }

    @Test fun `audio language and resolution from the Provider's Episode details are Confirmed`() = runBlocking {
        library.sync()

        val chelsea = episodes(chelseaDetective)
        val german = chelsea.episode(1, 1).version("DE - ").attributes
        assertEquals(Known("de", Provenance.Confirmed), german.language)
        assertEquals(Known(Quality.FullHD, Provenance.Confirmed), german.quality)
        assertEquals(Known(Quality.HD, Provenance.Confirmed), chelsea.episode(1, 1).version("EN - ").attributes.quality)
        // The Greek Version's first audio Track is English. The Provider names only the first Track,
        // so the file may hold Greek too: its Prefix's language stays, Likely.
        val greek = chelsea.episode(1, 1).version("GR - ").attributes
        assertEquals(Known("el", Provenance.Likely), greek.language)
        assertEquals(Known(Quality.SD, Provenance.Confirmed), greek.quality)
        // Its later seasons name no language at all; their resolution is still Confirmed.
        val greekLater = chelsea.episode(3, 1).version("GR - ").attributes
        assertEquals(Known("el", Provenance.Likely), greekLater.language)
        assertEquals(Known(Quality.HD, Provenance.Confirmed), greekLater.quality)
        // An Untagged Version gets its language from its Episodes.
        assertEquals(Known("en", Provenance.Confirmed), episodes(dogImpossible).episode(1, 1).allVersions().single().attributes.language)
    }

    @Test fun `an Episode is named from the Version in the Viewer's language`() = runBlocking {
        library.sync()

        assertEquals("The Wages of Sin", episodes(chelseaDetective).episode(1, 1).name)
        assertEquals("Der Lohn der Sünde", episodes(chelseaDetective, contentLanguages = listOf("de")).episode(1, 1).name)
        // `D+ - DDog: Impossible (US) - S01E01` has no Episode name.
        assertNull(episodes(dogImpossible).episode(1, 1).name)
    }

    @Test fun `show details come from API calls, one per Version, never a stream`() = runBlocking {
        library.sync()

        episodes(chelseaDetective)

        assertEquals(setOf(chelseaEn, chelseaGr, chelseaEs, chelseaBeNl, chelseaDe), provider.showDetailsRequests.toSet())
        assertEquals(5, provider.showDetailsRequests.size)
        assertEquals(0, provider.streamRequests)
    }

    @Test fun `show details are kept, and asked again only once the Provider changes that Version`() = runBlocking {
        library.sync()
        episodes(chelseaDetective)
        library.close()
        library = openLibrary()
        episodes(chelseaDetective)
        assertEquals(5, provider.showDetailsRequests.size)

        // The Provider added an Episode to the German Version: its series list entry says so.
        val fixture = javaClass.getResourceAsStream("/fixture/series_streams.json")!!.use { it.readBytes().decodeToString() }
        provider.showListOverride = fixture.replace(""""last_modified": "1792694358"""", """"last_modified": "1800000000"""")
        library.sync()
        episodes(chelseaDetective)

        assertEquals(listOf(chelseaDe), provider.showDetailsRequests.drop(5))
    }

    @Test fun `a Version whose details can't be read is left out and counted`() = runBlocking {
        provider.brokenShowDetails += chelseaGr
        library.sync()

        val show = episodes(chelseaDetective)

        assertEquals(listOf(1, 2, 3), show.seasons.map { it.number })
        assertEquals(1, show.unreadVersions)
    }

    @Test fun `a Movie has no Episodes`() = runBlocking {
        library.sync()

        assertNull(library.episodes(TitleId.Tmdb(TitleKind.Movie, 238), english))
    }

    private fun details(episodes: List<String>) = """{"seasons":[],"info":{},"episodes":[${episodes.joinToString(",")}]}"""

    private fun episode(id: Long, season: Int, number: Int, title: String, language: String) =
        """{"id":"$id","episode_num":$number,"season":$season,"title":"$title","container_extension":"mkv","added":"1700000000",""" +
            """"info":{"video":{"width":1920,"height":1080},"audio":{"tags":{"language":"$language"}}}}"""
}
