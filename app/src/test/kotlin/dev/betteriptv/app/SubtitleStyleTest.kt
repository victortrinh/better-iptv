package dev.betteriptv.app

import dev.betteriptv.core.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Subtitles are drawn at the Viewer's size and height, the same on both engines. */
class SubtitleStyleTest {
    @Test
    fun sizesAreAShareOfMedia3sDefault() {
        assertEquals(listOf(0.65f, 0.8f, 1f, 1.3f), SubtitleSize.entries.map { it.scale })
        assertEquals(SubtitleSize.Medium, SubtitleStyle().size)
    }

    @Test
    fun positionsAreAShareOfTheScreensHeight() {
        assertEquals(0.05f, SubtitlePosition.Bottom.fromBottom)
        assertEquals(0.26f, SubtitlePosition.Raised.fromBottom)
        // Raised clears the overlay's scrubber, whose track starts 258 lines up a 1080-line screen (#277).
        assertTrue(SubtitlePosition.Raised.fromBottom * 1080 > 258 + 12)
        assertEquals(SubtitlePosition.Bottom, SubtitleStyle().position)
    }

    @Test
    fun positionsStepFromTheBottomToHalfTheScreen() {
        val steps = generateSequence(SubtitlePosition(SubtitlePosition.LOWEST)) { it.higher().takeIf { next -> next != it } }.map { it.percent }.toList()
        assertEquals((2..50 step 3).toList(), steps)
        // Bottom and Raised, the two heights before they were stepped, are both a step.
        assertTrue(SubtitlePosition.Bottom.percent in steps && SubtitlePosition.Raised.percent in steps)
        assertEquals(SubtitlePosition(2), SubtitlePosition(2).lower())
        assertEquals(SubtitlePosition(50), SubtitlePosition(50).higher())
        assertEquals(SubtitlePosition(23), SubtitlePosition.Raised.lower())
    }

    @Test
    fun aStoredHeightOffTheStepsLandsOnTheNearest() {
        assertEquals(SubtitlePosition(26), SubtitlePosition.nearest(26))
        assertEquals(SubtitlePosition(26), SubtitlePosition.nearest(27))
        assertEquals(SubtitlePosition(29), SubtitlePosition.nearest(28))
        assertEquals(SubtitlePosition(2), SubtitlePosition.nearest(-4))
        assertEquals(SubtitlePosition(50), SubtitlePosition.nearest(90))
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
        assertEquals(238, SubtitleStyle(position = SubtitlePosition.Raised).vlcOptions(1080).option("--sub-margin"))
        assertEquals(475, SubtitleStyle(position = SubtitlePosition.Raised).vlcOptions(2160).option("--sub-margin"))
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
