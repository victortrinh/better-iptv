package dev.betteriptv.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import java.io.Closeable
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

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
    /** The audio formats the stick's own decoders turn into PCM, as MIME types (`audio/mp4a-latm`, `audio/ac3`). */
    val audioDecoders: Set<String> = emptySet(),
) {
    /**
     * How an audio Track of this MIME type plays: passed through when the TV or soundbar takes it as
     * it is, else decoded by the stick, else by the app's FFmpeg decoder, which is how DTS and TrueHD
     * play on a Fire stick without a capable soundbar. Like Media3, it passes DTS-HD through as its
     * DTS core and Dolby Atmos as plain E-AC-3 when the soundbar takes only those. Null when nothing
     * on the device plays it; a Track whose file doesn't name its format, or PCM, which needs no
     * decoder, plays as the stick's own audio.
     */
    fun audioOutput(mimeType: String?): AudioOutput? = when {
        mimeType == null || mimeType == PCM -> AudioOutput.PlatformDecoder
        mimeType in audioPassthrough || PASSTHROUGH_FALLBACK[mimeType] in audioPassthrough -> AudioOutput.Passthrough
        mimeType in audioDecoders -> AudioOutput.PlatformDecoder
        mimeType in SOFTWARE_AUDIO -> AudioOutput.SoftwareDecoder
        else -> null
    }

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
 * [contentLanguages], which choose its Tracks once the player has read them.
 */
data class PlayPlan(val titleId: TitleId, val version: Version, val contentLanguages: List<String>) {
    /**
     * Which of the file's [audio] and [subtitles] Tracks to play on [device]: the audio in the first
     * Content language the file has, with no subtitles. Among that language's Tracks, one that passes
     * through or plays on the stick's decoder beats one decoded in software (a Blu-ray rip's Dolby
     * Digital Track over its TrueHD one, without a soundbar), and audio nothing on the device plays is
     * never chosen. Without audio in any of them, the file's default audio plays with subtitles in
     * the first Content language, when the file has them.
     */
    fun chooseTracks(audio: List<Track>, subtitles: List<Track>, device: DeviceProfile): TrackChoice {
        val wanted = contentLanguages.map(::primaryLanguage)
        val outputs = audio.map { device.audioOutput(it.mimeType) }
        val audioIndex = wanted.firstNotNullOfOrNull { language ->
            audio.indices.filter { audio[it].isIn(language) && outputs[it] != null }.minByOrNull { outputs[it]!!.ordinal }
        }
        if (audioIndex != null) return TrackChoice(audioIndex, subtitle = null)
        val subtitle = wanted.firstOrNull()?.let { language -> subtitles.indexOfFirst { it.isIn(language) }.takeIf { it >= 0 } }
        return TrackChoice(audio = null, subtitle)
    }

    private fun Track.isIn(language: String) = this.language?.let(::trackLanguage) == language
}

/**
 * An audio or subtitle Track the player found in a file, with its [language] as the file tags it
 * (ISO 639-1 or 639-2) and its format as a [mimeType] (`audio/true-hd`), if the file names them.
 */
data class Track(val language: String?, val mimeType: String? = null)

/** How an audio Track reaches the Viewer's ears, best first: [PlayPlan.chooseTracks] ranks Tracks in this order. */
enum class AudioOutput {
    /** Sent as it is to the TV or soundbar, which decodes it. */
    Passthrough,

    /** Decoded by the stick's own decoders. */
    PlatformDecoder,

    /** Decoded by the app's FFmpeg decoder, on the stick's CPU. */
    SoftwareDecoder,
}

private const val PCM = "audio/raw"

/** The format Media3 passes a Track through as when the soundbar doesn't take it but takes this one, by MIME type. */
private val PASSTHROUGH_FALLBACK = mapOf("audio/vnd.dts.hd" to "audio/vnd.dts", "audio/eac3-joc" to "audio/eac3")

/** The audio formats the app's FFmpeg decoder plays, as MIME types: the formats Jellyfin's Media3 FFmpeg build enables. */
private val SOFTWARE_AUDIO = setOf(
    "audio/vnd.dts", "audio/vnd.dts.hd", "audio/true-hd", "audio/ac3", "audio/eac3", "audio/eac3-joc",
    "audio/mp4a-latm", "audio/mpeg", "audio/flac", "audio/alac", "audio/g711-mlaw", "audio/g711-alaw",
)

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

/** How long the Provider still counts a closed stream as open: the next one waits this long after it. */
internal val PROVIDER_RELEASE_GRACE = 500.milliseconds

/**
 * Enforces the Connection limit on the device: one lease at a time, so a second stream is never
 * opened while one is in use. [lease] waits until the current lease is closed, and then for
 * [releaseGrace], because the Provider counts a closed stream as open for a moment after.
 */
internal class ConnectionGate(private val releaseGrace: Duration = PROVIDER_RELEASE_GRACE) {
    private val semaphore = Semaphore(1)

    @Volatile private var releasedAt: TimeMark? = null

    suspend fun lease(): Lease {
        semaphore.acquire()
        try {
            releasedAt?.let { delay(releaseGrace - it.elapsedNow()) }
        } catch (e: CancellationException) {
            semaphore.release()
            throw e
        }
        return Lease()
    }

    inner class Lease : Closeable {
        private val released = AtomicBoolean()

        override fun close() {
            if (released.compareAndSet(false, true)) {
                releasedAt = TimeSource.Monotonic.markNow()
                semaphore.release()
            }
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
