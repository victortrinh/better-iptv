package dev.betteriptv.app

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

/** How long ago a Sync finished, as the Sync status line says it mid-sentence. */
@RunWith(RobolectricTestRunner::class)
class SyncAgoTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val now = Instant.parse("2026-10-30T12:00:00Z")

    private fun ago(elapsed: Duration) = context.ago(now - elapsed, now)

    @Test
    fun saysHowLongAgoInEnglish() {
        assertEquals("just now", ago(Duration.ofSeconds(20)))
        assertEquals("5 minutes ago", ago(Duration.ofMinutes(5)))
        assertEquals("3 hours ago", ago(Duration.ofHours(3)))
        assertEquals("2 days ago", ago(Duration.ofDays(2)))
        assertEquals("Synced 4 minutes ago in 239.7 s.", context.getString(R.string.sync_done, ago(Duration.ofMinutes(4)), "239.7"))
    }

    @Test
    @Config(qualifiers = "fr")
    fun readsMidSentenceInFrench() {
        assertEquals("à l'instant", ago(Duration.ofSeconds(20)))
        assertEquals("il y a 5 minutes", ago(Duration.ofMinutes(5)))
        assertEquals("il y a 2 semaines", ago(Duration.ofDays(15)))
        assertEquals("Synchronisé il y a 4 minutes en 239,7 s.", context.getString(R.string.sync_done, ago(Duration.ofMinutes(4)), "239,7"))
        assertEquals(
            "Synchronisation… 37 % (affichage du catalogue synchronisé il y a 5 minutes)",
            context.getString(R.string.sync_running_progress, "37 %", ago(Duration.ofMinutes(5))),
        )
    }
}
