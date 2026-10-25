package dev.betteriptv.app

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioCapabilities
import dev.betteriptv.core.DeviceProfile
import dev.betteriptv.core.Hdr

/**
 * What this stick and the TV it's plugged into can play, read from the platform: the TV's display
 * modes and HDR formats, the stick's hardware video decoders, and the audio formats the HDMI link
 * passes through. Read it again for each Play, since the TV or soundbar may have changed.
 */
fun readDeviceProfile(context: Context): DeviceProfile {
    val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    val decoders = hardwareVideoDecoders()
    // Mode sizes are landscape, but take the short side in case a stick reports them rotated.
    val displayHeight = display.supportedModes.maxOfOrNull { minOf(it.physicalWidth, it.physicalHeight) } ?: 1080
    // 4K Versions are HEVC; a stick that can't decode 4K HEVC can't play them however good the TV is.
    val decoderHeight = when {
        decoders.any { it.decodes(MimeTypes.VIDEO_H265, 3840, 2160) } -> 2160
        decoders.any { it.decodes(MimeTypes.VIDEO_H264, 1920, 1080) || it.decodes(MimeTypes.VIDEO_H265, 1920, 1080) } -> 1080
        else -> 720
    }
    return DeviceProfile(
        maxHeight = minOf(displayHeight, decoderHeight),
        hdr = hdrFormats(display, decoders),
        videoDecoders = decoders.flatMap { it.types }.toSet(),
        audioPassthrough = audioPassthrough(context),
    )
}

private class Decoder(val info: MediaCodecInfo, val types: List<String>) {
    fun decodes(type: String, width: Int, height: Int): Boolean =
        type in types && info.getCapabilitiesForType(type).videoCapabilities?.isSizeSupported(width, height) == true
}

private fun hardwareVideoDecoders(): List<Decoder> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
    .filter { !it.isEncoder && it.isHardware() }
    .map { info -> Decoder(info, info.supportedTypes.filter { it.startsWith("video/") }.map { it.lowercase() }) }
    .filter { it.types.isNotEmpty() }

/** Before Android 10 there is no flag, so software decoders are told apart by their well-known names. */
private fun MediaCodecInfo.isHardware(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isHardwareAccelerated
    else !name.startsWith("OMX.google.") && !name.startsWith("c2.android.")

/** The HDR formats the TV displays and the stick can decode: Dolby Vision needs its own decoder. */
@Suppress("DEPRECATION") // Display.Mode.getSupportedHdrTypes() only exists from Android 14.
private fun hdrFormats(display: Display, decoders: List<Decoder>): Set<Hdr> {
    val types = display.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()
    return buildSet {
        if (Display.HdrCapabilities.HDR_TYPE_HDR10 in types && decoders.any { MimeTypes.VIDEO_H265 in it.types }) add(Hdr.HDR10)
        if (Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in types && decoders.any { MimeTypes.VIDEO_DOLBY_VISION in it.types }) add(Hdr.DolbyVision)
    }
}

private val PASSTHROUGH = mapOf(
    C.ENCODING_AC3 to MimeTypes.AUDIO_AC3,
    C.ENCODING_E_AC3 to MimeTypes.AUDIO_E_AC3,
    C.ENCODING_E_AC3_JOC to MimeTypes.AUDIO_E_AC3_JOC,
    C.ENCODING_DTS to MimeTypes.AUDIO_DTS,
    C.ENCODING_DTS_HD to MimeTypes.AUDIO_DTS_HD,
    C.ENCODING_DOLBY_TRUEHD to MimeTypes.AUDIO_TRUEHD,
)

private fun audioPassthrough(context: Context): Set<String> {
    val capabilities = AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
    return PASSTHROUGH.filterKeys(capabilities::supportsEncoding).values.toSet()
}
