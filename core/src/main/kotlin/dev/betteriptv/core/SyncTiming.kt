package dev.betteriptv.core

import java.time.Duration
import java.util.Locale

/**
 * Where one Sync's time went, for a debug build's log (#566): each Step in order, and inside Saving
 * each of its parts, which add up to it. [toString] is the block to log.
 */
class SyncTiming(
    val steps: List<Part>,
    val saving: List<Part>,
    val channelListings: Int,
    val movieListings: Int,
    val showListings: Int,
) {
    data class Part(val name: String, val time: Duration)

    override fun toString(): String = buildString {
        val total = steps.sumOf { it.time.toNanos() }
        append("Sync took ${seconds(total)} s: $channelListings Channel, $movieListings movie and $showListings show Listings")
        val width = (steps + saving).maxOf { it.name.length }
        steps.forEach { append("\n  ${it.name.padEnd(width + 2)}  ${line(it, total)}") }
        saving.forEach { append("\n    ${it.name.padEnd(width)}  ${line(it, total)}") }
    }

    private fun line(part: Part, total: Long): String {
        val nanos = part.time.toNanos()
        return "${seconds(nanos).padStart(8)} s  ${(if (total == 0L) 0 else nanos * 100 / total).toString().padStart(3)}%"
    }

    private fun seconds(nanos: Long) = String.format(Locale.ROOT, "%.3f", nanos / 1e9)
}

/**
 * Times a Sync for a [SyncTiming]: each [mark] names what was done since the one before. A clock
 * read per mark, and a mark per Step or statement, never per Listing.
 */
internal class SyncTimer {
    private var last = System.nanoTime()
    private val parts = ArrayList<SyncTiming.Part>()

    fun mark(name: String) {
        val now = System.nanoTime()
        parts += SyncTiming.Part(name, Duration.ofNanos(now - last))
        last = now
    }

    /** The marks so far: the first three are the Channels, Movies and Shows Steps, the rest the parts of Saving. */
    fun timing(sync: CompletedSync): SyncTiming {
        val saving = parts.drop(3)
        val saved = saving.fold(Duration.ZERO) { sum, part -> sum + part.time }
        return SyncTiming(parts.take(3) + SyncTiming.Part(SyncStep.Saving.name, saved), saving, sync.channelListingCount, sync.movieListingCount, sync.showListingCount)
    }
}
