package dev.betteriptv.app

import kotlin.time.Duration.Companion.seconds

/**
 * The Viewer moving through a Movie or Episode with Left and Right (or rewind and fast-forward),
 * from [from]: only a [target] moves, so nothing reaches the Provider until the scrub commits.
 */
internal class Scrub(val from: Long, private val duration: Long?) {
    var target = from
        private set
    /** The latest key event, and how long the key had been held at its last repeat counted. */
    private var last = 0L
    private var counted = 0L
    private var held = false

    /**
     * A press of the key that went down at [downAt], or its repeat at [at], in milliseconds. A tap
     * moves 10 s; a held key moves by how long it has been held, faster the longer, whatever rate
     * the remote repeats at and however many repeats the app misses.
     */
    fun move(forward: Boolean, at: Long, downAt: Long) {
        val by = if (at == downAt) {
            counted = 0
            STEP
        } else {
            (heldFor(at - downAt) - heldFor(counted)).coerceAtLeast(0).also { counted = maxOf(counted, at - downAt) }
        }
        // A busy app can get a repeat after the key's up, stamped before it: that doesn't hold the key again.
        if (at > last) {
            last = at
            held = true
        }
        target = (target + if (forward) by else -by).coerceIn(0, duration ?: Long.MAX_VALUE)
    }

    /** The key that moved it let go, as the app saw at [at]. */
    fun release(at: Long) {
        last = maxOf(last, at)
        held = false
    }

    /**
     * When the scrub commits by itself, on the clock key events are stamped with: a second after
     * the key is let go (see [release]), never while it's held, however late the app gets to the key's repeats.
     */
    fun commitsAt(): Long? = if (held) null else last + COMMITS_AFTER.inWholeMilliseconds

    /**
     * The one seek that ends the scrub, from where playback is at [position]: null when the target
     * is back where the scrub started, so no request is made.
     */
    fun commit(position: Long): Long? = if (target == from) null else target - position

    /**
     * How far holding a key [held] milliseconds moves, past its first press: 20 ms of the file a
     * millisecond for the first second, 120 for the next half, then the whole file in about 2.5 s,
     * so a long Movie takes no longer to cross than an Episode.
     */
    private fun heldFor(held: Long): Long {
        val fastest = maxOf(300.0, (duration ?: 0) / 2_500.0)
        return when {
            held < 1_000 -> 20 * held
            held < 1_500 -> 20_000 + 120 * (held - 1_000)
            else -> 80_000 + (fastest * (held - 1_500)).toLong()
        }
    }

    companion object {
        /** How long the remote rests before the scrub commits by itself. */
        val COMMITS_AFTER = 1.seconds

        /** How far a tap moves. */
        private const val STEP = 10_000L
    }
}
