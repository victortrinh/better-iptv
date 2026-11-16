package dev.betteriptv.core

/**
 * Fills one screen's rows from the top so each Title shows once (the Row in CONTEXT.md): each row
 * skips the Titles shown above it. Continue watching
 * and Favourites stand apart and don't go through it. One per screen: Home, Movies and Shows each
 * dedupe on their own.
 */
internal class ScreenRows {
    private val shown = HashSet<TitleId>()

    /**
     * Up to [size] of [titles] not shown in a row above, in their order, each once, or none when
     * fewer than [min] are left: a row of two looks broken. A rail keys its cards by Title, so the
     * same Title twice in a row would crash the app (#378).
     */
    fun row(titles: List<StoredTitle>, size: Int, min: Int = 1): List<StoredTitle> {
        val left = titles.filter { it.id !in shown }.distinctBy { it.id }
        return if (left.size < min) emptyList() else left.take(size).also(::show)
    }

    /** Whether Title [id] is on a row already. */
    fun shows(id: TitleId): Boolean = id in shown

    private fun show(titles: List<StoredTitle>) = titles.forEach { shown += it.id }
}
