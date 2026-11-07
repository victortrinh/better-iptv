package dev.betteriptv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale

/** How the device list names a TV and its sign-in date (#434). */
class AccountSettingsTest {
    @Test fun `a Fire TV goes by Amazon's name for its model`() {
        assertEquals("Fire TV Stick 4K Max", deviceModelName("Amazon", "AFTKA"))
        assertEquals("Fire TV Cube (2nd Gen)", deviceModelName("Amazon", "AFTR"))
    }

    @Test fun `any other TV goes by its maker and model, the maker once`() {
        assertEquals("NVIDIA SHIELD Android TV", deviceModelName("NVIDIA", "SHIELD Android TV"))
        assertEquals("Google Chromecast", deviceModelName("google", "Chromecast"))
        assertEquals("Sony BRAVIA 4K VH2", deviceModelName("Sony", "Sony BRAVIA 4K VH2"))
        assertEquals("Amazon AFTNEW", deviceModelName("Amazon", "AFTNEW"))
        assertEquals("TV", deviceModelName("", ""))
    }

    @Test fun `the sign-in date is a long date in the App language`() {
        val at = Instant.parse("2026-11-03T20:00:00Z")
        assertTrue(signInDate(at, Locale.ENGLISH), signInDate(at, Locale.ENGLISH).matches(Regex("November [34], 2026")))
        assertTrue(signInDate(at, Locale.FRENCH), signInDate(at, Locale.FRENCH).matches(Regex("[34] novembre 2026")))
    }
}
