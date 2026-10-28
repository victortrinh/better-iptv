package dev.betteriptv.core

import java.time.LocalDate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How fast Background reading goes: a [gap] between reads, and at most [perDay] reads a day. A read
 * is one range request of about a second (two for an MP4), and the surveyed Provider refused none
 * with half a second between streams, so both paces leave it far more room than that.
 */
class BackgroundPace(val gap: Duration, val perDay: Int) {
    companion object {
        /** About two reads a minute, like a Viewer opening Titles: a home screen's cards in under two hours. */
        val Gentle = BackgroundPace(30.seconds, 200)
        val Normal = BackgroundPace(6.seconds, 1_000)
    }
}

/**
 * Background reading's day, as kept on the device: how many [reads] it made on [date], and whether
 * the Provider [refused] one, which stops it until the next day.
 */
internal data class ReadingDay(val date: LocalDate, val reads: Int = 0, val refused: Boolean = false) {
    fun kept(): String = "$date $reads ${if (refused) "refused" else "open"}"

    companion object {
        /** The day [kept] describes when it is [today], or a fresh one. */
        fun of(kept: String?, today: LocalDate): ReadingDay {
            val parts = kept?.split(' ')
            if (parts?.size != 3 || parts[0] != today.toString()) return ReadingDay(today)
            return ReadingDay(today, parts[1].toIntOrNull() ?: 0, parts[2] == "refused")
        }
    }
}
