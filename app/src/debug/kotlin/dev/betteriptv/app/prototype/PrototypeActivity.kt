package dev.betteriptv.app.prototype

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.BetterIptvApp
import dev.betteriptv.app.localized
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.NuageTheme
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type

/**
 * The design prototypes for #3, in debug builds only, with placeholder content.
 *
 *     adb shell am start -n dev.betteriptv.debug/dev.betteriptv.app.prototype.PrototypeActivity \
 *         --es screen details --ez focus true
 *
 * Screens: home, details, player, search, settings. `focus` moves the first focus from the main
 * button to the first card (home) or Version (details). `--ez tracks true` opens the player's
 * Audio and subtitles. They follow the Viewer's App language and Text size, read at launch.
 */
class PrototypeActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val appLanguage = (newBase.applicationContext as BetterIptvApp).settings.settings.value.appLanguage
        super.attachBaseContext(localized(newBase, appLanguage))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val screen = intent.getStringExtra("screen") ?: "home"
        val focus = intent.getBooleanExtra("focus", false)
        setContent {
            NuageTheme((application as BetterIptvApp).settings.settings.value.textSize) {
                Box(Modifier.fillMaxSize()) {
                    when (screen) {
                        "details" -> DetailsPrototype(focusVersions = focus)
                        "player" -> PlayerPrototype(tracks = intent.getBooleanExtra("tracks", false))
                        "search" -> SearchPrototype()
                        "settings" -> SettingsPrototype()
                        else -> HomePrototype(focusCard = focus)
                    }
                    // So nobody mistakes the made-up Versions for real ones. It sits in the top band, where
                    // nothing scrolls under it; the player's top right holds the buffering suggestion.
                    Text(
                        "Sample content",
                        style = Type.caption,
                        color = Colors.inkMuted,
                        modifier = Modifier
                            .align(if (screen == "player") Alignment.TopCenter else Alignment.TopEnd)
                            .padding(horizontal = Spacing.screenX, vertical = Spacing.screenY + 10.dp),
                    )
                }
            }
        }
    }
}
