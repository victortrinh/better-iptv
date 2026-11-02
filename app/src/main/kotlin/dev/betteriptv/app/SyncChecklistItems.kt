package dev.betteriptv.app

import dev.betteriptv.core.SyncStatus
import dev.betteriptv.core.SyncStep

/** One line of the first-Sync checklist: a step and where it is. */
data class ChecklistItem(val step: SyncStep, val state: StepState)

enum class StepState { Done, Running, Pending, Failed }

/**
 * The checklist's lines from [from] on, as [status] has them: the steps before the one running are
 * Done, the one running Running, the rest Pending; a failed Sync marks its step Failed. Before any
 * Sync, Channels that landed (the app closed between a first Sync's stages) are Done already. Home
 * and Live list every step; Movies and Shows start at Movies, once the Channels are theirs to skip.
 */
fun checklistItems(status: SyncStatus, from: SyncStep = SyncStep.Channels): List<ChecklistItem> {
    val steps = SyncStep.entries.filter { it >= from }
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
