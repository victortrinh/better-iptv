package dev.betteriptv.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** How many downloaded subtitles the player offers per language: another file is often the fix for one badly off. */
const val DOWNLOADS_PER_LANGUAGE = 3

/**
 * The downloaded subtitle each language suggests for [plan]'s Version, by [SubtitleOffer.id]: the
 * first of [offers] in its language ripped from the Version's Source (a Netflix release for a
 * Netflix copy), as it is likeliest timed to it. Only a suggestion: the cloud's order, and which file
 * shows by itself, stay as they are. Empty for a Version without a Source.
 */
fun suggestedSubtitles(plan: PlayPlan, offers: List<SubtitleOffer>): Set<String> {
    val source = plan.version.attributes.source?.value ?: return emptySet()
    return offers.groupBy { it.language }.values.mapNotNull { language -> language.firstOrNull { fromSource(it.release, source) }?.id }.toSet()
}

/**
 * The downloaded subtitles the player offers in [languages], in the cloud's order: the first
 * [DOWNLOADS_PER_LANGUAGE] in each, and after them the [suggested] one when it is further down.
 */
fun offeredSubtitles(offers: List<SubtitleOffer>, languages: List<String>, suggested: Set<String>): List<SubtitleOffer> =
    offers.filter { it.language in languages }.groupBy { it.language }.values.flatMap { language ->
        language.take(DOWNLOADS_PER_LANGUAGE) + language.drop(DOWNLOADS_PER_LANGUAGE).filter { it.id in suggested }
    }

/**
 * Which downloaded subtitle file shows, read by [load] (null, or a throw, when it won't download or
 * read). The latest ask wins: a file still downloading for an earlier one never replaces the
 * Viewer's later pick. A file that fails is noted, so its row can say so and the best in a
 * language falls to the next file.
 */
class SubtitleShowing<T : Any>(private val load: suspend (SubtitleOffer) -> T?) {
    /** The file shown ([file], read from [shown]), the one downloading, and those that failed, by id. */
    data class State<T>(val shown: SubtitleOffer? = null, val file: T? = null, val loading: SubtitleOffer? = null, val failed: Set<String> = emptySet())

    private val mutableState = MutableStateFlow(State<T>())
    val state: StateFlow<State<T>> = mutableState.asStateFlow()
    private val loaded = ConcurrentHashMap<String, T>()
    private val asks = AtomicLong()

    /** Shows [offer], as the Viewer picked it, trying it again if it failed before. Whether it shows. */
    suspend fun show(offer: SubtitleOffer): Boolean = showFirst(listOf(offer), retry = true)

    /**
     * Shows the first of [offers] that downloads and reads, passing over those that failed before
     * unless [retry]. False when none does, or a later ask came first.
     */
    suspend fun showFirst(offers: List<SubtitleOffer>, retry: Boolean = false): Boolean {
        val ask = asks.incrementAndGet()
        for (offer in offers.distinctBy { it.id }) {
            if (!retry && offer.id in mutableState.value.failed) continue
            if (!current(ask) { it.copy(loading = offer) }) return false
            val file = loaded[offer.id] ?: try {
                load(offer)
            } catch (e: CancellationException) {
                current(ask) { it.copy(loading = null) }
                throw e
            } catch (e: Exception) {
                null
            }
            if (file == null) {
                mutableState.update { it.copy(failed = it.failed + offer.id) }
                continue
            }
            loaded[offer.id] = file
            if (!current(ask) { it.copy(shown = offer, file = file, loading = null, failed = it.failed - offer.id) }) return false
            return true
        }
        current(ask) { it.copy(loading = null) }
        return false
    }

    /** Shows none, and drops any file still downloading. */
    fun hide() {
        asks.incrementAndGet()
        mutableState.update { it.copy(shown = null, file = null, loading = null) }
    }

    /** Applies [change] if [ask] is still the latest; whether it was. */
    private fun current(ask: Long, change: (State<T>) -> State<T>): Boolean {
        var applied = false
        mutableState.update { state ->
            applied = asks.get() == ask
            if (applied) change(state) else state
        }
        return applied
    }
}
