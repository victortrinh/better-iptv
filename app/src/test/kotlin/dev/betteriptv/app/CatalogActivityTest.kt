package dev.betteriptv.app

import dev.betteriptv.core.CompletedSync
import dev.betteriptv.core.SyncStatus
import dev.betteriptv.core.SyncStep
import dev.betteriptv.core.Unreachable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Clock
import kotlin.time.Instant

/** What the tab bar's pill says while the catalog on screen may still change (#304). */
class CatalogActivityTest {
    private val now = Clock.System.now()
    private val synced = CompletedSync(now, 1.seconds, 150, emptyList())

    @Test fun `nothing shows while the first Sync's checklist does`() {
        assertNull(catalogActivity(SyncStatus.NeverSynced()))
        assertNull(catalogActivity(SyncStatus.Running(null, null, SyncStep.Channels)))
    }

    @Test fun `a first Sync is adding the rest from its Channels landing, with a Head start or without`() {
        assertEquals(CatalogActivity.AddingTheRest, catalogActivity(SyncStatus.Running(null, now, SyncStep.Movies)))
        assertEquals(CatalogActivity.AddingTheRest, catalogActivity(SyncStatus.Running(null, now, SyncStep.Movies, headStartAt = now)))
        assertEquals(CatalogActivity.AddingTheRest, catalogActivity(SyncStatus.Running(null, now, SyncStep.Saving, 12_345, headStartAt = now)))
    }

    @Test fun `a later Sync running behind the catalog on screen is Syncing`() {
        assertEquals(CatalogActivity.Syncing, catalogActivity(SyncStatus.Running(synced, now, SyncStep.Channels)))
        assertEquals(CatalogActivity.Syncing, catalogActivity(SyncStatus.Running(synced, now, SyncStep.Shows)))
    }

    @Test fun `a first Sync that stopped or failed with its Head start on screen shows nothing`() {
        assertNull(catalogActivity(SyncStatus.NeverSynced(now, now)))
        assertNull(catalogActivity(SyncStatus.Failed(Unreachable("x"), null, now, SyncStep.Shows, now)))
    }

    @Test fun `Search says the catalog is still being added only while a first Sync adds the rest`() {
        assertTrue(stillAddingCatalog(SyncStatus.Running(null, now, SyncStep.Movies)))
        assertTrue(stillAddingCatalog(SyncStatus.Running(null, now, SyncStep.Shows, headStartAt = now)))
        assertFalse(stillAddingCatalog(SyncStatus.Synced(synced)))
        assertFalse(stillAddingCatalog(SyncStatus.Running(synced, now, SyncStep.Movies)))
        assertFalse(stillAddingCatalog(SyncStatus.Ready(synced, now)))
        assertFalse(stillAddingCatalog(SyncStatus.Failed(Unreachable("x"), null, now, SyncStep.Shows, now)))
    }

    @Test fun `a held catalog is ready`() {
        assertEquals(CatalogActivity.UpdateReady, catalogActivity(SyncStatus.Ready(synced, now)))
    }

    @Test fun `a first Sync's catalog that waits for the player to close is still adding the rest`() {
        val waiting = SyncStatus.Ready(null, now, landedChannelsAt = now, headStartAt = now)
        assertEquals(CatalogActivity.AddingTheRest, catalogActivity(waiting))
        assertTrue(stillAddingCatalog(waiting))
    }

    @Test fun `an idle or failed catalog shows nothing`() {
        assertNull(catalogActivity(SyncStatus.Synced(synced)))
        assertNull(catalogActivity(SyncStatus.Failed(Unreachable("x"), synced, now, SyncStep.Movies)))
    }

    @Test fun `work that finishes says Up to date, unless it failed or nothing showed`() {
        assertEquals(CatalogActivity.UpToDate, afterword(CatalogActivity.UpdateReady, SyncStatus.Synced(synced)))
        assertEquals(CatalogActivity.UpToDate, afterword(CatalogActivity.Syncing, SyncStatus.Synced(synced)))
        assertEquals(CatalogActivity.UpToDate, afterword(CatalogActivity.AddingTheRest, SyncStatus.Synced(synced)))
        assertNull(afterword(CatalogActivity.AddingTheRest, SyncStatus.Failed(Unreachable("x"), null, now, SyncStep.Movies, now)))
        assertNull(afterword(CatalogActivity.Syncing, SyncStatus.Failed(Unreachable("x"), synced, now, SyncStep.Movies)))
        assertNull(afterword(null, SyncStatus.Synced(synced)))
        assertNull(afterword(CatalogActivity.UpToDate, SyncStatus.Synced(synced)))
    }

    @Test fun `the pill takes over from the first Sync's checklist at once, and other work waits a beat`() {
        // The Channels land and the checklist goes: no moment with neither (#605).
        assertEquals(0L, appearsAfter(CatalogActivity.AddingTheRest, visible = false, shown = null))
        // A later Sync that starts while the screen is up may be quick: it shows after a beat, so it doesn't flash.
        assertEquals(PILL_APPEARS_AFTER, appearsAfter(CatalogActivity.Syncing, visible = false, shown = null))
        assertEquals(PILL_APPEARS_AFTER, appearsAfter(CatalogActivity.Syncing, visible = true, shown = CatalogActivity.UpToDate))
        // What follows work already showing changes its line at once.
        assertEquals(0L, appearsAfter(CatalogActivity.UpdateReady, visible = true, shown = CatalogActivity.Syncing))
    }

    @Test fun `a first Sync that finished is still adding the rest until the screen's rows are the full catalog's`() {
        // The genre rows of a big catalog take seconds to read: the pill doesn't say Up to date over the Head start's (#605).
        assertEquals(CatalogActivity.AddingTheRest, catalogActivity(SyncStatus.Synced(synced), shown = CatalogActivity.AddingTheRest, rowsBehind = true))
        assertNull(catalogActivity(SyncStatus.Synced(synced), shown = CatalogActivity.AddingTheRest, rowsBehind = false))
        // Rows read again at any other time (Live's Games, a screen just entered) show no pill.
        assertNull(catalogActivity(SyncStatus.Synced(synced), shown = null, rowsBehind = true))
        assertNull(catalogActivity(SyncStatus.Synced(synced), shown = CatalogActivity.UpToDate, rowsBehind = true))
        assertNull(catalogActivity(SyncStatus.Synced(synced), shown = CatalogActivity.Syncing, rowsBehind = true))
        // A first Sync that failed says so in Settings, whatever the rows are doing.
        assertNull(catalogActivity(SyncStatus.Failed(Unreachable("x"), null, now, SyncStep.Shows, now), shown = CatalogActivity.AddingTheRest, rowsBehind = true))
        // While it runs, the status says it all.
        assertEquals(CatalogActivity.AddingTheRest, catalogActivity(SyncStatus.Running(null, now, SyncStep.Shows), shown = null, rowsBehind = false))
    }
}
