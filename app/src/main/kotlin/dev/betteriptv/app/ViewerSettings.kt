package dev.betteriptv.app

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
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
    val appLanguage: String,
    val region: String,
    val qualityCap: QualityCap,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
)

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
enum class SubtitleSize(val scale: Float) { Small(0.8f), Medium(1f), Large(1.3f), Largest(1.6f) }

enum class SubtitleColour(val argb: Int) { White(0xFFFFFFFF.toInt()), Yellow(0xFFFFE14D.toInt()) }

/** What sits behind subtitles: a dark outline round the letters, a shadow, or a dark box. */
enum class SubtitleBackground { Outline, Shadow, Box }

/** How high subtitles sit, as a share of the picture's height from its bottom. */
enum class SubtitlePosition(val fromBottom: Float) { Bottom(0.08f), Raised(0.2f) }

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
            .putString(APP_LANGUAGE, new.appLanguage)
            .putString(REGION, new.region)
            .putString(QUALITY_CAP, new.qualityCap.name)
            .putString(SUBTITLE_SIZE, new.subtitleStyle.size.name)
            .putString(SUBTITLE_COLOUR, new.subtitleStyle.colour.name)
            .putString(SUBTITLE_BACKGROUND, new.subtitleStyle.background.name)
            .putString(SUBTITLE_POSITION, new.subtitleStyle.position.name)
            .apply()
        _settings.value = new
    }

    private fun read(): ViewerSettings {
        // The system's locale, not the app's: the app's follows the App language.
        val device = Resources.getSystem().configuration.locales[0]
        return ViewerSettings(
            contentLanguages = prefs.getString(CONTENT_LANGUAGES, null)?.split(',')?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
                ?: listOf(device.language),
            appLanguage = prefs.getString(APP_LANGUAGE, null) ?: device.language.takeIf { it in APP_LANGUAGES } ?: "en",
            region = prefs.getString(REGION, null) ?: device.country.takeIf { it.length == 2 } ?: "US",
            qualityCap = prefs.getString(QUALITY_CAP, null)?.let { name -> QualityCap.entries.find { it.name == name } } ?: QualityCap.UHD,
            subtitleStyle = SubtitleStyle(
                size = read(SUBTITLE_SIZE, SubtitleSize.entries) ?: SubtitleSize.Medium,
                colour = read(SUBTITLE_COLOUR, SubtitleColour.entries) ?: SubtitleColour.White,
                background = read(SUBTITLE_BACKGROUND, SubtitleBackground.entries) ?: SubtitleBackground.Outline,
                position = read(SUBTITLE_POSITION, SubtitlePosition.entries) ?: SubtitlePosition.Bottom,
            ),
        )
    }

    private fun <T : Enum<T>> read(key: String, entries: List<T>): T? = prefs.getString(key, null)?.let { name -> entries.find { it.name == name } }

    private companion object {
        const val CONTENT_LANGUAGES = "content_languages"
        const val APP_LANGUAGE = "app_language"
        const val REGION = "region"
        const val QUALITY_CAP = "quality_cap"
        const val SUBTITLE_SIZE = "subtitle_size"
        const val SUBTITLE_COLOUR = "subtitle_colour"
        const val SUBTITLE_BACKGROUND = "subtitle_background"
        const val SUBTITLE_POSITION = "subtitle_position"
    }
}

/** [base] with its resources in the App language [appLanguage], so the interface text is in it. */
fun localized(base: Context, appLanguage: String): Context {
    val configuration = Configuration(base.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(appLanguage))
    return base.createConfigurationContext(configuration)
}
