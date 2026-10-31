package dev.betteriptv.app

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer

/**
 * Focus coming into a row of cards: back to the card focused there last, and, the first time, to
 * [entry], the card at the left margin ([entryAt]), not the one nearest where focus came from.
 */
fun Modifier.rowFocus(entry: FocusRequester): Modifier = focusRestorer(entry)

/**
 * Makes the card at [place] the row's [entry] while it sits at the left margin of [rail]: its first
 * card, or on a looping rail the one after the card peeking in the margin.
 */
@Composable
fun Modifier.entryAt(rail: LazyListState, place: Int, entry: FocusRequester): Modifier {
    val atMargin by remember(rail, place) { derivedStateOf { rail.firstVisibleItemIndex == place } }
    return if (atMargin) focusRequester(entry) else this
}
