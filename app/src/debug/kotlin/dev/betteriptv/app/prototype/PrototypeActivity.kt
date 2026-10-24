package dev.betteriptv.app.prototype

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.betteriptv.app.ui.theme.Directions
import dev.betteriptv.app.ui.theme.NuageTheme

/**
 * The design prototypes for #3, in debug builds only.
 *
 *     adb shell am start -n dev.betteriptv/dev.betteriptv.app.prototype.PrototypeActivity \
 *         --es direction galerie --es screen details --ez focus true
 *
 * Screens: home, details, player, search, settings. `focus` moves the first focus from the main
 * button to the first card (home) or Version (details). The remote's menu key cycles directions.
 */
class PrototypeActivity : ComponentActivity() {
    private var direction by mutableStateOf(Directions.Repertoire)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        direction = Directions.byId(intent.getStringExtra("direction"))
        val screen = intent.getStringExtra("screen") ?: "home"
        val focus = intent.getBooleanExtra("focus", false)
        setContent {
            NuageTheme(direction) {
                key(direction) {
                    when (screen) {
                        "details" -> DetailsPrototype(focusVersions = focus)
                        "player" -> PlayerPrototype()
                        "search" -> SearchPrototype()
                        "settings" -> SettingsPrototype()
                        else -> HomePrototype(focusCard = focus)
                    }
                }
            }
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_MENU) return super.onKeyUp(keyCode, event)
        val all = Directions.all
        direction = all[(all.indexOf(direction) + 1) % all.size]
        return true
    }
}
