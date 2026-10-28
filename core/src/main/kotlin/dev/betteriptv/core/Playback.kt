package dev.betteriptv.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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

/** The highest quality the Viewer allows Play to pick: 4K, 1080p or 720p. */
enum class QualityCap(val quality: Quality) { UHD(Quality.UHD), FullHD(Quality.FullHD), HD(Quality.HD) }

/**
 * What pressing Play starts: a [version] with its [Version.backups], and the Viewer's
 * [contentLanguages] and [remembered] choices, which choose its Tracks once the player has read
 * them. [versions] are the Title's Versions the player can switch to, best first and the Viewer's
 * languages first, each with its Backups; [version] is among them. Playback starts [from]
 * milliseconds in: where the Viewer stopped last time, or the start. A Show's plan plays its
 * [episode]; a Movie's has none.
 */
data class PlayPlan(
    val titleId: TitleId,
    val version: Version,
    val contentLanguages: List<String>,
    val versions: List<Version>,
    val from: Long = 0,
    val episode: EpisodeNumber? = null,
    val remembered: RememberedTracks = RememberedTracks(),
) {
    /**
     * Which of the file's [audio] and [subtitles] Tracks to play on [device]: the audio in the
     * language the Viewer last picked, else in the first Content language the file has. Among that
     * language's Tracks, one that passes through or plays on the stick's decoder beats one decoded
     * in software (a Blu-ray rip's Dolby Digital Track over its TrueHD one, without a soundbar), and
     * audio nothing on the device plays is never chosen. Subtitles are the ones the Viewer last
     * picked (or none, when they last turned them off) when the file has them; otherwise there are
     * none, unless no audio is in any of those languages: then the file's default audio plays with
     * subtitles in the first Content language, when the file has them.
     */
    fun chooseTracks(audio: List<Track>, subtitles: List<Track>, device: DeviceProfile, remembered: RememberedTracks = this.remembered): TrackChoice {
        val wanted = rankingLanguages(remembered, contentLanguages).map(::primaryLanguage)
        val outputs = audio.map { device.audioOutput(it.mimeType) }
        val audioIndex = wanted.firstNotNullOfOrNull { language ->
            audio.indices.filter { audio[it].isIn(language) && outputs[it] != null }.minByOrNull { outputs[it]!!.ordinal }
        }
        val picked = remembered.subtitles?.let { language -> subtitles.indexOfFirst { it.isIn(primaryLanguage(language)) }.takeIf { it >= 0 } }
        val subtitle = when {
            remembered.subtitlesOff -> null
            picked != null -> picked
            audioIndex != null -> null
            else -> contentLanguages.firstOrNull()?.let { language -> subtitles.indexOfFirst { it.isIn(primaryLanguage(language)) }.takeIf { it >= 0 } }
        }
        return TrackChoice(audioIndex, subtitle)
    }

    private fun Track.isIn(language: String) = this.language?.let(::trackLanguage) == language
}

/** An Episode of a Show, by season and episode number. */
data class EpisodeNumber(val season: Int, val number: Int)

/**
 * The audio and subtitle languages the Viewer last picked in the player, kept for the next file:
 * [audio] is null until they pick one, and [subtitles] is a language, or none when they turned
 * subtitles off ([subtitlesOff]) or never picked any.
 */
data class RememberedTracks(val audio: String? = null, val subtitles: String? = null, val subtitlesOff: Boolean = false)

/** The languages Versions and Tracks are preferred in: the audio language the Viewer last picked, then their [contentLanguages]. */
internal fun rankingLanguages(remembered: RememberedTracks, contentLanguages: List<String>): List<String> =
    (listOfNotNull(remembered.audio) + contentLanguages).distinctBy(::primaryLanguage)

/**
 * The refresh rate among the TV's [rates] (in Hz) to show a film of [frameRate] frames a second
 * without judder: the lowest that is a whole multiple of it (24 Hz, or 48, for a 24 fps film; 50 Hz
 * for 25 fps). 24 Hz doesn't fit 23.976 fps: it would drop a frame every 42 seconds. Null when the
 * [current] rate already fits, or no rate does.
 */
fun refreshRateFor(frameRate: Float, current: Float, rates: List<Float>): Float? {
    if (frameRate <= 0) return null
    fun fits(rate: Float): Boolean {
        val multiple = Math.round(rate / frameRate)
        return multiple >= 1 && kotlin.math.abs(rate - multiple * frameRate) / rate < REFRESH_RATE_TOLERANCE
    }
    if (fits(current)) return null
    return rates.filter(::fits).minOrNull()
}

/** How far a refresh rate may be from a whole multiple of the frame rate, as a share of it: TVs report 23.976 Hz as 23.976025. */
private const val REFRESH_RATE_TOLERANCE = 0.0003f

/**
 * A film's frames a second from the presentation times (in µs) of frames shown one after another,
 * for files that don't name it (Media3 leaves it out of every MKV). Matroska rounds times to the
 * millisecond, so 23.976 fps arrives as 41 and 42 ms steps: the rate is taken over the whole span,
 * counting a dropped frame's longer step as two, and snapped to the standard rate it is closest to.
 * Null with too few frames, or when the times don't move forward.
 */
fun frameRateOf(presentationTimesUs: List<Long>): Float? {
    if (presentationTimesUs.size < MIN_FRAMES_FOR_RATE) return null
    val steps = presentationTimesUs.zipWithNext { a, b -> b - a }
    if (steps.any { it <= 0 }) return null
    val median = steps.sorted()[steps.size / 2]
    val frameUs = steps.filter { it < median * 3 / 2 }.average()
    val frames = steps.sumOf { Math.round(it / frameUs) }
    val measured = (frames * 1_000_000.0 / (presentationTimesUs.last() - presentationTimesUs.first())).toFloat()
    return STANDARD_FRAME_RATES.firstOrNull { kotlin.math.abs(it - measured) / it < FRAME_RATE_SNAP } ?: measured
}

/** Enough frames (about 4 s of film) that millisecond rounding can't pass 24 fps for 23.976. */
const val MIN_FRAMES_FOR_RATE = 96

private val STANDARD_FRAME_RATES = listOf(24000f / 1001, 24f, 25f, 30000f / 1001, 30f, 48000f / 1001, 48f, 50f, 60000f / 1001, 60f)

/** Half the gap between 23.976 and 24 fps, as a share of the rate. */
private const val FRAME_RATE_SNAP = 0.0005f

/**
 * An audio or subtitle Track found in a file, with its [language] as the file tags it (ISO 639-1
 * or 639-2), its format as a [mimeType] (`audio/true-hd`) and an audio Track's [channels], if the
 * file names them.
 */
data class Track(val language: String?, val mimeType: String? = null, val channels: Int? = null)

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

    /** Cancels the Header read holding the connection, if one is. */
    @Volatile private var preempt: (() -> Unit)? = null

    suspend fun lease(): Lease {
        // Playing comes first: a Header read gives the connection up at once.
        preempt?.invoke()
        semaphore.acquire()
        try {
            releasedAt?.let { delay(releaseGrace - it.elapsedNow()) }
        } catch (e: CancellationException) {
            semaphore.release()
            throw e
        }
        return Lease()
    }

    /** Cancels the Header read holding the connection, if one is, as a [lease] would. */
    fun cancelRead() {
        preempt?.invoke()
    }

    /**
     * Runs [read] holding the connection if it is free right now, without waiting for it. A [lease]
     * asked for meanwhile cancels [read] at once. Null when the connection was taken, before or during [read].
     */
    suspend fun <T : Any> whileFree(read: suspend () -> T): T? {
        if (!semaphore.tryAcquire()) return null
        val lease = Lease()
        return try {
            coroutineScope {
                val reading = async(start = CoroutineStart.LAZY) {
                    releasedAt?.let { delay(releaseGrace - it.elapsedNow()) }
                    read()
                }
                preempt = { reading.cancel() }
                try {
                    reading.await()
                } catch (e: CancellationException) {
                    // Rethrown when it's the caller that was cancelled, not the read.
                    ensureActive()
                    null
                }
            }
        } finally {
            preempt = null
            lease.close()
        }
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
