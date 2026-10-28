package dev.betteriptv.app

import dev.betteriptv.core.FileContents
import dev.betteriptv.core.Track
import org.junit.Assert.assertEquals
import org.junit.Test
import org.videolan.libvlc.interfaces.IMedia

class VlcContentsTest {
    private fun audio(codec: String, language: String?, channels: Int) = IMedia.AudioTrack(codec, codec, 0, 1, 0, 0, 0, language, null, channels, 48_000)

    private fun video(width: Int, height: Int) = IMedia.VideoTrack("hevc", "hevc", 0, 0, 0, 0, 0, null, null, height, width, 1, 1, 24, 1, 0, 0)

    private fun subtitles(language: String?) = IMedia.SubtitleTrack("subt", "subt", 0, 2, 0, 0, 0, language, null, null)

    @Test
    fun everyTrackVlcFoundIsWhatTheFileHolds() {
        val contents = vlcContents(listOf(video(1920, 800), audio("eac3", "eng", 6), audio("a52 ", "fre", 2), subtitles("fre"), subtitles(null)))

        assertEquals(
            FileContents(
                audio = listOf(Track("eng", "audio/eac3", 6), Track("fre", "audio/ac3", 2)),
                subtitles = listOf(Track("fre"), Track(null)),
                width = 1920,
                height = 800,
            ),
            contents,
        )
    }

    @Test
    fun aCodecWithoutAMediaTypeIsLeftUnnamed() {
        assertEquals(listOf(Track("ger", null, null)), vlcContents(listOf(audio("wma2", "ger", 0))).audio)
    }
}
