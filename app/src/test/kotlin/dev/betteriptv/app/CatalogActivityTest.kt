package dev.betteriptv.app

import dev.betteriptv.core.CompletedSync
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
        assertNull(catalogActivity(SyncStatus.NeverSynced()))
        assertNull(catalogActivity(SyncStatus.Running(null, null, SyncStep.Channels)))
    }

    @Test fun `a Sync running behind data on screen is Syncing`() {
        assertEquals(CatalogActivity.Syncing, catalogActivity(SyncStatus.Running(null, now, SyncStep.Movies)))
        assertEquals(CatalogActivity.Syncing, catalogActivity(SyncStatus.Running(synced, now, SyncStep.Shows)))
    }

    @Test fun `a held catalog is ready`() {
        assertEquals(CatalogActivity.UpdateReady, catalogActivity(SyncStatus.Ready(synced, now)))
    }

    @Test fun `an idle or failed catalog shows nothing`() {
        assertNull(catalogActivity(SyncStatus.Synced(synced)))
        assertNull(catalogActivity(SyncStatus.Failed(Unreachable("x"), synced, now, SyncStep.Movies)))
    }

    @Test fun `work that finishes says Up to date, unless it failed or nothing showed`() {
        assertEquals(CatalogActivity.UpToDate, afterword(CatalogActivity.UpdateReady, SyncStatus.Synced(synced)))
        assertEquals(CatalogActivity.UpToDate, afterword(CatalogActivity.Syncing, SyncStatus.Synced(synced)))
        assertNull(afterword(CatalogActivity.Syncing, SyncStatus.Failed(Unreachable("x"), synced, now, SyncStep.Movies)))
        assertNull(afterword(null, SyncStatus.Synced(synced)))
        assertNull(afterword(CatalogActivity.UpToDate, SyncStatus.Synced(synced)))
    }
}
