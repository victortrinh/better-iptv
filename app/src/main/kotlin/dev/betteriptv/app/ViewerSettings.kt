package dev.betteriptv.app

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import dev.betteriptv.app.ui.theme.TextSize
import dev.betteriptv.core.BackgroundPace
import dev.betteriptv.core.KeptSettings
import dev.betteriptv.core.League
import dev.betteriptv.core.Library
import dev.betteriptv.core.Profile
import dev.betteriptv.core.ProfileSetting
import dev.betteriptv.core.QualityCap
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

    /** The device's half. */
    val device: DeviceSettings get() = DeviceSettings(region, qualityCap, subtitleStyle, backgroundReading, textSize)

    companion object {
        fun of(profile: ProfileSettings, device: DeviceSettings) = ViewerSettings(
            contentLanguages = profile.contentLanguages,
            preferOriginalAudio = profile.preferOriginalAudio,
            appLanguage = profile.appLanguage,
            region = device.region,
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

/**
 * The settings the device keeps for everyone (#428): where the TV is ([region], the household's,
 * which syncs with the Account since #440) and what the TV, the stick and the room are like, which
 * never leave the TV.
 */
data class DeviceSettings(
    val region: String,
    val qualityCap: QualityCap,
    val subtitleStyle: SubtitleStyle,
    val backgroundReading: BackgroundReading,
    val textSize: TextSize,
)

/** How fast Background reading goes; [pace] is null when it is off. */
enum class BackgroundReading(val pace: BackgroundPace?) { Off(null), Gentle(BackgroundPace.Gentle), Normal(BackgroundPace.Normal) }

/**
 * How the player draws subtitles that carry no style of their own (styled ASS subtitles keep
 * theirs): their [size], [colour], what's behind them ([background]) and how high they sit ([position]).
 */
data class SubtitleStyle(
    val size: SubtitleSize = SubtitleSize.Medium,
    val colour: SubtitleColour = SubtitleColour.White,
    val background: SubtitleBackground = SubtitleBackground.Outline,
    val position: SubtitlePosition = SubtitlePosition.Bottom,
)

/** A subtitle size, as a share of Media3's default. */
enum class SubtitleSize(val scale: Float) { Small(0.65f), Medium(0.8f), Large(1f), Largest(1.3f) }

enum class SubtitleColour(val argb: Int) { White(0xFFFFFFFF.toInt()), Yellow(0xFFFFE14D.toInt()) }

/** What sits behind subtitles: a dark outline round the letters, a shadow, or a dark box. */
enum class SubtitleBackground { Outline, Shadow, Box }

/**
 * How high subtitles sit, in [percent] of the screen's height from its bottom (Media3 text cues), or
 * of the picture's (VLC), a [STEP] at a time from [LOWEST] to [HIGHEST]. [Bottom] lands in the black
 * bar under a wide picture (#292). [Raised] clears the player overlay's scrubber, whose track sits
 * 23% to 24% up a 1080-line screen (#277), on a 16:9 film too, where the screen is the picture: its
 * text ends about 2% above the track. Higher than half the screen, a line sits over faces.
 */
@JvmInline
value class SubtitlePosition(val percent: Int) {
    init {
        require(percent in LOWEST..HIGHEST && (percent - LOWEST) % STEP == 0) { "No subtitle position at $percent%" }
    }

    val fromBottom: Float get() = percent / 100f

    /** A step lower, or this one at the lowest. */
    fun lower(): SubtitlePosition = SubtitlePosition((percent - STEP).coerceAtLeast(LOWEST))

    /** A step higher, or this one at the highest. */
    fun higher(): SubtitlePosition = SubtitlePosition((percent + STEP).coerceAtMost(HIGHEST))

    companion object {
        const val LOWEST = 2
        const val HIGHEST = 50
        const val STEP = 3
        val Bottom = SubtitlePosition(5)
        val Raised = SubtitlePosition(26)

        /** The position [percent] up, or the nearest step to it within the range. */
        fun nearest(percent: Int): SubtitlePosition =
            SubtitlePosition(LOWEST + ((percent.coerceIn(LOWEST, HIGHEST) - LOWEST + STEP / 2) / STEP) * STEP)
    }
}

/** The app's interface languages: the App language is one of these. */
val APP_LANGUAGES = listOf("en", "fr")

/**
 * Keeps the [ViewerSettings]: the device's half in a file of its own, which never leaves the TV; the
 * current Profile's half and the Region in [kept], the Library, which syncs them with the Account
 * (#440) and follows the Profile the device is on. Until the Viewer changes them, they follow the
 * device: its language is the Content language, and the App language when it is French, and its
 * country is the Region. A new Profile starts the same way. What an older app kept in files (each
 * Profile's half in its own, and before Profiles (#428) both halves in the device's) moves into
 * [kept] once, as the oldest change, so an Account's settings win over it.
 */
class ViewerSettingsStore(private val context: Context, private val kept: KeptSettings, scope: CoroutineScope) {
    private val devicePrefs = context.getSharedPreferences(DEVICE_FILE, Context.MODE_PRIVATE)
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
        write(new.device)
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
        val device = readDevice()
        return ViewerSettings.of(readProfile(kept.profileSettings.value), device.copy(region = kept.region.value ?: device.region))
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

    private fun write(device: DeviceSettings) {
        devicePrefs.edit()
            .putString(QUALITY_CAP, device.qualityCap.name)
            .putString(SUBTITLE_SIZE, device.subtitleStyle.size.name)
            .putString(SUBTITLE_COLOUR, device.subtitleStyle.colour.name)
            .putString(SUBTITLE_BACKGROUND, device.subtitleStyle.background.name)
            .remove(SUBTITLE_POSITION)
            .putInt(SUBTITLE_HEIGHT, device.subtitleStyle.position.percent)
            .putString(BACKGROUND_READING, device.backgroundReading.name)
            .putString(TEXT_SIZE, device.textSize.name)
            .apply()
    }

    private fun readDevice(): DeviceSettings {
        val prefs = devicePrefs
        val device = Resources.getSystem().configuration.locales[0]
        return DeviceSettings(
            region = device.country.takeIf { it.length == 2 } ?: Library.DEFAULT_REGION,
            qualityCap = prefs.getString(QUALITY_CAP, null)?.let { name -> QualityCap.entries.find { it.name == name } } ?: QualityCap.UHD,
            subtitleStyle = SubtitleStyle(
                size = read(prefs, SUBTITLE_SIZE, SubtitleSize.entries) ?: SubtitleSize.Medium,
                colour = read(prefs, SUBTITLE_COLOUR, SubtitleColour.entries) ?: SubtitleColour.White,
                background = read(prefs, SUBTITLE_BACKGROUND, SubtitleBackground.entries) ?: SubtitleBackground.Outline,
                position = when {
                    prefs.contains(SUBTITLE_HEIGHT) -> SubtitlePosition.nearest(prefs.getInt(SUBTITLE_HEIGHT, SubtitlePosition.Bottom.percent))
                    // Before the height was stepped, it was Bottom or Raised.
                    prefs.getString(SUBTITLE_POSITION, null) == "Raised" -> SubtitlePosition.Raised
                    else -> SubtitlePosition.Bottom
                },
            ),
            backgroundReading = read(prefs, BACKGROUND_READING, BackgroundReading.entries) ?: BackgroundReading.Gentle,
            textSize = read(prefs, TEXT_SIZE, TextSize.entries) ?: TextSize.Medium,
        )
    }

    private fun <T : Enum<T>> read(prefs: SharedPreferences, key: String, entries: List<T>): T? =
        prefs.getString(key, null)?.let { name -> entries.find { it.name == name } }

    private companion object {
        /** The device's half; before #440 the Region too, and before Profiles both halves. */
        const val DEVICE_FILE = "viewer_settings"
        fun profileFile(id: Long) = "profile_settings_$id"
        val PROFILE_FILE = Regex("""profile_settings_(\d+)\.xml""")

        const val CONTENT_LANGUAGES = "content_languages"
        const val PREFER_ORIGINAL_AUDIO = "prefer_original_audio"
        const val APP_LANGUAGE = "app_language"
        const val REGION = "region"
        const val QUALITY_CAP = "quality_cap"
        const val SUBTITLE_SIZE = "subtitle_size"
        const val SUBTITLE_COLOUR = "subtitle_colour"
        const val SUBTITLE_BACKGROUND = "subtitle_background"
        const val SUBTITLE_POSITION = "subtitle_position"
        const val SUBTITLE_HEIGHT = "subtitle_height"
        const val PLAY_NEXT_AUTOMATICALLY = "play_next_automatically"
        const val BACKGROUND_READING = "background_reading"
        const val TEXT_SIZE = "text_size"
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
