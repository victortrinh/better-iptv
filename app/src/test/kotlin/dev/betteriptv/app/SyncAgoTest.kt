package dev.betteriptv.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** How long ago a Sync finished, as the Sync status line says it mid-sentence. */
@RunWith(RobolectricTestRunner::class)
class SyncAgoTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val now = Instant.parse("2026-10-30T12:00:00Z")

    private fun ago(elapsed: Duration) = context.ago(now - elapsed, now)

    @Test
    fun saysHowLongAgoInEnglish() {
        assertEquals("just now", ago(20.seconds))
        assertEquals("5 minutes ago", ago(5.minutes))
        assertEquals("3 hours ago", ago(3.hours))
        assertEquals("2 days ago", ago(2.days))
        assertEquals("Synced 4 minutes ago in 239.7 s.", context.getString(R.string.sync_done, ago(4.minutes), "239.7"))
    }

    @Test
    @Config(qualifiers = "fr")
    fun readsMidSentenceInFrench() {
        assertEquals("à l'instant", ago(20.seconds))
        assertEquals("il y a 5 minutes", ago(5.minutes))
        assertEquals("il y a 2 semaines", ago(15.days))
        assertEquals("Synchronisé il y a 4 minutes en 239,7 s.", context.getString(R.string.sync_done, ago(4.minutes), "239,7"))
        assertEquals(
            "Synchronisation… 37 % (affichage du catalogue synchronisé il y a 5 minutes)",
            context.getString(R.string.sync_running_progress, "37 %", ago(5.minutes)),
        )
    }
}
