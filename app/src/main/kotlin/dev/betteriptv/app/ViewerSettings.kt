package dev.betteriptv.app

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import dev.betteriptv.app.ui.theme.TextSize
import dev.betteriptv.core.BackgroundPace
import dev.betteriptv.core.League
import dev.betteriptv.core.Library
import dev.betteriptv.core.QualityCap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * What the Viewer set in Settings. Languages are BCP 47 tags; [region] is an ISO 3166 country code.
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
}

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
 * Keeps the [ViewerSettings] on the device. Until the Viewer changes them, they follow the device:
 * its language is the Content language, and the App language when it is French, and its country is the Region.
 */
class ViewerSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("viewer_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<ViewerSettings> = _settings.asStateFlow()

    fun update(change: (ViewerSettings) -> ViewerSettings) {
        val new = change(_settings.value)
        prefs.edit()
            .putString(CONTENT_LANGUAGES, new.contentLanguages.joinToString(","))
            .putBoolean(PREFER_ORIGINAL_AUDIO, new.preferOriginalAudio)
            .putString(APP_LANGUAGE, new.appLanguage)
            .putString(REGION, new.region)
            .putString(QUALITY_CAP, new.qualityCap.name)
            .putString(SUBTITLE_SIZE, new.subtitleStyle.size.name)
            .putString(SUBTITLE_COLOUR, new.subtitleStyle.colour.name)
            .putString(SUBTITLE_BACKGROUND, new.subtitleStyle.background.name)
            .remove(SUBTITLE_POSITION)
            .putInt(SUBTITLE_HEIGHT, new.subtitleStyle.position.percent)
            .putBoolean(PLAY_NEXT_AUTOMATICALLY, new.playNextAutomatically)
            .putString(BACKGROUND_READING, new.backgroundReading.name)
            .putString(TEXT_SIZE, new.textSize.name)
            .apply {
                // Chosen none is kept apart from not chosen: it stays none when the Region changes.
                val leagues = new.leagues
                if (leagues == null) remove(LEAGUES) else putString(LEAGUES, leagues.joinToString(",") { it.key })
            }
            .apply()
        _settings.value = new
    }

    private fun read(): ViewerSettings {
        // The system's locale, not the app's: the app's follows the App language.
        val device = Resources.getSystem().configuration.locales[0]
        return ViewerSettings(
            contentLanguages = prefs.getString(CONTENT_LANGUAGES, null)?.split(',')?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
                ?: listOf(device.language),
            preferOriginalAudio = prefs.getBoolean(PREFER_ORIGINAL_AUDIO, true),
            appLanguage = prefs.getString(APP_LANGUAGE, null) ?: device.language.takeIf { it in APP_LANGUAGES } ?: "en",
            region = prefs.getString(REGION, null) ?: device.country.takeIf { it.length == 2 } ?: Library.DEFAULT_REGION,
            qualityCap = prefs.getString(QUALITY_CAP, null)?.let { name -> QualityCap.entries.find { it.name == name } } ?: QualityCap.UHD,
            subtitleStyle = SubtitleStyle(
                size = read(SUBTITLE_SIZE, SubtitleSize.entries) ?: SubtitleSize.Medium,
                colour = read(SUBTITLE_COLOUR, SubtitleColour.entries) ?: SubtitleColour.White,
                background = read(SUBTITLE_BACKGROUND, SubtitleBackground.entries) ?: SubtitleBackground.Outline,
                position = when {
                    prefs.contains(SUBTITLE_HEIGHT) -> SubtitlePosition.nearest(prefs.getInt(SUBTITLE_HEIGHT, SubtitlePosition.Bottom.percent))
                    // Before the height was stepped, it was Bottom or Raised.
                    prefs.getString(SUBTITLE_POSITION, null) == "Raised" -> SubtitlePosition.Raised
                    else -> SubtitlePosition.Bottom
                },
            ),
            playNextAutomatically = prefs.getBoolean(PLAY_NEXT_AUTOMATICALLY, true),
            backgroundReading = read(BACKGROUND_READING, BackgroundReading.entries) ?: BackgroundReading.Gentle,
            textSize = read(TEXT_SIZE, TextSize.entries) ?: TextSize.Medium,
            // A key this release doesn't offer (a League dropped since) is left out, the rest kept.
            leagues = prefs.getString(LEAGUES, null)?.split(',')?.mapNotNull(League::byKey)?.toSet(),
        )
    }

    private fun <T : Enum<T>> read(key: String, entries: List<T>): T? = prefs.getString(key, null)?.let { name -> entries.find { it.name == name } }

    private companion object {
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
    }
}

/** [base] with its resources in the App language [appLanguage], so the interface text is in it. */
fun localized(base: Context, appLanguage: String): Context {
    val configuration = Configuration(base.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(appLanguage))
    return base.createConfigurationContext(configuration)
}
