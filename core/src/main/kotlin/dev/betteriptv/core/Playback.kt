package dev.betteriptv.core

import kotlinx.coroutines.sync.Semaphore
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the Viewer's stick and TV together can play, as the platform reports it. It decides which
 * Versions are playable: a Version whose picture the TV can't display is never picked.
 */
data class DeviceProfile(
    /** The tallest picture the stick can decode and the TV can display, in lines: 2160 for 4K, 1080 for HD. */
    val maxHeight: Int,
    /** The HDR formats the TV displays. */
    val hdr: Set<Hdr>,
    /** The video formats the stick decodes in hardware, as MIME types (`video/hevc`, `video/av01`). */
    val videoDecoders: Set<String> = emptySet(),
    /** The audio formats the stick can pass through to the TV or soundbar, as MIME types (`audio/eac3`, `audio/vnd.dts`). */
    val audioPassthrough: Set<String> = emptySet(),
) {
    /** Whether the TV can display a Version with these attributes. One of unknown quality is taken to be displayable. */
    fun canDisplay(attributes: VersionAttributes): Boolean {
        val height = when (attributes.quality?.value) {
            Quality.UHD -> 2160
            Quality.FullHD -> 1080
            Quality.HD, Quality.SD, null -> 0
        }
        return height <= maxHeight && attributes.hdr?.value.let { it == null || it in hdr }
    }
}

/**
 * What pressing Play starts: a [version] with its [Version.backups], and the Viewer's
 * [contentLanguages], which choose its Tracks once the player has read them. [versions] are the
 * Title's Versions the player can switch to, best first and the Viewer's languages first, each with
 * its Backups; [version] is among them.
 */
data class PlayPlan(val titleId: TitleId, val version: Version, val contentLanguages: List<String>, val versions: List<Version>) {
    /**
     * Which of the file's [audio] and [subtitles] Tracks to play: the audio in the first Content
     * language the file has, with no subtitles. Without audio in any of them, the file's default
     * audio plays with subtitles in the first Content language, when the file has them.
     */
    fun chooseTracks(audio: List<Track>, subtitles: List<Track>): TrackChoice {
        val wanted = contentLanguages.map(::primaryLanguage)
        val audioIndex = wanted.firstNotNullOfOrNull { language -> audio.indexOfFirst { it.isIn(language) }.takeIf { it >= 0 } }
        if (audioIndex != null) return TrackChoice(audioIndex, subtitle = null)
        val subtitle = wanted.firstOrNull()?.let { language -> subtitles.indexOfFirst { it.isIn(language) }.takeIf { it >= 0 } }
        return TrackChoice(audio = null, subtitle)
    }

    private fun Track.isIn(language: String) = this.language?.let(::trackLanguage) == language
}

/** An audio or subtitle Track the player found in a file, with its [language] as the file tags it (ISO 639-1 or 639-2), if it does. */
data class Track(val language: String?)

/**
 * Indexes into the Tracks given to [PlayPlan.chooseTracks]. A null [audio] leaves the file's default
 * audio; a null [subtitle] turns on no subtitles (forced ones, for foreign dialogue, may still show).
 */
data class TrackChoice(val audio: Int?, val subtitle: Int?)

/**
 * The Viewer's one connection to the Provider, held while a Version plays; close it once the player
 * has let go of the file. [location] is where the Version's file plays from. It carries the Provider
 * login: give it to the player engine only, and never log it.
 */
class Connection internal constructor(val location: String, private val lease: ConnectionGate.Lease) : Closeable {
    override fun close() = lease.close()

    override fun toString() = "Connection(***)"
}

/**
 * Enforces the Connection limit on the device: one lease at a time, so a second stream is never
 * opened while one is in use. [lease] waits until the current lease is closed.
 */
internal class ConnectionGate {
    private val semaphore = Semaphore(1)

    suspend fun lease(): Lease {
        semaphore.acquire()
        return Lease()
    }

    inner class Lease : Closeable {
        private val released = AtomicBoolean()

        override fun close() {
            if (released.compareAndSet(false, true)) semaphore.release()
        }
    }
}

/** ISO 639-1 codes by ISO 639-2 code, both the terminology (`fra`) and bibliographic (`fre`) forms. */
private val ISO_639_1: Map<String, String> by lazy {
    Locale.getISOLanguages().associateBy { Locale.forLanguageTag(it).isO3Language } + mapOf(
        "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh", "cze" to "cs", "dut" to "nl", "fre" to "fr",
        "geo" to "ka", "ger" to "de", "gre" to "el", "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms", "per" to "fa",
        "rum" to "ro", "slo" to "sk", "tib" to "bo", "wel" to "cy",
    )
}

/** A Track's language as a primary language subtag: `eng`, `en` and `en-US` are all `en`. */
private fun trackLanguage(tag: String): String = primaryLanguage(tag).let { ISO_639_1[it] ?: it }
