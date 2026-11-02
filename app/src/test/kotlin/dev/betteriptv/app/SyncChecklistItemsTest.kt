package dev.betteriptv.app

import dev.betteriptv.core.CompletedSync
import dev.betteriptv.core.SyncStatus
import dev.betteriptv.core.SyncStep
import dev.betteriptv.core.Unreachable
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** The first-Sync checklist's lines for each status, on Home and Live (every step) and on Movies and Shows (from Movies). */
class SyncChecklistItemsTest {
    private val now = Instant.now()
    private val synced = CompletedSync(now, Duration.ofSeconds(1), 150, emptyList())

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

    @Test fun `Movies and Shows list from Movies on, never the Channels`() {
        val items = checklistItems(SyncStatus.Running(null, now, SyncStep.Movies, 1), from = SyncStep.Movies)
        assertEquals(listOf(SyncStep.Movies, SyncStep.Shows, SyncStep.Saving), items.map { it.step })
        assertEquals(listOf(StepState.Running, StepState.Pending, StepState.Pending), items.map { it.state })
    }
}
