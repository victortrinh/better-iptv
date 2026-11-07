package dev.betteriptv.app

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import dev.betteriptv.app.ui.theme.TextSize
import dev.betteriptv.core.BackgroundPace
import dev.betteriptv.core.League
import dev.betteriptv.core.Library
import dev.betteriptv.core.Profile
import dev.betteriptv.core.QualityCap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * What the Viewer set in Settings. Languages are BCP 47 tags; [region] is an ISO 3166 country code.
 * Half is the current Profile's ([profile]), half the device's ([device]): switching Profile changes
 * the first and leaves the second.
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
 * The settings the device keeps for everyone (#428): where the TV is ([region]) and what the TV,
 * the stick and the room are like.
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
 * Keeps the [ViewerSettings] on the device: the device's half in one file, each Profile's half in
 * its own, for the Profile [profile] the device is on at first. Until the Viewer changes them, they
 * follow the device: its language is the Content language, and the App language when it is French,
 * and its country is the Region. A new Profile starts the same way. Before Profiles (#428) both
 * halves were kept in the device's file: its Profile half moves to the first Profile's once.
 */
class ViewerSettingsStore(private val context: Context, profile: Long) {
    private val devicePrefs = context.getSharedPreferences(DEVICE_FILE, Context.MODE_PRIVATE)
    private var profileId = profile
    private var profilePrefs = profilePrefs(profile)
    private val _settings: MutableStateFlow<ViewerSettings>
    val settings: StateFlow<ViewerSettings>

    init {
        moveProfileHalfOut()
        _settings = MutableStateFlow(ViewerSettings.of(readProfile(), readDevice()))
        settings = _settings.asStateFlow()
    }

    @Synchronized
    fun update(change: (ViewerSettings) -> ViewerSettings) {
        val new = change(_settings.value)
        write(new.profile)
        write(new.device)
        _settings.value = new
    }

    /** Puts the settings on Profile [id]'s half, the device's half as it was. */
    @Synchronized
    fun useProfile(id: Long) {
        if (id == profileId) return
        profileId = id
        profilePrefs = profilePrefs(id)
        _settings.value = ViewerSettings.of(readProfile(), _settings.value.device)
    }

    /** Deletes what Profile [id] kept, once the Profile is deleted. */
    @Synchronized
    fun forgetProfile(id: Long) {
        check(id != profileId) { "Profile $id is the one the device is on" }
        context.getSharedPreferences(profileFile(id), Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteSharedPreferences(profileFile(id))
    }

    private fun profilePrefs(id: Long): SharedPreferences = context.getSharedPreferences(profileFile(id), Context.MODE_PRIVATE)

    private fun moveProfileHalfOut() {
        val kept = PROFILE_KEYS.filter(devicePrefs::contains)
        if (kept.isEmpty()) return
        val first = context.getSharedPreferences(profileFile(Profile.FIRST_ID), Context.MODE_PRIVATE).edit()
        for (key in kept) {
            when (val value = devicePrefs.all[key]) {
                is Boolean -> first.putBoolean(key, value)
                is String -> first.putString(key, value)
            }
        }
        first.commit()
        devicePrefs.edit().apply { kept.forEach(::remove) }.commit()
    }

    private fun write(profile: ProfileSettings) {
        profilePrefs.edit()
            .putString(CONTENT_LANGUAGES, profile.contentLanguages.joinToString(","))
            .putBoolean(PREFER_ORIGINAL_AUDIO, profile.preferOriginalAudio)
            .putString(APP_LANGUAGE, profile.appLanguage)
            .putBoolean(PLAY_NEXT_AUTOMATICALLY, profile.playNextAutomatically)
            .apply {
                // Chosen none is kept apart from not chosen: it stays none when the Region changes.
                val leagues = profile.leagues
                if (leagues == null) remove(LEAGUES) else putString(LEAGUES, leagues.joinToString(",") { it.key })
            }
            .apply()
    }

    private fun write(device: DeviceSettings) {
        devicePrefs.edit()
            .putString(REGION, device.region)
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

    private fun readProfile(): ProfileSettings {
        val prefs = profilePrefs
        // The system's locale, not the app's: the app's follows the App language.
        val device = Resources.getSystem().configuration.locales[0]
        return ProfileSettings(
            contentLanguages = prefs.getString(CONTENT_LANGUAGES, null)?.split(',')?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
                ?: listOf(device.language),
            preferOriginalAudio = prefs.getBoolean(PREFER_ORIGINAL_AUDIO, true),
            appLanguage = prefs.getString(APP_LANGUAGE, null) ?: deviceAppLanguage(),
            playNextAutomatically = prefs.getBoolean(PLAY_NEXT_AUTOMATICALLY, true),
            // A key this release doesn't offer (a League dropped since) is left out, the rest kept.
            leagues = prefs.getString(LEAGUES, null)?.split(',')?.mapNotNull(League::byKey)?.toSet(),
        )
    }

    private fun readDevice(): DeviceSettings {
        val prefs = devicePrefs
        val device = Resources.getSystem().configuration.locales[0]
        return DeviceSettings(
            region = prefs.getString(REGION, null) ?: device.country.takeIf { it.length == 2 } ?: Library.DEFAULT_REGION,
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
        /** The device's half; before Profiles, both halves. */
        const val DEVICE_FILE = "viewer_settings"
        fun profileFile(id: Long) = "profile_settings_$id"

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

/** The App language a Profile starts with: the device's language when the app has it, else English. */
fun deviceAppLanguage(): String = Resources.getSystem().configuration.locales[0].language.takeIf { it in APP_LANGUAGES } ?: "en"

/** [base] with its resources in the App language [appLanguage], so the interface text is in it. */
fun localized(base: Context, appLanguage: String): Context {
    val configuration = Configuration(base.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(appLanguage))
    return base.createConfigurationContext(configuration)
}
