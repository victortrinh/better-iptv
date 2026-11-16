package dev.betteriptv.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** The device's settings, kept by the Core in whatever store the platform supplies (#583). */
class DeviceSettingsTest {
    private val store = InMemorySettings()

    private fun kept() = KeptDeviceSettings(store)

    @Test fun `a device that kept nothing has the defaults`() {
        assertEquals(
            DeviceSettings(QualityCap.UHD, SubtitleStyle(SubtitleSize.Medium, SubtitleColour.White, SubtitleBackground.Outline, SubtitlePosition.Bottom), BackgroundReading.Gentle, TextSize.Medium),
            kept().settings.value,
        )
    }

    @Test fun `every setting holds after the app reopens`() {
        val changed = DeviceSettings(
            QualityCap.HD,
            SubtitleStyle(SubtitleSize.Largest, SubtitleColour.Yellow, SubtitleBackground.Box, SubtitlePosition.Raised),
            BackgroundReading.Off,
            TextSize.Large,
        )
        val kept = kept()
        kept.update { changed }

        assertEquals(changed, kept.settings.value)
        assertEquals(changed, kept().settings.value)
    }

    @Test fun `changing one setting keeps the others`() {
        kept().update { it.copy(qualityCap = QualityCap.FullHD, backgroundReading = BackgroundReading.Normal) }
        kept().update { it.copy(textSize = TextSize.Small) }

        assertEquals(DeviceSettings(qualityCap = QualityCap.FullHD, backgroundReading = BackgroundReading.Normal, textSize = TextSize.Small), kept().settings.value)
    }

    @Test fun `a choice this release doesn't offer is the default`() {
        store.write(mapOf(KeptDeviceSettings.QUALITY_CAP to "EightK", KeptDeviceSettings.TEXT_SIZE to "Huge", KeptDeviceSettings.SUBTITLE_HEIGHT to "high"))

        assertEquals(DeviceSettings(), kept().settings.value)
    }

    @Test fun `a subtitle height between two steps is the nearest step`() {
        store.write(mapOf(KeptDeviceSettings.SUBTITLE_HEIGHT to "25"))

        assertEquals(SubtitlePosition.Raised, kept().settings.value.subtitleStyle.position)
    }
}
