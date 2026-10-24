package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class LoginTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val credentials = InMemoryCredentials()
    private val library by lazy { Library.open(folder.root.resolve("catalog.db").path, credentials) }

    @After fun tearDown() {
        library.close()
        provider.close()
    }

    @Test fun `a wrong password is refused and not kept`() = runBlocking {
        val result = library.logIn(ProviderLogin(provider.url, provider.username, "wrong"))

        assertEquals(WrongLogin, result)
        assertFalse(library.isLoggedIn)
    }

    @Test fun `an expired subscription reports when it expired`() = runBlocking {
        provider.expiresOn = Instant.parse("2026-01-15T00:00:00Z")

        val result = library.logIn(provider.login)

        assertEquals(Expired(Instant.parse("2026-01-15T00:00:00Z")), result)
    }

    @Test fun `a good login is kept and reports when the subscription ends`() = runBlocking {
        val result = library.logIn(provider.login)

        assertEquals(LoggedIn(Instant.parse("2030-01-01T00:00:00Z")), result)
        assertTrue(library.isLoggedIn)
    }

    @Test fun `an unreachable Provider is reported without the login`() = runBlocking {
        provider.down = true

        val result = library.logIn(provider.login)

        assertTrue(result is Unreachable)
        assertFalse(result.toString().contains(provider.password))
        assertFalse(library.isLoggedIn)
    }
}
