package dev.betteriptv.app

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import dev.betteriptv.app.ui.components.CardInfo
import dev.betteriptv.app.ui.components.CardLogo
import dev.betteriptv.app.ui.components.RailInfo
import dev.betteriptv.app.ui.components.RankedCard
import dev.betteriptv.app.ui.components.TitleCard
import dev.betteriptv.app.ui.theme.Cards
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Keyline
import dev.betteriptv.app.ui.theme.StayPut
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import androidx.compose.runtime.rememberCoroutineScope
import dev.betteriptv.core.Channel
import dev.betteriptv.core.ChartList
import dev.betteriptv.core.HomeRow
import dev.betteriptv.core.InProgress
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
 * What a screen of rows (Home, Movies, Shows) keeps while another screen shows: its scroll position
 * and each rail's, the rows read last, which show until they are read again so coming back doesn't
 * blank the screen, the card the info under its rail tells about, the one focused last, and the row
 * showing that info.
 */
@Stable
class RowsState {
    val list = LazyListState()
    var rows by mutableStateOf<List<HomeRow>?>(null)
    var focused by mutableStateOf<RowCard?>(null)

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
 * when given, shows in place of the rows; [empty] says why there are none.
 * A Title's card opens it ([onOpen]); a Channel's watches it ([onWatch]), and its menu key adds or
 * removes the Favourite.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RowsScreen(
    library: Library,
    contentLanguages: List<String>,
    state: RowsState,
    restoreFocus: RowCard?,
    focusTab: Boolean,
    reading: Any,
    read: suspend () -> List<HomeRow>,
    empty: String,
    onOpen: (RowCard, TitleId) -> Unit,
    onWatch: (RowCard, Channel) -> Unit = { _, _ -> },
    placeholder: (@Composable () -> Unit)? = null,
    header: @Composable (tab: FocusRequester) -> Unit,
) {
    val status by library.syncStatus.collectAsStateWithLifecycle()
    val synced = status.last
    val reader by rememberUpdatedState(read)
    // The card to focus, until it takes focus: asked for once, so a row composed again as the
    // Viewer scrolls back to it doesn't take focus back. The rows kept from last time show first,
    // so the card is checked again once they are read: the Title opened may have left Continue
    // watching meanwhile.
    var pending by remember(restoreFocus) { mutableStateOf(restoreFocus) }
    var focused by remember(restoreFocus) { mutableStateOf(false) }
    var checked by remember(restoreFocus) { mutableStateOf(false) }
    LaunchedEffect(synced, reading) {
        val before = state.rows
        state.rows = reader()
        if (!checked && restoreFocus != null && before != null) {
            val restored = restoredCard(restoreFocus, before.cards, state.rows.orEmpty().cards)
            if (restored != restoreFocus) pending = restored
        }
        checked = true
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
            library.setFavourite(channel.id, favourite)
            val before = state.rows.orEmpty()
            state.rows = reader()
            // Focus goes to its neighbour, or to the row that takes its place.
            if (!favourite) pending = restoredCard(RowCard(row.key, channelKey(channel)), before.cards, state.rows.orEmpty().cards)
        }
        Unit
    }

    val tab = remember { FocusRequester() }
    FocusTabOnArrival(focusTab, tab)
    // Like Netflix's: the focused card sits at the left margin and its row's heading at the top of
    // the rows, so focus stays in one place and the rows move under it.
    val density = LocalDensity.current
    val posterKeyline = remember(density) { with(density) { Keyline((Spacing.screenX + Cards.posterWidth / 2).toPx()) } }
    val stillKeyline = remember(density) { with(density) { Keyline((Spacing.screenX + Cards.continueWidth / 2).toPx()) } }
    val channelKeyline = remember(density) { with(density) { Keyline((Spacing.screenX + Cards.wideWidth / 2).toPx()) } }
    // A Top row's card brings its poster into view, which sits right of the numeral.
    val rankedKeyline = remember(density) { with(density) { Keyline((Spacing.screenX + Cards.numeralWidth + Cards.posterWidth / 2).toPx()) } }
    // How many cards of each width the screen shows at once, on the 960 dp canvas.
    val seen = remember {
        fun seen(width: Dp) = RailLoop.seen(width.value, Cards.gap.value, CANVAS_WIDTH.value, Spacing.screenX.value)
        Seen(posters = seen(Cards.posterWidth), stills = seen(Cards.continueWidth), channels = seen(Cards.wideWidth))
    }
    val focusedRow = rows?.indexOfFirst { it.key == state.focused?.row } ?: -1
    LaunchedEffect(focusedRow) {
        if (focusedRow < 0) return@LaunchedEffect
        state.list.animateScrollToItem(focusedRow)
        // The info moves under the row once it is at the top: the rows below it make way then, not
        // while they slide.
        state.settled = state.focused?.row
    }
    // The tabs stay on screen above the rows, which pass under them. The margins are content
    // padding, so a focused card grows into them instead of being clipped.
    Column(Modifier.fillMaxSize().background(Colors.ground)) {
        header(tab)
        if (placeholder != null) return@Column placeholder()
        CompositionLocalProvider(LocalBringIntoViewSpec provides StayPut) {
            LazyColumn(
                Modifier.fillMaxSize().backToTab(tab),
                state = state.list,
            ) {
                if (rows?.isEmpty() == true) {
                    item(key = "empty") {
                        Text(empty, style = Type.body, color = Colors.inkMuted, modifier = Modifier.padding(horizontal = Spacing.screenX))
                    }
                }
                rows.orEmpty().forEach { row ->
                    item(key = "row-${row.key}", contentType = "row") {
                        Text(
                            row.heading(),
                            style = Type.headline,
                            color = Colors.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = Spacing.screenX, end = Spacing.screenX, bottom = 12.dp),
                        )
                        val wide = row is HomeRow.ContinueWatching
                        val channels = row is HomeRow.Favourites
                        val ranked = row.ranked
                        val keys = row.keys
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
                        // The place to focus: the card's copy nearest where the rail is, so it doesn't jump.
                        val target = if (focusOn?.row == row.key) keys.indexOf(focusOn.key).takeIf { it >= 0 }?.let { loop.place(it, rail.firstVisibleItemIndex) } else null
                        CompositionLocalProvider(LocalBringIntoViewSpec provides when {
                            wide -> stillKeyline
                            channels -> channelKeyline
                            ranked -> rankedKeyline
                            else -> posterKeyline
                        }) {
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
                                modifier = Modifier.focusRestorer(),
                            ) {
                                items(loop.count, key = { if (loop.loops) it else keys[it] }, contentType = { if (row is HomeRow.Favourites) "channel" else "title" }) { place ->
                                    val index = loop.card(place)
                                    val focus = remember { FocusRequester() }
                                    val card = RowCard(row.key, keys[index])
                                    // A rail that doesn't loop ends at its first and last cards: Left and Right there go nowhere, not up to the tabs.
                                    val ends = Modifier.focusRequester(focus).focusProperties {
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
                                            onFocus = { state.focused = card },
                                        )
                                        return@items
                                    }
                                    val title = row.titles[index]
                                    val resume = (row as? HomeRow.ContinueWatching)?.titles?.get(index)
                                    // The info under the rail shows the badges, so the card only counts as on screen.
                                    OnScreen(library, title.id)
                                    val logo = title.about?.logo
                                    // A poster has its name printed on it, so the logo goes only over a textless one.
                                    val textless = title.about?.textlessPoster
                                    val poster = @Composable {
                                        TitleCard(
                                            image = rememberArtwork(if (wide) resume?.still ?: title.image else textless ?: title.image, card = true),
                                            name = title.name,
                                            badges = emptyList(),
                                            onClick = { onOpen(card, title.id) },
                                            modifier = ends,
                                            wide = wide,
                                            progress = resume?.progress,
                                            named = false,
                                            onFocus = { state.focused = card },
                                            art = if (wide || textless != null) {
                                                { CardLogo(rememberArtwork(logo, card = true), logo != null, title.name, wide) }
                                            } else null,
                                        )
                                    }
                                    // A Chart's Titles are ranked 1…n in order.
                                    if (ranked) RankedCard(index + 1) { poster() } else poster()
                                }
                            }
                        }
                        if (state.settled == row.key) {
                            RailInfo(library, row, state.focused, contentLanguages, Modifier.padding(start = Spacing.screenX, top = 12.dp))
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                }
                // Room under the last row, so it too comes up to the top of the rows. Only with the
                // rows: alone, it would be the item the list keeps in place as they arrive above it.
                if (!rows.isNullOrEmpty()) item(key = "end") { Spacer(Modifier.fillParentMaxHeight()) }
            }
        }
    }
}

/** The info under [row], telling about [card], the card focused last, when it is one of the row's. */
@Composable
private fun RailInfo(library: Library, row: HomeRow, card: RowCard?, contentLanguages: List<String>, modifier: Modifier) {
    val short = row is HomeRow.ContinueWatching || row is HomeRow.Favourites
    val channel = (row as? HomeRow.Favourites)?.channels?.firstOrNull { channelKey(it) == card?.key }
    if (channel != null) {
        RailInfo(CardInfo.Channel(channel.name, channel.menuHint()), emptyList(), short, modifier)
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
        is HomeRow.Favourites -> emptyList()
        is HomeRow.Chart -> titles
        is HomeRow.NewOnYourService -> titles
        is HomeRow.Genre -> titles
    }

/** Whether a row is a Top 10, drawn with its ranks and never looping. */
private val HomeRow.ranked: Boolean
    get() = this is HomeRow.Chart && (list == ChartList.TopMovies || list == ChartList.TopShows)

/** A row's key, as [RowCard]s hold it: the same row keeps it when rows before it come or go. */
private val HomeRow.key: String
    get() = when (this) {
        is HomeRow.ContinueWatching -> "continue"
        is HomeRow.Favourites -> "favourites"
        is HomeRow.Chart -> "chart/$list"
        is HomeRow.NewOnYourService -> "new"
        is HomeRow.Genre -> "genre/$name"
    }

/** Each row's key to its cards' keys, in screen order, for [restoredCard]. */
private val List<HomeRow>.cards: Map<String, List<String>>
    get() = associate { it.key to it.keys }

/** The keys of a row's cards, as [RowCard]s hold them. */
private val HomeRow.keys: List<String>
    get() = if (this is HomeRow.Favourites) channels.map(::channelKey) else titles.map { it.id.toString() }

private fun channelKey(channel: Channel) = "channel/${channel.id}"

private val CANVAS_WIDTH = 960.dp

/** How many cards each kind of rail shows at once ([RailLoop.seen]). */
private class Seen(val posters: Int, val stills: Int, val channels: Int)

@Composable
private fun HomeRow.heading(): String = when (this) {
    is HomeRow.ContinueWatching -> stringResource(R.string.row_continue_watching)
    is HomeRow.Favourites -> stringResource(R.string.row_favourites)
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
}
