package dev.betteriptv.app

import android.content.Context
import dev.betteriptv.app.ui.theme.TextSize
import dev.betteriptv.core.League
import dev.betteriptv.core.QualityCap
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
        assertEquals(TextSize.Medium, ViewerSettingsStore(context, 1).settings.value.textSize)
    }

    @Test
    fun textSizeIsKeptAcrossRestarts() {
        for (size in TextSize.entries) {
            ViewerSettingsStore(context, 1).update { it.copy(textSize = size) }
            assertEquals(size, ViewerSettingsStore(context, 1).settings.value.textSize)
        }
    }

    @Test
    fun preferOriginalAudioIsOnUntilTurnedOff() {
        assertEquals(true, ViewerSettingsStore(context, 1).settings.value.preferOriginalAudio)

        ViewerSettingsStore(context, 1).update { it.copy(preferOriginalAudio = false) }
        assertEquals(false, ViewerSettingsStore(context, 1).settings.value.preferOriginalAudio)

        ViewerSettingsStore(context, 1).update { it.copy(preferOriginalAudio = true) }
        assertEquals(true, ViewerSettingsStore(context, 1).settings.value.preferOriginalAudio)
    }

    @Test
    fun leaguesAreTheRegionsDefaultsUntilChosen() {
        val store = ViewerSettingsStore(context, 1)
        store.update { it.copy(region = "CA") }
        assertEquals(null, store.settings.value.leagues)
        assertEquals(League.defaults("CA"), store.settings.value.leaguesOn)

        store.update { it.copy(region = "FR") }
        assertEquals(League.defaults("FR"), ViewerSettingsStore(context, 1).settings.value.leaguesOn)
    }

    @Test
    fun chosenLeaguesAreKeptAcrossRestartsAndRegions() {
        val chosen = setOf(League.Nhl, League.LaLiga)
        ViewerSettingsStore(context, 1).update { it.copy(region = "CA", leagues = chosen) }
        val reopened = ViewerSettingsStore(context, 1)
        assertEquals(chosen, reopened.settings.value.leaguesOn)

        // A choice outlives a change of Region, even the choice of none.
        reopened.update { it.copy(region = "US") }
        assertEquals(chosen, ViewerSettingsStore(context, 1).settings.value.leaguesOn)
        reopened.update { it.copy(leagues = emptySet()) }
        assertEquals(emptySet<League>(), ViewerSettingsStore(context, 1).settings.value.leaguesOn)
    }

    @Test
    fun changingTextSizeKeepsTheOtherSettings() {
        val store = ViewerSettingsStore(context, 1)
        store.update { it.copy(playNextAutomatically = false, backgroundReading = BackgroundReading.Off) }
        store.update { it.copy(textSize = TextSize.Large) }
        val kept = ViewerSettingsStore(context, 1).settings.value
        assertEquals(false, kept.playNextAutomatically)
        assertEquals(BackgroundReading.Off, kept.backgroundReading)
        assertEquals(TextSize.Large, kept.textSize)
    }

    // Profiles (#428)

    @Test
    fun eachProfileKeepsItsOwnHalfAndSharesTheDevicesHalf() {
        val store = ViewerSettingsStore(context, 1)
        store.update {
            it.copy(
                contentLanguages = listOf("fr", "en"), appLanguage = "fr", preferOriginalAudio = false, playNextAutomatically = false, leagues = setOf(League.Nhl),
                region = "CA", qualityCap = QualityCap.FullHD, textSize = TextSize.Large, backgroundReading = BackgroundReading.Off,
                subtitleStyle = SubtitleStyle(size = SubtitleSize.Large),
            )
        }
        val first = store.settings.value

        store.useProfile(2)
        val second = store.settings.value
        // A new Profile starts with the device's language (Robolectric's is English) and the Region's Leagues.
        assertEquals(ProfileSettings(listOf("en"), preferOriginalAudio = true, appLanguage = "en", playNextAutomatically = true, leagues = null), second.profile)
        assertEquals(League.defaults("CA"), second.leaguesOn)
        assertEquals(first.device, second.device)

        store.update { it.copy(contentLanguages = listOf("de"), textSize = TextSize.Small) }
        store.useProfile(1)
        assertEquals(first.profile, store.settings.value.profile)
        // The device's half changed for everyone.
        assertEquals(TextSize.Small, store.settings.value.textSize)

        // A restart on Profile 2 finds it as it was left.
        assertEquals(listOf("de"), ViewerSettingsStore(context, 2).settings.value.contentLanguages)
    }

    @Test
    fun aForgottenProfilesSettingsAreGone() {
        val store = ViewerSettingsStore(context, 1)
        store.useProfile(3)
        store.update { it.copy(appLanguage = "fr") }
        store.useProfile(1)
        store.forgetProfile(3)

        store.useProfile(3)
        assertEquals("en", store.settings.value.appLanguage)
    }

    @Test
    fun settingsKeptBeforeProfilesBecomeTheFirstProfiles() {
        // As the app kept them before Profiles: both halves in one file.
        context.getSharedPreferences("viewer_settings", Context.MODE_PRIVATE).edit()
            .putString("content_languages", "fr,en")
            .putString("app_language", "fr")
            .putBoolean("prefer_original_audio", false)
            .putBoolean("play_next_automatically", false)
            .putString("leagues", "nhl")
            .putString("region", "FR")
            .putString("text_size", "Large")
            .commit()

        val store = ViewerSettingsStore(context, 1)
        val kept = store.settings.value
        assertEquals(ProfileSettings(listOf("fr", "en"), preferOriginalAudio = false, appLanguage = "fr", playNextAutomatically = false, leagues = setOf(League.Nhl)), kept.profile)
        assertEquals("FR", kept.region)
        assertEquals(TextSize.Large, kept.textSize)

        // They are the first Profile's only, and stay so after a restart.
        store.useProfile(2)
        assertEquals(listOf("en"), store.settings.value.contentLanguages)
        assertEquals("FR", store.settings.value.region)
        assertEquals(kept, ViewerSettingsStore(context, 1).settings.value)
    }
}
