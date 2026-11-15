package dev.betteriptv.app

import dev.betteriptv.core.SyncStatus
import dev.betteriptv.core.SyncStep

/** One line of the first-Sync checklist: a step and where it is. */
data class ChecklistItem(val step: SyncStep, val state: StepState)

enum class StepState { Done, Running, Pending, Failed }

/**
 * Whether a first Sync's checklist shows where a screen's rows will be: only until its Channels land,
 * on every tab (ADR 0019). From then on Home, Movies, Shows and Live show what has landed; a Sync
 * that fails later says so in Settings, with Retry.
 */
fun showsChecklist(status: SyncStatus): Boolean = status.channelsAt == null

/**
 * The checklist's lines, as [status] has them: the steps before the one running are Done, the one
 * running Running, the rest Pending; a failed Sync marks its step Failed. Before any Sync, Channels
 * that landed (the app closed between a first Sync's stages) are Done already.
 */
fun checklistItems(status: SyncStatus): List<ChecklistItem> {
    val steps = SyncStep.entries
    return steps.map { step ->
        val state = when (status) {
            is SyncStatus.Synced, is SyncStatus.Ready -> StepState.Done
            is SyncStatus.NeverSynced -> if (step == SyncStep.Channels && status.channelsAt != null) StepState.Done else StepState.Pending
            is SyncStatus.Running -> when {
                step < status.step -> StepState.Done
                step == status.step -> StepState.Running
                else -> StepState.Pending
            }
            is SyncStatus.Failed -> when {
                step < status.step -> StepState.Done
                step == status.step -> StepState.Failed
                else -> StepState.Pending
            }
        }
        ChecklistItem(step, state)
    }
}
