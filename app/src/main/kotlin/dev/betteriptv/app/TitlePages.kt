package dev.betteriptv.app

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * A long list read a page at a time around where the Viewer is, keeping a few pages in memory.
 * Each page is its own state, so a page arriving or dropped redraws only its own cards, not every
 * card on screen. The page either side of those showing is read ahead, so holding Down doesn't
 * reach cards that are still blank. Used from the main thread only.
 */
@Stable
class TitlePages<T>(private val scope: CoroutineScope, private val read: suspend (offset: Int, limit: Int) -> List<T>) {
    private val pages = HashMap<Int, MutableState<List<T>?>>()
    private val loading = HashSet<Int>()

    /** The item at [index], once its page is read; reading it in composition redraws when the page arrives. */
    operator fun get(index: Int): T? = page(index / PAGE_SIZE).value?.getOrNull(index % PAGE_SIZE)

    /** Reads the pages showing items [first] to [last] of [count], and the page either side. */
    fun showing(first: Int, last: Int, count: Int) {
        if (count == 0) return
        val firstPage = (first / PAGE_SIZE - 1).coerceAtLeast(0)
        val lastPage = (last / PAGE_SIZE + 1).coerceAtMost((count - 1) / PAGE_SIZE)
        // Pages in view first, then the one ahead, then the one behind.
        val inView = first / PAGE_SIZE..last / PAGE_SIZE
        (inView + listOf(lastPage, firstPage)).distinct().forEach { load(it, around = first / PAGE_SIZE) }
    }

    /** Pages read and kept, for tests. */
    internal val kept: Set<Int> get() = pages.filterValues { it.value != null }.keys

    private fun page(page: Int) = pages.getOrPut(page) { mutableStateOf(null) }

    /** Reads [page] in the screen's scope: a card scrolled away mustn't cancel it for the cards still showing. */
    private fun load(page: Int, around: Int) {
        if (page(page).value != null || !loading.add(page)) return
        scope.launch {
            try {
                page(page).value = read(page * PAGE_SIZE, PAGE_SIZE)
                val kept = kept
                if (kept.size > PAGES_KEPT) kept.maxBy { abs(it - around) }.let { pages.remove(it)?.value = null }
            } finally {
                loading -= page
            }
        }
    }

    companion object {
        const val PAGE_SIZE = 60
        const val PAGES_KEPT = 10
    }
}
