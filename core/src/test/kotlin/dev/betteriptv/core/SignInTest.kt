package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration

/** Signing the TV in to an Account from a phone (ADR 0015), through the Library, with the cloud faked. */
class SignInTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val cloud = FakeCloud()
    private val credentials = InMemoryCredentials()
    private lateinit var library: Library
    private val path get() = folder.root.resolve("catalog.db").path

    @Before fun logIn() = runBlocking {
        library = Library.open(path, credentials, cloud.url)
        library.logIn(provider.login)
        library.sync()
        Unit
    }

    @After fun tearDown() {
        library.close()
        provider.close()
        cloud.close()
    }

    @Test fun `a code to show, with the page's address for the QR, for 10 minutes`() = runBlocking {
        val signIn = library.startSignIn()!!
        assertTrue(signIn.code, Regex("[2-9A-HJ-NP-Z]{6}").matches(signIn.code))
        assertEquals("${cloud.url}/sign-in?code=${signIn.code}", signIn.url)
        assertEquals(Duration.ofMinutes(10), signIn.expiresIn)
        assertNotEquals(signIn.code, library.startSignIn()!!.code)
    }

    @Test fun `the poll finds the TV signed in once the phone taps the link, and the device remembers it`() = runBlocking {
        val signIn = library.startSignIn()!!
        assertNull(library.checkSignIn())
        assertNull(library.account.value)

        assertTrue(cloud.phoneSignsIn(signIn.code, "home@example.com"))

        assertEquals("home@example.com", library.checkSignIn())
        assertEquals("home@example.com", library.account.value)
        library.close()
        library = Library.open(path, credentials, cloud.url)
        assertEquals("home@example.com", library.account.value)
    }

    @Test fun `the same device token keeps working after sign-in`() = runBlocking {
        cloud.phoneSignsIn(library.startSignIn()!!.code, "home@example.com")
        library.checkSignIn()
        library.sync()
        val tokens = cloud.received.filter { it.path != "/v1/devices" }.mapNotNull { it.headers["Authorization"]?.single() }.toSet()
        assertEquals(1, tokens.size)
        assertEquals(1, cloud.received.count { it.path == "/v1/devices" })
    }

    @Test fun `the Provider login never goes to the cloud`() = runBlocking {
        cloud.phoneSignsIn(library.startSignIn()!!.code, "home@example.com")
        library.checkSignIn()
        val host = java.net.URI(provider.url).host + ":" + java.net.URI(provider.url).port
        cloud.received.forEach { request ->
            val sent = request.path + request.headers + request.body
            assertFalse(sent, sent.contains(provider.username))
            assertFalse(sent, sent.contains(provider.password))
            assertFalse(sent, sent.contains(host))
        }
        assertTrue(cloud.received.any { it.path == "/v1/sign-in" } && cloud.received.any { it.path == "/v1/account" })
    }

    @Test fun `a device that never signs in never asks the cloud about an Account`() = runBlocking {
        library.sync()
        assertNull(library.account.value)
        assertTrue(cloud.received.none { it.path == "/v1/sign-in" || it.path == "/v1/account" })
    }

    @Test fun `no code while the cloud can't be reached, and the poll keeps what the device knew`() = runBlocking {
        cloud.phoneSignsIn(library.startSignIn()!!.code, "home@example.com")
        library.checkSignIn()
        cloud.down = true
        assertNull(library.startSignIn())
        assertEquals("home@example.com", library.checkSignIn())
        assertEquals("home@example.com", library.account.value)
    }

    @Test fun `no code without a cloud`() = runBlocking {
        Library.open(folder.root.resolve("alone.db").path, InMemoryCredentials()).use { alone ->
            assertNull(alone.startSignIn())
            assertNull(alone.checkSignIn())
            assertNull(alone.account.value)
        }
    }

    @Test fun `the first Sync's card is offered to a device with no Account, and asks the cloud for a code`() = runBlocking {
        Library.open(folder.root.resolve("fresh.db").path, InMemoryCredentials(), cloud.url).use { fresh ->
            assertTrue(fresh.offersSignInCard.value)
            val signIn = fresh.startSignIn()!!
            assertTrue(cloud.received.any { it.path == "/v1/sign-in" })
            cloud.phoneSignsIn(signIn.code, "home@example.com")
            assertEquals("home@example.com", fresh.checkSignIn())
            assertFalse(fresh.offersSignInCard.value)
        }
        // Signed in, then out: the card still never comes back on this device.
        Library.open(folder.root.resolve("fresh.db").path, InMemoryCredentials(), cloud.url).use { again ->
            assertFalse(again.offersSignInCard.value)
        }
    }

    @Test fun `a dismissed card never comes back on that device`() = runBlocking {
        Library.open(folder.root.resolve("fresh.db").path, InMemoryCredentials(), cloud.url).use { fresh ->
            fresh.dismissSignInCard()
            assertFalse(fresh.offersSignInCard.value)
        }
        Library.open(folder.root.resolve("fresh.db").path, InMemoryCredentials(), cloud.url).use { again ->
            assertFalse(again.offersSignInCard.value)
        }
        assertTrue(cloud.received.none { it.path == "/v1/sign-in" })
    }

    @Test fun `no card without a cloud`() {
        Library.open(folder.root.resolve("alone.db").path, InMemoryCredentials()).use { alone ->
            assertFalse(alone.offersSignInCard.value)
        }
    }
}
