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
import androidx.compose.runtime.compositionLocalOf
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
import dev.betteriptv.core.SyncStatus
import kotlinx.coroutines.delay

/** What the tab bar's pill says while the catalog on screen may still change (#304). */
internal enum class CatalogActivity { Syncing, AddingTheRest, UpdateReady, UpToDate }

/**
 * The pill's line for [status]: a Sync running, or its held catalog waiting for the next screen; null
 * while neither is, and while the first Sync's checklist fills the screen, since it says as much. A
 * first Sync whose Channels have landed is adding the rest (ADR 0019): what is on screen, the Head
 * start's Titles too once they land, is only part of the catalog. A failed Sync shows only in Settings.
 */
internal fun catalogActivity(status: SyncStatus): CatalogActivity? = when {
    status.channelsAt == null -> null
    status is SyncStatus.Running -> if (status.last == null) CatalogActivity.AddingTheRest else CatalogActivity.Syncing
    // A first Sync's catalog held under the player lands as it closes: until then the rest is still to come.
    status is SyncStatus.Ready -> if (status.last == null) CatalogActivity.AddingTheRest else CatalogActivity.UpdateReady
    else -> null
}

/**
 * The pill's line on a screen of rows: [status]'s, and, once a first Sync has finished under a pill
 * that [shown] it adding the rest, still that until the screen's rows are the full catalog's
 * ([rowsBehind], [LocalRowsBehind]). The genre rows of a big catalog take seconds to work out, and
 * "Up to date" over the Head start's rows would be untrue (#605).
 */
internal fun catalogActivity(status: SyncStatus, shown: CatalogActivity?, rowsBehind: Boolean): CatalogActivity? =
    catalogActivity(status) ?: CatalogActivity.AddingTheRest.takeIf { status is SyncStatus.Synced && shown == it && rowsBehind }

/**
 * How long the pill waits before it says [next], given what it [shown] and whether that is [visible].
 * A first Sync's pill takes over from the checklist as the Channels land, so it shows at once: a wait
 * left the screen with neither (#605), and that work is never quick. Other work that starts while
 * the screen is up shows after [PILL_APPEARS_AFTER], so a quick one doesn't flash; what follows work
 * already showing changes the line at once.
 */
internal fun appearsAfter(next: CatalogActivity, visible: Boolean, shown: CatalogActivity?): Long = when {
    next == CatalogActivity.AddingTheRest -> 0L
    !visible || shown == CatalogActivity.UpToDate -> PILL_APPEARS_AFTER
    else -> 0L
}

/**
 * Whether the screen's rows are behind what they are read for: read again (a catalog landing, say)
 * and not yet showing. Said by a screen of rows ([RowsLayout]) to its tabs, for the Catalog pill.
 */
internal val LocalRowsBehind = compositionLocalOf { false }

/** Whether a Search that finds nothing should say more is coming: while a first Sync is adding the rest (ADR 0019). */
internal fun stillAddingCatalog(status: SyncStatus): Boolean = catalogActivity(status) == CatalogActivity.AddingTheRest

/** What the pill says once the work it [shown] ends: Up to date, unless the Sync failed or it showed nothing. */
internal fun afterword(shown: CatalogActivity?, status: SyncStatus): CatalogActivity? =
    if (shown != null && shown != CatalogActivity.UpToDate && status !is SyncStatus.Failed) CatalogActivity.UpToDate else null

/**
 * A glass pill at the tab bar's right end, never focusable, saying the rows may still change: a slow
 * pulsing dot, the checklist's, and one caption line. Work already running when the screen opens shows
 * at once, and so does a first Sync's as its checklist goes; other work that starts later shows after
 * [PILL_APPEARS_AFTER], so a quick one doesn't flash ([appearsAfter]). Once it ends, and the screen's rows
 * are those of the catalog that landed ([LocalRowsBehind]), "Up to date" shows for [UP_TO_DATE_FOR], then the pill fades.
 */
@Composable
internal fun CatalogActivityPill(library: Library, modifier: Modifier = Modifier) {
    val status by library.syncStatus.collectAsStateWithLifecycle()
    val latestStatus by rememberUpdatedState(status)
    // What the pill says, kept while it fades out; [visible] says whether it shows.
    var shown by remember { mutableStateOf(catalogActivity(status)) }
    var visible by remember { mutableStateOf(shown != null) }
    val current = catalogActivity(status, shown.takeIf { visible }, LocalRowsBehind.current)
    LaunchedEffect(current) {
        if (current != null) {
            delay(appearsAfter(current, visible, shown))
            shown = current
            visible = true
        } else {
            // Wait a beat before calling it done, so a held catalog landing doesn't flash "Up to date" twice.
            delay(PILL_APPEARS_AFTER)
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

internal fun CatalogActivity.label() = when (this) {
    CatalogActivity.Syncing -> R.string.catalog_activity_syncing
    CatalogActivity.AddingTheRest -> R.string.catalog_activity_adding_the_rest
    CatalogActivity.UpdateReady -> R.string.catalog_activity_ready
    CatalogActivity.UpToDate -> R.string.catalog_activity_up_to_date
}

/** Room between the tab capsule and the right margin, with the French "ready" and "adding the rest" lines in it. */
private val PILL_MAX_WIDTH = 240.dp
private val DOT_SIZE = 6.dp
private const val FADE_MS = 300
internal const val PILL_APPEARS_AFTER = 1_000L
private const val UP_TO_DATE_FOR = 3_000L
