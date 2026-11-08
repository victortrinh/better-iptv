package dev.betteriptv.core

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The device holds no metadata of the cloud's (#480, ADR 0014): a catalog the previous release
 * stored opens without a Sync, its metadata and chart tables dropped once, the image cache cleared
 * once, and nothing a screen reads asks the cloud for metadata, a season, upcoming Episodes or Charts.
 */
class WithoutCloudMetadataTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud().servingSurveyedTable(provider)
    private val credentials = InMemoryCredentials()
    private val libraries = mutableListOf<Library>()
    private lateinit var library: Library
    private val path get() = folder.root.resolve("catalog.db").path

    private fun openLibrary() = openSurveyedLibrary(path, credentials, cloud.url).also { libraries += it }

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

    private val godfather = TitleId.Numbered(TitleKind.Movie, 238)
    private val gameOfThrones = TitleId.Numbered(TitleKind.Show, 1399)
    private val english = listOf("en")

    /** Makes the catalog at [path] look as the release before #480 left it: its metadata and Charts kept, its image cache never cleared. */
    private fun asThePreviousReleaseLeftIt() = BundledSQLiteDriver().open(path).apply {
        execSQL("ALTER TABLE listing RENAME COLUMN title_number TO provider_tmdb")
        execSQL("CREATE TABLE title_metadata (title_key TEXT NOT NULL, language TEXT NOT NULL, body TEXT, fetched_at INTEGER NOT NULL, PRIMARY KEY (title_key, language))")
        execSQL("INSERT INTO title_metadata VALUES ('movie/238', 'en', '{\"titles\":[{\"language\":\"en\",\"title\":\"The Godfather\"}]}', 0)")
        execSQL("CREATE TABLE chart (region TEXT NOT NULL, language TEXT NOT NULL, body TEXT NOT NULL, fetched_at INTEGER NOT NULL, PRIMARY KEY (region, language))")
        execSQL("INSERT INTO chart VALUES ('CA', 'en', '{}', 0)")
        execSQL("DELETE FROM meta WHERE key = 'images_cleared'")
        close()
    }

    private fun tables(): Set<String> = BundledSQLiteDriver().open(path).run {
        try {
            prepare("SELECT name FROM sqlite_master WHERE type = 'table'").use { buildSet { while (it.step()) add(it.getText(0)) } }
        } finally {
            close()
        }
    }

    @Test fun `a catalog the previous release stored opens without a Sync, without its metadata and Charts, with where the Viewer stopped`() = runBlocking {
        library.sync()
        library.close()
        asThePreviousReleaseLeftIt()
        BundledSQLiteDriver().open(path).apply {
            execSQL("INSERT INTO progress VALUES (1, 'movie/238', 600000, 6000000, 0, ${System.currentTimeMillis()})")
            close()
        }
        val actions = java.util.concurrent.CopyOnWriteArrayList<String?>()
        provider.onRequest = { actions += it }

        library = openLibrary()

        assertTrue(library.syncStatus.value is SyncStatus.Synced)
        val stopped = library.homeRows(english, "en").filterIsInstance<HomeRow.ContinueWatching>().single().titles.single()
        assertEquals(godfather, stopped.title.id)
        assertEquals(0.1f, stopped.progress!!, 0.001f)
        assertEquals(godfather, library.search("godfather", english, "en").last().movies.first().id)
        assertTrue(tables().none { it == "title_metadata" || it == "chart" })
        assertEquals(emptyList<String?>(), actions)
    }

    @Test fun `the image cache is cleared once, on the first launch after the release, and never again`() = runBlocking {
        library.sync()
        library.close()
        asThePreviousReleaseLeftIt()
        var clears = 0

        library = openLibrary()
        library.clearImagesOnce { clears++ }
        library.clearImagesOnce { clears++ }
        library.close()
        library = openLibrary()
        library.clearImagesOnce { clears++ }

        assertEquals(1, clears)
    }

    @Test fun `an image cache that couldn't be cleared is cleared at the next launch`() = runBlocking {
        library.clearImagesOnce { throw java.io.IOException("disk full") }
        library.close()
        var clears = 0

        library = openLibrary()
        library.clearImagesOnce { clears++ }

        assertEquals(1, clears)
    }

    @Test fun `the cloud is asked no metadata, season, upcoming Episodes or Charts across a Sync, Home, a Title page, an Episode picker and a Search`() = runBlocking {
        library.sync()
        library.resumeTitleFacts()
        library.homeRows(english, "en")
        library.tabRows(TitleKind.Movie, english, "en")
        library.tabRows(TitleKind.Show, english, "en")
        library.title(godfather, english, "en")
        library.episodes(gameOfThrones, english, "en")
        library.search("godfather", english, "en").last()

        val asked = cloud.received.map { it.path }.toSet()
        assertEquals(emptySet<String>(), asked intersect setOf("/v1/metadata", "/v1/season", "/v1/upcoming", "/v1/charts", "/v1/match", "/v1/search"))
    }
}
