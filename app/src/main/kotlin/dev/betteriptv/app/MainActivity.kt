package dev.betteriptv.app

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.betteriptv.app.ui.theme.NuageTheme
import dev.betteriptv.core.Channel
import dev.betteriptv.core.PlayPlan
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleKind
import dev.betteriptv.core.UpdateState
import dev.betteriptv.core.offered
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
        // Only Titles, never a location: it holds the login.
        lifecycleScope.launch { app.library.confirmations.collect { Log.i("BackgroundReading", "Confirmed $it") } }
        // Only while the app shows: the Viewer may be watching in another app.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.settings.settings.map { it.backgroundReading.pace to it.contentLanguages }.distinctUntilChanged().collectLatest { (pace, languages) ->
                    if (pace != null) app.library.readInBackground(languages, pace)
                }
            }
        }
        setContent {
            val textSize by remember { app.settings.settings.map { it.textSize }.distinctUntilChanged() }
                .collectAsStateWithLifecycle(app.settings.settings.value.textSize)
            NuageTheme(textSize) { Root(app) }
        }
    }
}

@Composable
private fun Root(app: BetterIptvApp) {
    var loggedIn by remember { mutableStateOf(app.library.isLoggedIn) }
    val update by (app.updates?.state ?: NO_UPDATES).collectAsStateWithLifecycle()
    // "Later" puts the offered update away until the next launch, or until it's asked for in Settings.
    var updateLater by rememberSaveable { mutableStateOf(false) }
    val onUpdate = {
        updateLater = false
        app.update()
    }
    if (!updateLater && update.offered != null) {
        UpdateScreen(update, onUpdate = onUpdate, onLater = { updateLater = true })
    } else if (!loggedIn) {
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
        val home = remember { RowsState() }
        val movies = remember { TitlesState() }
        val shows = remember { TitlesState() }
        // The query stays while a Title found by it is open, so Back returns to the same results.
        var query by remember { mutableStateOf("") }
        var opened by remember { mutableStateOf<OpenedTitle?>(null) }
        // The card Back from a Title or Channel returns to, on Home or a Titles tab.
        var returnedTo by remember { mutableStateOf<RowCard?>(null) }
        // The screen was reached by picking its tab, which keeps focus there.
        var byTab by remember { mutableStateOf(false) }
        var playing by remember { mutableStateOf<NowPlaying?>(null) }
        val context = LocalContext.current
        val onTab = { selected: Browse ->
            tab = selected
            byTab = true
            returnedTo = null
        }
        val watch = { channel: Channel ->
            byTab = false
            playing = NowPlaying(app.library.channelPlayPlan(channel, contentLanguages), channel.name)
        }
        val now = playing
        if (now != null) {
            BackHandler { playing = null }
            PlayerScreen(
                app.library, now.plan, now.name, settings,
                deviceProfile = { readDeviceProfile(context) },
                onSubtitleStyle = { style -> app.settings.update { it.copy(subtitleStyle = style) } },
                inARow = now.inARow,
                onPlay = { plan, inARow -> playing = now.copy(plan = plan, inARow = inARow) },
                onEnded = { playing = null },
            )
        } else when (val title = opened) {
            null -> when (tab) {
                Browse.Home -> HomeScreen(
                    app.library, contentLanguages, appLanguage, settings.region, home,
                    restoreFocus = returnedTo,
                    focusTab = byTab,
                    onTab = onTab,
                    onOpen = { card, id -> opened = OpenedTitle(id, card) },
                    onWatch = { card, channel ->
                        // Back from the Channel comes back to its card.
                        returnedTo = card
                        watch(channel)
                    },
                )
                Browse.Search -> {
                    BackHandler { onTab(Browse.Movies) }
                    SearchScreen(
                        app.library, query, { edit -> query = edit(query) }, contentLanguages, appLanguage, settings.region,
                        focusTab = byTab,
                        onTab = onTab,
                        onOpen = { opened = OpenedTitle(it) },
                        onWatch = watch,
                    )
                }
                Browse.Settings -> SettingsScreen(app.library, settings, update, onChange = app.settings::update, onUpdate = onUpdate, onSyncNow = app::sync, focusTab = byTab, onTab = onTab)
                Browse.Movies, Browse.Shows -> {
                    val kind = if (tab == Browse.Movies) TitleKind.Movie else TitleKind.Show
                    // Each tab its own screen, so the card to focus on one isn't looked for on the other.
                    key(kind) {
                        TitlesScreen(
                            app.library, kind, contentLanguages, appLanguage, settings.region, if (kind == TitleKind.Movie) movies else shows,
                            restoreFocus = returnedTo,
                            focusTab = byTab,
                            onTab = onTab,
                            onOpen = { card, id -> opened = OpenedTitle(id, card) },
                        )
                    }
                }
            }
            else -> {
                BackHandler {
                    byTab = false
                    returnedTo = title.card
                    opened = null
                }
                TitleScreen(
                    app.library, title.id, contentLanguages, appLanguage, settings.region, settings.qualityCap,
                    deviceProfile = { readDeviceProfile(context) },
                    onPlay = { plan, name -> playing = NowPlaying(plan, name) },
                )
            }
        }
    }
}

/** What builds without the in-app updater show: nothing to offer. */
private val NO_UPDATES = MutableStateFlow<UpdateState>(UpdateState.Unknown)

/**
 * What the player plays: a [plan] of the Title or Channel named [name], the [inARow]th Episode in a
 * row to start by itself since a key was pressed.
 */
private data class NowPlaying(val plan: PlayPlan, val name: String, val inARow: Int = 0)

/** The Title whose details are open, and which card on a screen of rows opened it, so focus goes back there (none from Search). */
private data class OpenedTitle(val id: TitleId, val card: RowCard? = null)
