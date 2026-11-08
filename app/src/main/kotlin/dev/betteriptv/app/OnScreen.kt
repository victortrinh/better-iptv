package dev.betteriptv.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

/**
 * Whether the app is on screen: its activity started (not left for Home or another app, nor
 * stopped by the TV going to sleep) and no screensaver [dreaming] over it. On Fire OS 7 (Android 9)
 * the screensaver covers the activity without stopping it.
 */
internal fun onScreen(lifecycle: Lifecycle, dreaming: Flow<Boolean>): Flow<Boolean> =
    combine(lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }, dreaming) { started, dream -> started && !dream }
        .distinctUntilChanged()

/** [onScreen] for the composition's activity. */
@Composable
internal fun rememberOnScreen(): Flow<Boolean> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    return remember(lifecycle, context) { onScreen(lifecycle, context.dreaming()) }
}

/**
 * Runs [block] only while this is true and returns what it returns: turning false cancels it, and
 * turning true again runs it again. Work that holds the Viewer's one Provider stream runs
 * [onScreen] this way, so a stream never plays on behind another app, a screensaver or a sleeping
 * TV (#503).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun <T> Flow<Boolean>.whileTrue(block: suspend () -> T): T =
    distinctUntilChanged().transformLatest { on -> if (on) emit(block()) }.first()

/** Whether a screensaver (a dream) shows, from the system's broadcasts; none when collecting starts. */
private fun Context.dreaming(): Flow<Boolean> = callbackFlow {
    trySend(false)
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            trySend(intent.action == Intent.ACTION_DREAMING_STARTED)
        }
    }
    val actions = IntentFilter().apply {
        addAction(Intent.ACTION_DREAMING_STARTED)
        addAction(Intent.ACTION_DREAMING_STOPPED)
    }
    ContextCompat.registerReceiver(this@dreaming, receiver, actions, ContextCompat.RECEIVER_NOT_EXPORTED)
    awaitClose { unregisterReceiver(receiver) }
}
