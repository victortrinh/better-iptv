package dev.betteriptv.app

import android.content.Context
import dev.betteriptv.core.NowNext
import dev.betteriptv.core.Programme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import java.time.ZoneId

/** What a Channel's now-and-next line says (#261), beside its row and under its Favourite. */
@RunWith(RobolectricTestRunner::class)
class GuideLabelsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    // A Sunday evening in Toronto (standard time: 01:30 UTC is 8:30 PM).
    private val now = Instant.parse("2026-11-02T01:30:00Z")
    private val toronto = ZoneId.of("America/Toronto")
    private val national = Programme("The National", now - 30.minutes, now + 30.minutes)
    private val dragons = Programme("Dragons' Den", now + 30.minutes, now + 90.minutes)

    private fun label(guide: NowNext) = context.nowNextLabel(guide, now, toronto)

    @Test
    fun theProgrammeOnNowAndWhenTheNextStartsInTheDevicesClock() {
        assertEquals("The National · Next 9:00 PM", label(NowNext(national, dragons)))
    }

    @Test
    fun theProgrammeAloneWhenTheGuideNamesNoNext() {
        assertEquals("The National", label(NowNext(national, null)))
    }

    @Test
    fun theNextProgrammeWhenNothingIsOnNow() {
        assertEquals("Next 9:00 PM · Dragons' Den", label(NowNext(null, dragons)))
    }

    @Test
    fun aNextProgrammeTomorrowCarriesItsWeekday() {
        val late = Programme("Late show", now + 5.hours, now + 6.hours)
        assertEquals("The National · Next Mon 1:30 AM", label(NowNext(national, late)))
    }

    @Test
    @Config(qualifiers = "fr")
    fun inFrenchToo() {
        // The clock's shape is the device's; the words are the App language's.
        assertTrue(label(NowNext(national, dragons)).startsWith("The National · Ensuite "))
        assertTrue(label(NowNext(null, dragons)).startsWith("Ensuite "))
    }
}
