package dev.betteriptv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.betteriptv.app.ui.theme.NuageTheme
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleKind
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BetterIptvApp
        setContent {
            NuageTheme { Root(app) }
        }
    }
}

@Composable
private fun Root(app: BetterIptvApp) {
    var loggedIn by remember { mutableStateOf(app.library.isLoggedIn) }
    if (!loggedIn) {
        LoginScreen(app.library, onLoggedIn = {
            loggedIn = true
            app.sync()
        })
    } else {
        LaunchedEffect(Unit) { app.syncIfStale() }
        // Content languages default to the device's language until settings let the Viewer choose.
        val contentLanguages = remember { listOf(Locale.getDefault().toLanguageTag()) }
        // The App language is the device's when it is French, English otherwise, until settings let the Viewer choose.
        val appLanguage = remember { Locale.getDefault().takeIf { it.language == "fr" }?.toLanguageTag() ?: "en" }
        var kind by remember { mutableStateOf(TitleKind.Movie) }
        // Each list keeps its scroll position while the other is shown.
        val movies = rememberLazyGridState()
        val shows = rememberLazyGridState()
        var opened by remember { mutableStateOf<OpenedTitle?>(null) }
        var returnedFrom by remember { mutableStateOf(0) }
        when (val title = opened) {
            null -> TitlesScreen(
                app.library, kind, contentLanguages, appLanguage, if (kind == TitleKind.Movie) movies else shows,
                restoreFocus = returnedFrom,
                onSyncNow = app::sync,
                onKind = {
                    kind = it
                    returnedFrom = -1
                },
                onOpen = { index, id -> opened = OpenedTitle(index, id) },
            )
            else -> {
                BackHandler {
                    returnedFrom = title.cardIndex
                    opened = null
                }
                TitleScreen(app.library, title.id, contentLanguages, appLanguage)
            }
        }
    }
}

/** The Title whose details are open, and which card opened it, so focus goes back there. */
private data class OpenedTitle(val cardIndex: Int, val id: TitleId)
