package dev.betteriptv.app.prototype

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.betteriptv.app.R
import dev.betteriptv.app.ui.components.BadgeRow
import dev.betteriptv.app.ui.components.NuageButton
import dev.betteriptv.app.ui.components.TitleCard
import dev.betteriptv.app.ui.components.TopTabs
import dev.betteriptv.app.ui.theme.Nuage
import dev.betteriptv.app.ui.theme.Spacing
import dev.betteriptv.app.ui.theme.WideCards

val TABS = listOf("Home", "Movies", "Shows", "Live", "Search", "Settings")

/** The ground of a screen; in Nuage it takes a little of the focused Title's colour. */
@Composable
fun Ground(tint: Color?, content: @Composable BoxScope.(ground: Color) -> Unit) {
    val direction = Nuage.direction
    val ground = Nuage.colors.ground
    val target = if (direction.groundFollowsFocus && tint != null) lerp(ground, tint, 0.22f) else ground
    val color by animateColorAsState(target, tween(400), label = "ground")
    Box(Modifier.fillMaxSize().background(color)) { content(color) }
}

/** The backdrop at the top right, faded into the ground on its left and bottom edges. */
@Composable
fun BoxScope.Backdrop(image: Int, widthFraction: Float = 0.72f, heightFraction: Float = 0.68f, ground: Color) {
    if (Nuage.direction.heroFullBleed) {
        // The artwork fills the screen; scrims keep text on its left and the rows below readable.
        Image(painterResource(image), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to ground.copy(alpha = 0.85f), 0.55f to ground.copy(alpha = 0.25f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to ground.copy(alpha = 0.75f), 0.2f to Color.Transparent, 0.35f to Color.Transparent, 0.75f to ground.copy(alpha = 0.85f), 1f to ground)))
        return
    }
    Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(widthFraction).fillMaxHeight(heightFraction)) {
        Image(painterResource(image), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to ground, 0.45f to ground.copy(alpha = 0.55f), 1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to ground)))
    }
}

@Composable
fun HomePrototype(focusCard: Boolean) {
    val colors = Nuage.colors
    val featured = SampleCatalog.featured
    val wideCards = Nuage.direction.wideCards
    var tint by remember { mutableStateOf<Color?>(featured.tint) }
    val play = remember { FocusRequester() }
    val firstCard = remember { FocusRequester() }
    LaunchedEffect(Unit) { (if (focusCard) firstCard else play).requestFocus() }

    Ground(tint) { ground ->
        Backdrop(R.drawable.proto_still_general, ground = ground)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Spacing.screenY)) {
            item {
                TopTabs(TABS, selected = 0, onSelect = {}, modifier = Modifier.padding(horizontal = Spacing.screenX, vertical = Spacing.screenY))
            }
            item {
                val top = if (Nuage.direction.heroFullBleed) 96.dp else 18.dp
                Column(Modifier.padding(start = Spacing.screenX, top = top, bottom = 28.dp).width(440.dp)) {
                    Text(featured.name, style = Nuage.type.display, color = colors.ink, maxLines = 2)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${featured.year} · ${featured.runtime} · ${featured.genres}", style = Nuage.type.body, color = colors.inkMuted)
                        Spacer(Modifier.width(12.dp))
                        BadgeRow(featured.badges)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(featured.description, style = Nuage.type.body, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NuageButton("Play", {}, Modifier.focusRequester(play), icon = Icons.Filled.PlayArrow, primary = true)
                        NuageButton("Details", {})
                    }
                }
            }
            SampleCatalog.rows.forEachIndexed { rowIndex, row ->
                item {
                    Text(
                        row.heading,
                        style = Nuage.type.headline,
                        color = colors.ink,
                        modifier = Modifier.padding(start = Spacing.screenX, bottom = 12.dp),
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Spacing.screenX, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(Nuage.direction.cardGap),
                        modifier = Modifier.padding(bottom = 14.dp),
                    ) {
                        val wide = when (wideCards) {
                            WideCards.All -> true
                            WideCards.ContinueWatching -> row.progress != null
                            WideCards.None -> false
                        }
                        itemsIndexed(row.titles) { index, title ->
                            TitleCard(
                                painterResource(if (wide) title.still else title.poster),
                                title.name,
                                title.badges,
                                onClick = {},
                                progress = row.progress?.getOrNull(index),
                                wide = wide,
                                onFocus = { tint = title.tint },
                                modifier = if (rowIndex == 0 && index == 0) Modifier.focusRequester(firstCard) else Modifier,
                            )
                        }
                    }
                }
            }
        }
    }
}
