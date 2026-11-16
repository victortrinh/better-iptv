package dev.betteriptv.core

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CopyOnWriteArrayList

/** The Core opens on the four things a platform supplies (ADR 0020, #583), here a fake of each. */
class PlatformTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeProvider()
    private val credentials = InMemoryCredentials()
    private val settings = InMemorySettings()
    private val background = FakeBackgroundWork()
    private val asked = CopyOnWriteArrayList<String>()
    private val libraries = mutableListOf<Library>()

    private fun platform() = Platform(credentials, settings, background) { name ->
        asked += name
        folder.root.resolve(name).path
    }

    private fun openLibrary() = Library.open(platform(), network = InProcess.network()).also { libraries += it }

    @After fun tearDown() {
        libraries.forEach { it.close() }
        provider.close()
    }

    @Test fun `the catalog is kept where the platform says its database lives`() = runBlocking {
        val library = openLibrary()
        library.logIn(provider.login)
        library.sync()

        assertEquals(listOf("catalog.db"), asked)
        assertTrue(folder.root.resolve("catalog.db").isFile)
        library.close()
        assertEquals(150, openLibrary().movieListingCount())
    }

    @Test fun `the Provider login is kept in the platform's secret store`() = runBlocking {
        openLibrary().logIn(provider.login)

        assertEquals(provider.login, credentials.saved)
        assertTrue(openLibrary().isLoggedIn)
    }

    @Test fun `the Provider login is written nowhere but the secret store`() = runBlocking {
        val library = openLibrary()
        library.logIn(provider.login)
        library.sync()
        library.deviceSettings.update { it.copy(qualityCap = QualityCap.HD) }
        library.close()

        val secrets = listOf(provider.password, provider.username).map { it.toByteArray() }
        // The database and whatever SQLite keeps beside it.
        val files = folder.root.walk().filter { it.isFile }.toList()
        assertTrue(files.any { it.name == "catalog.db" })
        for (file in files) {
            val bytes = file.readBytes()
            for (secret in secrets) assertFalse("${file.name} holds the login", bytes.holds(secret))
        }
        for ((key, value) in settings.kept) {
            for (secret in secrets) assertFalse("The setting $key holds the login", value.toByteArray().holds(secret))
        }
        assertFalse(provider.password in provider.login.toString())
        assertFalse(provider.username in provider.login.toString())
    }

    @Test fun `the device's settings are kept in the platform's settings store`() {
        openLibrary().deviceSettings.update { it.copy(qualityCap = QualityCap.FullHD, textSize = TextSize.Large) }

        assertEquals("FullHD", settings.kept[KeptDeviceSettings.QUALITY_CAP])
        assertEquals(DeviceSettings(qualityCap = QualityCap.FullHD, textSize = TextSize.Large), openLibrary().deviceSettings.settings.value)
    }

    @Test fun `a Sync is kept alive from its first request to its end`() = runBlocking {
        val library = openLibrary()
        library.logIn(provider.login)
        val keptAlive = CopyOnWriteArrayList<Boolean>()
        provider.onRequest = { keptAlive += background.running == 1 }

        val status = library.sync()

        assertTrue(status is SyncStatus.Synced)
        assertTrue(keptAlive.isNotEmpty())
        assertTrue("Every request of the Sync was made while the platform kept the app alive", keptAlive.all { it })
        // Nothing holds the app once the Sync is over.
        assertEquals(0, background.running)
        assertEquals(1, background.finished)
    }

    @Test fun `a Sync that fails lets the app go`() = runBlocking {
        val library = openLibrary()
        library.logIn(provider.login)
        provider.down = true

        assertTrue(library.sync() is SyncStatus.Failed)

        assertEquals(0, background.running)
        assertEquals(1, background.finished)
    }

    @Test fun `a held catalog is Matched and landed while kept alive`() = runBlocking {
        val library = openLibrary()
        library.logIn(provider.login)
        library.sync()
        assertTrue(library.sync(hold = true) is SyncStatus.Ready)
        val before = background.finished

        assertTrue(library.applyPendingCatalog())

        assertEquals(0, background.running)
        assertEquals(before + 1, background.finished)
    }

    private fun ByteArray.holds(other: ByteArray): Boolean =
        (0..size - other.size).any { at -> other.indices.all { this[at + it] == other[it] } }
}
