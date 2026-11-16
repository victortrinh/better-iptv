package dev.betteriptv.app

import android.content.Context
import dev.betteriptv.core.KeptDeviceSettings
import dev.betteriptv.core.SettingsStore
import dev.betteriptv.core.SubtitlePosition

/**
 * Android's place for the device's settings, which the Core keeps there by key ([KeptDeviceSettings]):
 * the app-private file the app kept them in itself before the Core did (#583), under the same keys,
 * so an update finds every setting where it was. What an older app wrote in another form is rewritten
 * as the Core reads it, once, as the store opens.
 */
class AndroidSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    init {
        val old = prefs.all
        val edit = prefs.edit()
        // The subtitle height was kept as a number; the Core keeps text.
        (old[KeptDeviceSettings.SUBTITLE_HEIGHT] as? Int)?.let { edit.putString(KeptDeviceSettings.SUBTITLE_HEIGHT, it.toString()) }
        // Before the height was stepped, it was Bottom or Raised.
        if (SUBTITLE_POSITION in old) {
            edit.remove(SUBTITLE_POSITION)
            if (KeptDeviceSettings.SUBTITLE_HEIGHT !in old && old[SUBTITLE_POSITION] == "Raised") {
                edit.putString(KeptDeviceSettings.SUBTITLE_HEIGHT, SubtitlePosition.Raised.percent.toString())
            }
        }
        edit.apply()
    }

    override fun read(key: String): String? = prefs.all[key] as? String

    override fun write(changed: Map<String, String?>) {
        val edit = prefs.edit()
        changed.forEach { (key, value) -> if (value == null) edit.remove(key) else edit.putString(key, value) }
        edit.apply()
    }

    companion object {
        /** The device's settings; before #440 the Region too, and before Profiles each Profile's ([ViewerSettingsStore] moves those out). */
        const val FILE = "viewer_settings"
        private const val SUBTITLE_POSITION = "subtitle_position"
    }
}
