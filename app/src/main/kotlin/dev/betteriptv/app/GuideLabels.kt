package dev.betteriptv.app

import android.content.Context
import dev.betteriptv.core.NowNext
import java.time.Instant
import java.time.ZoneId

/**
 * What a Channel is showing, in one muted line (#261): the programme on now and when the next one
 * starts in the device's clock ("The National · Next 10:00 PM"; the weekday before the time when it
 * isn't today, as a Game's start reads), the programme alone when the guide names no next one, or
 * "Next 10:00 PM · Dragons' Den" when nothing is on now. Beside a Channel's row in the Channels
 * screen and under a focused Favourite; the player's top scrim names the programme on now alone.
 */
fun Context.nowNextLabel(guide: NowNext, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    val on = guide.now
    val next = guide.next
    return when {
        on != null && next != null -> getString(R.string.guide_now_next, on.title, startCaption(next.start, now, zone))
        on != null -> on.title
        next != null -> getString(R.string.guide_next, startCaption(next.start, now, zone), next.title)
        else -> ""
    }
}
