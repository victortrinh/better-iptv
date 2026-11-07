package dev.betteriptv.app

import android.content.Context
import dev.betteriptv.core.Known
import dev.betteriptv.core.Provenance
import dev.betteriptv.core.Version
import dev.betteriptv.core.VersionAttributes
import dev.betteriptv.core.VersionTableRow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The Versions screen names what choosing a Version costs: "German only" on the 4K whose audio is German alone (#414). */
@RunWith(RobolectricTestRunner::class)
class AudioOnlyLabelTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val german = VersionAttributes(language = Known("de", Provenance.Confirmed), audioLanguages = Known(setOf("de"), Provenance.Confirmed))
    private val only = VersionTableRow(Version(1, "4K-DE - Shadow Chase: Im Netz der Diebe (2025)", german), backup = false, audio = null, only = "de")
    private val plain = VersionTableRow(Version(1, "4K-DE - Shadow Chase: Im Netz der Diebe (2025)", german), backup = false, audio = null)

    @Test
    fun germanOnlyInEnglish() {
        assertEquals("German only", localized(context, "en").versionLine(only).audio)
    }

    @Test
    fun allemandSeulementInFrench() {
        assertEquals("Allemand seulement", localized(context, "fr").versionLine(only).audio)
    }

    @Test
    fun aRowNotMarkedOnlyKeepsItsAudioCellEmptyUnderItsHeading() {
        assertEquals("", localized(context, "en").versionLine(plain).audio)
    }
}
