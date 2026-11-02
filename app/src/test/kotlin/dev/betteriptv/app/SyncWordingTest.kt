package dev.betteriptv.app

import android.content.Context
import dev.betteriptv.core.CompletedSync
import dev.betteriptv.core.MatchingStatus
import dev.betteriptv.core.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

/** Settings › Catalog's Sync status line while a Sync's Titles regroup, then once they have (#271). */
@RunWith(RobolectricTestRunner::class)
class SyncWordingTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val synced = CompletedSync(Instant.now(), Duration.ofMillis(239_700), 150, emptyList())

    @Test
    fun saysTitlesAreStillRegroupingAndHowFarThenThatTheSyncIsDone() {
        assertEquals("Synced just now in 239.7 s. Still regrouping titles: finding the names to check.", context.describe(SyncStatus.Synced(synced), MatchingStatus(0, null)))
        assertEquals("Synced just now in 239.7 s. Still regrouping titles: 1,250 of 2,385 names checked.", context.describe(SyncStatus.Synced(synced), MatchingStatus(1_250, 2_385)))
        assertEquals("Synced just now in 239.7 s. Still regrouping titles: 0 of 1 name checked.", context.describe(SyncStatus.Synced(synced), MatchingStatus(0, 1)))
        assertEquals("Synced just now in 239.7 s.", context.describe(SyncStatus.Synced(synced)))
    }

    @Test
    @Config(qualifiers = "fr")
    fun saysItInFrench() {
        assertEquals(
            "Synchronisé à l'instant en 239,7 s. Regroupement des titres en cours : 25 noms vérifiés sur 60.",
            context.describe(SyncStatus.Synced(synced), MatchingStatus(25, 60)),
        )
        assertEquals("Synchronisé à l'instant en 239,7 s.", context.describe(SyncStatus.Synced(synced)))
    }
}
