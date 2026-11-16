package dev.betteriptv.core

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.last
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
import java.time.Instant

/**
 * The household's Profiles on one device (#428): what each Viewer keeps is their Profile's, what the
 * device keeps (the catalog, the login, the Provider's reads) is everyone's.
 */
class ProfilesTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val credentials = InMemoryCredentials()
    private val libraries = mutableListOf<Library>()
    private lateinit var library: Library
    private val path get() = folder.root.resolve("catalog.db").path

    private fun openLibrary() = Library.open(path, credentials).also { libraries += it }

    @Before fun logIn() = runBlocking {
        library = openLibrary()
        library.logIn(provider.login)
        Unit
    }

    @After fun tearDown() {
        libraries.forEach { it.close() }
        provider.close()
    }

    private val english = listOf("en")
    private val first = TitleId.Numbered(TitleKind.Movie, 1)
    private val second = TitleId.Numbered(TitleKind.Movie, 2)
    private val fullHdTv = DeviceProfile(maxHeight = 1080, hdr = emptySet())

    private suspend fun sync() {
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"EN - MOVIES"}]"""
        provider.movieListOverride = listOf("EN - First (2020)", "EN - Second (2021)").withIndex().joinToString(",", "[", "]") { (i, name) ->
            """{"stream_id":${i + 1},"name":"$name","tmdb":"${i + 1}","category_id":"1","added":"${1_700_000_000 - i}","container_extension":"mkv"}"""
        }
        provider.serveChannels("US: ESPN HD" to "US| SPORT", "UK: BBC ONE" to "UK| GENERAL")
        library.sync()
    }

    private suspend fun continueWatching(on: Library = library) =
        on.homeRows(english, "en").filterIsInstance<HomeRow.ContinueWatching>().singleOrNull()?.titles.orEmpty().map { it.title.name }

    private suspend fun favourites(on: Library = library) = on.favourites().map { it.name }

    private suspend fun channel(name: String) =
        library.search(surveyedPrefixTable().channelName(name).let { listOfNotNull(it.country, it.name).joinToString(" ") }, english, "en").last().channels.first { it.variants.any { variant -> variant.name == name } }

    private fun names(on: Library = library) = on.profiles.value.all.map { it.name }

    @Test fun `a device starts with one Profile, unnamed, that it is on`() {
        val profiles = library.profiles.value
        assertEquals(1, profiles.all.size)
        assertNull(profiles.current.name)
        assertFalse(profiles.current.kids)
    }

    @Test fun `each Profile has its own Continue watching, Favourites and player choices, over one catalog`() = runBlocking {
        sync()
        library.saveProgress(first, position = 10 * 60_000L, duration = 100 * 60_000L)
        library.setFavourite(channel("US: ESPN HD").key, true)
        library.rememberAudio("fr")
        val choices = TitleChoices(subtitleDelay = 500)
        library.saveChoices(library.playPlan(first, english, fullHdTv)!!, choices)

        val alex = library.addProfile("  Alex ")!!
        assertEquals("Alex", alex.name)
        // Adding a Profile doesn't move the device to it.
        assertEquals(null, library.profiles.value.current.name)
        library.switchProfile(alex.id)

        assertEquals(alex, library.profiles.value.current)
        // A new Profile has nothing watched, no Favourites and no choices; the catalog is the device's.
        assertEquals(emptyList<String>(), continueWatching())
        assertEquals(emptyList<String>(), favourites())
        assertEquals(RememberedTracks(), library.rememberedTracks())
        assertEquals(TitleChoices(), library.titleChoices(first))
        assertFalse(channel("US: ESPN HD").favourite)
        assertEquals(2, library.titleCount(TitleKind.Movie))

        library.saveProgress(second, position = 20 * 60_000L, duration = 100 * 60_000L)
        library.setFavourite(channel("UK: BBC ONE").key, true)
        assertEquals(listOf("Second"), continueWatching())
        assertEquals(listOf("BBC ONE"), favourites())

        // Back on the first Profile, its own are as it left them.
        library.switchProfile(library.profiles.value.all.first().id)
        assertEquals(listOf("First"), continueWatching())
        assertEquals(listOf("ESPN"), favourites())
        assertEquals("fr", library.rememberedTracks().audio)
        assertEquals(choices, library.titleChoices(first))
    }

    @Test fun `removing from Continue watching and Watched are the Profile's own`() = runBlocking {
        sync()
        library.saveProgress(first, position = 10 * 60_000L, duration = 100 * 60_000L)
        val alex = library.addProfile("Alex")!!
        library.switchProfile(alex.id)
        library.saveProgress(first, position = 30 * 60_000L, duration = 100 * 60_000L)
        library.finished(first)

        library.switchProfile(library.profiles.value.all.first().id)
        assertEquals(listOf("First"), continueWatching())
        library.removeFromContinueWatching(first)
        library.switchProfile(alex.id)
        assertEquals(emptyList<String>(), continueWatching())
        library.switchProfile(library.profiles.value.all.first().id)
        assertEquals(emptyList<String>(), continueWatching())
    }

    @Test fun `a household keeps up to five Profiles`() = runBlocking {
        repeat(Library.MAX_PROFILES - 1) { assertEquals("P$it", library.addProfile("P$it")?.name) }

        assertNull(library.addProfile("Sixth"))
        assertEquals(Library.MAX_PROFILES, library.profiles.value.all.size)
    }

    @Test fun `the chip pulses once, the first time a second Profile is made, and never again`() = runBlocking {
        assertFalse(library.profileChipPulse.value)
        val alex = library.addProfile("Alex")!!
        assertTrue(library.profileChipPulse.value)
        // Still due after a restart, until the chip has pulsed.
        library = openLibrary()
        assertTrue(library.profileChipPulse.value)
        library.profileChipPulsed()
        assertFalse(library.profileChipPulse.value)
        library.addProfile("Sam")
        library.deleteProfile(alex.id)
        library.addProfile("Jo")
        assertFalse(library.profileChipPulse.value)
        library = openLibrary()
        assertFalse(library.profileChipPulse.value)
    }

    @Test fun `a Profile is renamed, the first one too`() = runBlocking {
        val alex = library.addProfile("Alex")!!
        library.renameProfile(alex.id, "Alexis")
        library.renameProfile(library.profiles.value.current.id, " Sam")

        assertEquals(listOf("Sam", "Alexis"), names())
        assertEquals(listOf("Sam", "Alexis"), names(openLibrary()))
    }

    @Test fun `deleting a Profile deletes what it kept and nothing of the others'`() = runBlocking {
        sync()
        val sam = library.profiles.value.current
        library.saveProgress(first, position = 10 * 60_000L, duration = 100 * 60_000L)
        library.setFavourite(channel("US: ESPN HD").key, true)
        val alex = library.addProfile("Alex")!!
        library.switchProfile(alex.id)
        library.saveProgress(second, position = 10 * 60_000L, duration = 100 * 60_000L)
        library.setFavourite(channel("UK: BBC ONE").key, true)
        library.rememberSubtitles("en")

        // Deleting the Profile the device is on puts it on another.
        assertTrue(library.deleteProfile(alex.id))
        assertEquals(listOf(sam), library.profiles.value.all)
        assertEquals(sam, library.profiles.value.current)
        assertEquals(listOf("First"), continueWatching())
        assertEquals(listOf("ESPN"), favourites())
        assertEquals(RememberedTracks(), library.rememberedTracks())

        // A new Profile never takes over what a deleted one kept.
        val again = library.addProfile("Alex")!!
        assertTrue(again.id != alex.id)
        library.switchProfile(again.id)
        assertEquals(emptyList<String>(), continueWatching())
        assertEquals(emptyList<String>(), favourites())
    }

    @Test fun `the last Profile is never deleted`() = runBlocking {
        val only = library.profiles.value.current
        assertFalse(library.deleteProfile(only.id))
        assertEquals(listOf(only), library.profiles.value.all)

        val alex = library.addProfile("Alex")!!
        assertTrue(library.deleteProfile(only.id))
        assertEquals(alex, library.profiles.value.current)
        assertFalse(library.deleteProfile(alex.id))
    }

    @Test fun `after a restart the device is on the Profile it was on`() = runBlocking {
        sync()
        val alex = library.addProfile("Alex")!!
        library.addProfile("Robin")
        library.switchProfile(alex.id)
        library.saveProgress(first, position = 10 * 60_000L, duration = 100 * 60_000L)
        library.close()

        val reopened = openLibrary()
        assertEquals(alex, reopened.profiles.value.current)
        assertEquals(listOf(null, "Alex", "Robin"), names(reopened))
        assertEquals(listOf("First"), continueWatching(reopened))
    }

    @Test fun `a device upgraded from before Profiles keeps everything in its first Profile`() = runBlocking {
        sync()
        val espn = channel("US: ESPN HD").key
        val choices = TitleChoices(subtitleDelay = 500)
        library.close()
        // The tables as the app kept them before Profiles, with what a Viewer had kept in them.
        BundledSQLiteDriver().open(path).apply {
            listOf("favourite", "not_this_game", "progress", "episode_progress", "title_choices", "remembered_track", "profile").forEach { execSQL("DROP TABLE $it") }
            execSQL("CREATE TABLE favourite (key TEXT PRIMARY KEY, added_at INTEGER NOT NULL)")
            execSQL("CREATE TABLE not_this_game (game_key TEXT NOT NULL, channel_key TEXT NOT NULL, dropped_at INTEGER NOT NULL, PRIMARY KEY (game_key, channel_key))")
            execSQL("CREATE TABLE progress (title_key TEXT PRIMARY KEY, position INTEGER NOT NULL, duration INTEGER, watched INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
            execSQL(
                "CREATE TABLE episode_progress (title_key TEXT NOT NULL, season INTEGER NOT NULL, number INTEGER NOT NULL, position INTEGER NOT NULL, " +
                    "duration INTEGER, watched INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY (title_key, season, number))",
            )
            execSQL("CREATE TABLE title_choices (title_key TEXT PRIMARY KEY, body TEXT NOT NULL, updated_at INTEGER NOT NULL)")
            val now = Instant.now().toEpochMilli()
            execSQL("INSERT INTO favourite VALUES ('$espn', $now)")
            execSQL("INSERT INTO not_this_game VALUES ('nhl/1', 'us espn', $now)")
            execSQL("INSERT INTO progress VALUES ('movie/1', 600000, 6000000, 0, $now)")
            execSQL("INSERT INTO episode_progress VALUES ('show/7', 1, 2, 600000, 6000000, 0, $now)")
            execSQL("INSERT INTO title_choices VALUES ('movie/1', '${choices.toJson()}', $now)")
            execSQL("INSERT OR REPLACE INTO meta VALUES ('remembered_track_audio', 'fr')")
            close()
        }

        val upgraded = openLibrary()
        library = upgraded
        val profiles = upgraded.profiles.value
        assertEquals(1, profiles.all.size)
        assertNull(profiles.current.name)
        assertEquals(listOf("First"), continueWatching(upgraded))
        assertEquals(listOf("ESPN"), favourites(upgraded))
        assertEquals("fr", upgraded.rememberedTracks().audio)
        assertEquals(choices, upgraded.titleChoices(first))
        upgraded.close()
        CatalogStore(path).use { kept ->
            assertEquals(setOf(GameFeeds.droppedKey("nhl/1", "us espn")), kept.droppedFeeds(1, listOf("nhl/1")))
            assertEquals(1, kept.episodeProgress(1, TitleId.Numbered(TitleKind.Show, 7)).size)
        }
    }
}
