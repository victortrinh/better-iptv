package dev.betteriptv.app

import android.content.Context
import dev.betteriptv.app.ui.theme.TextSize
import dev.betteriptv.core.League
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
    fun leaguesAreTheRegionsDefaultsUntilChosen() {
        val store = ViewerSettingsStore(context)
        store.update { it.copy(region = "CA") }
        assertEquals(null, store.settings.value.leagues)
        assertEquals(League.defaults("CA"), store.settings.value.leaguesOn)

        store.update { it.copy(region = "FR") }
        assertEquals(League.defaults("FR"), ViewerSettingsStore(context).settings.value.leaguesOn)
    }

    @Test
    fun chosenLeaguesAreKeptAcrossRestartsAndRegions() {
        val chosen = setOf(League.Nhl, League.LaLiga)
        ViewerSettingsStore(context).update { it.copy(region = "CA", leagues = chosen) }
        val reopened = ViewerSettingsStore(context)
        assertEquals(chosen, reopened.settings.value.leaguesOn)

        // A choice outlives a change of Region, even the choice of none.
        reopened.update { it.copy(region = "US") }
        assertEquals(chosen, ViewerSettingsStore(context).settings.value.leaguesOn)
        reopened.update { it.copy(leagues = emptySet()) }
        assertEquals(emptySet<League>(), ViewerSettingsStore(context).settings.value.leaguesOn)
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
