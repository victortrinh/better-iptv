package dev.betteriptv.core

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
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
 * milliseconds in: where the Viewer stopped last time, or the start; [length] is the file's length
 * the player knew then, null when it didn't or the Viewer never stopped in it. A Show's plan plays
 * its [episode]; a Movie's has none. A Channel's plan has no [titleId]: it plays live.
 * [originalLanguage] is the language the Title was released in (TMDB's, ISO 639-1), when known.
 * [choices] is what the Viewer chose the last time this Title played (#291), which wins over
 * [remembered] for the Tracks the file still has. With [preferOriginalAudio] (the Viewer's Prefer
 * original audio setting), the audio Track in the original language is chosen when it is no
 * Content language, with subtitles in the first Content language.
 */
data class PlayPlan(
    val titleId: TitleId?,
    val version: Version,
    val contentLanguages: List<String>,
    val versions: List<Version>,
    val from: Long = 0,
    val episode: EpisodeNumber? = null,
    val remembered: RememberedTracks = RememberedTracks(),
    val length: Long? = null,
    val originalLanguage: String? = null,
    val choices: TitleChoices = TitleChoices(),
    val preferOriginalAudio: Boolean = false,
) {
    /** How far in the Viewer stopped, for a progress bar; null at the start or without the file's length. */
    val progress: Float? get() = length?.takeIf { it > 0 && from > 0 }?.let { (from.toFloat() / it).coerceIn(0f, 1f) }

    /** Whether it plays a Channel, live: there is nowhere to seek to and nothing to resume. */
    val live: Boolean get() = titleId == null

    /**
     * Whether the TV switches to the refresh rate that fits the file's frame rate. A Channel keeps the
     * TV's: the switch is an HDMI mode change that blanks the TV for a second or two, and it comes once
     * the frame rate is measured, just after the picture shows, on every Channel the Viewer zaps to
     * (#454). A 59.94 fps Channel on a 60 Hz TV only repeats a frame every 17 s.
     */
    val matchesRefreshRate: Boolean get() = !live

    /** This plan from the beginning, for Restart. Where the Viewer stopped stays kept until they pass the first minute. */
    fun fromStart(): PlayPlan = copy(from = 0)

    /**
     * Which of the file's [audio] and [subtitles] Tracks to play on [device]: the Tracks the Viewer
     * chose the last time this Title played ([choices]), when the file still has them (an Episode's
     * choice carries to the next, whose file has the same Tracks); else the audio in the
     * language the Viewer last picked, else, with [preferOriginalAudio] on a Title released outside
     * the Content languages, in the Title's [originalLanguage] (wherever that Track sits), else in
     * the first Content language the file has. Among that language's Tracks, one that passes
     * through or plays on the stick's decoder beats one decoded in software (a Blu-ray rip's Dolby
     * Digital Track over its TrueHD one, without a soundbar), and audio nothing on the device plays
     * is never chosen. Subtitles are the ones the Viewer last picked (or none, when they last turned
     * them off) when the file has them; otherwise there are none, unless the audio is in none of
     * the Viewer's languages (the original, or no language at all): then it plays with subtitles in
     * the first Content language, when the file has them.
     *
     * Where the file lacks the subtitles that would show, [TrackChoice.downloaded] names the language
     * to show downloaded ones in instead: the ones the Viewer chose for this Title (by their file,
     * [TrackChoice.downloadedOffer]), else the language they last picked, or, when no audio is in
     * those languages, the first Content language.
     */
    fun chooseTracks(
        audio: List<Track>,
        subtitles: List<Track>,
        device: DeviceProfile,
        remembered: RememberedTracks = this.remembered,
        choices: TitleChoices = this.choices,
    ): TrackChoice {
        val viewers = rankingLanguages(remembered, contentLanguages).map(::primaryLanguage)
        val original = originalAudioLanguage(originalLanguage, viewers, preferOriginalAudio)
        val wanted = listOfNotNull(remembered.audio?.let(::primaryLanguage), original) + viewers
        val outputs = audio.map { device.audioOutput(it.mimeType) }
        val chosenAudio = choices.audio?.find(audio)?.takeIf { outputs[it] != null }
        val audioIndex = chosenAudio ?: wanted.firstNotNullOfOrNull { language ->
            audio.indices.filter { audio[it].isIn(language) && outputs[it] != null }.minByOrNull { outputs[it]!!.ordinal }
        }
        // Original audio outside the Viewer's languages plays with subtitles, as audio in no language of theirs does.
        val heard = audioIndex != null && (chosenAudio != null || viewers.any { audio[audioIndex].isIn(it) })
        val chosenSubtitles = choices.subtitles
        when (chosenSubtitles) {
            ChosenSubtitles.Off -> return TrackChoice(audioIndex, null)
            is ChosenSubtitles.Downloaded -> return TrackChoice(audioIndex, null, downloaded = primaryLanguage(chosenSubtitles.language), downloadedOffer = chosenSubtitles.offerId)
            is ChosenSubtitles.Embedded -> chosenSubtitles.track.find(subtitles)?.let { return TrackChoice(audioIndex, it) }
            null -> Unit
        }
        val picked = remembered.subtitles?.let { language -> subtitles.indexOfFirst { it.isIn(primaryLanguage(language)) }.takeIf { it >= 0 } }
        val subtitle = when {
            remembered.subtitlesOff -> null
            remembered.closedCaptions -> subtitles.indexOfFirst { it.isClosedCaptions }.takeIf { it >= 0 }
            picked != null -> picked
            remembered.subtitles != null -> return TrackChoice(audioIndex, null, downloaded = primaryLanguage(remembered.subtitles))
            heard -> null
            else -> contentLanguages.firstOrNull()?.let { language ->
                subtitles.indexOfFirst { it.isIn(primaryLanguage(language)) }.takeIf { it >= 0 }
                    ?: return TrackChoice(audioIndex, null, downloaded = primaryLanguage(language))
            }
        }
        return TrackChoice(audioIndex, subtitle)
    }

    /**
     * The languages to offer downloaded subtitles in for a file with these embedded [subtitles]: the
     * one the Viewer last picked and their Content languages, those the file has no subtitles in,
     * and the language of the downloaded ones they chose for this Title, whatever the file has.
     */
    fun downloadLanguages(subtitles: List<Track>, remembered: RememberedTracks = this.remembered, choices: TitleChoices = this.choices): List<String> {
        val chosen = (choices.subtitles as? ChosenSubtitles.Downloaded)?.language?.let(::primaryLanguage)
        return subtitleLanguages(remembered, choices).filter { language -> language == chosen || subtitles.none { it.isIn(language) } }
    }

    /**
     * Every language subtitles may be downloaded in for this plan, whatever the file holds, at most
     * [MAX_SUBTITLE_LANGUAGES]; the services know languages by ISO 639-1 code only.
     */
    fun subtitleLanguages(remembered: RememberedTracks = this.remembered, choices: TitleChoices = this.choices): List<String> =
        (listOfNotNull((choices.subtitles as? ChosenSubtitles.Downloaded)?.language, remembered.subtitles) + contentLanguages)
            .map(::primaryLanguage).filter { it.length == 2 }.distinct().take(MAX_SUBTITLE_LANGUAGES)

    /** Whether [track] is in the language the Title was released in: the player calls such audio "Original". */
    fun isOriginal(track: Track): Boolean = originalLanguage?.let { track.isIn(primaryLanguage(it)) } == true

    private fun Track.isIn(language: String) = this.language?.let(::trackLanguage) == language
}

/** The most languages one search for downloaded subtitles asks about, as the cloud allows. */
const val MAX_SUBTITLE_LANGUAGES = 5

/** An Episode of a Show, by season and episode number. */
data class EpisodeNumber(val season: Int, val number: Int)

/**
 * The audio and subtitle languages the Viewer last picked in the player, kept for the next file:
 * [audio] is null until they pick one, and [subtitles] is a language, or none when they turned
 * subtitles off ([subtitlesOff]), picked a Channel's [closedCaptions], or never picked any.
 */
data class RememberedTracks(val audio: String? = null, val subtitles: String? = null, val subtitlesOff: Boolean = false, val closedCaptions: Boolean = false)

/**
 * What the Viewer chose while a Title played, kept for the Title (#291): the [audio] Track and the
 * [subtitles] they picked, and how much later than the picture the sound ([audioDelay]) and the
 * subtitles ([subtitleDelay]) play, in milliseconds. Resuming the Title, or playing a Show's next
 * Episode, starts the same way where the file has the same Tracks; a null choice is one they never
 * made for this Title, so what they last picked anywhere ([RememberedTracks]) decides.
 */
data class TitleChoices(
    val audio: ChosenTrack? = null,
    val subtitles: ChosenSubtitles? = null,
    val audioDelay: Long = 0,
    val subtitleDelay: Long = 0,
)

/**
 * A Track the Viewer picked: its [language] as the file tags it (null when it doesn't), and its
 * [index] among the file's [count] Tracks of its kind, which finds an untagged Track again.
 */
data class ChosenTrack(val index: Int, val count: Int, val language: String?) {
    /**
     * The same Track among [tracks]: the one at its index when that is in its language, else the
     * first in its language; an untagged one is the one at its index in a file with as many Tracks,
     * untagged too. Null when the file doesn't have it.
     */
    internal fun find(tracks: List<Track>): Int? {
        val wanted = language?.let(::trackLanguage)
        if (wanted == null) return index.takeIf { tracks.size == count && it in tracks.indices && tracks[it].language == null }
        if (index in tracks.indices && tracks[index].language?.let(::trackLanguage) == wanted) return index
        return tracks.indexOfFirst { it.language?.let(::trackLanguage) == wanted }.takeIf { it >= 0 }
    }
}

/** The subtitles the Viewer chose for a Title: none, one of the file's Tracks, or a downloaded file. */
sealed interface ChosenSubtitles {
    /** The Viewer turned subtitles off for this Title. */
    data object Off : ChosenSubtitles

    /** One of the file's subtitle Tracks. */
    data class Embedded(val track: ChosenTrack) : ChosenSubtitles

    /** A downloaded subtitle file, by its [SubtitleOffer.id], in [language] (ISO 639-1). */
    data class Downloaded(val offerId: String, val language: String) : ChosenSubtitles
}

/** [TitleChoices] as kept on the device. */
internal fun TitleChoices.toJson(): String {
    fun track(track: ChosenTrack) = mapOf("index" to track.index, "count" to track.count, "language" to track.language)
    return choicesJson.toJson(
        mapOf(
            "audio" to audio?.let(::track),
            "subtitles" to when (val chosen = subtitles) {
                null -> null
                ChosenSubtitles.Off -> mapOf("off" to true)
                is ChosenSubtitles.Embedded -> mapOf("track" to track(chosen.track))
                is ChosenSubtitles.Downloaded -> mapOf("offer" to chosen.offerId, "language" to chosen.language)
            },
            "audioDelay" to audioDelay,
            "subtitleDelay" to subtitleDelay,
        ),
    )
}

/** Reads [TitleChoices] kept as JSON; null when it isn't in that shape. */
internal fun titleChoices(text: String): TitleChoices? = try {
    (choicesJson.fromJson(text) as? Map<*, *>)?.let { body ->
        fun track(map: Map<*, *>?) = map?.let { ChosenTrack((it["index"] as Number).toInt(), (it["count"] as Number).toInt(), it["language"] as? String) }
        val subtitles = (body["subtitles"] as? Map<*, *>)?.let {
            when {
                it["off"] == true -> ChosenSubtitles.Off
                it["offer"] is String -> ChosenSubtitles.Downloaded(it["offer"] as String, it["language"] as String)
                else -> ChosenSubtitles.Embedded(track(it["track"] as Map<*, *>)!!)
            }
        }
        TitleChoices(track(body["audio"] as? Map<*, *>), subtitles, (body["audioDelay"] as? Number)?.toLong() ?: 0, (body["subtitleDelay"] as? Number)?.toLong() ?: 0)
    }
} catch (e: IOException) {
    null
} catch (e: JsonDataException) {
    null
} catch (e: ClassCastException) {
    null
} catch (e: NullPointerException) {
    null
}

private val choicesJson = Moshi.Builder().build().adapter(Any::class.java).serializeNulls()

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
data class Track(val language: String?, val mimeType: String? = null, val channels: Int? = null) {
    /** Whether it is a live Channel's closed captions (CEA-608 or 708), which carry no language. */
    val isClosedCaptions get() = mimeType == CEA_608 || mimeType == CEA_708
}

/** The MIME types of closed captions carried in a live Channel's picture, as Media3 names them. */
const val CEA_608 = "application/cea-608"
const val CEA_708 = "application/cea-708"

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
 * [downloaded] is a language (ISO 639-1) to show downloaded subtitles in, which the file lacks;
 * [downloadedOffer] is the file to show, by [SubtitleOffer.id], when the Viewer chose one for this
 * Title, else the best in that language.
 */
data class TrackChoice(val audio: Int?, val subtitle: Int?, val downloaded: String? = null, val downloadedOffer: String? = null)

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
 * How long Play lets the Header read in flight finish before cutting it off, from the press: reads
 * took 0.5 to 1.6 s on a Mac and about 1.1 s an MKV on the stick (#414).
 */
internal val PLAY_WAITS_FOR_READ = 1_500.milliseconds

/** How long after Play is pressed no new Header read starts, should the plan it was pressed for never connect. */
private val PLAY_HOLDS_READS = 5_000.milliseconds

/**
 * Enforces the Connection limit on the device: one lease at a time, so a second stream is never
 * opened while one is in use. [lease] waits until the current lease is closed, and then for
 * [releaseGrace], because the Provider counts a closed stream as open for a moment after. A Header
 * read holding the connection is let finish for up to [readBound] from the press of Play
 * ([finishRead]), then cancelled; reads not yet started are dropped.
 */
internal class ConnectionGate(private val releaseGrace: Duration = PROVIDER_RELEASE_GRACE, private val readBound: Duration = PLAY_WAITS_FOR_READ) {
    private val semaphore = Semaphore(1)

    @Volatile private var releasedAt: TimeMark? = null

    /** The Header read holding the connection, if one is: cancelling it frees the connection at once. */
    @Volatile private var reading: Job? = null

    /** Set when Play is pressed, until its stream leases the connection: no Header read starts meanwhile. */
    @Volatile private var playPressedAt: TimeMark? = null

    /**
     * Takes the connection for playback. A Header read in flight is let finish, within [readBound]
     * of the press ([finishRead] counts the time already waited), and the reads behind it are dropped.
     */
    suspend fun lease(): Lease {
        finishRead()
        semaphore.acquire()
        try {
            releasedAt?.let { delay(releaseGrace - it.elapsedNow()) }
        } catch (e: CancellationException) {
            semaphore.release()
            throw e
        }
        playPressedAt = null
        return Lease()
    }

    /**
     * Play was pressed: lets the Header read holding the connection, if one is, finish, for up to
     * [readBound] from the first press, then cancels it, and keeps any further read from starting
     * until the stream leases the connection (or for a while, should none come). What the read
     * Confirmed is kept, so the play plan made after this names the Version it Confirmed.
     */
    suspend fun finishRead() {
        val pressed = playPressedAt?.takeIf { it.elapsedNow() < PLAY_HOLDS_READS } ?: TimeSource.Monotonic.markNow().also { playPressedAt = it }
        val read = reading ?: return
        val left = readBound - pressed.elapsedNow()
        if (left <= Duration.ZERO || withTimeoutOrNull(left) { read.join() } == null) read.cancel()
    }

    /** Cancels the Header read holding the connection, if one is, as Play once did. */
    fun cancelRead() {
        reading?.cancel()
    }

    /**
     * Runs [read] holding the connection if it is free right now, without waiting for it, and no
     * Play is on its way. A [lease] asked for meanwhile lets [read] finish within its bound, then
     * cancels it. Null when the connection was taken, before or during [read].
     */
    suspend fun <T : Any> whileFree(read: suspend () -> T): T? {
        if (playPressedAt?.let { it.elapsedNow() < PLAY_HOLDS_READS } == true) return null
        if (!semaphore.tryAcquire()) return null
        val lease = Lease()
        return try {
            coroutineScope {
                val job = async(start = CoroutineStart.LAZY) {
                    releasedAt?.let { delay(releaseGrace - it.elapsedNow()) }
                    read()
                }
                reading = job
                try {
                    job.await()
                } catch (e: CancellationException) {
                    // Rethrown when it's the caller that was cancelled, not the read.
                    ensureActive()
                    null
                }
            }
        } finally {
            reading = null
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

/**
 * A Track as VLC's player names it, for a stream whose Tracks LibVLC lists no other way, such as a
 * live Channel's: "Track 1 - [English]" is in English, "Closed captions 1" is CEA-608 captions. Null
 * for the closed captions after the first, which almost every Channel leaves empty.
 */
fun vlcNamedTrack(name: String): Track? {
    VLC_CLOSED_CAPTIONS.matchEntire(name)?.let { return if (it.groupValues[1] == "1") Track(null, CEA_608) else null }
    val language = VLC_LANGUAGE.find(name)?.groupValues?.get(1)?.let { LANGUAGES_BY_NAME[it.lowercase(Locale.ROOT)] }
    return Track(language)
}

private val VLC_CLOSED_CAPTIONS = Regex("""Closed captions (\d+)""")

/** The language VLC names at the end of a Track's name, in brackets. */
private val VLC_LANGUAGE = Regex("""\[([^\]]+)]\s*$""")

/** ISO 639-1 codes by their languages' English names, lower case, as VLC names them. */
private val LANGUAGES_BY_NAME: Map<String, String> by lazy {
    Locale.getISOLanguages().associateBy { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT) }
}
