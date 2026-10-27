package dev.betteriptv.core

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * What a Version's file holds, as a Header read or Playback found it: its audio and subtitle
 * Tracks, the picture's size and HDR format, and its bitrate in bits per second. Null means the
 * file didn't say.
 */
data class FileContents(
    val audio: List<Track>,
    val subtitles: List<Track>,
    val width: Int? = null,
    val height: Int? = null,
    val hdr: Hdr? = null,
    val bitrate: Long? = null,
)

/**
 * These Likely attributes with what the [file] holds Confirmed. The language stays the Prefix's
 * (keeping a regional one, `fr-CA`) when an audio Track is in it, and is otherwise the first audio
 * Track's: an `FR` Version without a French Track moves to the language it really has. An HDR
 * format the file doesn't name leaves the Prefix's standing, since not every file names it.
 */
internal fun VersionAttributes.confirmedBy(file: FileContents): VersionAttributes {
    val heard = file.audio.mapNotNull { iso639(it.language) }
    val likely = language?.value
    return copy(
        language = when {
            heard.isEmpty() -> language
            likely != null && primaryLanguage(likely) in heard -> Known(likely, Provenance.Confirmed)
            else -> Known(heard.first(), Provenance.Confirmed)
        },
        subtitles = Known(file.subtitles.mapNotNull { iso639(it.language) }.toSet(), Provenance.Confirmed),
        quality = quality(file.width, file.height)?.let { Known(it, Provenance.Confirmed) } ?: quality,
        hdr = file.hdr?.let { Known(it, Provenance.Confirmed) } ?: hdr,
        bitrate = file.bitrate?.let { Known(it, Provenance.Confirmed) } ?: bitrate,
        dolbyAudio = dolbyAudio || file.audio.any { it.mimeType in DOLBY_AUDIO },
    )
}

private val DOLBY_AUDIO = setOf("audio/ac3", "audio/eac3", "audio/eac3-joc", "audio/true-hd")

/** The file extensions a Header read understands. */
internal val HEADER_READABLE = setOf("mkv", "webm", "mp4", "m4v", "mov")

/**
 * Header reads a Version's file: about the first 256 KB of an MKV through one range request, and an
 * MP4's start, plus its end when the track index sits there. Cancelling stops the request at once,
 * which frees the Viewer's connection.
 */
internal class HeaderReader(private val http: OkHttpClient) {
    /** What the file at [location] holds; null when its header can't be read. Throws an [IOException] when the Provider refuses. */
    suspend fun read(location: String, container: String): FileContents? {
        val start = fetch(location, 0, START_BYTES)
        if (container !in MP4_CONTAINERS) return readMatroska(start.bytes, start.size)
        var part = start
        var offset = 0L
        // The start, then the index where the start says it is; never more.
        repeat(2) {
            when (val read = readMp4(part.bytes, offset, start.size)) {
                is Mp4Read.Found -> return read.contents
                Mp4Read.Unreadable -> return null
                is Mp4Read.More -> {
                    offset = read.from
                    part = fetch(location, read.from, read.length)
                }
            }
        }
        return (readMp4(part.bytes, offset, start.size) as? Mp4Read.Found)?.contents
    }

    private class Part(val bytes: ByteArray, val size: Long?)

    /** Up to [length] bytes of the file from [from], and the whole file's size when the Provider says. */
    private suspend fun fetch(location: String, from: Long, length: Long): Part = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url(location).header("Range", "bytes=$from-${from + length - 1}").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        // Never the location in a message: it holds the login.
                        if (response.code != 200 && response.code != 206) throw IOException("The Provider answered a Header read with HTTP ${response.code}.")
                        val size = if (response.code == 206) response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                        else response.body!!.contentLength().takeIf { it >= 0 }
                        // A Provider that ignores the range sends the whole file: the start is all that's read of it.
                        val source = response.body!!.source()
                        val buffer = Buffer()
                        while (buffer.size < length && source.read(buffer, length - buffer.size) != -1L) Unit
                        continuation.resume(Part(buffer.readByteArray(minOf(buffer.size, length)), size))
                    }
                } catch (e: IOException) {
                    continuation.resumeWithException(e)
                }
            }
        })
    }

    private companion object {
        const val START_BYTES = 256L * 1024
        val MP4_CONTAINERS = setOf("mp4", "m4v", "mov")
    }
}

// Matroska

/**
 * What a Matroska file's start says: its Tracks, picture and, with the file's [size], bitrate.
 * Null when [bytes] end before the Tracks do.
 */
internal fun readMatroska(bytes: ByteArray, size: Long?): FileContents? {
    val segment = bytes.ebml(0, bytes.size).firstOrNull { it.id == SEGMENT } ?: return null
    var scale = 1_000_000L
    var duration: Double? = null
    var tracks: List<MatroskaTrack>? = null
    for (child in bytes.ebml(segment.start, segment.end)) {
        when (child.id) {
            INFO -> bytes.ebml(child.start, child.end).forEach {
                when (it.id) {
                    TIMESTAMP_SCALE -> scale = bytes.uint(it)
                    DURATION -> duration = bytes.float(it)
                }
            }
            TRACKS -> {
                if (!child.complete) return null
                tracks = bytes.ebml(child.start, child.end).filter { it.id == TRACK_ENTRY }.map { bytes.matroskaTrack(it) }.toList()
            }
            CLUSTER -> break
        }
    }
    val read = tracks ?: return null
    val video = read.firstOrNull { it.type == 1L }
    val seconds = duration?.let { it * scale / 1e9 }?.takeIf { it > 0 }
    return FileContents(
        audio = read.filter { it.type == 2L }.map { Track(it.language, MATROSKA_CODECS[it.codec], it.channels) },
        subtitles = read.filter { it.type == 17L }.map { Track(it.language, MATROSKA_CODECS[it.codec]) },
        width = video?.width,
        height = video?.height,
        hdr = when {
            video == null -> null
            video.dolbyVision -> Hdr.DolbyVision
            video.transfer == PQ -> Hdr.HDR10
            else -> null
        },
        bitrate = if (size != null && seconds != null) (size * 8 / seconds).toLong() else null,
    )
}

private class MatroskaTrack(
    val type: Long?,
    val codec: String?,
    val language: String?,
    val channels: Int?,
    val width: Int?,
    val height: Int?,
    val transfer: Long?,
    val dolbyVision: Boolean,
)

private fun ByteArray.matroskaTrack(entry: Ebml): MatroskaTrack {
    var type: Long? = null
    var codec: String? = null
    // Matroska's default language, for a Track that names none.
    var language = "eng"
    var bcp47: String? = null
    var channels: Int? = null
    var width: Int? = null
    var height: Int? = null
    var transfer: Long? = null
    var dolbyVision = false
    for (field in ebml(entry.start, entry.end)) when (field.id) {
        TRACK_TYPE -> type = uint(field)
        CODEC_ID -> codec = text(field)
        LANGUAGE -> language = text(field)
        LANGUAGE_BCP47 -> bcp47 = text(field)
        AUDIO -> ebml(field.start, field.end).filter { it.id == CHANNELS }.forEach { channels = uint(it).toInt() }
        VIDEO -> ebml(field.start, field.end).forEach { video ->
            when (video.id) {
                PIXEL_WIDTH -> width = uint(video).toInt()
                PIXEL_HEIGHT -> height = uint(video).toInt()
                COLOUR -> ebml(video.start, video.end).filter { it.id == TRANSFER }.forEach { transfer = uint(it) }
            }
        }
        BLOCK_ADDITION_MAPPING -> ebml(field.start, field.end).filter { it.id == BLOCK_ADD_ID_TYPE }.forEach {
            if (uint(it) in DOLBY_VISION_CONFIGURATIONS) dolbyVision = true
        }
    }
    return MatroskaTrack(type, codec, bcp47 ?: language, channels, width, height, transfer, dolbyVision)
}

/** One EBML element: its id and where its content lies in the bytes read. [complete] is false when they end before it does. */
private class Ebml(val id: Long, val start: Int, val end: Int, val complete: Boolean)

/** The EBML elements between [from] and [to]. One of unknown size runs to [to], as a live-muxed Segment does. */
private fun ByteArray.ebml(from: Int, to: Int): Sequence<Ebml> = sequence {
    var at = from
    while (at < to) {
        val (id, idLength) = vint(at, to, keepMarker = true) ?: break
        val (size, sizeLength) = vint(at + idLength, to, keepMarker = false) ?: break
        val start = at + idLength + sizeLength
        val declaredEnd = if (size < 0) to.toLong() else start + size
        yield(Ebml(id, start, minOf(declaredEnd, to.toLong()).toInt(), declaredEnd <= to))
        at = minOf(declaredEnd, to.toLong()).toInt()
    }
}

/** An EBML variable-length integer at [at] and its length; a size of all ones (unknown) is -1. */
private fun ByteArray.vint(at: Int, to: Int, keepMarker: Boolean): Pair<Long, Int>? {
    if (at >= to) return null
    val first = this[at].toInt() and 0xFF
    if (first == 0) return null
    val length = Integer.numberOfLeadingZeros(first) - 23
    if (at + length > to) return null
    var value = (if (keepMarker) first else first and (0xFF shr length)).toLong()
    var allOnes = value == (0xFF shr length).toLong()
    for (i in 1 until length) {
        val b = this[at + i].toInt() and 0xFF
        allOnes = allOnes && b == 0xFF
        value = (value shl 8) or b.toLong()
    }
    return (if (!keepMarker && allOnes) -1L else value) to length
}

private fun ByteArray.uint(element: Ebml): Long = (element.start until element.end).fold(0L) { acc, i -> (acc shl 8) or (this[i].toLong() and 0xFF) }

private fun ByteArray.float(element: Ebml): Double? = when (element.end - element.start) {
    4 -> Float.fromBits(uint(element).toInt()).toDouble()
    8 -> Double.fromBits(uint(element))
    else -> null
}

private fun ByteArray.text(element: Ebml): String = String(this, element.start, element.end - element.start, Charsets.UTF_8).trimEnd('\u0000')

private const val SEGMENT = 0x18538067L
private const val INFO = 0x1549A966L
private const val TIMESTAMP_SCALE = 0x2AD7B1L
private const val DURATION = 0x4489L
private const val TRACKS = 0x1654AE6BL
private const val TRACK_ENTRY = 0xAEL
private const val TRACK_TYPE = 0x83L
private const val CODEC_ID = 0x86L
private const val LANGUAGE = 0x22B59CL
private const val LANGUAGE_BCP47 = 0x22B59DL
private const val AUDIO = 0xE1L
private const val CHANNELS = 0x9FL
private const val VIDEO = 0xE0L
private const val PIXEL_WIDTH = 0xB0L
private const val PIXEL_HEIGHT = 0xBAL
private const val COLOUR = 0x55B0L
private const val TRANSFER = 0x55BAL
private const val BLOCK_ADDITION_MAPPING = 0x41E4L
private const val BLOCK_ADD_ID_TYPE = 0x41E7L
private const val CLUSTER = 0x1F43B675L

/** SMPTE ST 2084, the transfer HDR10 pictures use. */
private const val PQ = 16L

/** `dvcC` and `dvvC`: a Dolby Vision configuration, as a Matroska block addition type. */
private val DOLBY_VISION_CONFIGURATIONS = setOf(0x64766343L, 0x64767643L)

/** Media3's MIME types by Matroska codec id. */
private val MATROSKA_CODECS = mapOf(
    "V_MPEG4/ISO/AVC" to "video/avc", "V_MPEGH/ISO/HEVC" to "video/hevc", "V_AV1" to "video/av01", "V_VP9" to "video/x-vnd.on2.vp9",
    "A_AAC" to "audio/mp4a-latm", "A_AC3" to "audio/ac3", "A_EAC3" to "audio/eac3", "A_DTS" to "audio/vnd.dts", "A_TRUEHD" to "audio/true-hd",
    "A_OPUS" to "audio/opus", "A_FLAC" to "audio/flac", "A_MPEG/L3" to "audio/mpeg", "A_MPEG/L2" to "audio/mpeg-L2", "A_VORBIS" to "audio/vorbis",
    "S_TEXT/UTF8" to "application/x-subrip", "S_TEXT/ASS" to "text/x-ssa", "S_TEXT/SSA" to "text/x-ssa", "S_HDMV/PGS" to "application/pgs",
    "S_VOBSUB" to "application/vobsub", "S_TEXT/WEBVTT" to "text/vtt",
)

// MP4

internal sealed interface Mp4Read {
    class Found(val contents: FileContents) : Mp4Read

    /** The track index lies elsewhere: [length] bytes from [from] hold it. */
    class More(val from: Long, val length: Long) : Mp4Read

    data object Unreadable : Mp4Read
}

/**
 * What [bytes], read from [offset] in an MP4 file of [size] bytes, say about it: its Tracks once
 * they hold the whole track index (`moov`), or where to read it from.
 */
internal fun readMp4(bytes: ByteArray, offset: Long, size: Long?): Mp4Read {
    var at = 0L
    while (at + 8 <= bytes.size) {
        val (type, length, header) = bytes.box(at.toInt(), bytes.size) ?: return Mp4Read.Unreadable
        val boxSize = if (length == 0L) (size ?: return Mp4Read.Unreadable) - offset - at else length
        if (boxSize < header) return Mp4Read.Unreadable
        if (type == "moov") {
            if (at + boxSize <= bytes.size) return Mp4Read.Found(readMoov(bytes, (at + header).toInt(), (at + boxSize).toInt(), size))
            // Fetched once from its start and still short, it is beyond reading.
            return if (at == 0L && offset > 0 || boxSize > MAX_INDEX) Mp4Read.Unreadable else Mp4Read.More(offset + at, boxSize)
        }
        at += boxSize
    }
    // The next box starts past what was read: the index is after the picture.
    val next = offset + at
    if (size != null && next >= size) return Mp4Read.Unreadable
    return Mp4Read.More(next, minOf(MAX_INDEX, (size ?: Long.MAX_VALUE) - next))
}

/** The largest track index read: a feature film's is a few MB. */
private const val MAX_INDEX = 8L * 1024 * 1024

/** A box's type, size and header length at [at], or null when [bytes] end within its header. */
private fun ByteArray.box(at: Int, to: Int): Triple<String, Long, Int>? {
    if (at + 8 > to) return null
    val size = u32(at)
    val type = String(this, at + 4, 4, Charsets.ISO_8859_1)
    if (size != 1L) return Triple(type, size, 8)
    if (at + 16 > to) return null
    return Triple(type, (u32(at + 8) shl 32) or u32(at + 12), 16)
}

private class Mp4Box(val type: String, val start: Int, val end: Int)

private fun ByteArray.boxes(from: Int, to: Int): Sequence<Mp4Box> = sequence {
    var at = from
    while (at + 8 <= to) {
        val (type, size, header) = box(at, to) ?: break
        val end = if (size == 0L) to.toLong() else at + size
        if (size != 0L && size < header) break
        yield(Mp4Box(type, at + header, minOf(end, to.toLong()).toInt()))
        at = minOf(end, to.toLong()).toInt()
    }
}

private fun ByteArray.child(box: Mp4Box, type: String) = boxes(box.start, box.end).firstOrNull { it.type == type }

private class Mp4Track(val handler: String, val track: Track, val width: Int?, val height: Int?, val hdr: Hdr?)

private fun readMoov(bytes: ByteArray, from: Int, to: Int, size: Long?): FileContents {
    var seconds: Double? = null
    val tracks = mutableListOf<Mp4Track>()
    for (box in bytes.boxes(from, to)) when (box.type) {
        "mvhd" -> seconds = bytes.mvhdSeconds(box)
        "trak" -> bytes.mp4Track(box)?.let(tracks::add)
    }
    val video = tracks.firstOrNull { it.handler == "vide" }
    return FileContents(
        audio = tracks.filter { it.handler == "soun" }.map { it.track },
        subtitles = tracks.filter { it.handler in setOf("subt", "sbtl", "text") }.map { it.track },
        width = video?.width,
        height = video?.height,
        hdr = video?.hdr,
        bitrate = if (size != null && seconds != null && seconds > 0) (size * 8 / seconds).toLong() else null,
    )
}

private fun ByteArray.mvhdSeconds(box: Mp4Box): Double? {
    val version = this[box.start].toInt()
    val (scaleAt, durationAt) = if (version == 1) 20 to 24 else 12 to 16
    if (box.start + durationAt + (if (version == 1) 8 else 4) > box.end) return null
    val scale = u32(box.start + scaleAt).takeIf { it > 0 } ?: return null
    val duration = if (version == 1) (u32(box.start + durationAt) shl 32) or u32(box.start + durationAt + 4) else u32(box.start + durationAt)
    return duration.toDouble() / scale
}

private fun ByteArray.mp4Track(trak: Mp4Box): Mp4Track? {
    val mdia = child(trak, "mdia") ?: return null
    val handler = child(mdia, "hdlr")?.takeIf { it.start + 12 <= it.end }?.let { String(this, it.start + 8, 4, Charsets.ISO_8859_1) } ?: return null
    val language = child(mdia, "mdhd")?.let { mdhd ->
        val at = mdhd.start + if (this[mdhd.start].toInt() == 1) 32 else 20
        if (at + 2 > mdhd.end) return@let null
        // Three letters of five bits each, offset from 0x60.
        val packed = u16(at)
        buildString { listOf(10, 5, 0).forEach { shift -> append((((packed shr shift) and 31) + 0x60).toChar()) } }
    }
    val stsd = child(mdia, "minf")?.let { child(it, "stbl") }?.let { child(it, "stsd") }
    val entry = stsd?.let { boxes(it.start + 8, it.end).firstOrNull() }
        ?: return Mp4Track(handler, Track(language), null, null, null)
    val codec = MP4_CODECS[entry.type]
    // A sample entry's own fields start after 6 reserved bytes and a data reference index.
    val p = entry.start
    return when (handler) {
        "vide" -> {
            val sized = p + 28 <= entry.end
            val children = if (p + 78 <= entry.end) boxes(p + 78, entry.end).toList() else emptyList()
            val transfer = children.firstOrNull { it.type == "colr" && it.start + 8 <= it.end && String(this, it.start, 4, Charsets.ISO_8859_1) == "nclx" }
                ?.let { u16(it.start + 6).toLong() }
            val hdr = when {
                entry.type in setOf("dvh1", "dvhe", "dva1", "dvav") || children.any { it.type == "dvcC" || it.type == "dvvC" } -> Hdr.DolbyVision
                transfer == PQ -> Hdr.HDR10
                else -> null
            }
            Mp4Track(handler, Track(language, codec), if (sized) u16(p + 24) else null, if (sized) u16(p + 26) else null, hdr)
        }
        "soun" -> Mp4Track(handler, Track(language, codec, if (p + 18 <= entry.end) u16(p + 16) else null), null, null, null)
        else -> Mp4Track(handler, Track(language, codec), null, null, null)
    }
}

private fun ByteArray.u32(at: Int): Long =
    ((this[at].toLong() and 0xFF) shl 24) or ((this[at + 1].toLong() and 0xFF) shl 16) or ((this[at + 2].toLong() and 0xFF) shl 8) or (this[at + 3].toLong() and 0xFF)

private fun ByteArray.u16(at: Int): Int = ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)

/** Media3's MIME types by MP4 sample entry type. */
private val MP4_CODECS = mapOf(
    "avc1" to "video/avc", "avc3" to "video/avc", "hvc1" to "video/hevc", "hev1" to "video/hevc", "dvh1" to "video/dolby-vision",
    "dvhe" to "video/dolby-vision", "av01" to "video/av01", "vp09" to "video/x-vnd.on2.vp9",
    "mp4a" to "audio/mp4a-latm", "ac-3" to "audio/ac3", "ec-3" to "audio/eac3", "Opus" to "audio/opus", "fLaC" to "audio/flac",
    "tx3g" to "text/3gpp", "wvtt" to "text/vtt", "stpp" to "application/ttml+xml",
)

// Kept as JSON, on the device and in the cloud

/** [FileContents] as kept on the device and shared through the cloud. */
internal fun FileContents.toJson(): String = json.toJson(
    mapOf(
        "audio" to audio.map { mapOf("language" to it.language, "codec" to it.mimeType, "channels" to it.channels) },
        "subtitles" to subtitles.map { mapOf("language" to it.language, "codec" to it.mimeType) },
        "width" to width,
        "height" to height,
        "hdr" to hdr?.name,
        "bitrate" to bitrate,
    ),
)

/** Reads [FileContents] kept as JSON; null when it isn't in that shape. */
internal fun fileContents(text: String): FileContents? = try {
    (json.fromJson(text) as? Map<*, *>)?.let { file ->
        fun tracks(name: String) = (file[name] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().map {
            Track(it["language"] as? String, it["codec"] as? String, (it["channels"] as? Number)?.toInt())
        }
        FileContents(
            tracks("audio"),
            tracks("subtitles"),
            (file["width"] as? Number)?.toInt(),
            (file["height"] as? Number)?.toInt(),
            Hdr.entries.find { it.name == file["hdr"] },
            (file["bitrate"] as? Number)?.toLong(),
        )
    }
} catch (e: IOException) {
    null
} catch (e: JsonDataException) {
    null
}

private val json = Moshi.Builder().build().adapter(Any::class.java).serializeNulls()
