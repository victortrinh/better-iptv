package dev.betteriptv.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.NuageButton
import dev.betteriptv.app.ui.components.PulsingDot
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.SyncStatus
import dev.betteriptv.core.SyncStep

/**
 * A first Sync's checklist, centred where the rows will be: one line per step from [from] on,
 * marked done, running, pending or failed, with no counts, so the Viewer sees how far through the
 * list the Sync is and what it is doing, readable from the couch. Only the running step's dot
 * moves, a slow pulse. A failed step names why under it, with Retry, which takes focus. Home and
 * Live show it until the Channels land; Movies and Shows, from Movies on, until the Titles do.
 */
@Composable
internal fun SyncChecklist(status: SyncStatus, from: SyncStep, onRetry: () -> Unit) {
    val items = remember(status, from) { checklistItems(status, from) }
    val failed = status as? SyncStatus.Failed
    val retry = remember { FocusRequester() }
    if (failed != null) LaunchedEffect(failed) { retry.requestFocus() }
    Box(Modifier.fillMaxSize().padding(horizontal = Spacing.screenX, vertical = Spacing.screenY), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = CHECKLIST_WIDTH)) {
            Text(stringResource(R.string.sync_checklist_title), style = Type.headline, color = Colors.ink)
            Spacer(Modifier.height(16.dp))
            items.forEach { item ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    StepDot(item.state)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(item.step.label()) + if (item.state == StepState.Running) "…" else "",
                        style = Type.body,
                        color = when (item.state) {
                            StepState.Done, StepState.Running -> Colors.ink
                            StepState.Pending -> Colors.inkMuted
                            StepState.Failed -> Colors.error
                        },
                    )
                }
                if (item.state == StepState.Failed && failed != null) {
                    Text(
                        LocalContext.current.describe(failed.problem),
                        style = Type.caption,
                        color = Colors.error,
                        modifier = Modifier.padding(start = DOT_SIZE + 12.dp, bottom = 8.dp),
                    )
                    NuageButton(stringResource(R.string.sync_retry), onRetry, Modifier.padding(start = DOT_SIZE + 12.dp).focusRequester(retry))
                }
            }
        }
    }
}

/** A step's mark: filled ink when done, pulsing when running, a muted ring while pending, failure red when failed. */
@Composable
private fun StepDot(state: StepState) {
    val modifier = Modifier.size(DOT_SIZE)
    when (state) {
        StepState.Done -> Box(modifier.background(Colors.ink, CircleShape))
        StepState.Failed -> Box(modifier.background(Colors.error, CircleShape))
        StepState.Pending -> Box(modifier.border(1.5.dp, Colors.inkMuted, CircleShape))
        StepState.Running -> PulsingDot(DOT_SIZE)
    }
}

private fun SyncStep.label() = when (this) {
    SyncStep.Channels -> R.string.sync_step_channels
    SyncStep.Movies -> R.string.sync_step_movies
    SyncStep.Shows -> R.string.sync_step_shows
    SyncStep.Saving -> R.string.sync_step_saving
}

/** Wide enough for the French failure line on two lines at caption size. */
private val CHECKLIST_WIDTH = 440.dp
private val DOT_SIZE = 10.dp
