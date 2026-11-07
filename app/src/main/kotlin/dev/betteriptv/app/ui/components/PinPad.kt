package dev.betteriptv.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.tv.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.R
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Type

/**
 * A digit pad for the D-pad, as the [RemoteKeyboard] is for letters (#437): 1–9 on a 3-column grid
 * as a phone's, then 0 and a wide Delete under them, so the grid has no hole. [entry] focuses 1,
 * where focus lands on arrival. While not [enabled] (the Household PIN locked by wrong tries) the
 * keys keep focus, look muted and do nothing.
 */
@Composable
fun PinPad(onDigit: (Char) -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, entry: FocusRequester? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(PIN_KEY_GAP)) {
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(PIN_KEY_GAP)) {
                row.forEach { digit ->
                    PinKey(digit.toString(), { onDigit(digit) }, enabled, modifier = if (digit == '1' && entry != null) Modifier.focusRequester(entry) else Modifier)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(PIN_KEY_GAP)) {
            PinKey("0", { onDigit('0') }, enabled)
            PinKey(stringResource(R.string.keyboard_delete), onDelete, enabled, width = PIN_KEY * 2 + PIN_KEY_GAP, icon = true)
        }
    }
}

/** [length] dots, the first [entered] filled: how many digits of a PIN are in, never which. */
@Composable
fun PinDots(entered: Int, length: Int, modifier: Modifier = Modifier) {
    Row(
        modifier.testTag(PIN_DOTS_TAG).semantics { contentDescription = "$entered of $length" },
        horizontalArrangement = Arrangement.spacedBy(PIN_DOT_GAP),
    ) {
        repeat(length) { i ->
            Box(
                Modifier.size(PIN_DOT).then(
                    if (i < entered) Modifier.background(Colors.ink, CircleShape) else Modifier.border(1.5.dp, Colors.inkMuted, CircleShape),
                ),
            )
        }
    }
}

@Composable
private fun PinKey(label: String, onClick: () -> Unit, enabled: Boolean, width: Dp = PIN_KEY, modifier: Modifier = Modifier, icon: Boolean = false) {
    FocusSurface(
        { if (enabled) onClick() },
        modifier.size(width, PIN_KEY),
        weight = FocusWeight.Control,
        corner = PIN_KEY / 2,
        container = Colors.surface,
        focusedContainer = Colors.focus,
    ) { focused ->
        val ink = when {
            focused -> Colors.onFocus
            enabled -> Colors.ink
            else -> Colors.inkMuted
        }
        if (icon) {
            Icon(NuageIcons.Backspace, contentDescription = label, tint = ink, modifier = Modifier.align(Alignment.Center).size(PIN_ICON))
        } else {
            Text(label, style = Type.title, color = ink, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center))
        }
    }
}

const val PIN_DOTS_TAG = "pin dots"

/** A digit key: bigger than the keyboard's, the pad being all a PIN page asks for. */
private val PIN_KEY = 48.dp
private val PIN_KEY_GAP = 8.dp
private val PIN_ICON = 22.dp
private val PIN_DOT = 14.dp
private val PIN_DOT_GAP = 16.dp

/** How wide the pad is, its three columns and their gaps. */
val PIN_PAD_WIDTH = PIN_KEY * 3 + PIN_KEY_GAP * 2
