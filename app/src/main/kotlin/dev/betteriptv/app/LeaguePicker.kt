package dev.betteriptv.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import dev.betteriptv.app.ui.components.FocusSurface
import dev.betteriptv.app.ui.components.FocusWeight
import dev.betteriptv.app.ui.theme.Colors
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.Type
import dev.betteriptv.core.League
import dev.betteriptv.core.Sport
import kotlinx.coroutines.launch

/**
 * The Leagues row's value: the Leagues [on], in [League]'s order, by their names in the App
 * language, or None.
 */
@Composable
internal fun leaguesValue(on: Set<League>): String {
    val names = League.entries.filter { it in on }.map { leagueName(it) }
    return if (names.isEmpty()) stringResource(R.string.settings_leagues_none) else names.joinToString(", ")
}

/**
 * Settings' League picker (#353): every League as a logo tile, [PER_LINE] across, under its sport's
 * heading, the sports in [Sport]'s order and each sport's Leagues in [League]'s. A sport rail sits at
 * the left, like the Region list's letter rail: Left from a sport's first-column tile moves to its
 * sport, Up and Down move between sports, OK or Right on one jumps to its first tile. OK on a tile
 * turns its League on or off ([onChange]); every one can be off, and the Live tab then shows no
 * Games. The first tile takes first focus.
 */
@Composable
internal fun LeaguePicker(on: Set<League>, onChange: (Set<League>) -> Unit) {
    val groups = remember { Sport.entries.associateWith { sport -> League.entries.filter { it.sport == sport } }.filterValues { it.isNotEmpty() } }
    val tiles = remember { League.entries.associateWith { FocusRequester() } }
    val sports = remember { groups.keys.associateWith { FocusRequester() } }
    // Each group is its heading, then its tiles.
    val firstIndex = remember {
        var index = 0
        groups.mapValues { (_, leagues) -> (index + 1).also { index += 1 + leagues.size } }
    }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    fun jump(sport: Sport) {
        scope.launch {
            // The heading too, so the sport's name comes into view with its tiles.
            grid.scrollToItem(firstIndex.getValue(sport) - 1)
            withFrameNanos {}
            tiles.getValue(groups.getValue(sport).first()).requestFocus()
        }
    }
    LaunchedEffect(Unit) {
        // The grid places its tiles in its first layout, after this composition.
        withFrameNanos {}
        tiles.getValue(League.entries.first()).requestFocus()
    }
    Row(Modifier.padding(start = Spacing.screenX)) {
        Column(
            Modifier.width(IntrinsicSize.Max).padding(vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for ((sport, focus) in sports) {
                SportKey(sportName(sport), onClick = { jump(sport) }, Modifier.fillMaxWidth().focusRequester(focus).testTag("sport:${sport.name}"))
            }
        }
        Spacer(Modifier.width(Spacing.l))
        LazyVerticalGrid(
            GridCells.Fixed(PER_LINE),
            state = grid,
            contentPadding = PaddingValues(end = Spacing.screenX, top = 20.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
            modifier = Modifier.fillMaxSize(),
        ) {
            for ((sport, leagues) in groups) {
                item(key = "sport:${sport.name}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        sportName(sport),
                        style = Type.headline,
                        color = Colors.ink,
                        modifier = Modifier.padding(top = 14.dp, bottom = 10.dp).testTag("heading:${sport.name}"),
                    )
                }
                items(leagues, key = { it.key }) { league ->
                    val column = leagues.indexOf(league) % PER_LINE
                    LeagueTile(
                        league,
                        isOn = league in on,
                        onClick = { onChange(if (league in on) on - league else on + league) },
                        Modifier
                            .focusRequester(tiles.getValue(league))
                            .focusProperties { if (column == 0) left = sports.getValue(sport) },
                    )
                }
            }
        }
    }
}

/**
 * A League's tile: its dark-background logo fitted whole on glass, a check badge at the top right
 * while it is on, the logo dimmed while it is off. `FocusWeight.Card`, so focus lifts it and never
 * fills it white. Its name shows under it only while it is focused; the slot is kept, so nothing
 * moves when it does. A logo that can't load leaves the name on the tile.
 */
@Composable
private fun LeagueTile(league: League, isOn: Boolean, onClick: () -> Unit, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val logo = rememberArtwork(league.logo)
    val name = leagueName(league)
    Column(Modifier.onFocusChanged { focused = it.hasFocus }.padding(bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FocusSurface(
            onClick,
            modifier.fillMaxWidth().height(TILE).testTag("league:${league.key}"),
            container = Colors.surface,
        ) {
            Box(Modifier.fillMaxSize().padding(TILE_PADDING).alpha(if (isOn) 1f else OFF_ALPHA), contentAlignment = Alignment.Center) {
                if (logo != null) {
                    Image(logo, contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } else {
                    Text(name, style = Type.label, color = Colors.ink, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            if (isOn) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(CHECK).background(Colors.ink, CircleShape).testTag("league-on"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Colors.onFocus, modifier = Modifier.size(CHECK - 6.dp))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(NAME_SLOT).padding(top = 6.dp), contentAlignment = Alignment.TopCenter) {
            if (focused) {
                Text(
                    name,
                    style = Type.caption,
                    color = Colors.ink,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("league-name"),
                )
            }
        }
    }
}

/** A sport on the picker's rail; Right, like OK, jumps to its Leagues. */
@Composable
private fun SportKey(name: String, onClick: () -> Unit, modifier: Modifier) {
    FocusSurface(
        onClick,
        modifier.height(KEY).onPreviewKeyEvent { event ->
            if (event.key == Key.DirectionRight && event.type == KeyEventType.KeyDown) onClick()
            event.key == Key.DirectionRight
        },
        weight = FocusWeight.Control,
        corner = KEY / 2,
        focusedContainer = Colors.focus,
    ) { focused ->
        Text(
            name,
            style = Type.label,
            color = if (focused) Colors.onFocus else Colors.inkMuted,
            maxLines = 1,
            modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp),
        )
    }
}

/** A sport's name in the App language, as the picker's headings and rail name it. */
@Composable
private fun sportName(sport: Sport): String = stringResource(
    when (sport) {
        Sport.Hockey -> R.string.sport_hockey
        Sport.Football -> R.string.sport_football
        Sport.Basketball -> R.string.sport_basketball
        Sport.Baseball -> R.string.sport_baseball
        Sport.Soccer -> R.string.sport_soccer
        Sport.Motorsport -> R.string.sport_motorsport
        Sport.Golf -> R.string.sport_golf
        Sport.Fighting -> R.string.sport_fighting
        Sport.Rugby -> R.string.sport_rugby
        Sport.AustralianFootball -> R.string.sport_australian_football
    },
)

/** Tiles across: about six beside the rail, as the spec settled (#339). */
private const val PER_LINE = 6
private val TILE = 84.dp
private val TILE_GAP = 14.dp
private val TILE_PADDING = 14.dp
/** Two caption lines, for a long name ("Basketball masculin NCAA"). */
private val NAME_SLOT = 34.dp
private val CHECK = 20.dp
private val KEY = 36.dp
/** An off League's logo. */
private const val OFF_ALPHA = 0.35f
