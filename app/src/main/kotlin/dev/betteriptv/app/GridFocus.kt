package dev.betteriptv.app

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.Stable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Moves focus in a grid of cards a row at a time by index when Up or Down is pressed.
 *
 * Compose's own focus search only looks among the cards laid out. Holding Down, the next press
 * comes while the grid is still scrolling to the row just reached, before the row after it is laid
 * out; the search then lays out cards past the edge one at a time and takes the first one below,
 * so focus drifted to the first column, or the press was lost (#33). Here the card a row away is
 * focused directly, once the grid has laid it out.
 */
@Stable
class GridFocus(private val grid: LazyGridState, private val scope: CoroutineScope) {
    private val requesters = HashMap<Int, FocusRequester>()
    private var focused = -1
    private var pending: Job? = null

    /** The card at [index] gained focus, from a key or otherwise. */
    fun focused(index: Int) {
        focused = index
    }

    /** A card's focus target, known here while the card is composed. */
    fun card(index: Int): Card = Card(index)

    /** Handles Up and Down presses in a grid of [count] cards; other keys go on to the focus search. */
    fun onKey(event: KeyEvent, count: Int): Boolean {
        if (event.type != KeyEventType.KeyDown || focused < 0) return false
        val down = when (event.key) {
            Key.DirectionDown -> true
            Key.DirectionUp -> false
            else -> return false
        }
        val columns = grid.layoutInfo.visibleItemsInfo.maxOfOrNull { it.column + 1 } ?: return false
        val target = rowMove(focused, columns, count, down) ?: return false
        focused = target
        pending?.cancel()
        val requester = requesters[target]
        if (requester != null && grid.layoutInfo.visibleItemsInfo.any { it.index == target }) {
            requester.requestFocus()
        } else {
            // Not laid out yet: bring its row in at the edge it comes from, then focus it, which
            // scrolls it the rest of the way like any focus move.
            val rowStart = target - target % columns
            grid.requestScrollToItem(if (down) (rowStart - columns).coerceAtLeast(0) else rowStart)
            pending = scope.launch {
                snapshotFlow { grid.layoutInfo.visibleItemsInfo.any { it.index == target } && target in requesters }.first { it }
                requesters[target]?.requestFocus()
            }
        }
        return true
    }

    /** Registers a composed card's [requester] with the grid while it stays composed. */
    inner class Card(private val index: Int) : RememberObserver {
        val requester = FocusRequester()

        override fun onRemembered() {
            requesters[index] = requester
        }

        override fun onForgotten() {
            requesters.remove(index, requester)
        }

        override fun onAbandoned() = Unit
    }
}

/**
 * The card a row below ([down]) or above [focused] in a grid of [count] cards in [columns], or null
 * when focus should leave the grid. Down from the row before a shorter last row goes to its last card.
 */
fun rowMove(focused: Int, columns: Int, count: Int, down: Boolean): Int? {
    if (down) {
        val target = focused + columns
        if (target < count) return target
        val lastRowStart = (count - 1) - (count - 1) % columns
        return if (focused < lastRowStart) count - 1 else null
    }
    return (focused - columns).takeIf { it >= 0 }
}
