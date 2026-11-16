package dev.betteriptv.app

import dev.betteriptv.core.CompletedSync
import dev.betteriptv.core.SyncStatus
import dev.betteriptv.core.SyncStep
import dev.betteriptv.core.Unreachable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Clock
import kotlin.time.Instant

/** The first-Sync checklist: its lines for each status, and when it shows (only until the Channels land, on every tab). */
class SyncChecklistItemsTest {
    private val now = Clock.System.now()
    private val synced = CompletedSync(now, 1.seconds, 150, emptyList())

    @Test fun `before any Sync, every step is pending`() {
        assertEquals(listOf(StepState.Pending, StepState.Pending, StepState.Pending, StepState.Pending), checklistItems(SyncStatus.NeverSynced()).map { it.state })
    }

    @Test fun `Channels landed by an app closed mid-Sync are done`() {
        assertEquals(listOf(StepState.Done, StepState.Pending, StepState.Pending, StepState.Pending), checklistItems(SyncStatus.NeverSynced(now)).map { it.state })
    }

    @Test fun `the steps before the one running are done, the rest pending`() {
        val items = checklistItems(SyncStatus.Running(null, now, SyncStep.Shows, 200))
        assertEquals(listOf(SyncStep.Channels, SyncStep.Movies, SyncStep.Shows, SyncStep.Saving), items.map { it.step })
        assertEquals(listOf(StepState.Done, StepState.Done, StepState.Running, StepState.Pending), items.map { it.state })
    }

    @Test fun `a failed Sync marks its step failed`() {
        assertEquals(
            listOf(StepState.Done, StepState.Failed, StepState.Pending, StepState.Pending),
            checklistItems(SyncStatus.Failed(Unreachable("x"), null, now, SyncStep.Movies)).map { it.state },
        )
    }

    @Test fun `a finished Sync has every step done`() {
        assertEquals(List(4) { StepState.Done }, checklistItems(SyncStatus.Synced(synced)).map { it.state })
        assertEquals(List(4) { StepState.Done }, checklistItems(SyncStatus.Ready(synced, now)).map { it.state })
    }

    @Test fun `the checklist shows until the Channels land`() {
        assertTrue(showsChecklist(SyncStatus.NeverSynced()))
        assertTrue(showsChecklist(SyncStatus.Running(null, null, SyncStep.Channels, 10)))
        assertTrue(showsChecklist(SyncStatus.Failed(Unreachable("x"), null, null, SyncStep.Channels)))
    }

    @Test fun `once the Channels have landed no tab shows the checklist, whatever the Sync is doing`() {
        SyncStep.entries.forEach { step ->
            assertFalse(showsChecklist(SyncStatus.Running(null, now, step, 1)))
            assertFalse(showsChecklist(SyncStatus.Running(null, now, step, 1, headStartAt = now)))
            assertFalse(showsChecklist(SyncStatus.Failed(Unreachable("x"), null, now, step)))
        }
        assertFalse(showsChecklist(SyncStatus.NeverSynced(now)))
        assertFalse(showsChecklist(SyncStatus.NeverSynced(now, headStartAt = now)))
    }

    @Test fun `a later Sync never shows the checklist`() {
        assertFalse(showsChecklist(SyncStatus.Synced(synced)))
        assertFalse(showsChecklist(SyncStatus.Ready(synced, now)))
        assertFalse(showsChecklist(SyncStatus.Running(synced, now, SyncStep.Channels, 1)))
        assertFalse(showsChecklist(SyncStatus.Failed(Unreachable("x"), synced, now, SyncStep.Channels)))
    }

    @Test fun `a first Sync with its Channels in and no Head start says the movies and shows are on their way`() {
        assertTrue(titlesOnTheirWay(SyncStatus.Running(null, now, SyncStep.Movies, 1)))
        assertTrue(titlesOnTheirWay(SyncStatus.Running(null, now, SyncStep.Saving, 1)))
        assertTrue(titlesOnTheirWay(SyncStatus.NeverSynced(now)))
    }

    @Test fun `the line never shows once a Head start has landed`() {
        assertFalse(titlesOnTheirWay(SyncStatus.Running(null, now, SyncStep.Movies, 1, headStartAt = now)))
        assertFalse(titlesOnTheirWay(SyncStatus.NeverSynced(now, headStartAt = now)))
    }

    @Test fun `nor under the checklist, after a failure, or on a later Sync`() {
        assertFalse(titlesOnTheirWay(SyncStatus.NeverSynced()))
        assertFalse(titlesOnTheirWay(SyncStatus.Running(null, null, SyncStep.Channels, 1)))
        assertFalse(titlesOnTheirWay(SyncStatus.Failed(Unreachable("x"), null, now, SyncStep.Movies)))
        assertFalse(titlesOnTheirWay(SyncStatus.Synced(synced)))
        assertFalse(titlesOnTheirWay(SyncStatus.Running(synced, now, SyncStep.Movies, 1)))
    }
}
