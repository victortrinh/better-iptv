package dev.betteriptv.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.PulsingDot
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.Library
import dev.betteriptv.core.MatchingStatus
import dev.betteriptv.core.SyncStatus
import kotlinx.coroutines.delay

/** What the tab bar's pill says while the catalog on screen may still change (#304). */
internal enum class CatalogActivity { Syncing, UpdateReady, CheckingTitles, UpToDate }

/**
 * The pill's line for [status] and [matching]: a Sync running, its held catalog waiting for the next
 * screen, or Matching regrouping the Titles; null while none is, and while the first Sync's checklist
 * fills the screen, since it says as much. A failed Sync shows only in Settings.
 */
internal fun catalogActivity(status: SyncStatus, matching: MatchingStatus?): CatalogActivity? = when {
    status.channelsAt == null -> null
    status is SyncStatus.Running -> CatalogActivity.Syncing
    status is SyncStatus.Ready -> CatalogActivity.UpdateReady
    matching != null -> CatalogActivity.CheckingTitles
    else -> null
}

/** What the pill says once the work it [shown] ends: Up to date, unless the Sync failed or it showed nothing. */
internal fun afterword(shown: CatalogActivity?, status: SyncStatus): CatalogActivity? =
    if (shown != null && shown != CatalogActivity.UpToDate && status !is SyncStatus.Failed) CatalogActivity.UpToDate else null

/**
 * A glass pill at the tab bar's right end, never focusable, saying the rows may still change: a slow
 * pulsing dot, the checklist's, and one caption line. Work already running when the screen opens shows
 * at once; work that starts later shows after [APPEAR_AFTER], so a Matching with nothing to ask doesn't
 * flash. Once it ends, "Up to date" shows for [UP_TO_DATE_FOR], then the pill fades.
 */
@Composable
internal fun CatalogActivityPill(library: Library, modifier: Modifier = Modifier) {
    val status by library.syncStatus.collectAsStateWithLifecycle()
    val matching by library.matchingStatus.collectAsStateWithLifecycle()
    val current = catalogActivity(status, matching)
    val latestStatus by rememberUpdatedState(status)
    // What the pill says, kept while it fades out; [visible] says whether it shows.
    var shown by remember { mutableStateOf(current) }
    var visible by remember { mutableStateOf(current != null) }
    LaunchedEffect(current) {
        if (current != null) {
            if (!visible || shown == CatalogActivity.UpToDate) delay(APPEAR_AFTER)
            shown = current
            visible = true
        } else {
            // A held catalog landing goes on to Matching: wait a beat before calling it done.
            delay(APPEAR_AFTER)
            val after = afterword(shown.takeIf { visible }, latestStatus)
            if (after != null) {
                shown = after
                delay(UP_TO_DATE_FOR)
            }
            visible = false
        }
    }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(FADE_MS), label = "pill")
    val words = shown ?: return
    val capsule = RoundedCornerShape(50)
    Row(
        modifier
            .graphicsLayer { this.alpha = alpha }
            .widthIn(max = PILL_MAX_WIDTH)
            .background(Colors.surface, capsule)
            .padding(horizontal = Spacing.m, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (words == CatalogActivity.UpToDate) {
            Box(Modifier.size(DOT_SIZE).background(Colors.ink, CircleShape))
        } else {
            PulsingDot(DOT_SIZE)
        }
        Spacer(Modifier.width(Spacing.s))
        // Glass over artwork: white at 85%, as the inactive tabs, keeps 4.5:1.
        Text(stringResource(words.label()), style = Type.caption, color = Colors.ink.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun CatalogActivity.label() = when (this) {
    CatalogActivity.Syncing -> R.string.catalog_activity_syncing
    CatalogActivity.UpdateReady -> R.string.catalog_activity_ready
    CatalogActivity.CheckingTitles -> R.string.catalog_activity_checking
    CatalogActivity.UpToDate -> R.string.catalog_activity_up_to_date
}

/** Room between the tab capsule and the right margin, with the French "ready" line in it. */
private val PILL_MAX_WIDTH = 240.dp
private val DOT_SIZE = 6.dp
private const val FADE_MS = 300
private const val APPEAR_AFTER = 1_000L
private const val UP_TO_DATE_FOR = 3_000L
