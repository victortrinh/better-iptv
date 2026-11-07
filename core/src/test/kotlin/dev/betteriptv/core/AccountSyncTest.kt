package dev.betteriptv.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Everything a Profile owns, and the Profiles themselves, syncing with the Account (#440): two or
 * three TVs, each with its own catalog database, over one Provider (or two) and one cloud, faked.
 */
class AccountSyncTest {
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
    private val first = TitleId.Tmdb(TitleKind.Movie, 1)
    private val homeVideo = TitleId.Unmatched(TitleKind.Movie, 2)
    private val hour = 60 * 60_000L
    private val email = "home@example.com"

    /** A TV of the household, logged in to the Provider (at [address], the same host by default) and Synced. */
    private suspend fun tv(name: String, address: String = provider.url): Library {
        val library = Library.open(folder.root.resolve("$name.db").path, InMemoryCredentials(), cloud.url).also { libraries += it }
        provider.movieCategoriesOverride = """[{"category_id":"1","category_name":"EN - MOVIES"}]"""
        provider.movieListOverride = listOf("EN - First (2020)" to "1", "EN - Home Video (2019)" to "").withIndex().joinToString(",", "[", "]") { (i, movie) ->
            """{"stream_id":${i + 1},"name":"${movie.first}","tmdb":"${movie.second}","category_id":"1","added":"${1_700_000_000 - i}","container_extension":"mkv"}"""
        }
        provider.serveChannels("US: ESPN HD" to "US| SPORT", "UK: BBC ONE" to "UK| ENTERTAINMENT")
        library.logIn(ProviderLogin(address, provider.login.username, provider.login.password))
        library.sync()
        return library
    }

    private suspend fun Library.signIn() {
        assertTrue(cloud.phoneSignsIn(startSignIn()!!.code, email))
        assertEquals(email, checkSignIn())
        // The merge into the Account runs in the background as sign-in completes: this waits for it.
        syncHistory()
    }

    /** A pull and a screen entry: what the TV shows once the Viewer moves to another screen. */
    private suspend fun Library.pullAndEnter() {
        assertTrue(syncHistory())
        applyPendingHistory()
    }

    private fun Library.names() = profiles.value.all.map { it.name }

    private fun Library.named(name: String) = profiles.value.all.single { it.name == name }

    private suspend fun Library.channelKey(name: String) =
        search(PrefixTable.base().channelName(name).let { listOfNotNull(it.country, it.name).joinToString(" ") }, english, "en").last()
            .channels.first { it.variants.any { variant -> variant.name == name } }.key

    private suspend fun Library.favouriteNames() = favourites().map { it.variants.first().name }

    private suspend fun Library.watching() =
        homeRows(english, "en").filterIsInstance<HomeRow.ContinueWatching>().singleOrNull()?.titles.orEmpty().map { it.title.id }

    @Test fun `a Profile made, renamed or deleted on one TV is so on the other after its pull, its Favourites and settings with it`() = runBlocking {
        val living = tv("living")
        val bedroom = tv("bedroom")
        living.signIn()
        bedroom.signIn()
        living.pullAndEnter()
        bedroom.pullAndEnter()

        val sam = living.addProfile("Sam")!!
        living.switchProfile(sam.id)
        living.setFavourite(living.channelKey("US: ESPN HD"), true)
        living.saveProfileSettings(mapOf(ProfileSetting.LEAGUES to "nhl,nfl", ProfileSetting.APP_LANGUAGE to "fr"))
        assertTrue(living.syncHistory())

        // Nothing moves under focus: the bedroom sees Sam once it enters a screen.
        assertTrue(bedroom.syncHistory())
        assertEquals(listOf<String?>(null), bedroom.names())
        bedroom.applyPendingHistory()
        assertEquals(listOf(null, "Sam"), bedroom.names())
        // The bedroom stays on the Profile it was on.
        assertEquals(null, bedroom.profiles.value.current.name)
        bedroom.switchProfile(bedroom.named("Sam").id)
        assertEquals(listOf("US: ESPN HD"), bedroom.favouriteNames())
        assertEquals(mapOf(ProfileSetting.LEAGUES to "nhl,nfl", ProfileSetting.APP_LANGUAGE to "fr"), bedroom.profileSettings.value)

        living.renameProfile(sam.id, "Samuel")
        living.syncHistory()
        bedroom.pullAndEnter()
        assertEquals(listOf(null, "Samuel"), bedroom.names())

        // Deleted here while the bedroom is on it: the bedroom goes to the one it used before.
        living.switchProfile(living.profiles.value.all.first().id)
        assertTrue(living.deleteProfile(sam.id))
        living.syncHistory()
        bedroom.pullAndEnter()
        assertEquals(listOf<String?>(null), bedroom.names())
        assertEquals(emptyList<String>(), bedroom.favouriteNames())
        assertEquals(emptyMap<String, String>(), bedroom.profileSettings.value)
    }

    @Test fun `only a Profile's own settings and the Region sync, never the device's`() = runBlocking {
        val living = tv("living")
        living.signIn()
        living.pullAndEnter()
        living.saveProfileSettings(mapOf(ProfileSetting.CONTENT_LANGUAGES to "fr,en", ProfileSetting.PLAY_NEXT_AUTOMATICALLY to "false"))
        living.saveRegion("FR")
        living.syncHistory()

        val pushed = cloud.entriesOf(email).filter { it["kind"] == "setting" || it["kind"] == "household" }.map { it["key"] }
        assertEquals(setOf(ProfileSetting.CONTENT_LANGUAGES, ProfileSetting.PLAY_NEXT_AUTOMATICALLY, "region"), pushed.toSet())
        // Quality cap, Text size, subtitle style and Background reading are the app's, kept on the device: the Library takes none.
        assertTrue(runCatching { living.saveProfileSettings(mapOf("qualityCap" to "HD")) }.isFailure)
        assertTrue(runCatching { living.saveProfileSettings(mapOf("textSize" to "Large")) }.isFailure)
    }

    @Test fun `signing in merges the TV's Profiles into the Account's by name, a Kids one staying Kids, and adds the rest`() = runBlocking {
        val living = tv("living")
        val bedroom = tv("bedroom")
        living.addProfile("Sam")
        living.addProfile("Alex", kids = true)
        living.signIn()
        living.pullAndEnter()
        // The bedroom's Alex isn't Kids, its sam is spelt in lower case, and it has a Profile of its own. It is on sam.
        bedroom.addProfile("Alex")
        val sam = bedroom.addProfile("sam")!!
        bedroom.addProfile("Jo")
        bedroom.switchProfile(sam.id)
        bedroom.setFavourite(bedroom.channelKey("UK: BBC ONE"), true)

        bedroom.signIn()
        bedroom.pullAndEnter()
        living.pullAndEnter()

        for (tv in listOf(living, bedroom)) {
            assertEquals(4, tv.profiles.value.all.size)
            assertEquals(setOf(null, "Alex", "Jo"), tv.profiles.value.all.map { it.name }.toSet() - setOf("Sam", "sam"))
            assertEquals(1, tv.profiles.value.all.count { it.name.equals("sam", ignoreCase = true) })
            assertTrue(tv.named("Alex").kids)
            assertFalse(tv.named("Jo").kids)
        }
        // The bedroom is still on its sam, with its Favourite; the living room sees it on its Sam.
        assertTrue(bedroom.profiles.value.current.name.equals("sam", ignoreCase = true))
        assertEquals(listOf("UK: BBC ONE"), bedroom.favouriteNames())
        living.switchProfile(living.profiles.value.all.single { it.name.equals("sam", ignoreCase = true) }.id)
        assertEquals(listOf("UK: BBC ONE"), living.favouriteNames())
    }

    @Test fun `signing in past five Profiles asks which to keep first, and one left out is deleted from every TV`() = runBlocking {
        val living = tv("living")
        val bedroom = tv("bedroom")
        listOf("Sam", "Alex", "Jo").forEach { living.addProfile(it) }
        living.signIn()
        living.pullAndEnter()
        // The bedroom has 3 of its own, unnamed included: with the Account's 4, that is 7.
        val kim = bedroom.addProfile("Kim")!!
        bedroom.addProfile("Lou")
        bedroom.switchProfile(kim.id)
        living.switchProfile(living.named("Jo").id)
        living.saveProfileSettings(mapOf(ProfileSetting.APP_LANGUAGE to "fr"))
        living.syncHistory()

        bedroom.signIn()
        assertFalse(bedroom.syncHistory())
        val choice = bedroom.profileChoice.value
        assertNotNull(choice)
        assertEquals(5, choice!!.most)
        // The unnamed ones merge: 3 of the Account's, 2 of the TV's own, 1 both.
        assertEquals(6, choice.profiles.size)
        assertTrue(choice.profiles.single { it.name == "Kim" }.current)
        assertTrue(choice.profiles.single { it.name == null }.let { it.inAccount && it.onThisTv })
        // Nothing merged yet, either way.
        assertEquals(4, cloud.entriesOf(email).count { it["kind"] == "profile" })
        assertEquals(listOf(null, "Kim", "Lou"), bedroom.names())

        // Jo, the Account's, and Lou, the TV's, are left out.
        val keep = choice.profiles.filter { it.name != "Jo" && it.name != "Lou" }.map { it.key }.toSet()
        assertTrue(bedroom.keepProfiles(keep))
        assertNull(bedroom.profileChoice.value)
        bedroom.applyPendingHistory()
        living.pullAndEnter()

        for (tv in listOf(living, bedroom)) assertEquals(setOf(null, "Sam", "Alex", "Kim"), tv.names().toSet())
        assertEquals("Kim", bedroom.profiles.value.current.name)
        // The living room was on Jo: it goes to the one it used before.
        assertTrue(living.profiles.value.current.name != "Jo")
        assertTrue(cloud.entriesOf(email).none { it["kind"] == "setting" })
    }

    @Test fun `Favourites and the progress of what has no TMDB ID stay with their Provider, and history by TMDB ID follows the Account`() = runBlocking {
        val living = tv("living")
        // The same panel by another name: another Provider host, whose Channels and Listings may be anything.
        val elsewhere = tv("elsewhere", provider.url.replace("127.0.0.1", "localhost"))
        living.signIn()
        elsewhere.signIn()
        living.pullAndEnter()
        elsewhere.pullAndEnter()
        living.setFavourite(living.channelKey("US: ESPN HD"), true)
        living.saveProgress(first, hour, duration = 2 * hour)
        living.saveProgress(homeVideo, hour, duration = 2 * hour)
        living.syncHistory()

        elsewhere.pullAndEnter()
        assertEquals(emptyList<String>(), elsewhere.favouriteNames())
        assertEquals(listOf(first), elsewhere.watching())
        // "Not this game" marks are kept per Provider as Favourites are (cloud/test/sync.test.ts).
        val other = providerKey(ProviderLogin(provider.url.replace("127.0.0.1", "localhost"), provider.login.username, provider.login.password))
        assertTrue(cloud.entriesOf(email, other).none { it["kind"] == "favourite" })
        assertEquals(1, cloud.entriesOf(email, providerKey(provider.login)).count { it["kind"] == "favourite" })
    }

    @Test fun `the Region is the household's, changed on one TV it is the other's at its next pull`() = runBlocking {
        val living = tv("living")
        val bedroom = tv("bedroom")
        living.signIn()
        bedroom.signIn()
        living.saveRegion("CA")
        living.pullAndEnter()
        bedroom.pullAndEnter()
        assertEquals("CA", bedroom.region.value)

        bedroom.saveRegion("GB")
        bedroom.syncHistory()
        living.pullAndEnter()
        assertEquals("GB", living.region.value)
        // On a Profile of its own too: the Region isn't a Profile's.
        val sam = living.addProfile("Sam")!!
        living.switchProfile(sam.id)
        assertEquals("GB", living.region.value)
    }

    @Test fun `the PIN set on one TV is asked on the other, and resetting it through the link clears it on both`() = runBlocking {
        val living = tv("living")
        val bedroom = tv("bedroom")
        living.signIn()
        bedroom.signIn()
        living.pullAndEnter()
        bedroom.pullAndEnter()
        living.householdPin.set("2468")
        living.syncHistory()

        assertFalse(bedroom.householdPin.isSet.value)
        bedroom.pullAndEnter()
        assertTrue(bedroom.householdPin.isSet.value)
        assertEquals(PinCheck.Wrong(HouseholdPin.TRIES - 1), bedroom.householdPin.check("1111"))
        assertEquals(PinCheck.Right, bedroom.householdPin.check("2468"))
        // Wrong tries are each TV's own.
        assertEquals(PinCheck.Right, living.householdPin.check("2468"))

        // The latest set wins: the bedroom's, though it reaches the cloud first.
        Thread.sleep(5)
        bedroom.householdPin.set("1357")
        bedroom.syncHistory()
        living.pullAndEnter()
        assertEquals(PinCheck.Right, living.householdPin.check("1357"))

        cloud.resetHouseholdPin(email)
        living.pullAndEnter()
        bedroom.pullAndEnter()
        assertFalse(living.householdPin.isSet.value)
        assertFalse(bedroom.householdPin.isSet.value)
        // The next PIN set, when a Kids profile is left, is everyone's again.
        bedroom.householdPin.set("9999")
        bedroom.syncHistory()
        living.pullAndEnter()
        assertEquals(PinCheck.Right, living.householdPin.check("9999"))
    }

    @Test fun `deleting a Profile on one TV takes its history and Favourites off the other, and a new one of its name starts blank`() = runBlocking {
        val living = tv("living")
        val bedroom = tv("bedroom")
        living.signIn()
        bedroom.signIn()
        val sam = living.addProfile("Sam")!!
        living.switchProfile(sam.id)
        living.saveProgress(first, hour, duration = 2 * hour)
        living.setFavourite(living.channelKey("US: ESPN HD"), true)
        living.pullAndEnter()
        bedroom.pullAndEnter()
        bedroom.switchProfile(bedroom.named("Sam").id)
        assertEquals(listOf(first), bedroom.watching())

        bedroom.switchProfile(bedroom.profiles.value.all.first().id)
        assertTrue(bedroom.deleteProfile(bedroom.named("Sam").id))
        bedroom.syncHistory()
        living.pullAndEnter()
        assertEquals(listOf<String?>(null), living.names())

        val again = living.addProfile("Sam")!!
        living.switchProfile(again.id)
        assertEquals(emptyList<TitleId>(), living.watching())
        assertEquals(emptyList<String>(), living.favouriteNames())
        assertTrue(cloud.entriesOf(email).none { it["kind"] == "favourite" })
    }

    @Test fun `signing out leaves one blank Profile, and signing in again brings the Account's back`() = runBlocking {
        val living = tv("living")
        living.addProfile("Sam")
        living.addProfile("Alex", kids = true)
        living.householdPin.set("2468")
        living.saveRegion("CA")
        living.signIn()
        living.pullAndEnter()

        assertTrue(living.signOut())
        assertEquals(listOf<String?>(null), living.names())
        assertFalse(living.householdPin.isSet.value)
        assertNull(living.region.value)
        assertTrue(living.isLoggedIn)

        living.signIn()
        living.pullAndEnter()
        assertEquals(setOf(null, "Sam", "Alex"), living.names().toSet())
        assertTrue(living.householdPin.isSet.value)
        assertEquals("CA", living.region.value)
    }

    @Test fun `a TV with no Account keeps its Profiles and settings on the device, as before`() = runBlocking {
        val living = tv("living")
        val sam = living.addProfile("Sam")!!
        living.renameProfile(sam.id, "Samuel")
        living.saveProfileSettings(mapOf(ProfileSetting.APP_LANGUAGE to "fr"))
        living.saveRegion("FR")
        living.householdPin.set("2468")
        living.setFavourite(living.channelKey("US: ESPN HD"), true)

        assertFalse(living.syncHistory())
        assertFalse(living.applyPendingHistory())
        assertEquals(listOf(null, "Samuel"), living.names())
        assertEquals("FR", living.region.first())
        assertTrue(cloud.received.none { it.path == "/v1/sync" })
    }
}
