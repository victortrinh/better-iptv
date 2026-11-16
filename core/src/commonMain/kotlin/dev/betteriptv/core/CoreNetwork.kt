package dev.betteriptv.core

import io.ktor.client.HttpClient
import kotlin.time.TimeSource

/**
 * What the Core reaches the Provider and the Cloud with, given by the platform it runs on (ADR 0020):
 * the [http] client its own requests go through (the catalog, details, Header reads, Title facts,
 * subtitles, updates), and the [relay] a player engine that overlaps its requests reads a file through.
 */
class CoreNetwork(
    val http: HttpClient,
    /** A relay on this device for the file at a [Connection]'s location, one Provider stream at a time. */
    val relay: (location: String) -> StreamRelay,
)

/**
 * Serves a Version's file to a player engine on this device through one Provider stream at a time.
 * It listens on a local socket, so each platform brings its own. Its [location] holds no login, so
 * the engine can't log one.
 */
interface StreamRelay {
    /** Where the engine reads the file from. */
    val location: String

    /**
     * When the Provider last started sending the file's bytes for one of the engine's requests, or null
     * before it first did.
     */
    val answeredAt: TimeSource.Monotonic.ValueTimeMark?

    fun close()
}
