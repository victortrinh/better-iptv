package dev.betteriptv.app

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
fun Modifier.backToTab(tab: FocusRequester): Modifier = focusRestorer().onKeyEvent { event ->
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
