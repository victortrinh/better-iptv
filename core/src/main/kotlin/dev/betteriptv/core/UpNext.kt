package dev.betteriptv.core

import kotlin.time.Duration.Companion.seconds

/**
 * Whether [position] of a file [duration] long (milliseconds, null while the player doesn't know it)
 * is in its credits: its last 10%, or its last 10 minutes when that is less (ADR 0008). Providers
 * mark no credits, so this is the rule for them: from here a Title counts as Watched, and an
 * Episode offers the next one.
 */
fun inCredits(position: Long, duration: Long?): Boolean =
    duration != null && duration > 0 && position >= duration - minOf((duration * CREDITS_SHARE).toLong(), CREDITS_TIME)

private const val CREDITS_SHARE = 0.10
private const val CREDITS_TIME = 10 * 60_000L

/**
 * How the next Episode is offered once one reaches its credits: a card with the next Episode,
 * counting down to start it by itself unless the Viewer cancels. So a whole Show doesn't play to an
 * empty room on the Viewer's one connection, the countdown stops after a few in a row with no key pressed.
 */
object UpNext {
    /** How long the card counts down before the next Episode starts by itself. */
    val COUNTDOWN = 10.seconds

    /** How many Episodes may start by themselves in a row, with no key pressed, before the card waits for the Viewer. */
    const val IN_A_ROW = 3

    /**
     * Whether the next Episode starts by itself after [COUNTDOWN]: the Viewer allows it
     * ([automatic], from Settings) and fewer than [IN_A_ROW] have started by themselves since a key was pressed ([inARow]).
     */
    fun countsDown(automatic: Boolean, inARow: Int): Boolean = automatic && inARow < IN_A_ROW
}
