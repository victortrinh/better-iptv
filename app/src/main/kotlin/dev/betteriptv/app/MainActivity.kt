package dev.betteriptv.app

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.NuageTheme
import dev.betteriptv.core.Channel
import dev.betteriptv.core.FeaturedTitle
import dev.betteriptv.core.Feed
import dev.betteriptv.core.GameFeeds
import dev.betteriptv.core.League
import dev.betteriptv.core.LiveCountry
import dev.betteriptv.core.PlayPlan
import dev.betteriptv.core.Replay
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleKind
import dev.betteriptv.core.UpdateState
import dev.betteriptv.core.offered
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        // Each time the app comes to the front: a held catalog lands, or a stale one is Synced. Never on a timer.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { app.onResumed() }
        }
        // Only while the app shows: the Viewer may be watching in another app.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.settings.settings.map { Triple(it.backgroundReading.pace, it.contentLanguages, it.preferOriginalAudio) }.distinctUntilChanged().collectLatest { (pace, languages, original) ->
                    if (pace != null) app.library.readInBackground(languages, pace, original)
                }
            }
        }
        setContent {
            val textSize by remember { app.settings.settings.map { it.textSize }.distinctUntilChanged() }
                .collectAsStateWithLifecycle(app.settings.settings.value.textSize)
            // Backdrops refused as they're decoded are remembered by the Library, so they count as none everywhere (#475).
            val backdrops = remember { Backdrops(onRefused = app.library::refuseBackdrop) }
            // A Box, so the screen fade (in [Root], after the screens) is drawn over them.
            NuageTheme(textSize) {
                CompositionLocalProvider(LocalBackdrops provides backdrops) { Box(Modifier.fillMaxSize().background(Colors.ground)) { Root(app) } }
            }
        }
    }
}

@Composable
private fun BoxScope.Root(app: BetterIptvApp) {
    var loggedIn by remember { mutableStateOf(app.library.isLoggedIn) }
    val update by (app.updates?.state ?: NO_UPDATES).collectAsStateWithLifecycle()
    // "Later" puts the offered build away until the next launch, a newer build, or until it's asked for in Settings.
    var laterFor by rememberSaveable { mutableStateOf<Long?>(null) }
    val offered = update.offered
    val onUpdate = {
        laterFor = null
        app.update()
    }
    // Opened while an update installs (#452): says so until Android closes the app, unless the Viewer goes on with Back.
    var finishingPutAway by rememberSaveable { mutableStateOf(false) }
    val showUpdate = if (update == UpdateState.Finishing) !finishingPutAway else offered != null && laterFor != offered.versionCode
    if (showUpdate) {
        UpdateScreen(update, onUpdate = onUpdate, onLater = { if (offered != null) laterFor = offered.versionCode else finishingPutAway = true })
    } else if (!loggedIn) {
        LoginScreen(app.library, onLoggedIn = {
            loggedIn = true
            app.sync()
        })
    } else {
        val settings by app.settings.settings.collectAsStateWithLifecycle()
        val contentLanguages = settings.contentLanguages
        val appLanguage = settings.appLanguage
        // Saved, so the Viewer stays in Settings when changing the App language recreates the screen.
        var tab by rememberSaveable { mutableStateOf(Browse.Home) }
        // Each screen keeps its scroll position while another is shown, and starts over on another Profile, whose rows are its own.
        val profile by app.library.profiles.collectAsStateWithLifecycle()
        val profileId = profile.currentId
        val home = remember(profileId) { RowsState() }
        val movies = remember(profileId) { TitlesState() }
        val shows = remember(profileId) { TitlesState() }
        val live = remember(profileId) { RowsState() }
        // The query stays while a Title found by it is open, so Back returns to the same results.
        var query by remember { mutableStateOf("") }
        var opened by remember { mutableStateOf<OpenedTitle?>(null) }
        // The Game whose page is open, and which card on Live or Home opened it.
        var openedGame by remember { mutableStateOf<OpenedGame?>(null) }
        // The country whose Channels screen is open, from Live's Channels row, and what that screen keeps while a Channel plays.
        var browsing by remember { mutableStateOf<LiveCountry?>(null) }
        val channels = remember { ChannelsState() }
        // The card Back from a Title, Game or Channel returns to, on Home, a Titles tab or Live.
        var returnedTo by remember { mutableStateOf<RowCard?>(null) }
        // The screen was reached by picking its tab, which keeps focus there.
        var byTab by remember { mutableStateOf(false) }
        var playing by remember { mutableStateOf<NowPlaying?>(null) }
        // "Add a profile" in "Who's watching?" opens Settings on the new Profile's name.
        var addingProfile by remember { mutableStateOf(false) }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val onTab = { selected: Browse ->
            tab = selected
            byTab = true
            returnedTo = null
        }
        val watch = { channel: Channel ->
            byTab = false
            // The best variant depends on what the stick and TV play, read off the main thread as a Title's Play does.
            scope.launch {
                val plan = withContext(Dispatchers.Default) { app.library.channelPlayPlan(channel, contentLanguages, readDeviceProfile(context), settings.qualityCap) }
                playing = NowPlaying(plan, channel.name, channel = channel)
            }
            Unit
        }
        // A Sports replay plays as its Listing's Version, named [name] in the player.
        val playReplay = { replay: Replay, name: String ->
            byTab = false
            scope.launch { playing = NowPlaying(app.library.replayPlayPlan(replay, contentLanguages), name) }
            Unit
        }
        // The hero's Play starts what the Title page's Play would; with nothing to play, the Title page
        // opens to say why. Back from either comes back to the hero's [card].
        val playFeatured = { card: RowCard, title: FeaturedTitle ->
            byTab = false
            returnedTo = card
            scope.launch {
                val plan = withContext(Dispatchers.Default) {
                    app.library.titlePlayPlan(title.id, contentLanguages, appLanguage, readDeviceProfile(context), settings.qualityCap, settings.preferOriginalAudio)
                }
                if (plan != null) playing = NowPlaying(plan, title.name, art = title.startingArt) else opened = OpenedTitle(title.id, card)
            }
            Unit
        }
        val now = playing
        val country = browsing
        SideEffect { app.playerOpen = now != null }
        // Entering a top-level screen (a tab, or back from the player, a Title, a Game or a country) lands a held
        // Sync's catalog, so rows change between screens, never under focus; leaving Settings after Sync now too.
        val topLevel = now == null && opened == null && openedGame == null && country == null
        LaunchedEffect(tab, topLevel) { if (topLevel) app.applyPendingCatalog() }
        val chipPulse by app.library.profileChipPulse.collectAsStateWithLifecycle()
        // With two Profiles or more, the tabs' chip opens "Who's watching?" over whichever browsing screen is shown (#432).
        ProfileSwitcher(
            profile,
            app.library.householdPin,
            onSwitch = { app.changeProfiles(ProfileChange.Switch(it)) },
            onAdd = {
                // Settings' add flow, under its own tabs: Back from it lands on "Add a profile".
                tab = Browse.Settings
                byTab = false
                addingProfile = true
            },
            pulse = chipPulse,
            onPulsed = { scope.launch { app.library.profileChipPulsed() } },
        ) {
            if (now != null) {
                BackHandler { playing = null }
                // Plays another of the Game's Feeds, a new plan for its Channel, as the Game page's Play does.
                val playFeed = { entry: GameFeeds, feed: Feed ->
                    scope.launch {
                        val plan = withContext(Dispatchers.Default) { app.library.channelPlayPlan(feed.channel, contentLanguages, readDeviceProfile(context), settings.qualityCap) }
                        if (playing == now) playing = NowPlaying(plan, now.name, game = PlayingFeed(entry, feed), channel = feed.channel)
                    }
                    Unit
                }
                PlayerScreen(
                    app.library, now.plan, now.name, settings,
                    deviceProfile = { readDeviceProfile(context) },
                    onSubtitleStyle = { style -> app.settings.update { it.copy(subtitleStyle = style) } },
                    inARow = now.inARow,
                    onPlay = { plan, inARow -> playing = now.copy(plan = plan, inARow = inARow) },
                    onEnded = { playing = null },
                    playingFeed = now.game,
                    onFeed = { feed -> now.game?.let { playFeed(it.entry, feed) } },
                    channel = now.channel,
                    art = now.art ?: now.channel?.let { StartingArt.Channel(it.image) },
                    onNotThisGame = {
                        now.game?.let { game ->
                            scope.launch {
                                val updated = app.library.notThisGame(game.entry, game.feed, contentLanguages, settings.region)
                                // The Game page under the player lists the Feed last and marked when Back returns to it.
                                if (openedGame?.entry?.key == updated.key) openedGame = openedGame?.copy(entry = updated)
                                // Only while the player still plays what was dropped: Back meanwhile must not reopen it.
                                if (playing != now) return@launch
                                val next = updated.best
                                if (next == null) playing = null else playFeed(updated, next)
                            }
                        }
                    },
                )
            } else if (openedGame != null) {
                val game = openedGame!!
                BackHandler {
                    byTab = false
                    returnedTo = game.card
                    openedGame = null
                }
                GameScreen(
                    game.entry,
                    deviceProfile = { readDeviceProfile(context) },
                    planFor = { channel, device -> app.library.channelPlayPlan(channel, contentLanguages, device, settings.qualityCap) },
                    onPlay = { plan, name, feed -> playing = NowPlaying(plan, name, game = PlayingFeed(game.entry, feed), channel = feed.channel) },
                    replayFor = { app.library.gameReplay(it) },
                    replayPlanFor = { app.library.replayPlayPlan(it, contentLanguages) },
                    onPlayReplay = { plan, name -> playing = NowPlaying(plan, name) },
                )
            } else if (country != null) {
                BackHandler {
                    byTab = false
                    browsing = null
                }
                ChannelsScreen(app.library, country, appLanguage, channels, onWatch = watch)
            } else when (val title = opened) {
                null -> when (tab) {
                    Browse.Home -> HomeScreen(
                        app.library, contentLanguages, appLanguage, settings.region, League.entries.filter { it in settings.leaguesOn }, home,
                        restoreFocus = returnedTo,
                        focusTab = byTab,
                        onTab = onTab,
                        onOpen = { card, id, backdrop -> opened = OpenedTitle(id, card, backdrop) },
                        onWatch = { card, channel ->
                            // Back from the Channel comes back to its card.
                            returnedTo = card
                            watch(channel)
                        },
                        // Back from the Game's page comes back to its card, or to what took its place once it ended.
                        onOpenGame = { card, entry -> openedGame = OpenedGame(entry, card) },
                        onPlay = playFeatured,
                        onRetry = app::sync,
                    )
                    Browse.Live -> LiveScreen(
                        // In Settings' order, which the League rows follow.
                        app.library, contentLanguages, appLanguage, settings.region, League.entries.filter { it in settings.leaguesOn }, live,
                        restoreFocus = returnedTo,
                        focusTab = byTab,
                        onTab = onTab,
                        onWatch = { card, channel ->
                            returnedTo = card
                            watch(channel)
                        },
                        onOpenGame = { card, entry -> openedGame = OpenedGame(entry, card) },
                        onOpenCountry = { card, picked ->
                            // Back from the Channels screen comes back to the country's card.
                            returnedTo = card
                            channels.reset()
                            browsing = picked
                        },
                        onPlayReplay = { card, entry ->
                            returnedTo = card
                            playReplay(entry.replay, context.matchup(entry))
                        },
                        onRetry = app::sync,
                    )
                    Browse.Search -> {
                        BackHandler { onTab(Browse.Movies) }
                        SearchScreen(
                            app.library, query, { edit -> query = edit(query) }, contentLanguages, appLanguage,
                            focusTab = byTab,
                            onTab = onTab,
                            onOpen = { opened = OpenedTitle(it) },
                            onWatch = watch,
                            onPlayReplay = { playReplay(it, it.heading) },
                        )
                    }
                    Browse.Settings -> SettingsScreen(
                        app.library, settings, update, onChange = app.settings::update, onUpdate = onUpdate, onSyncNow = app::sync, focusTab = byTab, onTab = onTab,
                        profiles = profile,
                        onProfile = app::changeProfiles,
                        addProfile = addingProfile,
                        onAddProfileShown = { addingProfile = false },
                    )
                    Browse.Movies, Browse.Shows -> {
                        val kind = if (tab == Browse.Movies) TitleKind.Movie else TitleKind.Show
                        // Each tab its own screen, so the card to focus on one isn't looked for on the other.
                        key(kind) {
                            TitlesScreen(
                                app.library, kind, contentLanguages, appLanguage, if (kind == TitleKind.Movie) movies else shows,
                                restoreFocus = returnedTo,
                                focusTab = byTab,
                                onTab = onTab,
                                onOpen = { card, id, backdrop -> opened = OpenedTitle(id, card, backdrop) },
                                onPlay = playFeatured,
                                onRetry = app::sync,
                            )
                        }
                    }
                }
                else -> {
                    // Back from the Versions screen, the Episode picker or the Trailer screen returns to the Title page, on the action that opened it.
                    val closeSection = { opened = title.copy(section = null, returnedFrom = title.section) }
                    BackHandler {
                        byTab = false
                        if (title.section != null) {
                            closeSection()
                        } else {
                            returnedTo = title.card
                            opened = null
                        }
                    }
                    TitleScreen(
                        app.library, title.id, contentLanguages, appLanguage, settings.qualityCap,
                        preferOriginalAudio = settings.preferOriginalAudio,
                        openedOn = title.backdrop,
                        deviceProfile = { readDeviceProfile(context) },
                        onPlay = { plan, name, art -> playing = NowPlaying(plan, name, art = art) },
                        section = title.section,
                        returnedFrom = title.returnedFrom,
                        onSection = { opened = title.copy(section = it, returnedFrom = null) },
                        // A trailer that ends, or opens in the YouTube app instead, closes its screen too.
                        onCloseSection = closeSection,
                    )
                }
            }
            // Each screen change fades the new screen in over the ground; the player opens and closes as a cut.
            ScreenFade(
                when {
                    now != null -> PlayerShown
                    openedGame != null -> GameShown(openedGame!!.entry.key)
                    country != null -> country
                    opened != null -> TitleShown(opened!!.id, opened!!.section)
                    else -> tab
                },
                // A Title page opened from the hero's Details opens on the hero's backdrop, so no veil hides it; its own
                // content still fades in (TitlePage). Its Versions screen and Episode picker, and the page again after them, fade in as any screen.
                asIs = { shown ->
                    val title = opened
                    shown == PlayerShown || (shown is TitleShown && title != null && shown == TitleShown(title.id, null) &&
                        title.section == null && title.returnedFrom == null && title.backdrop != null)
                },
            )
            // Signing in would bring the household past five Profiles: which to keep comes first, over any screen (#440).
            val choice by app.library.profileChoice.collectAsStateWithLifecycle()
            choice?.let {
                KeepProfiles(
                    it,
                    onKeep = { keys -> scope.launch { app.library.keepProfiles(keys) } },
                    onCancel = { scope.launch { app.library.signOut() } },
                )
            }
        }
    }
}

/** The player is on screen: it opens and closes with no fade over the picture. */
private object PlayerShown

/** The Title page of [id] is on screen, or with a [section], that section's screen. */
private data class TitleShown(val id: TitleId, val section: TitleSection?)

/** The page of the Game with this key is on screen. */
private data class GameShown(val key: String)

/** What builds without the in-app updater show: nothing to offer. */
private val NO_UPDATES = MutableStateFlow<UpdateState>(UpdateState.Unknown)

/**
 * What the player plays: a [plan] of the Title or Channel named [name], the [inARow]th Episode in a
 * row to start by itself since a key was pressed; for a Game's Feed, which [game] and Feed, so the
 * player's menu key lists the others; for a Channel (a Favourite's, a Feed's or a browsed one), the
 * [channel], so the player's top scrim names the programme on now and its logo shows while it starts;
 * for a Title, the [art] shown while it starts.
 */
private data class NowPlaying(val plan: PlayPlan, val name: String, val inARow: Int = 0, val game: PlayingFeed? = null, val channel: Channel? = null, val art: StartingArt? = null)

/**
 * The Title whose details are open, and which card on a screen of rows opened it, so focus goes back there (none from Search),
 * with the [backdrop] the featured hero drew when its Details opened it, which the page opens on. A [section] is open over the page (its
 * Versions screen, Episode picker or Trailer screen); [returnedFrom] is the one Back just closed, whose action takes focus.
 */
private data class OpenedTitle(
    val id: TitleId,
    val card: RowCard? = null,
    val backdrop: String? = null,
    val section: TitleSection? = null,
    val returnedFrom: TitleSection? = null,
)

/** The Game whose page is open ([entry], with its Feeds as its card had them), and which card on Live or Home opened it. */
private data class OpenedGame(val entry: GameFeeds, val card: RowCard)
