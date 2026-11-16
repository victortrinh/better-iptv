package dev.betteriptv.core

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

/** The Account's device list, signing out and a deleted Account (#434): two TVs, two Libraries, one cloud. */
class AccountDevicesTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private val livingRoomLogin = InMemoryCredentials()
    private lateinit var livingRoom: Library
    private lateinit var bedroom: Library
    private val fullHdTv = DeviceProfile(maxHeight = 1080, hdr = emptySet())

    @Before fun twoTvsSignedIn() = runBlocking {
        livingRoom = Library.open(folder.root.resolve("living.db").path, livingRoomLogin, cloud.url)
        bedroom = Library.open(folder.root.resolve("bedroom.db").path, InMemoryCredentials(), cloud.url)
        for ((library, model) in listOf(livingRoom to "Fire TV Stick 4K Max", bedroom to "Fire TV Cube")) {
            library.logIn(provider.login)
            library.sync()
            assertTrue(cloud.phoneSignsIn(library.startSignIn(model)!!.code, "home@example.com"))
            assertEquals("home@example.com", library.checkSignIn())
        }
    }

    @After fun tearDown() {
        livingRoom.close()
        bedroom.close()
        provider.close()
        cloud.close()
    }

    @Test fun `each TV lists both, by model and sign-in date, itself marked`() = runBlocking {
        val fromLivingRoom = livingRoom.accountDevices()!!
        assertEquals(listOf("Fire TV Stick 4K Max", "Fire TV Cube"), fromLivingRoom.map { it.model })
        assertEquals(listOf(true, false), fromLivingRoom.map { it.thisDevice })
        assertTrue(fromLivingRoom[0].signedInAt <= fromLivingRoom[1].signedInAt)
        val fromBedroom = bedroom.accountDevices()!!
        assertEquals(listOf(false, true), fromBedroom.map { it.thisDevice })
        assertEquals(fromLivingRoom.map { it.id }, fromBedroom.map { it.id })
    }

    @Test fun `signing this TV out clears its link at once and in the cloud`() = runBlocking {
        assertTrue(livingRoom.signOut())
        assertNull(livingRoom.account.value)
        assertNull(livingRoom.accountDevices())
        assertEquals(listOf("Fire TV Cube"), bedroom.accountDevices()!!.map { it.model })
    }

    @Test fun `a TV signed out from another goes anonymous at its next request, whatever it asks`() = runBlocking {
        val other = livingRoom.accountDevices()!!.single { !it.thisDevice }
        assertTrue(livingRoom.signOut(other))
        assertEquals("home@example.com", bedroom.account.value)

        bedroom.sync()

        assertNull(bedroom.account.value)
        assertEquals("home@example.com", livingRoom.account.value)
        assertEquals(listOf("Fire TV Stick 4K Max"), livingRoom.accountDevices()!!.map { it.model })
    }

    @Test fun `a signed-out TV keeps its catalog and Provider login and plays as before`() = runBlocking {
        val movies = livingRoom.titleCount()
        val movie = livingRoom.titles(0, 1, listOf("en"), "en").first().id
        assertNotNull(livingRoom.playPlan(movie, listOf("en"), fullHdTv))

        livingRoom.signOut()
        Library.open(folder.root.resolve("living.db").path, livingRoomLogin, cloud.url).close()

        assertNotNull(livingRoomLogin.load())
        assertEquals(movies, livingRoom.titleCount())
        assertNotNull(livingRoom.playPlan(movie, listOf("en"), fullHdTv))
        livingRoom.sync()
        assertEquals(movies, livingRoom.titleCount())
    }

    @Test fun `a deleted Account sends every TV back to anonymous at its next contact`() = runBlocking {
        cloud.deleteAccount("home@example.com")
        livingRoom.sync()
        bedroom.sync()
        assertNull(livingRoom.account.value)
        assertNull(bedroom.account.value)
    }

    @Test fun `signing out while the cloud can't hear it tells the cloud at the next request`() = runBlocking {
        cloud.down = true
        assertTrue(livingRoom.signOut())
        assertNull(livingRoom.account.value)
        cloud.down = false

        livingRoom.sync()
        // The sign-out goes on its own thread, after the answer that found the device still signed in.
        val deadline = System.currentTimeMillis() + 5_000
        while (bedroom.accountDevices()!!.size != 1 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(listOf("Fire TV Cube"), bedroom.accountDevices()!!.map { it.model })
        assertNull(livingRoom.account.value)
    }

    @Test fun `signing another TV out fails plainly while the cloud can't be reached`() = runBlocking {
        val other = livingRoom.accountDevices()!!.single { !it.thisDevice }
        cloud.down = true
        assertFalse(livingRoom.signOut(other))
        cloud.down = false
        assertEquals(2, livingRoom.accountDevices()!!.size)
    }

    @Test fun `the model goes to the cloud with the Sign-in code and nowhere else, and the Provider login never`() = runBlocking {
        livingRoom.accountDevices()
        livingRoom.sync()
        val withModel = cloud.received.filter { it.body.contains("Fire TV Stick 4K Max") || it.headers.toString().contains("Fire TV Stick 4K Max") }
        assertEquals(listOf("/v1/sign-in"), withModel.map { it.path })
        cloud.received.forEach { request ->
            val sent = request.path + request.headers + request.body
            assertFalse(sent, sent.contains(provider.username))
            assertFalse(sent, sent.contains(provider.password))
        }
    }
}
