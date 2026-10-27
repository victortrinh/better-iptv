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
)

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
        )
    }

    private companion object {
        const val CONTENT_LANGUAGES = "content_languages"
        const val APP_LANGUAGE = "app_language"
        const val REGION = "region"
        const val QUALITY_CAP = "quality_cap"
    }
}

/** [base] with its resources in the App language [appLanguage], so the interface text is in it. */
fun localized(base: Context, appLanguage: String): Context {
    val configuration = Configuration(base.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(appLanguage))
    return base.createConfigurationContext(configuration)
}
