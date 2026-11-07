package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun `a wrong password answered with HTTP 513 is still a wrong login`() = runBlocking {
        provider.wrongLoginStatus = 513

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

    @Test fun `an M3U URL gives the Provider address, username and password, and logs in`() = runBlocking {
        val m3u = "${provider.url}/get.php?username=${provider.username}&password=${provider.password}&type=m3u_plus&output=ts"

        val login = ProviderLogin.fromM3uUrl(m3u)

        assertEquals(provider.login, login)
        assertEquals(LoggedIn(Instant.parse("2030-01-01T00:00:00Z")), library.logIn(login!!))
        assertEquals(provider.login, credentials.saved)
    }

    @Test fun `an M3U URL's credentials are decoded and its address keeps the scheme, host and port`() {
        val login = ProviderLogin.fromM3uUrl(" https://tv.example.com:8443/get.php?type=m3u_plus&password=p%40ss+w0rd&username=jo%2Bel ")

        assertEquals(ProviderLogin("https://tv.example.com:8443", "jo+el", "p@ss w0rd"), login)
    }

    @Test fun `an M3U URL without a scheme or port still gives its address`() {
        assertEquals(
            ProviderLogin("http://tv.example.com", "jo", "pw"),
            ProviderLogin.fromM3uUrl("tv.example.com/get.php?username=jo&password=pw"),
        )
    }

    @Test fun `a URL without both credentials, or a bare address, gives no login`() {
        listOf(
            "",
            "tv.example.com:8080",
            "http://tv.example.com:8080",
            "http://tv.example.com:8080/get.php?type=m3u_plus",
            "http://tv.example.com:8080/get.php?username=jo&type=m3u_plus",
            "http://tv.example.com:8080/get.php?username=jo&password=",
            "not a url at all ?username=jo&password=pw",
        ).forEach { assertNull(it, ProviderLogin.fromM3uUrl(it)) }
    }

    @Test fun `a login from an M3U URL is never sent to the cloud`() = runBlocking {
        FakeCloud().use { cloud ->
            val m3u = "${provider.url}/get.php?username=${provider.username}&password=${provider.password}&type=m3u_plus"
            Library.open(folder.root.resolve("cloud.db").path, credentials, cloud.url).use { library ->
                library.logIn(ProviderLogin.fromM3uUrl(m3u)!!)
                library.sync()
            }

            assertTrue(cloud.received.isNotEmpty())
            cloud.received.forEach { request ->
                val sent = request.path + request.headers + request.body
                assertFalse(request.path, sent.contains(provider.password))
                assertFalse(request.path, sent.contains(provider.url))
            }
        }
    }
}
