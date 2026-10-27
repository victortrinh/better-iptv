package dev.betteriptv.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** A Track to write into a test file: [kind] is `audio` or `subtitle`, [language] ISO 639-2 as muxers write it. */
data class FileTrack(val kind: String, val language: String?, val codec: String, val channels: Int = 2)

/**
 * A Matroska file as mkvmerge lays one out: the EBML header, then a Segment with its Info and
 * Tracks, then Clusters, padded to [size] bytes so its bitrate is known.
 */
fun matroskaFile(
    tracks: List<FileTrack>,
    width: Int = 1920,
    height: Int = 800,
    videoCodec: String = "V_MPEGH/ISO/HEVC",
    transfer: Int? = null,
    dolbyVision: Boolean = false,
    seconds: Double = 100.0,
    size: Int = 1_000_000,
): ByteArray {
    val header = ebml(0x1A45DFA3, ebmlUint(0x4282, "matroska"))
    val info = ebml(0x1549A966, ebmlUint(0x2AD7B1, 1_000_000L) + ebml(0x4489, ByteBuffer.allocate(8).putDouble(seconds * 1000).array()))
    val video = ebml(
        0xAE,
        ebmlUint(0xD7, 1L) + ebmlUint(0x83, 1L) + ebmlUint(0x86, videoCodec) +
            (if (dolbyVision) ebml(0x41E4, ebmlUint(0x41E7, 0x64766343L)) else ByteArray(0)) +
            ebml(0xE0, ebmlUint(0xB0, width.toLong()) + ebmlUint(0xBA, height.toLong()) + (transfer?.let { ebml(0x55B0, ebmlUint(0x55BA, it.toLong())) } ?: ByteArray(0))),
    )
    val entries = tracks.mapIndexed { i, track ->
        ebml(
            0xAE,
            ebmlUint(0xD7, i + 2L) + ebmlUint(0x83, if (track.kind == "audio") 2L else 17L) + ebmlUint(0x86, track.codec) +
                (track.language?.let { ebmlUint(0x22B59C, it) } ?: ByteArray(0)) +
                (if (track.kind == "audio") ebml(0xE1, ebmlUint(0x9F, track.channels.toLong())) else ByteArray(0)),
        )
    }
    val tracksElement = ebml(0x1654AE6B, entries.fold(video) { acc, entry -> acc + entry })
    val segmentStart = header.size + 4 + 8
    val clusterHeader = 4 + 8
    val clusterBody = ByteArray(size - segmentStart - info.size - tracksElement.size - clusterHeader)
    return header + ebml(0x18538067, info + tracksElement + ebml(0x1F43B675, clusterBody))
}

/**
 * An MP4 file with its track index (`moov`) at its start, as a streaming-ready file has it, or at
 * its end after [pictureBytes] of picture, as a plain encode has it.
 */
fun mp4File(
    tracks: List<FileTrack>,
    width: Int = 1920,
    height: Int = 1080,
    videoCodec: String = "avc1",
    transfer: Int? = null,
    seconds: Long = 100,
    indexAtEnd: Boolean = true,
    pictureBytes: Int = 600_000,
): ByteArray {
    val ftyp = box("ftyp", "isom".toByteArray() + u32(512) + "isomiso2avc1mp41".toByteArray())
    val mvhd = box("mvhd", u32(0) + u32(0) + u32(0) + u32(1000) + u32(seconds * 1000) + ByteArray(80))
    val visual = ByteArray(6) + u16(1) + ByteArray(16) + u16(width) + u16(height) + ByteArray(50) +
        (transfer?.let { box("colr", "nclx".toByteArray() + u16(9) + u16(it) + u16(9) + byteArrayOf(0)) } ?: ByteArray(0))
    val traks = listOf(trak("vide", "und", box(videoCodec, visual))) + tracks.map { track ->
        val entry = if (track.kind == "audio") box(track.codec, ByteArray(6) + u16(1) + ByteArray(8) + u16(track.channels) + u16(16) + ByteArray(4) + u32(48000L shl 16))
        else box(track.codec, ByteArray(6) + u16(1))
        trak(if (track.kind == "audio") "soun" else "sbtl", track.language ?: "und", entry)
    }
    val moov = box("moov", traks.fold(mvhd) { acc, trak -> acc + trak })
    val mdat = box("mdat", ByteArray(pictureBytes))
    return if (indexAtEnd) ftyp + mdat + moov else ftyp + moov + mdat
}

private fun trak(handler: String, language: String, sampleEntry: ByteArray): ByteArray {
    val packed = language.fold(0) { acc, c -> (acc shl 5) or (c.code - 0x60) }
    val mdhd = box("mdhd", u32(0) + u32(0) + u32(0) + u32(1000) + u32(0) + u16(packed) + u16(0))
    val hdlr = box("hdlr", u32(0) + u32(0) + handler.toByteArray() + ByteArray(12) + byteArrayOf(0))
    val stsd = box("stsd", u32(0) + u32(1) + sampleEntry)
    val tkhd = box("tkhd", u32(0) + ByteArray(80))
    return box("trak", tkhd + box("mdia", mdhd + hdlr + box("minf", box("stbl", stsd))))
}

private fun box(type: String, payload: ByteArray) = u32(8L + payload.size) + type.toByteArray(Charsets.ISO_8859_1) + payload

private fun u32(value: Long) = ByteBuffer.allocate(4).putInt(value.toInt()).array()

private fun u32(value: Int) = u32(value.toLong())

private fun u16(value: Int) = ByteBuffer.allocate(2).putShort(value.toShort()).array()

/** An EBML element with an 8-byte size, as mkvmerge writes the large ones. */
private fun ebml(id: Long, payload: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    var idBytes = id
    val ids = ArrayList<Byte>()
    while (idBytes > 0) {
        ids.add(0, (idBytes and 0xFF).toByte())
        idBytes = idBytes shr 8
    }
    out.write(ids.toByteArray())
    out.write(byteArrayOf(0x01) + ByteBuffer.allocate(8).putLong(payload.size.toLong()).array().copyOfRange(1, 8))
    out.write(payload)
    return out.toByteArray()
}

private fun ebmlUint(id: Long, value: Long) = ebml(id, ByteBuffer.allocate(8).putLong(value).array())

private fun ebmlUint(id: Long, text: String) = ebml(id, text.toByteArray())

private fun ebml(id: Int, payload: ByteArray) = ebml(id.toLong(), payload)

private fun ebmlUint(id: Int, value: Long) = ebmlUint(id.toLong(), value)

private fun ebmlUint(id: Int, text: String) = ebmlUint(id.toLong(), text)
