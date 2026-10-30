package dev.betteriptv.app

import android.content.Context
import dev.betteriptv.core.CEA_608
import dev.betteriptv.core.CEA_708
import dev.betteriptv.core.Track
import dev.betteriptv.core.Version
import dev.betteriptv.core.VersionAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** How a live Channel's Tracks and Versions are named. */
@RunWith(RobolectricTestRunner::class)
class LiveLabelsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val hls = Version(1, "CA EN: CBC TORONTO HD", VersionAttributes(), liveFormat = "m3u8")
    private val ts = Version(1, "CA EN: CBC TORONTO HD", VersionAttributes(), backups = listOf(hls), liveFormat = "ts")

    @Test
    fun closedCaptionsAreNamedForWhatTheyAre() {
        assertEquals("Closed captions", context.trackName(Track(null, CEA_608)))
        assertEquals("Closed captions", context.trackName(Track("und", CEA_608)))
        assertEquals("Closed captions · English", context.trackName(Track("eng", CEA_708)))
    }

    @Test
    @Config(qualifiers = "fr")
    fun closedCaptionsAreNamedInFrenchToo() {
        assertEquals("Sous-titres codés", context.trackName(Track(null, CEA_608)))
    }

    @Test
    fun otherTracksKeepTheirLanguage() {
        assertEquals("English", context.trackName(Track("eng")))
        assertEquals("Unknown language", context.trackName(Track(null)))
    }

    @Test
    fun aChannelsVersionRowSaysHowItStreamsInsteadOfAQualityAndALanguage() {
        val line = context.versionLine(ts)

        assertEquals("Live · MPEG-TS", line.live)
        assertEquals(emptyList<Any>(), line.badges)
        assertNull(line.note)
        // Once its HLS Backup plays, the row says so.
        assertEquals("Live · HLS", context.versionLine(ts, playing = hls).live)
    }

    @Test
    fun aMoviesVersionRowKeepsItsColumns() {
        assertNull(context.versionLine(Version(2, "EN - Movie (2025)", VersionAttributes())).live)
    }
}
