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
        val grid = rememberLazyGridState()
        var opened by remember { mutableStateOf<Pair<Int, TitleId>?>(null) }
        var returnedFrom by remember { mutableStateOf<Int?>(null) }
        when (val title = opened) {
            null -> MoviesScreen(app.library, contentLanguages, grid, restoreFocus = returnedFrom ?: 0, onSyncNow = app::sync, onOpen = { index, id ->
                opened = index to id
            })
            else -> {
                BackHandler {
                    returnedFrom = title.first
                    opened = null
                }
                TitleScreen(app.library, title.second, contentLanguages)
            }
        }
    }
}
