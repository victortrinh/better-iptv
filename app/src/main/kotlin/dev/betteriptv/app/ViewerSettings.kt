package dev.betteriptv.app

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import dev.betteriptv.core.BackgroundReading
import dev.betteriptv.core.DeviceSettings
import dev.betteriptv.core.KeptDeviceSettings
import dev.betteriptv.core.KeptSettings
import dev.betteriptv.core.League
import dev.betteriptv.core.Library
import dev.betteriptv.core.Profile
import dev.betteriptv.core.ProfileSetting
import dev.betteriptv.core.QualityCap
import dev.betteriptv.core.SubtitleColour
import dev.betteriptv.core.SubtitleSize
import dev.betteriptv.core.SubtitleStyle
import dev.betteriptv.core.TextSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * What the Viewer set in Settings. Languages are BCP 47 tags; [region] is an ISO 3166 country code.
 * Half is the current Profile's ([profile]), half the device's ([device]): switching Profile changes
 * the first and leaves the second. The Profile's half and the Region sync with the Account (#440).
 */
data class ViewerSettings(
    val contentLanguages: List<String>,
    /** Prefer original audio: a Title plays with Original audio when any Version has it, ahead of a Version in a Content language at any quality. */
    val preferOriginalAudio: Boolean = true,
    val appLanguage: String,
    val region: String,
    val qualityCap: QualityCap,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    /** Whether an Episode's credits count down to the next Episode, which then plays by itself. */
    val playNextAutomatically: Boolean = true,
    val backgroundReading: BackgroundReading = BackgroundReading.Gentle,
    /** How big the interface text is, for the TV and how far the Viewer sits from it. */
    val textSize: TextSize = TextSize.Medium,
    /** The Leagues the Viewer chose in Settings (none when they turned every one off), or null while they haven't: the [region]'s defaults are on. */
    val leagues: Set<League>? = null,
) {
    /** The Leagues on, whose Games the Live tab shows and the cloud is asked for: the Viewer's choice, or the Region's defaults. */
    val leaguesOn: Set<League> get() = League.on(leagues, region)

    /** The current Profile's half. */
    val profile: ProfileSettings get() = ProfileSettings(contentLanguages, preferOriginalAudio, appLanguage, playNextAutomatically, leagues)

    /**
     * The device's half (#428): what the TV, the stick and the room are like, which the Core keeps on
     * the device ([KeptDeviceSettings]). The [region] is the household's, and syncs with the Account (#440).
     */
    val device: DeviceSettings get() = DeviceSettings(qualityCap, subtitleStyle, backgroundReading, textSize)

    companion object {
        fun of(profile: ProfileSettings, device: DeviceSettings, region: String) = ViewerSettings(
            contentLanguages = profile.contentLanguages,
            preferOriginalAudio = profile.preferOriginalAudio,
            appLanguage = profile.appLanguage,
            region = region,
            qualityCap = device.qualityCap,
            subtitleStyle = device.subtitleStyle,
            playNextAutomatically = profile.playNextAutomatically,
            backgroundReading = device.backgroundReading,
            textSize = device.textSize,
            leagues = profile.leagues,
        )
    }
}

/** The settings each Profile keeps its own (#428): what a household member reads, hears and follows. */
data class ProfileSettings(
    val contentLanguages: List<String>,
    val preferOriginalAudio: Boolean,
    val appLanguage: String,
    val playNextAutomatically: Boolean,
    val leagues: Set<League>?,
)

/** A subtitle size, as a share of Media3's default. */
val SubtitleSize.scale: Float
    get() = when (this) {
        SubtitleSize.Small -> 0.65f
        SubtitleSize.Medium -> 0.8f
        SubtitleSize.Large -> 1f
        SubtitleSize.Largest -> 1.3f
    }

val SubtitleColour.argb: Int
    get() = when (this) {
        SubtitleColour.White -> 0xFFFFFFFF.toInt()
        SubtitleColour.Yellow -> 0xFFFFE14D.toInt()
    }

/** The app's interface languages: the App language is one of these. */
val APP_LANGUAGES = listOf("en", "fr")

/**
 * Keeps the [ViewerSettings]: the device's half in [device], the Core's, which keeps it in the
 * platform's settings store ([AndroidSettingsStore]) and never lets it leave the TV; the
 * current Profile's half and the Region in [kept], the Library, which syncs them with the Account
 * (#440) and follows the Profile the device is on. Until the Viewer changes them, they follow the
 * device: its language is the Content language, and the App language when it is French, and its
 * country is the Region. A new Profile starts the same way. What an older app kept in files (each
 * Profile's half in its own, and before Profiles (#428) both halves in the device's) moves into
 * [kept] once, as the oldest change, so an Account's settings win over it.
 */
class ViewerSettingsStore(private val context: Context, private val kept: KeptSettings, private val device: KeptDeviceSettings, scope: CoroutineScope) {
    /** The file the device's half is in ([AndroidSettingsStore]), for what older apps kept there besides. */
    private val devicePrefs = context.getSharedPreferences(AndroidSettingsStore.FILE, Context.MODE_PRIVATE)
    private val _settings: MutableStateFlow<ViewerSettings>
    val settings: StateFlow<ViewerSettings>

    init {
        moveFilesIntoKept()
        _settings = MutableStateFlow(read())
        settings = _settings.asStateFlow()
        // A Profile switched to, or what another TV of the Account changed, landing between screens.
        scope.launch { combine(kept.profileSettings, kept.region) { _, _ -> }.collect { refresh() } }
    }

    @Synchronized
    fun update(change: (ViewerSettings) -> ViewerSettings) {
        val old = _settings.value
        val new = change(old)
        if (new.device != old.device) device.update { new.device }
        val before = encode(old.profile)
        val changed = encode(new.profile).filter { (key, value) -> before[key] != value }
        kept.saveProfileSettings(changed)
        if (new.region != old.region) kept.saveRegion(new.region)
        _settings.value = new
    }

    @Synchronized
    private fun refresh() {
        _settings.value = read()
    }

    private fun read(): ViewerSettings {
        // Until the household has a Region, it is the device's country.
        val region = kept.region.value ?: Resources.getSystem().configuration.locales[0].country.takeIf { it.length == 2 } ?: Library.DEFAULT_REGION
        return ViewerSettings.of(readProfile(kept.profileSettings.value), device.settings.value, region)
    }

    /** What older apps kept in files, moved into [kept]: each Profile's half, and the Region. */
    private fun moveFilesIntoKept() {
        // Before Profiles, the device's file held the first Profile's half too.
        val beforeProfiles = PROFILE_KEYS.filter(devicePrefs::contains)
        if (beforeProfiles.isNotEmpty()) {
            val first = context.getSharedPreferences(profileFile(Profile.FIRST_ID), Context.MODE_PRIVATE).edit()
            for (key in beforeProfiles) {
                when (val value = devicePrefs.all[key]) {
                    is Boolean -> first.putBoolean(key, value)
                    is String -> first.putString(key, value)
                }
            }
            first.commit()
            devicePrefs.edit().apply { beforeProfiles.forEach(::remove) }.commit()
        }
        val region = devicePrefs.getString(REGION, null)
        val files = context.filesDir.parentFile?.resolve("shared_prefs")?.list().orEmpty()
            .mapNotNull { PROFILE_FILE.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }
        for (id in files) {
            val prefs = context.getSharedPreferences(profileFile(id), Context.MODE_PRIVATE)
            kept.importSettings(id, encodeFile(prefs), region)
            prefs.edit().clear().commit()
            context.deleteSharedPreferences(profileFile(id))
        }
        if (region != null) {
            if (files.isEmpty()) kept.importSettings(Profile.FIRST_ID, emptyMap(), region)
            devicePrefs.edit().remove(REGION).commit()
        }
    }

    /** A Profile's file as [kept] keeps it: by [ProfileSetting] name, as [encode] writes it. */
    private fun encodeFile(prefs: SharedPreferences): Map<String, String> = buildMap {
        prefs.getString(CONTENT_LANGUAGES, null)?.let { put(ProfileSetting.CONTENT_LANGUAGES, it) }
        if (prefs.contains(PREFER_ORIGINAL_AUDIO)) put(ProfileSetting.PREFER_ORIGINAL_AUDIO, prefs.getBoolean(PREFER_ORIGINAL_AUDIO, true).toString())
        prefs.getString(APP_LANGUAGE, null)?.let { put(ProfileSetting.APP_LANGUAGE, it) }
        if (prefs.contains(PLAY_NEXT_AUTOMATICALLY)) put(ProfileSetting.PLAY_NEXT_AUTOMATICALLY, prefs.getBoolean(PLAY_NEXT_AUTOMATICALLY, true).toString())
        prefs.getString(LEAGUES, null)?.let { put(ProfileSetting.LEAGUES, it) }
    }

    private companion object {
        fun profileFile(id: Long) = "profile_settings_$id"
        val PROFILE_FILE = Regex("""profile_settings_(\d+)\.xml""")

        const val CONTENT_LANGUAGES = "content_languages"
        const val PREFER_ORIGINAL_AUDIO = "prefer_original_audio"
        const val APP_LANGUAGE = "app_language"
        const val REGION = "region"
        const val PLAY_NEXT_AUTOMATICALLY = "play_next_automatically"
        const val LEAGUES = "leagues"

        /** What the device's file held of a Profile's half before Profiles. */
        val PROFILE_KEYS = listOf(CONTENT_LANGUAGES, PREFER_ORIGINAL_AUDIO, APP_LANGUAGE, PLAY_NEXT_AUTOMATICALLY, LEAGUES)
    }
}

/**
 * A Profile's half as [KeptSettings] keeps it, by [ProfileSetting] name. No Leagues chosen is
 * missing (null), apart from the choice of none (""), which stays none when the Region changes.
 */
internal fun encode(profile: ProfileSettings): Map<String, String?> = mapOf(
    ProfileSetting.CONTENT_LANGUAGES to profile.contentLanguages.joinToString(","),
    ProfileSetting.PREFER_ORIGINAL_AUDIO to profile.preferOriginalAudio.toString(),
    ProfileSetting.APP_LANGUAGE to profile.appLanguage,
    ProfileSetting.PLAY_NEXT_AUTOMATICALLY to profile.playNextAutomatically.toString(),
    ProfileSetting.LEAGUES to profile.leagues?.joinToString(",") { it.key },
)

/** A Profile's half from what [KeptSettings] keeps: a setting never set follows the device. */
internal fun readProfile(kept: Map<String, String>): ProfileSettings {
    // The system's locale, not the app's: the app's follows the App language.
    val device = Resources.getSystem().configuration.locales[0]
    return ProfileSettings(
        contentLanguages = kept[ProfileSetting.CONTENT_LANGUAGES]?.split(',')?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() } ?: listOf(device.language),
        preferOriginalAudio = kept[ProfileSetting.PREFER_ORIGINAL_AUDIO]?.toBooleanStrictOrNull() ?: true,
        appLanguage = kept[ProfileSetting.APP_LANGUAGE] ?: deviceAppLanguage(),
        playNextAutomatically = kept[ProfileSetting.PLAY_NEXT_AUTOMATICALLY]?.toBooleanStrictOrNull() ?: true,
        // A key this release doesn't offer (a League dropped since) is left out, the rest kept.
        leagues = kept[ProfileSetting.LEAGUES]?.split(',')?.mapNotNull(League::byKey)?.toSet(),
    )
}

/** The App language a Profile starts with: the device's language when the app has it, else English. */
fun deviceAppLanguage(): String = Resources.getSystem().configuration.locales[0].language.takeIf { it in APP_LANGUAGES } ?: "en"

/** [base] with its resources in the App language [appLanguage], so the interface text is in it. */
fun localized(base: Context, appLanguage: String): Context {
    val configuration = Configuration(base.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(appLanguage))
    return base.createConfigurationContext(configuration)
}
