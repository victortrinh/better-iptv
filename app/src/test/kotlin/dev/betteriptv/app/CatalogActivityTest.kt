package dev.betteriptv.app

import dev.betteriptv.core.CompletedSync
import dev.betteriptv.core.MatchingStatus
import dev.betteriptv.core.SyncStatus
import dev.betteriptv.core.SyncStep
import dev.betteriptv.core.Unreachable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** What the tab bar's pill says while the catalog on screen may still change (#304). */
class CatalogActivityTest {
    private val now = Instant.now()
    private val synced = CompletedSync(now, Duration.ofSeconds(1), 150, emptyList())

    @Test fun `nothing shows while the first Sync's checklist does`() {
        assertNull(catalogActivity(SyncStatus.NeverSynced(), null))
        assertNull(catalogActivity(SyncStatus.Running(null, null, SyncStep.Channels), null))
    }

    @Test fun `a Sync running behind data on screen is Syncing`() {
        assertEquals(CatalogActivity.Syncing, catalogActivity(SyncStatus.Running(null, now, SyncStep.Movies), null))
        assertEquals(CatalogActivity.Syncing, catalogActivity(SyncStatus.Running(synced, now, SyncStep.Shows), null))
    }

    @Test fun `a held catalog is ready`() {
        assertEquals(CatalogActivity.UpdateReady, catalogActivity(SyncStatus.Ready(synced, now), null))
    }

    @Test fun `Matching is Checking titles, unless a new Sync runs meanwhile`() {
        assertEquals(CatalogActivity.CheckingTitles, catalogActivity(SyncStatus.Synced(synced), MatchingStatus(0, null)))
        assertEquals(CatalogActivity.Syncing, catalogActivity(SyncStatus.Running(synced, now, SyncStep.Channels), MatchingStatus(10, 20)))
    }

    @Test fun `an idle or failed catalog shows nothing`() {
        assertNull(catalogActivity(SyncStatus.Synced(synced), null))
        assertNull(catalogActivity(SyncStatus.Failed(Unreachable("x"), synced, now, SyncStep.Movies), null))
    }

    @Test fun `work that finishes says Up to date, unless it failed or nothing showed`() {
        assertEquals(CatalogActivity.UpToDate, afterword(CatalogActivity.CheckingTitles, SyncStatus.Synced(synced)))
        assertEquals(CatalogActivity.UpToDate, afterword(CatalogActivity.Syncing, SyncStatus.Synced(synced)))
        assertNull(afterword(CatalogActivity.Syncing, SyncStatus.Failed(Unreachable("x"), synced, now, SyncStep.Movies)))
        assertNull(afterword(null, SyncStatus.Synced(synced)))
        assertNull(afterword(CatalogActivity.UpToDate, SyncStatus.Synced(synced)))
    }
}
