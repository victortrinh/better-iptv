package dev.betteriptv.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/**
 * For a browsing screen's cards below the tabs (#146): Back moves focus to the selected [tab]
 * without scrolling, and Down from the tab comes back to the card that had focus. Give each row of
 * cards inside its own [focusRestorer] too, so the card is found again within its row.
 */
fun Modifier.backToTab(tab: FocusRequester): Modifier = focusRestorer().backKeyToTab(tab)

/** Back moves focus to [tab], as in [backToTab], for cards that find their way back by other means. */
fun Modifier.backKeyToTab(tab: FocusRequester): Modifier = onKeyEvent { event ->
    when (backToTab(event.type, event.key)) {
        BackKey.Pass -> false
        BackKey.Hold -> true
        BackKey.MoveToTab -> {
            tab.requestFocus()
            true
        }
    }
}

/** What a key does among the cards: see [backToTab]. */
enum class BackKey { Pass, Hold, MoveToTab }

/**
 * Back moves focus on release, and the press and its repeats are held: moved on the press, the
 * release would land on the tab and reach the system's Back, leaving the screen.
 */
fun backToTab(type: KeyEventType, key: Key): BackKey = when {
    key != Key.Back -> BackKey.Pass
    type == KeyEventType.KeyUp -> BackKey.MoveToTab
    else -> BackKey.Hold
}

/**
 * Focuses the selected [tab] when the screen was reached by picking that tab ([focusTab]), so Left
 * and Right keep walking the tabs. The tab picked goes away with the screen before, leaving nothing
 * focused, and the next press went to the Home tab (#187). Asked as soon as the screen is composed,
 * not after its first frame: that takes half a second on the stick for a grid, long enough for a
 * press to land first.
 */
@Composable
fun FocusTabOnArrival(focusTab: Boolean, tab: FocusRequester) {
    if (focusTab) LaunchedEffect(Unit) {
        tab.requestFocus()
    }
}
