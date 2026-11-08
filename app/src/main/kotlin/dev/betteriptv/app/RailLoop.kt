package dev.betteriptv.app

/**
 * How a Home rail of [cards] loops, like Netflix's: Right on its last card goes on to the first, and
 * Left on the first back to the last. The rail lays its cards out again and again, [COPIES] times,
 * and opens in the middle, so the Viewer never reaches an end. A rail with no more cards than the
 * screen shows at once ([seen]) doesn't loop: the same card would show twice while it slides.
 */
class RailLoop(val cards: Int, seen: Int) {
    val loops = cards > seen

    /** How many places the rail has. */
    val count = if (loops) cards * COPIES else cards

    /** The place the rail opens at, its first card's. */
    val start = if (loops) cards * (COPIES / 2) else 0

    /** Which card [place] shows. */
    fun card(place: Int) = place % cards

    /** The place showing [card] nearest to [near]: where a card focused again is, so the rail doesn't jump. */
    fun place(card: Int, near: Int): Int {
        if (!loops) return card
        val base = near - near.mod(cards) + card
        return listOf(base - cards, base, base + cards).filter { it in 0 until count }.minBy { kotlin.math.abs(it - near) }
    }

    companion object {
        /** About ten thousand presses either way from the start, for the longest rail. */
        const val COPIES = 1000

        /**
         * How many cards [width] wide, [gap] apart, the screen [screen] wide shows at once when the
         * first sits at [margin]: those starting before its right edge, and the one peeking in the
         * margin at the left.
         */
        fun seen(width: Float, gap: Float, screen: Float, margin: Float): Int = 1 + kotlin.math.ceil((screen - margin) / (width + gap)).toInt()
    }
}
