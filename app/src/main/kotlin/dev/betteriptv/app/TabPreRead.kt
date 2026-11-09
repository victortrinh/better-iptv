package dev.betteriptv.app

import android.os.SystemClock
import dev.betteriptv.core.FeaturedTitle
import dev.betteriptv.core.HomeRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * A browsing tab the root screen reads ahead of its first visit ([preReadTabs]): its [state], what
 * its rows are read from ([read]), and its hero's pick ([feature]), none for Live, as its screen
 * reads them ([RowsLayout]).
 */
internal class PreReadTab(
    val state: RowsState,
    val read: suspend () -> List<HomeRow>,
    val feature: (suspend (readDetails: Boolean) -> FeaturedTitle?)? = null,
)

/**
 * Reads [tabs] ahead, one at a time and in order, so a tab's first visit shows at once as a visit
 * back to it does: its rows, and its hero picked as the next entry's ([RowsState.upNext]) by [hero],
 * which loads its poster. A tab's rows and hero land together, once
 * both are read, so a visit meanwhile reads them as a first entry does. Before each tab it waits for
 * the remote to rest ([idle]), so the reading never competes with focus moving.
 *
 * A tab whose screen has read it ([RowsState.preReadFor] null with rows) is left alone: its screen
 * reads it again on each entry. A tab read ahead for something other than [readingFor] (the
 * languages, Region, Leagues or catalog its rows are read for) is read again.
 */
internal suspend fun preReadTabs(
    tabs: List<PreReadTab>,
    readingFor: Any,
    idle: suspend () -> Unit,
    hero: suspend (feature: suspend (readDetails: Boolean) -> FeaturedTitle?) -> FeaturedTitle?,
) {
    for (tab in tabs) {
        val state = tab.state
        if (state.rows != null && (state.preReadFor == null || state.preReadFor == readingFor)) continue
        idle()
        val (rows, featured) = withContext(Dispatchers.Default) {
            tab.read().withUniqueCards() to tab.feature?.let { hero(it) }
        }
        state.rows = rows
        state.held = null
        if (tab.feature != null) {
            state.upNext = featured
            state.upNextRead = true
        }
        state.preReadFor = readingFor
    }
}

/**
 * When the remote was last pressed, so reading ahead waits for it to rest ([awaitRest]). The
 * activity marks each key ([pressed]).
 */
internal object RemoteRest {
    @Volatile private var lastPressAt = SystemClock.uptimeMillis()

    fun pressed() {
        lastPressAt = SystemClock.uptimeMillis()
    }

    /** Returns once no key has been pressed for [millis]. */
    suspend fun awaitRest(millis: Long) {
        while (true) {
            val since = SystemClock.uptimeMillis() - lastPressAt
            if (since >= millis) return
            delay(millis - since)
        }
    }
}

/** How long the remote rests before a tab is read ahead: focus has settled by then. */
internal const val PRE_READ_REST_MILLIS = 1_500L
