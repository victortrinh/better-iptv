package dev.betteriptv.app

import dev.betteriptv.core.Quality
import org.junit.Assert.assertEquals
import org.junit.Test

/** Subtitles are drawn at the Viewer's size and height, the same on both engines. */
class SubtitleStyleTest {
    @Test
    fun sizesAreAShareOfMedia3sDefault() {
        assertEquals(listOf(0.65f, 0.8f, 1f, 1.3f), SubtitleSize.entries.map { it.scale })
        assertEquals(SubtitleSize.Medium, SubtitleStyle().size)
    }

    @Test
    fun positionsAreAShareOfThePicturesHeight() {
        assertEquals(listOf(0.05f, 0.15f), SubtitlePosition.entries.map { it.fromBottom })
        assertEquals(SubtitlePosition.Bottom, SubtitleStyle().position)
    }

    @Test
    fun vlcDrawsTextAtMedia3sSize() {
        // Media3's 5.33% of the picture's height against VLC's 6.25%.
        assertEquals(
            listOf(55, 68, 85, 111),
            SubtitleSize.entries.map { SubtitleStyle(size = it).vlcOptions(1080).option("--sub-text-scale") },
        )
    }

    @Test
    fun vlcsMarginGrowsWithThePicture() {
        // On top of the 4% VLC leaves by itself.
        assertEquals(11, SubtitleStyle(position = SubtitlePosition.Bottom).vlcOptions(1080).option("--sub-margin"))
        assertEquals(22, SubtitleStyle(position = SubtitlePosition.Bottom).vlcOptions(2160).option("--sub-margin"))
        assertEquals(119, SubtitleStyle(position = SubtitlePosition.Raised).vlcOptions(1080).option("--sub-margin"))
        assertEquals(238, SubtitleStyle(position = SubtitlePosition.Raised).vlcOptions(2160).option("--sub-margin"))
    }

    @Test
    fun vlcDrawsOnTheTallerOfTheFileAndTheScreen() {
        assertEquals(2160, vlcSubtitleHeight(Quality.UHD, 1080))
        assertEquals(1080, vlcSubtitleHeight(Quality.FullHD, 1080))
        assertEquals(1080, vlcSubtitleHeight(Quality.HD, 1080))
        assertEquals(1080, vlcSubtitleHeight(null, 1080))
        assertEquals(2160, vlcSubtitleHeight(Quality.FullHD, 2160))
    }

    private fun List<String>.option(name: String): Int = single { it.startsWith("$name=") }.substringAfter('=').toInt()
}
