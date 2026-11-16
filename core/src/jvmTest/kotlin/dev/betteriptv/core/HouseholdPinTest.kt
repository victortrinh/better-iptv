package dev.betteriptv.core

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
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
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The Household PIN (#437): four digits that let a Viewer leave a Kids profile, kept on the device
 * as a hash, five wrong tries waiting 30 s.
 */
class HouseholdPinTest {
    @get:Rule val folder = TemporaryFolder()
    private val credentials = InMemoryCredentials()
    private val libraries = mutableListOf<Library>()
    private val path get() = folder.root.resolve("catalog.db").path
    private var now = Instant.parse("2026-11-07T20:00:00Z")

    private fun openLibrary() = Library.open(path, credentials).also {
        libraries += it
        (it.householdPin as KeptHouseholdPin).clock = object : Clock {
            override fun now() = now
        }
    }

    private val library by lazy { openLibrary() }
    private val pin get() = library.householdPin

    @After fun tearDown() = libraries.forEach { it.close() }

    @Test fun aHouseholdStartsWithNoPin() = runBlocking {
        assertFalse(pin.isSet.value)
        // With none set there is nothing to get right.
        assertEquals(PinCheck.Wrong(triesLeft = 5), pin.check("1234"))
    }

    @Test fun theRightPinIsRightAndAnyOtherWrong() = runBlocking {
        pin.set("2580")

        assertTrue(pin.isSet.value)
        assertEquals(PinCheck.Right, pin.check("2580"))
        assertEquals(PinCheck.Wrong(triesLeft = 4), pin.check("0852"))
    }

    @Test fun onlyFourDigitsMakeAPin() = runBlocking {
        for (bad in listOf("123", "12345", "12a4", "")) {
            assertTrue(bad, runCatching { pin.set(bad) }.isFailure)
        }
        assertFalse(pin.isSet.value)
    }

    @Test fun fiveWrongTriesLockThePinForThirtySecondsThenItWorksAgain() = runBlocking {
        pin.set("2580")
        repeat(4) { assertEquals(PinCheck.Wrong(triesLeft = 4 - it), pin.check("0000")) }

        val until = now + 30.seconds
        assertEquals(PinCheck.Locked(until), pin.check("0000"))
        assertEquals(30.seconds, pin.lockedFor())
        // Locked, even the right PIN waits.
        now += 29.seconds
        assertEquals(PinCheck.Locked(until), pin.check("2580"))

        now += 1.seconds
        assertNull(pin.lockedFor())
        // Five tries again.
        assertEquals(PinCheck.Wrong(triesLeft = 4), pin.check("0000"))
        assertEquals(PinCheck.Right, pin.check("2580"))
    }

    @Test fun theRightPinStartsTheTriesAgain() = runBlocking {
        pin.set("2580")
        repeat(4) { pin.check("0000") }
        assertEquals(PinCheck.Right, pin.check("2580"))

        assertEquals(PinCheck.Wrong(triesLeft = 4), pin.check("0000"))
    }

    @Test fun thePinAndItsLockOutliveTheApp() = runBlocking {
        pin.set("2580")
        repeat(5) { pin.check("0000") }
        library.close()

        // Closing the app doesn't buy a child five more tries.
        val reopened = openLibrary()
        assertTrue(reopened.householdPin.isSet.value)
        assertNotNull(reopened.householdPin.lockedFor())
        now += 30.seconds
        assertEquals(PinCheck.Right, reopened.householdPin.check("2580"))
    }

    @Test fun changingThePinKeepsOnlyTheNewOne() = runBlocking {
        pin.set("2580")
        pin.set("1397")

        assertEquals(PinCheck.Right, pin.check("1397"))
        assertEquals(PinCheck.Wrong(triesLeft = 4), pin.check("2580"))
    }

    @Test fun theDeviceKeepsAHashNeverThePin() = runBlocking {
        pin.set("2580")
        library.close()

        val connection = BundledSQLiteDriver().open(path)
        val kept = connection.prepare("SELECT hash FROM household_pin").use { it.step(); it.getText(0) }
        connection.close()
        assertFalse(kept, "2580" in kept)
        // Salted: the same PIN never hashes the same twice, so two households' hashes don't tell they match.
        val again = openLibrary().householdPin
        again.set("2580")
        val second = BundledSQLiteDriver().open(path).let { c -> c.prepare("SELECT hash FROM household_pin").use { it.step(); it.getText(0) }.also { c.close() } }
        assertTrue(kept != second)
    }

    @Test fun aNewCatalogShapeKeepsThePin() = runBlocking {
        pin.set("2580")
        library.close()
        // An older catalog's shape drops the catalog and clears meta; the PIN isn't the catalog's.
        BundledSQLiteDriver().open(path).apply { execSQL("PRAGMA user_version = 1") }.close()

        assertEquals(PinCheck.Right, openLibrary().householdPin.check("2580"))
    }
}
