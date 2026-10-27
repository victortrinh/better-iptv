package dev.betteriptv.app

import android.content.Context
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.betteriptv.app.ui.theme.NuageTheme
import dev.betteriptv.core.PlayPlan
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleKind
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    /** The App language the interface text was loaded in: changing it recreates the activity. */
    private lateinit var appLanguage: String

    override fun attachBaseContext(newBase: Context) {
        appLanguage = (newBase.applicationContext as BetterIptvApp).settings.settings.value.appLanguage
        // Dates and numbers formatted with the default locale follow the App language too.
        Locale.setDefault(Locale.forLanguageTag(appLanguage))
        super.attachBaseContext(localized(newBase, appLanguage))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BetterIptvApp
        lifecycleScope.launch {
            app.settings.settings.collect { if (it.appLanguage != appLanguage) recreate() }
        }
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
        val settings by app.settings.settings.collectAsStateWithLifecycle()
        val contentLanguages = settings.contentLanguages
        val appLanguage = settings.appLanguage
        // Saved, so the Viewer stays in Settings when changing the App language recreates the screen.
        var tab by rememberSaveable { mutableStateOf(Browse.Home) }
        // Each screen keeps its scroll position while another is shown.
        val home = remember { HomeState() }
        val movies = rememberLazyGridState()
        val shows = rememberLazyGridState()
        // The query stays while a Title found by it is open, so Back returns to the same results.
        var query by remember { mutableStateOf("") }
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
                Browse.Search -> {
                    BackHandler { onTab(Browse.Movies) }
                    SearchScreen(
                        app.library, query, { query = it }, contentLanguages, appLanguage,
                        onTab = onTab,
                        onOpen = { opened = OpenedTitle(-1, it) },
                    )
                }
                Browse.Settings -> SettingsScreen(app.library, settings, onChange = app.settings::update, onTab = onTab)
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
                        app.library, title.id, contentLanguages, appLanguage, settings.qualityCap,
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
