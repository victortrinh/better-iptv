package dev.betteriptv.app

import android.content.Context
import dev.betteriptv.app.ui.theme.TextSize
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The Viewer's settings are kept on the device. */
@RunWith(RobolectricTestRunner::class)
class ViewerSettingsStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun textSizeIsMediumUntilChanged() {
        assertEquals(TextSize.Medium, ViewerSettingsStore(context).settings.value.textSize)
    }

    @Test
    fun textSizeIsKeptAcrossRestarts() {
        for (size in TextSize.entries) {
            ViewerSettingsStore(context).update { it.copy(textSize = size) }
            assertEquals(size, ViewerSettingsStore(context).settings.value.textSize)
        }
    }

    @Test
    fun changingTextSizeKeepsTheOtherSettings() {
        val store = ViewerSettingsStore(context)
        store.update { it.copy(playNextAutomatically = false, backgroundReading = BackgroundReading.Off) }
        store.update { it.copy(textSize = TextSize.Large) }
        val kept = ViewerSettingsStore(context).settings.value
        assertEquals(false, kept.playNextAutomatically)
        assertEquals(BackgroundReading.Off, kept.backgroundReading)
        assertEquals(TextSize.Large, kept.textSize)
    }
}
