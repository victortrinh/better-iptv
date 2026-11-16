package dev.betteriptv.core

import java.io.IOException
import java.time.Instant

/**
 * One programme in the Provider's guide for a Channel: its [title] (decoded from the base64 the
 * Provider sends) and when it runs, [start] included and [end] excluded.
 */
data class Programme(val title: String, val start: Instant, val end: Instant)

/**
 * What the Provider's guide says a Channel is showing: the programme on [now], when the guide names
 * one for this moment, and the [next] one after it. Null for both when the guide listed programmes
 * for the Channel but none from now on.
 */
data class NowNext(val now: Programme?, val next: Programme?) {
    /** Whether the guide knows anything the screens can show. */
    val isEmpty: Boolean get() = now == null && next == null
}

/**
 * A Channel's guide as the cache keeps it: the [programmes] the Provider answered, in order, kept
 * [until] the last of them ends ([NowNext] is read off them as the clock moves), or, for a Channel
 * the guide listed nothing for, for a while, so the Provider isn't asked again at every focus.
 */
internal class GuideEntry(val programmes: List<Programme>, val until: Instant) {
    /** The programme on at [now] and the one after it, among [programmes]. */
    fun at(now: Instant): NowNext {
        val on = programmes.firstOrNull { !it.start.isAfter(now) && it.end.isAfter(now) }
        val next = programmes.firstOrNull { it.start.isAfter(now) }
        return NowNext(on, next)
    }
}

/**
 * The Provider refused a guide call: it answered with HTTP [status] rather than a guide. The guide
 * stops asking for the rest of the day, as Background reading does after a refused Header read.
 */
internal class GuideRefused(val status: Int) : IOException("The Provider answered a guide call with HTTP $status.")
