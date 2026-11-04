package dev.betteriptv.app

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import dev.betteriptv.app.ui.theme.Corners
import dev.betteriptv.core.SyncStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import dev.betteriptv.app.ui.components.CardInfo
import dev.betteriptv.app.ui.components.CardLogo
import dev.betteriptv.app.ui.components.GameCard
import dev.betteriptv.app.ui.components.RowHeading
import dev.betteriptv.app.ui.components.CountryCard
import dev.betteriptv.app.ui.components.RailInfo
import dev.betteriptv.app.ui.components.RankedCard
import dev.betteriptv.app.ui.components.TitleCard
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.StayPut
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import androidx.compose.runtime.rememberCoroutineScope
import dev.betteriptv.core.Channel
import dev.betteriptv.core.ChartList
import dev.betteriptv.core.GameFeeds
import dev.betteriptv.core.HomeRow
import dev.betteriptv.core.InProgress
import dev.betteriptv.core.LeagueReplay
import dev.betteriptv.core.LiveCountry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import dev.betteriptv.core.Library
import dev.betteriptv.core.TitleId
import dev.betteriptv.core.TitleKind
import dev.betteriptv.core.TitleSummary

/**
 * A card on Home, Movies or Shows: the [row] it is in, by the row's key (not its place, which
 * changes as Continue watching and Favourites come and go), and its Title's or Channel's [key].
 */
data class RowCard(val row: String, val key: String)

/**
 * Where focus goes for [card], a card of the rows [before], once the screen shows [after] (each row's
 * key to its cards' keys, in screen order): the card itself; else the card now in its place in the
 * same row, the one after it or the last; else the first card of the row now where its row was.
 * Null when there are no cards.
 */
fun restoredCard(card: RowCard, before: Map<String, List<String>>, after: Map<String, List<String>>): RowCard? {
    val cards = after[card.row]
    if (cards != null && card.key in cards) return card
    if (!cards.isNullOrEmpty()) {
        val at = before[card.row]?.indexOf(card.key) ?: -1
        return RowCard(card.row, cards[at.coerceIn(0, cards.lastIndex)])
    }
    val rows = after.filterValues { it.isNotEmpty() }.keys.toList()
    if (rows.isEmpty()) return null
    val at = before.keys.indexOf(card.row).coerceIn(0, rows.lastIndex)
    return RowCard(rows[at], after.getValue(rows[at]).first())
}

/**
 * What the screen shows once [fresh] rows are read while focus is in the row keyed [focusedRow] of
 * the rows [shown], so no row moves under focus: the fresh rows, except that the focused row stays
 * as it is when its cards changed (one came, went or moved), in its place among the fresh rows,
 * after the row it followed. Its cards still take their fresh reading wherever the fresh rows have
 * them, so a Game going live says "Live" in place. With no focused row, or one whose cards are the
 * same, the fresh rows show as they are. The caller reads the fresh rows again through this once
 * focus leaves the row, which then takes its own change.
 */
internal fun rowsUnderFocus(shown: List<HomeRow>, fresh: List<HomeRow>, focusedRow: String?): List<HomeRow> {
    val held = shown.firstOrNull { it.key == focusedRow } ?: return fresh
    val at = fresh.indexOfFirst { it.key == focusedRow }
    if (at >= 0 && fresh[at].keys == held.keys) return fresh
    val kept = held.refreshedFrom(fresh)
    if (at >= 0) return fresh.toMutableList().apply { set(at, kept) }
    // The row has gone from the fresh rows: it stays after the nearest row above it that is still there.
    val above = shown.subList(0, shown.indexOf(held)).asReversed().firstNotNullOfOrNull { row -> fresh.indexOfFirst { it.key == row.key }.takeIf { it >= 0 } }
    return fresh.toMutableList().apply { add((above ?: -1) + 1, kept) }
}

/** This row with each of its cards as [fresh] rows have it, where they do: the same cards, read again. */
private fun HomeRow.refreshedFrom(fresh: List<HomeRow>): HomeRow {
    val freshGames by lazy { fresh.flatMap { it.games.orEmpty() }.associateBy(::gameKey) }
    val freshTitles by lazy { fresh.flatMap { it.titles }.associateBy { it.id } }
    val freshChannels by lazy {
        fresh.flatMap { row ->
            when (row) {
                is HomeRow.Favourites -> row.channels
                is HomeRow.MoreLiveEvents -> row.events.map { it.channel }
                else -> emptyList()
            }
        }.associateBy(::channelKey)
    }
    return when (this) {
        is HomeRow.ContinueWatching -> {
            val resumes = fresh.filterIsInstance<HomeRow.ContinueWatching>().flatMap { it.titles }.associateBy { it.title.id }
            copy(titles = titles.map { resumes[it.title.id] ?: it })
        }
        is HomeRow.Favourites -> copy(channels = channels.map { freshChannels[channelKey(it)] ?: it })
        is HomeRow.Channels -> {
            val freshCountries = fresh.filterIsInstance<HomeRow.Channels>().flatMap { it.countries }.associateBy(::countryKey)
            copy(countries = countries.map { freshCountries[countryKey(it)] ?: it })
        }
        is HomeRow.Chart -> copy(titles = titles.map { freshTitles[it.id] ?: it })
        is HomeRow.NewOnYourService -> copy(titles = titles.map { freshTitles[it.id] ?: it })
        is HomeRow.Genre -> copy(titles = titles.map { freshTitles[it.id] ?: it })
        is HomeRow.LiveNow -> copy(games = games.map { freshGames[gameKey(it)] ?: it })
        is HomeRow.Today -> copy(games = games.map { freshGames[gameKey(it)] ?: it })
        is HomeRow.LeagueGames -> copy(games = games.map { freshGames[gameKey(it)] ?: it })
        is HomeRow.MoreLiveEvents -> {
            val freshEvents = fresh.filterIsInstance<HomeRow.MoreLiveEvents>().flatMap { it.events }.associateBy { channelKey(it.channel) }
            copy(events = events.map { freshEvents[channelKey(it.channel)] ?: it })
        }
        is HomeRow.Replays -> {
            val freshReplays = fresh.filterIsInstance<HomeRow.Replays>().flatMap { it.replays }.associateBy(::replayKey)
            copy(replays = replays.map { freshReplays[replayKey(it)] ?: it })
        }
    }
}

/**
 * The textless backdrops the cards on either side of [card] grow into, in [rows]: what the next Left
 * or Right grows into. On a rail that [loops] the first card's left is the last card, and the last
 * card's right is the first. None for a card that doesn't grow (Continue watching's stills,
 * Channels, Games) or a neighbour with no textless backdrop.
 */
internal fun neighbourBackdrops(rows: List<HomeRow>, card: RowCard, loops: (HomeRow) -> Boolean): List<String> {
    val row = rows.firstOrNull { it.key == card.row } ?: return emptyList()
    if (row is HomeRow.ContinueWatching) return emptyList()
    val titles = row.titles
    val at = titles.indexOfFirst { it.id.toString() == card.key }
    if (at < 0) return emptyList()
    val around = if (loops(row)) listOf(at - 1, at + 1).map { it.mod(titles.size) } else listOf(at - 1, at + 1).filter { it in titles.indices }
    return around.filter { it != at }.distinct().mapNotNull { titles[it].let { title -> cardArt(title.image, title.about, textlessPosters = true).grown?.backdrop } }
}

/**
 * What a screen of rows (Home, Movies, Shows) keeps while another screen shows: its scroll position
 * and each rail's, the rows read last, which show until they are read again so coming back doesn't
 * blank the screen, the card the info under its rail tells about, the one focused last, and the row
 * showing that info.
 */
@OptIn(ExperimentalFoundationApi::class)
@Stable
class RowsState {
    /**
     * Keeps about a row composed past each edge of the screen, and composes the next one ahead
     * between frames. Without it, rows came and went at the screen's edges while Down was held, as
     * focus moved ahead of the scroll: 150 Down on the Movies tab composed its ~22 rows 69 times
     * (489 cards); with it, 33 times (255 cards), on the emulator (#63).
     */
    val list = LazyListState(LazyLayoutCacheWindow(ahead = ROW_CACHE, behind = ROW_CACHE))
    var rows by mutableStateOf<List<HomeRow>?>(null)
    var focused by mutableStateOf<RowCard?>(null)

    /** The rows read last, while the focused row waits to take its change from them ([rowsUnderFocus]). */
    var held: List<HomeRow>? = null

    /** The row whose info shows under it: the focused card's, once that row has come up to the top. */
    var settled by mutableStateOf<String?>(null)
    private val rails = HashMap<String, Pair<Int, LazyListState>>()

    /** Row [key]'s scroll, as its [loop] lays it out: from its start again when its count changes. */
    fun rail(key: String, loop: RailLoop): LazyListState {
        val kept = rails[key]
        if (kept != null && kept.first == loop.count) return kept.second
        return LazyListState(loop.start).also { rails[key] = loop.count to it }
    }
}

/**
 * A screen of rows, Home's or a Movies or Shows tab's, under its [header] (which holds the tabs and
 * gets the selected tab's focus). The rows come from [read], again whenever the screen shows, a Sync
 * finishes or [reading] (what they are read for) changes, so a Title just played moves to the front
 * of Continue watching. [restoreFocus] is the card to focus on arrival, the one the Viewer opened
 * before coming back, or the card now in its place when it has gone; the first card when null,
 * unless the screen was reached by picking its tab ([focusTab]), which keeps focus. [placeholder],
 * when given, shows in place of the rows; [empty] says why there are none. Until the rows are first
 * read, [PlaceholderRows] hold their place, so the screen is never blank under its tabs.
 * A Title's card opens it ([onOpen]), with the textless backdrop it had grown into, if it had,
 * so the Title page can open on that picture; a Channel's watches it ([onWatch]), and its menu key adds or
 * removes the Favourite; a Game's opens its page ([onOpenGame]), unless it has no Feed; a Replay's
 * (Live's Replays row) plays it ([onPlayReplay]); a country's (Live's Channels row, named in the
 * [appLanguage]) opens its Channels screen ([onOpenCountry]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RowsScreen(
    library: Library,
    contentLanguages: List<String>,
    appLanguage: String,
    state: RowsState,
    restoreFocus: RowCard?,
    focusTab: Boolean,
    reading: Any,
    read: suspend () -> List<HomeRow>,
    empty: String,
    onOpen: (RowCard, TitleId, String?) -> Unit,
    onWatch: (RowCard, Channel) -> Unit = { _, _ -> },
    onOpenGame: (RowCard, GameFeeds) -> Unit = { _, _ -> },
    onOpenCountry: (RowCard, LiveCountry) -> Unit = { _, _ -> },
    onPlayReplay: (RowCard, LeagueReplay) -> Unit = { _, _ -> },
    placeholder: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    header: @Composable (tab: FocusRequester) -> Unit,
) {
    val status by library.syncStatus.collectAsStateWithLifecycle()
    RowsLayout(
        state, restoreFocus, focusTab,
        reading = listOf(status.last, reading),
        read = read,
        empty = empty,
        setFavourite = { channel, favourite -> library.setFavourite(channel.key, favourite) },
        onScreen = { OnScreen(library, it) },
        info = { row, card, modifier -> RailInfo(library, row, card, contentLanguages, appLanguage, modifier) },
        appLanguage = appLanguage,
        onOpen = onOpen,
        onWatch = onWatch,
        onOpenGame = onOpenGame,
        onOpenCountry = onOpenCountry,
        onPlayReplay = onPlayReplay,
        placeholder = placeholder,
        trailing = trailing,
        header = header,
    )
}

/**
 * [RowsScreen]'s rows, with what they ask the Library for stood in: [reading] is what they are read
 * for (a Sync landing among it), [setFavourite] saves a Favourite, [onScreen] counts a Title's card
 * as on screen, and [info] draws the info under a rail about its card. Screen tests stand them in.
 *
 * Rows never move under focus: rows read again while the Viewer is on one ([rowsUnderFocus]) update
 * their cards in place and come and go around the focused row, which stays where it is; a change
 * to the focused row itself waits until focus leaves it. Entering the screen shows the rows as read.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun RowsLayout(
    state: RowsState,
    restoreFocus: RowCard?,
    focusTab: Boolean,
    reading: Any,
    read: suspend () -> List<HomeRow>,
    empty: String,
    setFavourite: suspend (Channel, Boolean) -> Unit,
    onScreen: @Composable (TitleId) -> Unit,
    info: @Composable (HomeRow, RowCard?, Modifier) -> Unit,
    appLanguage: String,
    onOpen: (RowCard, TitleId, String?) -> Unit,
    onWatch: (RowCard, Channel) -> Unit = { _, _ -> },
    onOpenGame: (RowCard, GameFeeds) -> Unit = { _, _ -> },
    onOpenCountry: (RowCard, LiveCountry) -> Unit = { _, _ -> },
    onPlayReplay: (RowCard, LeagueReplay) -> Unit = { _, _ -> },
    placeholder: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    header: @Composable (tab: FocusRequester) -> Unit,
) {
    val reader by rememberUpdatedState(read)
    // The card to focus, until it takes focus: asked for once, so a row composed again as the
    // Viewer scrolls back to it doesn't take focus back. The rows kept from last time show first,
    // so the card is checked again once they are read: the Title opened may have left Continue
    // watching meanwhile.
    var pending by remember(restoreFocus) { mutableStateOf(restoreFocus) }
    var focused by remember(restoreFocus) { mutableStateOf(false) }
    var checked by remember(restoreFocus) { mutableStateOf(false) }
    val tab = remember { FocusRequester() }
    FocusTabOnArrival(focusTab, tab)
    // The row focus is in, while it is in the rows (not up on the tabs): the one row a reading mustn't change.
    var inRows by remember { mutableStateOf(false) }
    val focusRow by remember(state) { derivedStateOf { if (inRows) state.focused?.row else null } }
    LaunchedEffect(reading) {
        val before = state.rows
        val fresh = reader()
        state.held = null
        if (!checked && restoreFocus != null && before != null) {
            state.rows = fresh
            val restored = restoredCard(restoreFocus, before.cards, fresh.cards)
            if (restored != restoreFocus) pending = restored
        } else if (checked && before != null) {
            // Rows read again while the Viewer is on a card (Live's Games every couple of minutes, a
            // Sync landing): the focused row stays as it is until focus leaves it.
            val shown = rowsUnderFocus(before, fresh, focusRow)
            state.rows = shown
            if (shown != fresh) state.held = fresh
        } else {
            state.rows = fresh
        }
        checked = true
    }
    // Once focus leaves the row a reading held back, the row takes it.
    LaunchedEffect(focusRow) {
        val fresh = state.held ?: return@LaunchedEffect
        val shown = rowsUnderFocus(state.rows.orEmpty(), fresh, focusRow)
        state.rows = shown
        if (shown == fresh) state.held = null
    }
    val rows = state.rows
    val focusOn = pending ?: rows?.firstOrNull()?.takeIf { !focused && !focusTab }?.let { RowCard(it.key, it.keys.first()) }
    val onFocused = {
        pending = null
        focused = true
    }
    val scope = rememberCoroutineScope()
    val onFavourite = { row: HomeRow, channel: Channel, favourite: Boolean ->
        scope.launch {
            setFavourite(channel, favourite)
            val before = state.rows.orEmpty()
            state.rows = reader()
            state.held = null
            if (!favourite) {
                // Focus goes to its neighbour, or to the row that takes its place; with no row left
                // (the last Favourite on Live), to the tab, as Back would, so focus is never nowhere.
                val next = restoredCard(RowCard(row.key, channelKey(channel)), before.cards, state.rows.orEmpty().cards)
                if (next == null) tab.requestFocus() else pending = next
            }
        }
        Unit
    }
    // Like Netflix's: the focused card sits at the left margin and its row's heading at the top of
    // the rows, so focus stays in one place and the rows move under it. A Top row's card starts
    // with its numeral, so the numeral sits there and the poster right of it.
    // How many cards of each width the screen shows at once, on the 960 dp canvas.
    val seen = remember {
        fun seen(width: Dp) = RailLoop.seen(width.value, Cards.gap.value, CANVAS_WIDTH.value, Spacing.screenX.value)
        Seen(posters = seen(Cards.posterWidth), stills = seen(Cards.continueWidth), channels = seen(Cards.wideWidth))
    }
    // Only the focused row's place is read here, so a press along a row doesn't compose the screen
    // again (#63).
    val focusedRow by remember(state) { derivedStateOf { state.rows?.indexOfFirst { it.key == state.focused?.row } ?: -1 } }
    // Once focus rests on a card, the backdrops its neighbours grow into are loaded into memory, so
    // the next Left or Right grows straight into its art. Nothing while presses come faster than
    // the settle: each press starts the wait again. Read in a flow, so a press composes nothing.
    val context = LocalContext.current
    LaunchedEffect(state) {
        snapshotFlow { state.focused }.collectLatest { card ->
            if (card == null) return@collectLatest
            delay(SETTLE_MILLIS)
            val rows = state.rows ?: return@collectLatest
            val backdrops = neighbourBackdrops(rows, card) { row -> RailLoop(row.keys.size, seen.posters, row.ranked).loops }
            backdrops.forEach { preloadGrownBackdrop(context, it) }
        }
    }
    LaunchedEffect(focusedRow) {
        if (focusedRow < 0) return@LaunchedEffect
        // Every row keeps the info's slot, so the info moves under the focused row as it slides up,
        // and nothing below it makes way.
        state.settled = state.focused?.row
        state.list.animateScrollToItem(focusedRow)
    }
    // The tabs stay on screen above the rows, which pass under them. The margins are content
    // padding, so a focused card grows into them instead of being clipped.
    Column(Modifier.fillMaxSize().background(Colors.ground)) {
        header(tab)
        if (placeholder != null) return@Column placeholder()
        if (rows == null) return@Column PlaceholderRows()
        CompositionLocalProvider(LocalBringIntoViewSpec provides StayPut) {
            LazyColumn(
                Modifier.fillMaxSize().onFocusChanged { inRows = it.hasFocus }.backToTab(tab),
                state = state.list,
            ) {
                if (rows?.isEmpty() == true && trailing == null) {
                    item(key = "empty") {
                        Text(empty, style = Type.body, color = Colors.inkMuted, modifier = Modifier.padding(horizontal = Spacing.screenX))
                    }
                }
                rows.orEmpty().forEach { row ->
                    item(key = "row-${row.key}", contentType = "row") {
                        row.Heading(Modifier.padding(start = Spacing.screenX, end = Spacing.screenX, bottom = 12.dp))
                        val wide = row is HomeRow.ContinueWatching
                        // Channels, countries, Games and Replays are wide cards alike.
                        val channels = row is HomeRow.Favourites || row is HomeRow.Channels || row is HomeRow.MoreLiveEvents || row.games != null || row is HomeRow.Replays
                        val ranked = row.ranked
                        // Kept with the row: new lists would compose each of its cards again.
                        val keys = remember(row) { row.keys }
                        val titles = remember(row) { row.titles }
                        val loop = remember(keys.size, wide, channels, ranked) {
                            RailLoop(keys.size, if (wide) seen.stills else if (channels) seen.channels else seen.posters, ranked)
                        }
                        val cardWidth = when {
                            wide -> Cards.continueWidth
                            channels -> Cards.wideWidth
                            ranked -> Cards.rankedWidth
                            else -> Cards.posterWidth
                        }
                        val rail = state.rail(row.key, loop)
                        // The card a row entered for the first time starts at.
                        val entry = remember { FocusRequester() }
                        // The place to focus: the card's copy nearest where the rail is, so it doesn't jump.
                        val target = if (focusOn?.row == row.key) keys.indexOf(focusOn.key).takeIf { it >= 0 }?.let { loop.place(it, rail.firstVisibleItemIndex) } else null
                        // The rail keeps the focused card at the left margin by gliding there ([glideTo]),
                        // not by bringing it into view, which a card growing as it scales would throw off.
                        LazyRow(
                            state = rail,
                            // Room after a rail that doesn't loop, so its last card too scrolls to the margin, under the info.
                            contentPadding = PaddingValues(
                                start = Spacing.screenX,
                                end = if (loop.loops) Spacing.screenX else CANVAS_WIDTH - Spacing.screenX - cardWidth,
                                top = 10.dp,
                                bottom = 10.dp,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(Cards.gap),
                            modifier = Modifier.rowFocus(entry),
                        ) {
                            // Each card by its Title's (Channel's, Game's…) key and, on a looping rail, which copy
                            // of it, not by its place: a rail read again with other cards never shows a card
                            // composed for another, its art and all, in that place.
                            items(loop.count, key = { if (loop.loops) "${keys[loop.card(it)]}#${it / keys.size}" else keys[it] }, contentType = { row.contentType }) { place ->
                                val index = loop.card(place)
                                val focus = remember { FocusRequester() }
                                val card = RowCard(row.key, keys[index])
                                val onFocus = {
                                    state.focused = card
                                    scope.launch { rail.glideTo(place) }
                                    Unit
                                }
                                // A rail that doesn't loop ends at its first and last cards: Left and Right there go nowhere, not up to the tabs.
                                val ends = Modifier.focusRequester(focus).entryAt(rail, place, entry).focusProperties {
                                    if (!loop.loops && index == 0) left = FocusRequester.Cancel
                                    if (!loop.loops && index == keys.lastIndex) right = FocusRequester.Cancel
                                }
                                if (card == focusOn && place == target) LaunchedEffect(Unit) {
                                    focus.requestFocus()
                                    onFocused()
                                }
                                if (row is HomeRow.Favourites) {
                                    ChannelCard(
                                        row.channels[index],
                                        onWatch = { onWatch(card, it) },
                                        onFavourite = { it, favourite -> onFavourite(row, it, favourite) },
                                        modifier = ends,
                                        named = false,
                                        onFocus = onFocus,
                                    )
                                    return@items
                                }
                                if (row is HomeRow.MoreLiveEvents) {
                                    // An Event channel is a Channel: OK watches it at once, the menu key favourites it.
                                    ChannelCard(
                                        row.events[index].channel,
                                        onWatch = { onWatch(card, it) },
                                        onFavourite = { it, favourite -> onFavourite(row, it, favourite) },
                                        modifier = ends,
                                        named = false,
                                        onFocus = onFocus,
                                    )
                                    return@items
                                }
                                if (row is HomeRow.Channels) {
                                    val country = row.countries[index]
                                    CountryCard(country.name(appLanguage), onClick = { onOpenCountry(card, country) }, modifier = ends, onFocus = onFocus)
                                    return@items
                                }
                                val games = row.games
                                if (games != null) {
                                    GameCard(games[index], onOpen = { onOpenGame(card, it) }, modifier = ends, onFocus = onFocus, league = row.mixesLeagues)
                                    return@items
                                }
                                if (row is HomeRow.Replays) {
                                    ReplayGameCard(row.replays[index], onPlay = { onPlayReplay(card, it) }, modifier = ends, onFocus = onFocus)
                                    return@items
                                }
                                val title = titles[index]
                                val resume = (row as? HomeRow.ContinueWatching)?.titles?.get(index)
                                // The info under the rail shows the badges, so the card only counts as on screen.
                                onScreen(title.id)
                                val logo = title.about?.logo
                                // A poster has its name printed on it, so the logo goes only over a textless one;
                                // focused, it grows into its textless backdrop (#216).
                                val art = remember(title) { cardArt(title.image, title.about, textlessPosters = true) }
                                val grown = art.grown
                                val poster = @Composable {
                                    TitleCard(
                                        image = rememberArtwork(if (wide) resume?.still ?: title.image else art.poster, card = true),
                                        name = title.name,
                                        badges = emptyList(),
                                        onClick = { onOpen(card, title.id, grown?.backdrop.takeIf { !wide }) },
                                        modifier = ends,
                                        wide = wide,
                                        progress = resume?.progress,
                                        named = false,
                                        onFocus = onFocus,
                                        art = if (wide || art.posterLogo != null) {
                                            { CardLogo(rememberArtwork(logo, card = true), logo != null, title.name, wide) }
                                        } else null,
                                        grown = if (!wide && grown != null) {
                                            { GrownArtwork(grown, title.name) }
                                        } else null,
                                    )
                                }
                                // A Chart's Titles are ranked 1…n in order.
                                if (ranked) RankedCard(index + 1) { poster() } else poster()
                            }
                        }
                        SettledInfo(row, state, info)
                        Spacer(Modifier.height(14.dp))
                    }
                }
                // Room under the last row, so it too comes up to the top of the rows. Only with the
                // rows: alone, it would be the item the list keeps in place as they arrive above it.
                if (trailing != null) item(key = "trailing", contentType = "trailing") { trailing() }
                if (!rows.isNullOrEmpty()) item(key = "end") { Spacer(Modifier.fillParentMaxHeight()) }
            }
        }
    }
}

/**
 * The info under [row]: about the focused card while the row is the one at the top
 * ([RowsState.settled]), else an empty slot of the same height, so the rows under it never move
 * down when the info arrives. The focused and settled cards are read here, not in the row: there
 * they composed the rows on screen and each of their cards again at every press (#63).
 */
@Composable
private fun SettledInfo(row: HomeRow, state: RowsState, info: @Composable (HomeRow, RowCard?, Modifier) -> Unit) {
    val card = if (state.settled == row.key) state.focused else null
    info(row, card, Modifier.padding(start = Spacing.screenX, top = 12.dp))
}

/** The info under [row], telling about [card], the card focused last, when it is one of the row's. */
@Composable
private fun RailInfo(library: Library, row: HomeRow, card: RowCard?, contentLanguages: List<String>, appLanguage: String, modifier: Modifier) {
    val short = row is HomeRow.ContinueWatching || row is HomeRow.Favourites || row is HomeRow.Channels || row is HomeRow.MoreLiveEvents
    val channel = (row as? HomeRow.Favourites)?.channels?.firstOrNull { channelKey(it) == card?.key }
    if (channel != null) {
        // What the Favourite is showing, from the guide cache (#261), read again as guide calls land.
        val context = LocalContext.current
        val guideChanges by library.guideChanges.collectAsStateWithLifecycle()
        val programme = remember(channel, guideChanges) { library.guide(channel)?.let { context.nowNextLabel(it) } }
        RailInfo(CardInfo.Channel(channel.name, channel.caption(programme)), emptyList(), short, modifier)
        return
    }
    // An Event channel: its name (its card is only a logo, when it has one) and when its event starts, in the device's clock.
    val event = (row as? HomeRow.MoreLiveEvents)?.events?.firstOrNull { channelKey(it.channel) == card?.key }
    if (event != null) {
        val context = LocalContext.current
        RailInfo(CardInfo.Channel(event.channel.name, remember(event) { context.startCaption(event.start) }), emptyList(), short, modifier)
        return
    }
    val games = row.games
    if (games != null) {
        val game = games.firstOrNull { gameKey(it) == card?.key }
        val context = LocalContext.current
        RailInfo(game?.let { remember(it) { context.gameInfo(it) } }, emptyList(), short = true, modifier)
        return
    }
    // A Replay: its League and teams, as a Game's, and the date in its name.
    if (row is HomeRow.Replays) {
        val replay = row.replays.firstOrNull { replayKey(it) == card?.key }
        val context = LocalContext.current
        RailInfo(replay?.let { remember(it) { context.replayInfo(it) } }, emptyList(), short = true, modifier)
        return
    }
    // A country: its name, since its card has nothing but it, and how many Channels it has.
    val country = (row as? HomeRow.Channels)?.countries?.firstOrNull { countryKey(it) == card?.key }
    if (country != null) {
        RailInfo(CardInfo.Channel(country.name(appLanguage), pluralStringResource(R.plurals.channels_count, country.channels, country.channels)), emptyList(), short, modifier)
        return
    }
    val titles = row.titles
    val index = titles.indexOfFirst { it.id.toString() == card?.key }
    if (index < 0) {
        RailInfo(null, emptyList(), short, modifier)
        return
    }
    val title = titles[index]
    val resume = (row as? HomeRow.ContinueWatching)?.titles?.get(index)
    val context = LocalContext.current
    val info = remember(title, resume) { context.cardInfo(title, resume) }
    // Continue watching tells where the Viewer is, as Netflix's does, without badges.
    RailInfo(info, if (resume == null) liveBest(library, title, contentLanguages).cardBadges() else emptyList(), short, modifier)
}

/**
 * What the info under a rail tells about [title]: where the Viewer is when they're in it ([resume]),
 * else its year, runtime and genres and its description.
 */
private fun Context.cardInfo(title: TitleSummary, resume: InProgress?): CardInfo {
    if (resume != null) {
        val left = resume.left?.let { left ->
            // Rounded up, so the last seconds still read "1 min left".
            val minutes = ((left.toMillis() + 59_999) / 60_000).toInt().coerceAtLeast(1)
            getString(R.string.time_left, runtime(minutes))
        }
        val episode = resume.episode ?: return CardInfo.InProgress(title.name, left)
        val number = episode.number
        val heading = episode.name?.let { getString(R.string.details_episode_named, number.season, number.number, it) }
            ?: getString(R.string.details_episode, number.season, number.number)
        return CardInfo.InProgress(heading, if (episode.started) left else getString(R.string.start_next_episode))
    }
    val about = title.about
    val metadata = listOfNotNull(title.year?.toString(), about?.runtimeMinutes?.let(::runtime), about?.genres?.take(2)?.joinToString(", ")?.ifEmpty { null })
    return CardInfo.About(metadata.joinToString(" · "), about?.description)
}

private val HomeRow.titles: List<TitleSummary>
    get() = when (this) {
        is HomeRow.ContinueWatching -> titles.map { it.title }
        is HomeRow.Favourites, is HomeRow.Channels, is HomeRow.LiveNow, is HomeRow.Today, is HomeRow.LeagueGames, is HomeRow.MoreLiveEvents, is HomeRow.Replays -> emptyList()
        is HomeRow.Chart -> titles
        is HomeRow.NewOnYourService -> titles
        is HomeRow.Genre -> titles
    }

/** A Game row's Games, or null for a row of anything else. */
private val HomeRow.games: List<GameFeeds>?
    get() = when (this) {
        is HomeRow.LiveNow -> games
        is HomeRow.Today -> games
        is HomeRow.LeagueGames -> games
        else -> null
    }

/** Whether a Game row may hold several Leagues' Games (Live now, Today), whose cards then carry their League's logo; a League's own row names it in its heading. */
internal val HomeRow.mixesLeagues: Boolean
    get() = this is HomeRow.LiveNow || this is HomeRow.Today

/** Whether a row is a Top 10, drawn with its ranks and never looping. */
private val HomeRow.ranked: Boolean
    get() = this is HomeRow.Chart && (list == ChartList.TopMovies || list == ChartList.TopShows)

/** A row's key, as [RowCard]s hold it: the same row keeps it when rows before it come or go. */
private val HomeRow.key: String
    get() = when (this) {
        is HomeRow.ContinueWatching -> "continue"
        is HomeRow.Favourites -> "favourites"
        is HomeRow.Channels -> "channels"
        is HomeRow.Chart -> "chart/$list"
        is HomeRow.NewOnYourService -> "new"
        is HomeRow.Genre -> "genre/$name"
        is HomeRow.LiveNow -> "live-now"
        is HomeRow.Today -> "today"
        is HomeRow.LeagueGames -> "league/${league.key}"
        is HomeRow.MoreLiveEvents -> "more-live-events"
        is HomeRow.Replays -> "replays"
    }

/** Each row's key to its cards' keys, in screen order, for [restoredCard]. */
private val List<HomeRow>.cards: Map<String, List<String>>
    get() = associate { it.key to it.keys }

/** The keys of a row's cards, as [RowCard]s hold them. */
private val HomeRow.keys: List<String>
    get() = when {
        this is HomeRow.Favourites -> channels.map(::channelKey)
        this is HomeRow.Channels -> countries.map(::countryKey)
        this is HomeRow.MoreLiveEvents -> events.map { channelKey(it.channel) }
        this is HomeRow.Replays -> replays.map(::replayKey)
        games != null -> games!!.map(::gameKey)
        else -> titles.map { it.id.toString() }
    }

/** What a row's cards are, so a `LazyRow` reuses a card's composition only for one of the same kind. */
private val HomeRow.contentType: String
    get() = when (this) {
        is HomeRow.Favourites, is HomeRow.MoreLiveEvents -> "channel"
        is HomeRow.Channels -> "country"
        is HomeRow.LiveNow, is HomeRow.Today, is HomeRow.LeagueGames, is HomeRow.Replays -> "game"
        else -> "title"
    }

private fun channelKey(channel: Channel) = "channel/${channel.key}"

private fun countryKey(country: LiveCountry) = "country/${country.code}"

private fun gameKey(game: GameFeeds) = "game/${game.key}"

private fun replayKey(replay: LeagueReplay) = "replay/${replay.replay.listingId}"

/**
 * Scrolls a rail so the card at [place] starts at its left margin, the keyline, as Netflix's rows do
 * (#167). The distance left is read again at every frame and covered in proportion to the time
 * left, so the card lands exactly there though the card focused before shrinks and this one grows
 * meanwhile (#216). A card not laid out is jumped to.
 */
private suspend fun LazyListState.glideTo(place: Int) {
    fun distance() = layoutInfo.visibleItemsInfo.firstOrNull { it.index == place }?.offset?.toFloat()
    if (distance() == null) return scrollToItem(place)
    scroll {
        var done = 0f
        animate(0f, 1f, animationSpec = GLIDE) { progress, _ ->
            val left = distance() ?: return@animate
            scrollBy(if (progress >= 1f || done >= 1f) left else left * (progress - done) / (1f - done))
            done = progress
        }
    }
}

/** As bringing a card into view moved a rail before (#167): quick, without overshooting. */
private val GLIDE = spring<Float>(visibilityThreshold = 0.001f)

private val CANVAS_WIDTH = 960.dp

/** About a row of posters with its heading: what [RowsState.list] keeps composed past each edge. */
private val ROW_CACHE = 300.dp

/** How many cards each kind of rail shows at once ([RailLoop.seen]). */
private class Seen(val posters: Int, val stills: Int, val channels: Int)

/** A row's heading: its name, and on a League's row the League's logo before it ([RowHeading]), the name alone while the logo can't be loaded. */
@Composable
internal fun HomeRow.Heading(modifier: Modifier = Modifier) {
    RowHeading(heading(), modifier, logo = (this as? HomeRow.LeagueGames)?.let { rememberArtwork(it.league.logo) })
}

@Composable
private fun HomeRow.heading(): String = when (this) {
    is HomeRow.ContinueWatching -> stringResource(R.string.row_continue_watching)
    is HomeRow.Favourites -> stringResource(R.string.row_favourites)
    is HomeRow.Channels -> stringResource(R.string.row_channels)
    is HomeRow.Chart -> when (list) {
        ChartList.Trending -> stringResource(R.string.row_trending)
        ChartList.TrendingMovies -> stringResource(R.string.row_trending_movies)
        ChartList.TrendingShows -> stringResource(R.string.row_trending_shows)
        ChartList.TopMovies, ChartList.TopShows -> when (val country = region?.let { countryName(it, LocalConfiguration.current.locales[0].language) }) {
            null -> stringResource(if (list == ChartList.TopMovies) R.string.row_top_movies_worldwide else R.string.row_top_shows_worldwide)
            else -> stringResource(if (list == ChartList.TopMovies) R.string.row_top_movies else R.string.row_top_shows, country)
        }
    }
    is HomeRow.NewOnYourService -> stringResource(
        when (kind) {
            null -> R.string.row_new
            TitleKind.Movie -> R.string.row_new_movies
            TitleKind.Show -> R.string.row_new_shows
        },
    )
    is HomeRow.Genre -> name
    is HomeRow.LiveNow -> stringResource(R.string.row_live_now)
    is HomeRow.Today -> stringResource(R.string.row_today)
    is HomeRow.LeagueGames -> leagueName(league)
    is HomeRow.MoreLiveEvents -> stringResource(R.string.row_more_live_events)
    is HomeRow.Replays -> stringResource(R.string.row_replays)
}

/**
 * Where the rows go while they are first read: two rows of glass posters in their places, without
 * headings, focus or motion, so the screen under the tabs is never blank (#213). With a [caption],
 * they hold the places of rows still to come (Home's Title rows while a first Sync reads them), and
 * the caption says so above them.
 */
@Composable
internal fun PlaceholderRows(caption: String? = null) {
    Column(Modifier.fillMaxSize()) {
        if (caption != null) {
            Text(caption, style = Type.caption, color = Colors.inkMuted, modifier = Modifier.padding(start = Spacing.screenX, bottom = 12.dp))
        }
        repeat(PLACEHOLDER_ROWS) {
            Box(Modifier.padding(start = Spacing.screenX).size(PLACEHOLDER_HEADING_WIDTH, PLACEHOLDER_HEADING_HEIGHT).background(Colors.surface, RoundedCornerShape(Corners.badge)))
            Row(
                Modifier.wrapContentWidth(Alignment.Start, unbounded = true).padding(start = Spacing.screenX, top = 12.dp + 10.dp, bottom = 10.dp + 14.dp),
                horizontalArrangement = Arrangement.spacedBy(Cards.gap),
            ) {
                repeat(PLACEHOLDER_POSTERS) {
                    Box(Modifier.size(Cards.posterWidth, Cards.posterWidth * 3 / 2).background(Colors.surface, RoundedCornerShape(Corners.card)))
                }
            }
        }
    }
}

private const val PLACEHOLDER_ROWS = 2
/** As many posters as the screen shows, the last peeking at the right edge. */
private const val PLACEHOLDER_POSTERS = 6
private val PLACEHOLDER_HEADING_WIDTH = 160.dp
private val PLACEHOLDER_HEADING_HEIGHT = 16.dp

