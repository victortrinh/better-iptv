package dev.betteriptv.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel as Seen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.seconds

/**
 * The finished content of Settings' lists, asked of the Library as an app asks for a screen
 * ([Library.screen], #592): the Profiles ([ProfilesScreen]), the Content languages
 * ([ContentLanguagesScreen]), the Leagues by sport ([LeaguesScreen]) and the Account's devices
 * ([AccountDevicesScreen]), and what a Kids profile is handed of each.
 */
class SettingsScreensTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private val libraries = mutableListOf<Library>()

    @After fun tearDown() {
        libraries.forEach { it.close() }
        provider.close()
        cloud.close()
    }

    private suspend fun library(): Library {
        val library = Library.open(folder.root.resolve("catalog-${libraries.size}.db").path, InMemoryCredentials(), cloud.url).also { libraries += it }
        library.logIn(provider.login)
        return library
    }

    private suspend fun onKidsProfile(library: Library): Profile =
        library.addProfile("Kids", kids = true)!!.also { library.switchProfile(it.id) }

    // Profiles

    @Test fun `the first Profile is listed alone, current, opening its page, and one more can be added`() = runBlocking {
        val library = library()

        val content = library.screen(ProfilesScreen).first()

        val first = library.profiles.value.current
        assertEquals(ProfilesContent(listOf(ListedProfile(first, current = true, opens = ListedProfile.Opens.Page)), AddingProfile.Offered), content)
    }

    @Test fun `Profiles are listed in the order they were made, the one the device is on marked`() = runBlocking {
        val library = library()
        val ada = library.addProfile("Ada")!!
        val bo = library.addProfile("Bo")!!
        library.switchProfile(ada.id)

        val content = library.screen(ProfilesScreen).first()

        assertEquals(listOf(null, "Ada", "Bo"), content.profiles.map { it.profile.name })
        assertEquals(listOf(false, true, false), content.profiles.map { it.current })
        assertEquals(listOf(Profile.FIRST_ID, ada.id, bo.id), content.profiles.map { it.profile.id })
        assertTrue(content.profiles.all { it.opens == ListedProfile.Opens.Page })
    }

    @Test fun `once the household has all its Profiles, adding one is shown full`() = runBlocking {
        val library = library()
        repeat(Library.MAX_PROFILES - 2) { library.addProfile("Profile $it") }
        assertEquals(AddingProfile.Offered, library.screen(ProfilesScreen).first().adding)

        library.addProfile("The last")

        assertEquals(AddingProfile.Full, library.screen(ProfilesScreen).first().adding)
    }

    @Test fun `on a Kids profile the other Profiles only switch, behind the PIN, its own opens nothing, and none is added`() = runBlocking {
        val library = library()
        library.addProfile("Ada")
        onKidsProfile(library)

        val content = library.screen(ProfilesScreen).first()

        assertEquals(listOf(null, "Ada", "Kids"), content.profiles.map { it.profile.name })
        assertEquals(listOf(ListedProfile.Opens.SwitchWithPin, ListedProfile.Opens.SwitchWithPin, null), content.profiles.map { it.opens })
        assertEquals(listOf(false, false, true), content.profiles.map { it.current })
        assertEquals(AddingProfile.NotOffered, content.adding)
    }

    @Test fun `the list for the Profiles an app holds is the one the Library hands`() = runBlocking {
        val library = library()
        library.addProfile("Ada")
        assertEquals(ProfilesContent.of(library.profiles.value), library.screen(ProfilesScreen).first())

        onKidsProfile(library)

        assertEquals(ProfilesContent.of(library.profiles.value), library.screen(ProfilesScreen).first())
    }

    @Test fun `a Profile added, renamed or deleted while the list shows hands it again, without being asked`() = runBlocking {
        val library = library()
        val seen = Seen<ProfilesContent>(Seen.UNLIMITED)
        val collecting = launch(Dispatchers.Default) { library.screen(ProfilesScreen).collect { seen.send(it) } }
        suspend fun next() = withTimeout(10.seconds) { seen.receive() }.profiles.map { it.profile.name }
        assertEquals(listOf(null), next())

        val ada = library.addProfile("Ada")!!
        assertEquals(listOf(null, "Ada"), next())

        library.renameProfile(ada.id, "Ada L.")
        assertEquals(listOf(null, "Ada L."), next())

        library.deleteProfile(ada.id)
        assertEquals(listOf(null), next())
        collecting.cancelAndJoin()
    }

    // Content languages

    @Test fun `Content languages are the chosen ones in the order chosen, then the catalog's others, most Listings first`() = runBlocking {
        val library = library()
        library.sync()
        val catalog = library.catalogLanguages()
        assertTrue("the fixture has several languages: $catalog", catalog.size >= 3)

        val chosen = listOf(catalog.last(), catalog[1])
        val content = library.screen(ContentLanguagesScreen(chosen)).first()

        assertEquals(chosen + (catalog - chosen.toSet()), content.languages)
    }

    @Test fun `a chosen language the catalog doesn't have is still listed, and before a Sync only the chosen are`() = runBlocking {
        val library = library()
        assertEquals(listOf("ja", "en"), library.screen(ContentLanguagesScreen(listOf("ja", "en"))).first().languages)

        library.sync()

        val content = library.screen(ContentLanguagesScreen(listOf("ja", "en"))).first()
        assertEquals(listOf("ja", "en"), content.languages.take(2))
        assertEquals(library.catalogLanguages() - "en", content.languages.drop(2))
    }

    @Test fun `a Sync that lands while Content languages show hands them again with the catalog's`() = runBlocking {
        val library = library()
        val seen = Seen<ContentLanguagesContent>(Seen.UNLIMITED)
        val collecting = launch(Dispatchers.Default) { library.screen(ContentLanguagesScreen(listOf("en"))).collect { seen.send(it) } }
        assertEquals(listOf("en"), withTimeout(10.seconds) { seen.receive() }.languages)

        library.sync()

        var content = withTimeout(10.seconds) { seen.receive() }
        while (content.languages.size < library.catalogLanguages().size) content = withTimeout(10.seconds) { seen.receive() }
        assertEquals(listOf("en") + (library.catalogLanguages() - "en"), content.languages)
        collecting.cancelAndJoin()
    }

    // Leagues

    @Test fun `Leagues are grouped by sport, the sports in their order, each with its Leagues in theirs, every League once`() = runBlocking {
        val library = library()

        val content = library.screen(LeaguesScreen).first()

        assertEquals(Sport.entries.filter { sport -> League.entries.any { it.sport == sport } }, content.sports.map { it.sport })
        for (group in content.sports) {
            assertTrue("${group.sport} has Leagues", group.leagues.isNotEmpty())
            assertEquals(League.entries.filter { it.sport == group.sport }, group.leagues)
        }
        assertEquals(League.entries.toSet(), content.sports.flatMap { it.leagues }.toSet())
        assertEquals(League.entries.size, content.sports.sumOf { it.leagues.size })
        // Hockey leads, with the NHL first; the NBA and the WNBA stand together under Basketball.
        assertEquals(Sport.Hockey, content.sports.first().sport)
        assertEquals(League.Nhl, content.sports.first().leagues.first())
        val basketball = content.sports.single { it.sport == Sport.Basketball }.leagues
        assertTrue(basketball.indexOf(League.Nba) < basketball.indexOf(League.Wnba))
        assertEquals(LeaguesContent.ALL, content)
    }

    // The Account's devices

    private suspend fun signedIn(library: Library, model: String) {
        assertTrue(cloud.phoneSignsIn(library.startSignIn(model)!!.code, "home@example.com"))
        assertEquals("home@example.com", library.checkSignIn())
    }

    @Test fun `the Account's devices are listed in the order they signed in, this one marked`() = runBlocking {
        val livingRoom = library()
        val bedroom = library()
        signedIn(livingRoom, "Fire TV Stick 4K Max")
        signedIn(bedroom, "Fire TV Cube")

        val fromLivingRoom = livingRoom.screen(AccountDevicesScreen).first() as AccountDevicesContent.Listed
        assertEquals(listOf("Fire TV Stick 4K Max" to true, "Fire TV Cube" to false), fromLivingRoom.devices.map { it.model to it.thisDevice })
        val fromBedroom = bedroom.screen(AccountDevicesScreen).first() as AccountDevicesContent.Listed
        assertEquals(listOf("Fire TV Stick 4K Max" to false, "Fire TV Cube" to true), fromBedroom.devices.map { it.model to it.thisDevice })
        assertEquals(fromLivingRoom.devices.map { it.id }, fromBedroom.devices.map { it.id })
    }

    @Test fun `a device signed out from another is gone from the list asked again`() = runBlocking {
        val livingRoom = library()
        val bedroom = library()
        signedIn(livingRoom, "Fire TV Stick 4K Max")
        signedIn(bedroom, "Fire TV Cube")
        val other = (livingRoom.screen(AccountDevicesScreen).first() as AccountDevicesContent.Listed).devices.single { !it.thisDevice }

        assertTrue(livingRoom.signOut(other))

        val listed = livingRoom.screen(AccountDevicesScreen).first() as AccountDevicesContent.Listed
        assertEquals(listOf("Fire TV Stick 4K Max"), listed.devices.map { it.model })
    }

    @Test fun `a device that isn't signed in, or can't reach the cloud, has no list to show`() = runBlocking {
        val anonymous = library()
        assertEquals(AccountDevicesContent.Unavailable, anonymous.screen(AccountDevicesScreen).first())

        val signedIn = library()
        signedIn(signedIn, "Fire TV Cube")
        cloud.close()

        assertEquals(AccountDevicesContent.Unavailable, signedIn.screen(AccountDevicesScreen).first())
    }

    // A Kids profile

    @Test fun `a Kids profile is handed its Profiles to switch, and no Content languages, Leagues or devices`() = runBlocking {
        val library = library()
        library.sync()
        signedIn(library, "Fire TV Cube")
        assertTrue(library.screen(ContentLanguagesScreen(listOf("en"))).first().languages.size > 1)
        assertEquals(1, (library.screen(AccountDevicesScreen).first() as AccountDevicesContent.Listed).devices.size)

        onKidsProfile(library)

        assertEquals(listOf(ListedProfile.Opens.SwitchWithPin, null), library.screen(ProfilesScreen).first().profiles.map { it.opens })
        assertEquals(ContentLanguagesContent(emptyList()), library.screen(ContentLanguagesScreen(listOf("en"))).first())
        assertEquals(LeaguesContent(emptyList()), library.screen(LeaguesScreen).first())
        assertEquals(AccountDevicesContent.Listed(emptyList()), library.screen(AccountDevicesScreen).first())
    }

    @Test fun `leaving the Kids profile while a list shows hands the whole list again`() = runBlocking {
        val library = library()
        val kids = onKidsProfile(library)
        val seen = Seen<LeaguesContent>(Seen.UNLIMITED)
        val collecting = launch(Dispatchers.Default) { library.screen(LeaguesScreen).collect { seen.send(it) } }
        assertEquals(LeaguesContent(emptyList()), withTimeout(10.seconds) { seen.receive() })

        library.switchProfile(Profile.FIRST_ID)

        assertEquals(LeaguesContent.ALL, withTimeout(10.seconds) { seen.receive() })
        assertEquals(false, library.profiles.value.current.kids)
        assertTrue(kids.kids)
        collecting.cancelAndJoin()
    }
}
