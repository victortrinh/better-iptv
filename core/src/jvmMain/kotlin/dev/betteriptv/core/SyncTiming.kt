package dev.betteriptv.core

import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Where one Sync's time went, for a debug build's log (#566): each Step in order, a first Sync's
 * Head start between Channels and Movies when it read one ([HEAD_START]), and inside Saving
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

    companion object {
        /** The part a first Sync's Head start is timed under, between Channels and Movies (ADR 0019): it is no Step. */
        const val HEAD_START = "Head start"
    }

    override fun toString(): String = buildString {
        val total = steps.sumOf { it.time.inWholeNanoseconds }
        append("Sync took ${seconds(total)} s: $channelListings Channel, $movieListings movie and $showListings show Listings")
        val width = (steps + saving).maxOf { it.name.length }
        steps.forEach { append("\n  ${it.name.padEnd(width + 2)}  ${line(it, total)}") }
        saving.forEach { append("\n    ${it.name.padEnd(width)}  ${line(it, total)}") }
    }

    private fun line(part: Part, total: Long): String {
        val nanos = part.time.inWholeNanoseconds
        return "${seconds(nanos).padStart(8)} s  ${(if (total == 0L) 0 else nanos * 100 / total).toString().padStart(3)}%"
    }

    /** [nanos] in seconds to the millisecond, rounded: `239.700`. */
    private fun seconds(nanos: Long): String {
        val millis = (nanos + 500_000) / 1_000_000
        return "${millis / 1_000}.${(millis % 1_000).toString().padStart(3, '0')}"
    }
}

/**
 * Times a Sync for a [SyncTiming]: each [mark] names what was done since the one before. A clock
 * read per mark, and a mark per Step or statement, never per Listing.
 */
internal class SyncTimer {
    private var last = TimeSource.Monotonic.markNow()
    private val parts = ArrayList<SyncTiming.Part>()

    fun mark(name: String) {
        val now = TimeSource.Monotonic.markNow()
        parts += SyncTiming.Part(name, now - last)
        last = now
    }

    /**
     * The marks so far: up to the Shows Step's they are the Steps (Channels, a first Sync's Head start
     * when it read one, Movies, Shows), the rest the parts of Saving.
     */
    fun timing(sync: CompletedSync): SyncTiming {
        val read = parts.indexOfFirst { it.name == SyncStep.Shows.name } + 1
        val saving = parts.drop(read)
        val saved = saving.fold(Duration.ZERO) { sum, part -> sum + part.time }
        return SyncTiming(parts.take(read) + SyncTiming.Part(SyncStep.Saving.name, saved), saving, sync.channelListingCount, sync.movieListingCount, sync.showListingCount)
    }
}
