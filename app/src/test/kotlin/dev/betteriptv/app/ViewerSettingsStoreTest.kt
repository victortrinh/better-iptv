package dev.betteriptv.app

import android.content.Context
import dev.betteriptv.app.ui.theme.TextSize
import dev.betteriptv.core.KeptSettings
import dev.betteriptv.core.League
import dev.betteriptv.core.ProfileSetting
import dev.betteriptv.core.QualityCap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The Viewer's settings: the device's half kept on the device, the Profile's half and the Region in
 * the Library, which syncs them with the Account (#440), here [FakeKept].
 */
@RunWith(RobolectricTestRunner::class)
class ViewerSettingsStoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val kept = FakeKept()

    @After fun tearDown() = scope.cancel()

    /** The Library as the settings see it: each Profile's settings by name, the one the device is on, and the Region. */
    private class FakeKept : KeptSettings {
        val byProfile = HashMap<Long, MutableMap<String, String>>()
        var current = 1L
        val saved = ArrayList<Map<String, String?>>()
        override val profileSettings = MutableStateFlow<Map<String, String>>(emptyMap())
        override val region = MutableStateFlow<String?>(null)

        override fun saveProfileSettings(changed: Map<String, String?>) {
            if (changed.isEmpty()) return
            saved += changed
            val mine = byProfile.getOrPut(current) { HashMap() }
            changed.forEach { (key, value) -> if (value == null) mine.remove(key) else mine[key] = value }
            profileSettings.value = mine.toMap()
        }

        override fun saveRegion(region: String) {
            this.region.value = region
        }

        override fun importSettings(profile: Long, values: Map<String, String>, region: String?) {
            val theirs = byProfile.getOrPut(profile) { HashMap() }
            values.forEach { (key, value) -> theirs.putIfAbsent(key, value) }
            if (this.region.value == null) this.region.value = region
            if (profile == current) profileSettings.value = theirs.toMap()
        }

        /** What switching Profile, or a change landing from another TV, does to what the store reads. */
        fun switchTo(profile: Long) {
            current = profile
            profileSettings.value = byProfile[profile].orEmpty().toMap()
        }
    }

    private fun store() = ViewerSettingsStore(context, kept, scope)

    @Test
    fun textSizeIsMediumUntilChanged() {
        assertEquals(TextSize.Medium, store().settings.value.textSize)
    }

    @Test
    fun textSizeIsKeptAcrossRestarts() {
        for (size in TextSize.entries) {
            store().update { it.copy(textSize = size) }
            assertEquals(size, store().settings.value.textSize)
        }
    }

    @Test
    fun preferOriginalAudioIsOnUntilTurnedOff() {
        assertEquals(true, store().settings.value.preferOriginalAudio)

        store().update { it.copy(preferOriginalAudio = false) }
        assertEquals(false, store().settings.value.preferOriginalAudio)

        store().update { it.copy(preferOriginalAudio = true) }
        assertEquals(true, store().settings.value.preferOriginalAudio)
    }

    @Test
    fun leaguesAreTheRegionsDefaultsUntilChosen() {
        val store = store()
        store.update { it.copy(region = "CA") }
        assertEquals(null, store.settings.value.leagues)
        assertEquals(League.defaults("CA"), store.settings.value.leaguesOn)

        store.update { it.copy(region = "FR") }
        assertEquals(League.defaults("FR"), store().settings.value.leaguesOn)
    }

    @Test
    fun chosenLeaguesAreKeptAcrossRestartsAndRegions() {
        val chosen = setOf(League.Nhl, League.LaLiga)
        store().update { it.copy(region = "CA", leagues = chosen) }
        val reopened = store()
        assertEquals(chosen, reopened.settings.value.leaguesOn)

        // A choice outlives a change of Region, even the choice of none.
        reopened.update { it.copy(region = "US") }
        assertEquals(chosen, store().settings.value.leaguesOn)
        reopened.update { it.copy(leagues = emptySet()) }
        assertEquals(emptySet<League>(), store().settings.value.leaguesOn)
    }

    @Test
    fun changingTextSizeKeepsTheOtherSettings() {
        val store = store()
        store.update { it.copy(playNextAutomatically = false, backgroundReading = BackgroundReading.Off) }
        store.update { it.copy(textSize = TextSize.Large) }
        val kept = store().settings.value
        assertEquals(false, kept.playNextAutomatically)
        assertEquals(BackgroundReading.Off, kept.backgroundReading)
        assertEquals(TextSize.Large, kept.textSize)
    }

    // Profiles (#428) and the Account (#440)

    @Test
    fun onlyTheProfilesHalfThatChangedGoesToTheLibraryAndTheDevicesHalfNever() {
        val store = store()
        store.update { it.copy(qualityCap = QualityCap.FullHD, textSize = TextSize.Large, subtitleStyle = SubtitleStyle(size = SubtitleSize.Large), backgroundReading = BackgroundReading.Off) }
        // Nothing of a Profile's changed: nothing is written, so a default never stands over another TV's choice.
        assertEquals(emptyList<Map<String, String?>>(), kept.saved)

        store.update { it.copy(appLanguage = "fr", textSize = TextSize.Small) }
        assertEquals(listOf(mapOf(ProfileSetting.APP_LANGUAGE to "fr")), kept.saved)
    }

    @Test
    fun eachProfileKeepsItsOwnHalfAndSharesTheDevicesHalf() {
        val store = store()
        store.update {
            it.copy(
                contentLanguages = listOf("fr", "en"), appLanguage = "fr", preferOriginalAudio = false, playNextAutomatically = false, leagues = setOf(League.Nhl),
                region = "CA", qualityCap = QualityCap.FullHD, textSize = TextSize.Large, backgroundReading = BackgroundReading.Off,
                subtitleStyle = SubtitleStyle(size = SubtitleSize.Large),
            )
        }
        val first = store.settings.value

        kept.switchTo(2)
        val second = store.settings.value
        // A new Profile starts with the device's language (Robolectric's is English) and the Region's Leagues.
        assertEquals(ProfileSettings(listOf("en"), preferOriginalAudio = true, appLanguage = "en", playNextAutomatically = true, leagues = null), second.profile)
        assertEquals(League.defaults("CA"), second.leaguesOn)
        assertEquals(first.device, second.device)

        store.update { it.copy(contentLanguages = listOf("de"), textSize = TextSize.Small) }
        kept.switchTo(1)
        assertEquals(first.profile, store.settings.value.profile)
        // The device's half changed for everyone.
        assertEquals(TextSize.Small, store.settings.value.textSize)
        kept.switchTo(2)
        assertEquals(listOf("de"), store().settings.value.contentLanguages)
    }

    @Test
    fun whatAnotherTvChangedShowsOnceItLands() {
        val store = store()
        kept.profileSettings.value = mapOf(ProfileSetting.APP_LANGUAGE to "fr", ProfileSetting.LEAGUES to "nhl")
        kept.region.value = "GB"
        assertEquals("fr", store.settings.value.appLanguage)
        assertEquals(setOf(League.Nhl), store.settings.value.leagues)
        assertEquals("GB", store.settings.value.region)
    }

    @Test
    fun settingsKeptInFilesMoveToTheLibraryOnce() {
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
        // And a second Profile's own file, as #428 kept it.
        context.getSharedPreferences("profile_settings_2", Context.MODE_PRIVATE).edit().putString("app_language", "en").putString("leagues", "").commit()

        val store = store()
        val moved = store.settings.value
        assertEquals(ProfileSettings(listOf("fr", "en"), preferOriginalAudio = false, appLanguage = "fr", playNextAutomatically = false, leagues = setOf(League.Nhl)), moved.profile)
        assertEquals("FR", moved.region)
        assertEquals(TextSize.Large, moved.textSize)
        assertEquals(mapOf(ProfileSetting.APP_LANGUAGE to "en", ProfileSetting.LEAGUES to ""), kept.byProfile[2])

        kept.switchTo(2)
        assertEquals(emptySet<League>(), store.settings.value.leagues)
        assertEquals("FR", store.settings.value.region)
        // The files are gone; the device keeps its own half.
        assertTrue(context.getSharedPreferences("viewer_settings", Context.MODE_PRIVATE).all.keys.none { it in setOf("region", "app_language", "leagues") })
        assertEquals(emptyMap<String, Any?>(), context.getSharedPreferences("profile_settings_2", Context.MODE_PRIVATE).all)
        kept.switchTo(1)
        assertEquals(moved, store().settings.value)
    }
}
