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
import androidx.compose.ui.platform.LocalContext
import dev.betteriptv.app.ui.theme.NuageTheme
import dev.betteriptv.core.PlayPlan
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
        var tab by remember { mutableStateOf(Browse.Home) }
        // Each screen keeps its scroll position while another is shown.
        val home = remember { HomeState() }
        val movies = rememberLazyGridState()
        val shows = rememberLazyGridState()
        var opened by remember { mutableStateOf<OpenedTitle?>(null) }
        var returnedFrom by remember { mutableStateOf(0) }
        var returnedToHome by remember { mutableStateOf<HomeCard?>(null) }
        var playing by remember { mutableStateOf<PlayPlan?>(null) }
        val context = LocalContext.current
        val onTab = { selected: Browse ->
            tab = selected
            returnedFrom = -1
            returnedToHome = null
        }
        when (val title = opened) {
            null -> when (tab) {
                Browse.Home -> HomeScreen(
                    app.library, contentLanguages, appLanguage, home,
                    restoreFocus = returnedToHome,
                    onTab = onTab,
                    onOpen = { opened = OpenedTitle(-1, it.id, it) },
                )
                Browse.Movies, Browse.Shows -> {
                    val kind = if (tab == Browse.Movies) TitleKind.Movie else TitleKind.Show
                    TitlesScreen(
                        app.library, kind, contentLanguages, appLanguage, if (kind == TitleKind.Movie) movies else shows,
                        restoreFocus = returnedFrom,
                        onSyncNow = app::sync,
                        onTab = onTab,
                        onOpen = { index, id -> opened = OpenedTitle(index, id) },
                    )
                }
            }
            else -> when (val plan = playing) {
                null -> {
                    BackHandler {
                        returnedFrom = title.cardIndex
                        returnedToHome = title.homeCard
                        opened = null
                    }
                    TitleScreen(
                        app.library, title.id, contentLanguages, appLanguage,
                        deviceProfile = { readDeviceProfile(context) },
                        onPlay = { playing = it },
                    )
                }
                else -> {
                    BackHandler { playing = null }
                    PlayerScreen(app.library, plan, onEnded = { playing = null })
                }
            }
        }
    }
}

/** The Title whose details are open, and which card opened it (in a list, or on the home screen), so focus goes back there. */
private data class OpenedTitle(val cardIndex: Int, val id: TitleId, val homeCard: HomeCard? = null)
