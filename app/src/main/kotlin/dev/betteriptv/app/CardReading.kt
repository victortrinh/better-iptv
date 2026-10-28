package dev.betteriptv.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.betteriptv.core.Library
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleSummary
import dev.betteriptv.core.VersionAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withContext

/**
 * The attributes [title]'s card shows, updated when Background reading Confirms its Version. While
 * the card is composed it counts as on screen, so Background reading reads it first: a lazy list
 * composes the cards in view and one or two beyond.
 */
@Composable
fun cardBest(library: Library, title: TitleSummary, contentLanguages: List<String>): VersionAttributes {
    DisposableEffect(library, title.id) {
        CardsOnScreen.add(library, title.id)
        onDispose { CardsOnScreen.remove(library, title.id) }
    }
    var best by remember(title) { mutableStateOf(title.best) }
    LaunchedEffect(title.id, contentLanguages) {
        library.confirmations.filter { it == title.id }.collect {
            withContext(Dispatchers.IO) { library.cardBest(title.id, contentLanguages) }?.let { best = it }
        }
    }
    return best
}

/** The cards composed right now, in the order they came on screen. Used from the main thread only. */
private object CardsOnScreen {
    private val shown = LinkedHashMap<TitleId, Int>()

    fun add(library: Library, id: TitleId) {
        // The same Title can show twice, in two home rows.
        shown[id] = (shown[id] ?: 0) + 1
        library.onScreen(shown.keys.toList())
    }

    fun remove(library: Library, id: TitleId) {
        val count = shown[id] ?: return
        if (count > 1) shown[id] = count - 1 else shown.remove(id)
        library.onScreen(shown.keys.toList())
    }
}
