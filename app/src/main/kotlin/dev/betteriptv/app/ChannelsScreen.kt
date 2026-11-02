package dev.betteriptv.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.FocusSurface
import dev.betteriptv.app.ui.components.FocusWeight
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Corners
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.BrowsedCategory
import dev.betteriptv.core.Channel
import dev.betteriptv.core.Library
import dev.betteriptv.core.LiveCountry
import kotlinx.coroutines.launch

/**
 * What the Channels screen keeps while the player shows over it: the Category the Viewer picked and
 * the Channel they focused last, so Back from the Channel returns to its row. Reset when another
 * country is opened.
 */
@Stable
class ChannelsState {
    var category by mutableStateOf<String?>(null)
    var channel by mutableStateOf<String?>(null)

    fun reset() {
        category = null
        channel = null
    }
}

/**
 * The Channels screen, a [country]'s Channel browser (#259), laid out like Search: its Categories in
 * a left column with their Channel counts (merged and named without the country code and the
 * Provider's tags; no Event or Clutter Categories), and the focused Category's Channels on the right
 * as rows with logo and name. Left and Right move between the column and the list, Up and Down
 * scroll, OK on a Channel watches it ([onWatch]), the menu key adds or removes the Favourite, and
 * Back (handled by the caller) returns to the country's card on Live. What is on now and next
 * beside each Channel comes with #261 ([ChannelRow]'s programme). The Categories are read again
 * after a Sync.
 */
@Composable
fun ChannelsScreen(library: Library, country: LiveCountry, appLanguage: String, state: ChannelsState, onWatch: (Channel) -> Unit) {
    val status by library.syncStatus.collectAsStateWithLifecycle()
    var categories by remember { mutableStateOf<List<BrowsedCategory>?>(null) }
    LaunchedEffect(country, status.last) { categories = library.browsedCategories(country.code) }
    ChannelsLayout(
        countryName(country.code, appLanguage), country.channels, categories, state,
        channelsOf = { library.categoryChannels(it) },
        onWatch = onWatch,
        onFavourite = { channel, favourite -> library.setFavourite(channel.key, favourite) },
    )
}

/**
 * The Channels screen's layout, apart from where its data comes from, so its focus can be tested:
 * the [country]'s name and its [channelCount] over the column of [categories] (null while they are
 * read) and the list of the picked Category's Channels ([channelsOf]). The picked Category is the
 * one focused last ([ChannelsState.category]), or the first; the list opens at its top. Right (or
 * OK) from a Category goes to the Channel focused last in the list, else its first, and Left from
 * any Channel returns to the picked Category, not the row beside it. On arrival, focus goes to
 * the Channel focused last ([ChannelsState.channel]) when the list still has it, else to the picked
 * Category. The menu key on a Channel calls [onFavourite] with the new state and marks the row at once.
 */
@Composable
internal fun ChannelsLayout(
    country: String,
    channelCount: Int,
    categories: List<BrowsedCategory>?,
    state: ChannelsState,
    channelsOf: suspend (BrowsedCategory) -> List<Channel>,
    onWatch: (Channel) -> Unit,
    onFavourite: suspend (Channel, Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val picked = categories?.firstOrNull { it.name == state.category } ?: categories?.firstOrNull()
    var channels by remember { mutableStateOf<List<Channel>?>(null) }
    val list = rememberLazyListState()
    // The Channel focused last in this list, which Right from the column returns to; none once the list is another Category's.
    var lastFocused by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(picked) {
        channels = picked?.let { channelsOf(it) }
        // Another Category's Channels show from their top.
        list.requestScrollToItem(0)
        lastFocused = null
    }
    // One requester per Category row, so Left from a Channel lands on the picked one, and one on the
    // row Right from the column lands on: the Channel focused last, else the first. Both rows are
    // composed: the list only scrolls while focus is in it, or back to its top with a new Category.
    val categoryFocus = remember(categories) { categories.orEmpty().associate { it.name to FocusRequester() } }
    val entry = remember { FocusRequester() }
    val entryKey = lastFocused ?: channels?.firstOrNull()?.key
    // On arrival, focus goes to the Channel focused last when the list still has it, else to the picked Category.
    var arriving by remember { mutableStateOf(true) }
    val column = rememberLazyListState()
    LaunchedEffect(categories, channels) {
        if (!arriving || picked == null || channels == null) return@LaunchedEffect
        val at = channels.orEmpty().indexOfFirst { it.key == state.channel }
        if (at >= 0) {
            list.requestScrollToItem(at)
            lastFocused = state.channel
            withFrameNanos {}
            entry.requestFocus()
        } else {
            column.requestScrollToItem(categories.orEmpty().indexOf(picked))
            withFrameNanos {}
            categoryFocus.getValue(picked.name).requestFocus()
        }
        arriving = false
    }

    Column(Modifier.fillMaxSize().background(Colors.ground).padding(top = Spacing.screenY)) {
        Row(Modifier.padding(horizontal = Spacing.screenX), verticalAlignment = Alignment.CenterVertically) {
            Text(country, style = Type.headline, color = Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(Spacing.m))
            Text(pluralStringResource(R.plurals.channels_count, channelCount, channelCount), style = Type.caption, color = Colors.inkMuted, maxLines = 1)
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxSize()) {
            // The bottom margin is content padding, so the last rows scroll up from under the edge.
            LazyColumn(
                Modifier.width(COLUMN_WIDTH + Spacing.screenX),
                state = column,
                contentPadding = PaddingValues(start = Spacing.screenX, bottom = Spacing.screenY),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                itemsIndexed(categories.orEmpty(), key = { _, category -> category.name }) { _, category ->
                    CategoryRow(
                        category,
                        picked = category == picked,
                        // OK goes on into its Channels, as Right does; nowhere while there are none.
                        onClick = { if (entryKey != null) entry.requestFocus() },
                        modifier = Modifier
                            .focusRequester(categoryFocus.getValue(category.name))
                            .focusProperties { right = if (entryKey == null) FocusRequester.Cancel else entry }
                            .onFocusChanged {
                                if (it.isFocused) {
                                    state.category = category.name
                                    arriving = false
                                }
                            },
                    )
                }
            }
            Spacer(Modifier.width(40.dp))
            LazyColumn(
                Modifier.weight(1f),
                state = list,
                contentPadding = PaddingValues(end = Spacing.screenX, bottom = Spacing.screenY),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                itemsIndexed(channels.orEmpty(), key = { _, channel -> channel.key }) { _, channel ->
                    val toPicked = picked?.let { categoryFocus[it.name] }
                    var modifier = Modifier
                        .focusProperties { if (toPicked != null) left = toPicked }
                        .onFocusChanged {
                            if (it.isFocused) {
                                state.channel = channel.key
                                lastFocused = channel.key
                                arriving = false
                            }
                        }
                    if (channel.key == entryKey) modifier = modifier.focusRequester(entry)
                    ChannelRow(
                        channel,
                        onWatch = { onWatch(channel) },
                        onFavourite = {
                            val favourite = !channel.favourite
                            channels = channels?.map { if (it.key == channel.key) it.copy(favourite = favourite) else it }
                            scope.launch { onFavourite(channel, favourite) }
                        },
                        modifier = modifier,
                    )
                }
            }
        }
    }
}

/**
 * A Category in the Channels screen's column: its name on the left, its Channel count on the right
 * in muted detail. The [picked] one, whose Channels are listed, sits on stronger glass while focus
 * is in the list, as the selected tab does.
 */
@Composable
private fun CategoryRow(category: BrowsedCategory, picked: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FocusSurface(
        onClick,
        modifier.fillMaxWidth(),
        weight = FocusWeight.Row,
        container = if (picked) Colors.surfaceHigh else Colors.surface,
        focusedContainer = Colors.focus,
    ) { focused ->
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(category.name, style = Type.label, color = if (focused) Colors.onFocus else Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Spacing.m))
            Text(category.channels.toString(), style = Type.detail, color = if (focused) Colors.onFocus else Colors.inkMuted, maxLines = 1)
        }
    }
}

/**
 * A Channel in the Channels screen's list: its logo, whole, on a small glass tile, its name in label
 * type and, while focused, what the menu key does in muted caption at the right. [programme], what
 * is on now and when the next programme starts, goes muted on the right once #261 brings it; null
 * leaves the row to the logo and the name, as it must for the Channels the guide doesn't know.
 * Pressing the row watches the Channel ([onWatch]); the menu key calls [onFavourite].
 */
@Composable
private fun ChannelRow(channel: Channel, onWatch: () -> Unit, onFavourite: () -> Unit, modifier: Modifier = Modifier, programme: String? = null) {
    FocusSurface(
        onWatch,
        modifier.fillMaxWidth().onPreviewKeyEvent { event ->
            if (event.key != Key.Menu) return@onPreviewKeyEvent false
            if (event.type == KeyEventType.KeyUp) onFavourite()
            true
        },
        weight = FocusWeight.Row,
        container = Colors.surface,
        focusedContainer = Colors.focus,
    ) { focused ->
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val logo = rememberArtwork(channel.image, card = true)
            Box(Modifier.width(LOGO_WIDTH).aspectRatio(16f / 9f).background(if (focused) Colors.onFocus.copy(alpha = 0.08f) else Colors.surface, RoundedCornerShape(Corners.badge))) {
                // Logos come in every shape: shown whole, never cropped.
                if (logo != null) Image(logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(Spacing.xs))
            }
            Spacer(Modifier.width(Spacing.m))
            Text(channel.name, style = Type.label, color = if (focused) Colors.onFocus else Colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (programme != null) {
                Spacer(Modifier.width(Spacing.m))
                Text(programme, style = Type.detail, color = if (focused) Colors.onFocus else Colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = PROGRAMME_WIDTH))
            }
            if (focused) {
                Spacer(Modifier.width(Spacing.m))
                Text(channel.menuHint(), style = Type.caption, color = Colors.onFocus.copy(alpha = 0.7f), maxLines = 1)
            }
        }
    }
}

/** The column of Categories: Search's keyboard column's width. */
private val COLUMN_WIDTH = 290.dp
private val LOGO_WIDTH = 64.dp
private val PROGRAMME_WIDTH = 260.dp
