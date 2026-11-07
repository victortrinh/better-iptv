package dev.betteriptv.app

import android.content.Context
import dev.betteriptv.core.CompletedSync
import dev.betteriptv.core.SyncStatus
import dev.betteriptv.core.SyncStep
import dev.betteriptv.core.Unreachable
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

/** Settings › Catalog's Sync row and Checking titles row, as worded for each status. */
@RunWith(RobolectricTestRunner::class)
class SyncWordingTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val synced = CompletedSync(Instant.now(), Duration.ofMillis(239_700), 150, emptyList(), showListingCount = 45, channelListingCount = 5)

    @Test
    fun namesAFirstSyncsStepAndCountsItsEntries() {
        assertEquals("Syncing… Channels", context.describe(SyncStatus.Running(null, null, SyncStep.Channels)))
        assertEquals("Syncing… Movies · 12,345 entries", context.describe(SyncStatus.Running(null, Instant.now(), SyncStep.Movies, 12_345)))
        assertEquals("Syncing… Saving · 1 entry", context.describe(SyncStatus.Running(null, Instant.now(), SyncStep.Saving, 1)))
    }

    @Test
    fun tellsALaterSyncHowFarItIsByTheLastOnesCounts() {
        assertEquals("Syncing… 50% (showing the catalog from just now)", context.describe(SyncStatus.Running(synced, synced.finishedAt, SyncStep.Movies, 100)))
    }

    @Test
    fun saysAHeldCatalogShowsOnComingBack() {
        assertEquals("Synced just now. It shows when you come back.", context.describe(SyncStatus.Ready(synced, Instant.now())))
    }

    @Test
    fun saysTheSyncIsDone() {
        assertEquals("Synced just now in 239.7 s.", context.describe(SyncStatus.Synced(synced)))
    }

    @Test
    fun namesWhyASyncFailed() {
        assertEquals("Sync failed: Couldn't reach your Provider. x Showing the catalog from just now.", context.describe(SyncStatus.Failed(Unreachable("x"), synced, synced.finishedAt, SyncStep.Movies)))
    }

    @Test
    @Config(qualifiers = "fr")
    fun saysItInFrench() {
        assertEquals("Synchronisation… Films · 12\u202f345 entrées", context.describe(SyncStatus.Running(null, Instant.now(), SyncStep.Movies, 12_345)))
        assertEquals("Synchronisé à l'instant en 239,7 s.", context.describe(SyncStatus.Synced(synced)))
        assertEquals("Synchronisé à l'instant. Il s'affichera à votre retour.", context.describe(SyncStatus.Ready(synced, Instant.now())))
    }
}
