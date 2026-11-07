package dev.betteriptv.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.PinDots
import dev.betteriptv.app.ui.components.PinPad
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.HouseholdPin
import dev.betteriptv.core.PinCheck
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration

/**
 * Asks for the Household PIN (#437) under [title] and [detail], and calls [onRight] once it's
 * typed right. A wrong one empties the dots and says how many tries are left; the fifth locks the
 * pad, which counts the seconds down before it takes digits again. Back is the caller's: it leaves
 * without the PIN.
 */
@Composable
fun AskPin(pin: HouseholdPin, title: String, detail: String?, onRight: () -> Unit) {
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableIntStateOf(0) }
    var triesLeft by remember { mutableStateOf<Int?>(null) }
    // Whole seconds left of a lock, counted down here; the PIN itself still says no until it ends.
    var lockLeft by remember { mutableStateOf(pin.lockedFor()?.let(::wholeSeconds)) }
    var checking by remember { mutableStateOf(false) }
    val locked = lockLeft
    if (locked != null) {
        LaunchedEffect(Unit) {
            var left = locked
            while (left > 0) {
                delay(1_000)
                left--
                lockLeft = left
            }
            lockLeft = null
            triesLeft = null
        }
    }
    PinEntry(
        title,
        detail,
        message = when {
            locked != null -> stringResource(R.string.pin_locked, locked)
            triesLeft != null -> pluralStringResource(R.plurals.pin_wrong, triesLeft!!, triesLeft!!)
            else -> null
        },
        error = locked != null || triesLeft != null,
        enabled = locked == null && !checking,
        attempt = attempt,
        onPin = { typed ->
            checking = true
            scope.launch {
                val check = pin.check(typed)
                checking = false
                when (check) {
                    PinCheck.Right -> onRight()
                    is PinCheck.Wrong -> {
                        triesLeft = check.triesLeft
                        attempt++
                    }
                    is PinCheck.Locked -> {
                        lockLeft = wholeSeconds(pin.lockedFor() ?: HouseholdPin.LOCK)
                        attempt++
                    }
                }
            }
        },
    )
}

/**
 * Chooses a Household PIN: typed under [title] and [detail], then again to be sure; a second that
 * doesn't match starts over, saying so. [onChosen] gets the PIN typed twice.
 */
@Composable
fun ChoosePin(title: String, detail: String?, onChosen: (String) -> Unit) {
    var first by remember { mutableStateOf<String?>(null) }
    var mismatch by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val chosen = first
    PinEntry(
        if (chosen == null) title else stringResource(R.string.pin_again),
        if (chosen == null) detail else null,
        message = if (mismatch) stringResource(R.string.pin_mismatch) else null,
        error = mismatch,
        enabled = true,
        attempt = attempt,
        onPin = { typed ->
            attempt++
            when {
                chosen == null -> {
                    first = typed
                    mismatch = false
                }
                typed == chosen -> onChosen(typed)
                else -> {
                    first = null
                    mismatch = true
                }
            }
        },
    )
}

/**
 * A PIN page: [title] in headline type, [detail] in muted body under it, the four dots, the
 * [PinPad] with focus on 1, and a line under it for [message] (in the error colour when [error]),
 * kept even when empty so nothing moves. The fourth digit gives the PIN to [onPin]; each new
 * [attempt] empties the dots.
 */
@Composable
private fun PinEntry(title: String, detail: String?, message: String?, error: Boolean, enabled: Boolean, attempt: Int, onPin: (String) -> Unit) {
    val entry = remember { FocusRequester() }
    LaunchedEffect(Unit) { entry.requestFocus() }
    var typed by remember { mutableStateOf("") }
    // A new attempt starts with no digits; the pad, and its focus, stay.
    LaunchedEffect(attempt) { typed = "" }
    Column(Modifier.testTag(PIN_PAGE_TAG), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsHeading(title)
            if (detail != null) Text(detail, style = Type.body, color = Colors.inkMuted)
        }
        key(attempt) { PinDots(typed.length, HouseholdPin.LENGTH, Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp)) }
        PinPad(
            onDigit = { digit ->
                if (typed.length < HouseholdPin.LENGTH) {
                    typed += digit
                    if (typed.length == HouseholdPin.LENGTH) onPin(typed)
                }
            },
            onDelete = { typed = typed.dropLast(1) },
            enabled = enabled && typed.length < HouseholdPin.LENGTH,
            entry = entry,
        )
        Text(
            message.orEmpty(),
            style = Type.caption,
            color = if (error) Colors.error else Colors.inkMuted,
            modifier = Modifier.width(PIN_MESSAGE_WIDTH).heightIn(min = 36.dp),
        )
    }
}

/** A PIN page in place of the Settings column, as far in. */
@Composable
fun PinPage(content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = Spacing.screenX, vertical = 20.dp).width(SETTINGS_WIDTH)) { content() }
}

private fun wholeSeconds(duration: Duration): Long = (duration.toMillis() + 999) / 1_000

internal const val PIN_PAGE_TAG = "pin page"

private val PIN_MESSAGE_WIDTH = 360.dp
