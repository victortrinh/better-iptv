package dev.betteriptv.app

import android.content.Context
import dev.betteriptv.core.BackgroundReading
import dev.betteriptv.core.DeviceSettings
import dev.betteriptv.core.KeptDeviceSettings
import dev.betteriptv.core.QualityCap
import dev.betteriptv.core.SubtitleBackground
import dev.betteriptv.core.SubtitleColour
import dev.betteriptv.core.SubtitlePosition
import dev.betteriptv.core.SubtitleSize
import dev.betteriptv.core.SubtitleStyle
import dev.betteriptv.core.TextSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The update that moves the device's settings behind the Core's settings store (#583) starts from
 * what the app before it kept in its own file: the Viewer finds every setting as they left it.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsAcrossTheUpdateTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val oldFile get() = context.getSharedPreferences("viewer_settings", Context.MODE_PRIVATE)

    /** What the Core reads after the update. */
    private fun afterTheUpdate() = KeptDeviceSettings(AndroidSettingsStore(context)).settings.value

    @Test
    fun everySettingTheLastReleaseKeptHoldsAfterTheUpdate() {
        // As the release before #583 wrote the device's half: names as text, the subtitle height as a number.
        oldFile.edit()
            .putString("quality_cap", "HD")
            .putString("subtitle_size", "Largest")
            .putString("subtitle_colour", "Yellow")
            .putString("subtitle_background", "Box")
            .putInt("subtitle_height", 26)
            .putString("background_reading", "Off")
            .putString("text_size", "Large")
            .commit()

        assertEquals(
            DeviceSettings(
                QualityCap.HD,
                SubtitleStyle(SubtitleSize.Largest, SubtitleColour.Yellow, SubtitleBackground.Box, SubtitlePosition.Raised),
                BackgroundReading.Off,
                TextSize.Large,
            ),
            afterTheUpdate(),
        )
    }

    @Test
    fun theyStillHoldOnTheLaunchAfterThat() {
        oldFile.edit().putString("quality_cap", "FullHD").putInt("subtitle_height", 11).putString("text_size", "Small").commit()
        val first = afterTheUpdate()

        // The height is now text, as the Core keeps it, and reads the same.
        assertEquals("11", oldFile.all["subtitle_height"])
        assertEquals(first, afterTheUpdate())
        assertEquals(DeviceSettings(qualityCap = QualityCap.FullHD, subtitleStyle = SubtitleStyle(position = SubtitlePosition(11)), textSize = TextSize.Small), first)
    }

    @Test
    fun aSettingChangedAfterTheUpdateLeavesTheOthersAsTheyWere() {
        oldFile.edit().putString("quality_cap", "HD").putInt("subtitle_height", 26).putString("background_reading", "Normal").commit()

        KeptDeviceSettings(AndroidSettingsStore(context)).update { it.copy(textSize = TextSize.Large) }

        assertEquals(
            DeviceSettings(QualityCap.HD, SubtitleStyle(position = SubtitlePosition.Raised), BackgroundReading.Normal, TextSize.Large),
            afterTheUpdate(),
        )
    }

    @Test
    fun raisedSubtitlesFromBeforeTheHeightWasSteppedStayRaised() {
        oldFile.edit().putString("subtitle_position", "Raised").putString("subtitle_size", "Small").commit()

        assertEquals(SubtitleStyle(size = SubtitleSize.Small, position = SubtitlePosition.Raised), afterTheUpdate().subtitleStyle)
        assertFalse(oldFile.contains("subtitle_position"))
        assertEquals(SubtitlePosition.Raised, afterTheUpdate().subtitleStyle.position)
    }

    @Test
    fun aDeviceThatNeverChangedASettingHasTheDefaults() {
        assertEquals(DeviceSettings(), afterTheUpdate())
    }
}
