package dev.betteriptv.core

/**
 * Fills one screen's rows from the top so each Title shows once (the Row in CONTEXT.md): a Chart
 * keeps every Title it ranks, and each row below skips the Titles shown above it. Continue watching
 * and Favourites stand apart and don't go through it. One per screen: Home, Movies and Shows each
 * dedupe on their own.
 */
internal class ScreenRows {
    private val shown = HashSet<TitleId>()

    /** A Chart's [titles], all of them, or none when there are fewer than [min]. The rows below skip them. */
    fun chart(titles: List<StoredTitle>, min: Int = 1): List<StoredTitle> = if (titles.size < min) emptyList() else titles.also(::show)

    /**
     * Up to [size] of [titles] not shown in a row above, in their order, or none when fewer than
     * [min] are left: a row of two looks broken.
     */
    fun row(titles: List<StoredTitle>, size: Int, min: Int = 1): List<StoredTitle> {
        val left = titles.filter { it.id !in shown }
        return if (left.size < min) emptyList() else left.take(size).also(::show)
    }

    private fun show(titles: List<StoredTitle>) = titles.forEach { shown += it.id }
}
