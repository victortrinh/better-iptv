package dev.betteriptv.core

import kotlinx.coroutines.flow.StateFlow

/**
 * The settings that sync with the Account (#440), kept by the [Library]: each Profile's own, by
 * [ProfileSetting] name, and the household's Region. The app writes them as text and reads them
 * back; what a setting means is the app's. The device's own settings (Quality cap, Text size,
 * subtitle style, Background reading) are never here, and never leave the TV.
 */
interface KeptSettings {
    /** The current Profile's settings, by name; one the Viewer never set is missing, and follows the device. */
    val profileSettings: StateFlow<Map<String, String>>

    /** The household's Region, an ISO 3166 country code; null until set on any TV of the Account. */
    val region: StateFlow<String?>

    /** Keeps the current Profile's [changed] settings (a null value takes one back), synced to the Account's other TVs. */
    fun saveProfileSettings(changed: Map<String, String?>)

    /** Makes [region] the household's Region, on every TV of the Account. */
    fun saveRegion(region: String)

    /**
     * Keeps what an older app kept for Profile [profile] in its own files ([values], by name), and
     * the [region] it kept, unless set since: as the oldest change, so an Account's settings win.
     */
    fun importSettings(profile: Long, values: Map<String, String>, region: String?)
}

/** The names of the settings each Profile keeps its own ([KeptSettings.profileSettings]). */
object ProfileSetting {
    const val CONTENT_LANGUAGES = "contentLanguages"
    const val PREFER_ORIGINAL_AUDIO = "preferOriginalAudio"
    const val APP_LANGUAGE = "appLanguage"
    const val PLAY_NEXT_AUTOMATICALLY = "playNextAutomatically"
    const val LEAGUES = "leagues"

    val ALL: Set<String> = setOf(CONTENT_LANGUAGES, PREFER_ORIGINAL_AUDIO, APP_LANGUAGE, PLAY_NEXT_AUTOMATICALLY, LEAGUES)
}
